"""M3 · 社区/全局分析层 契约测试（graphrag/community）。

断言（任务 §交付/回报 + 过门双门要点）：
1. **基线口径**：REFERENCES 默认可见=440（P1 修复，非旧 446）、文件内=499、
   低于 0.45 隐藏=59；社区输入 = RELATES_TO+REFERENCES 表级融合、confidence 加权。
2. **入算法低置信/存疑=0**：喂 greedy_modularity 的边全部 conf≥0.45 且非存疑；
   被排除计数（低置信 REL/REF、存疑、跨级、自环）均登记。
3. **不新建来源外实体/关系**：社区成员 ⊆ A 级(有域)表；每条 kept 边带可回溯
   provenance（quote_hash + er-model/*.md 或 test_erp.sql）；耦合/枢纽结论可回溯到具体表/边。
4. **NMI 可复现 + 正确**：两次同输入同输出；对独立手算 NMI 一致（含恒等性 NMI(X;X)=1）。
5. **00 权威分组不被覆盖**：产物标 community_role=analysis_view_only、权威源=00 §四；
   节点域归属 = graph node.domain（未被社区改写）；18 域(+OT) 齐全、Σ声明=349。
6. **依赖红线**：仅 networkx.greedy_modularity_communities；未引入 python-louvain/igraph/leidenalg。
"""

from __future__ import annotations

import importlib
import math
import sys
from collections import defaultdict

import pytest

from graphrag.ingest.config import CONFIDENCE_DEFAULT_MIN, DOMAIN_DECLARED
from graphrag.community import communities as comm

# 经 importlib 显式取**子模块对象**：本文件按「模块.函数」用法（nmi_mod.nmi_vs_baseline(...)）
# 需要模块而非函数。P2-③ 消歧后包属性 `nmi_vs_baseline` 已恢复为子模块（详见包 docstring），
# 此处仍保留显式模块路径，使"取模块"这一意图不依赖包属性语义。
nmi_mod = importlib.import_module("graphrag.community.nmi_vs_baseline")


# ---------------------------------------------------------------- fixtures
@pytest.fixture(scope="module")
def run_once():
    return comm.run(write=False)


@pytest.fixture(scope="module")
def result(run_once):
    return run_once["result"]


@pytest.fixture(scope="module")
def G(run_once):
    return run_once["graph"]


@pytest.fixture(scope="module")
def kept(run_once):
    return run_once["kept"]


@pytest.fixture(scope="module")
def domain_map(run_once):
    return run_once["domain_map"]


# ---------------------------------------------------------------- 1 基线口径
def test_reference_visible_baseline_440(result):
    eg = result["edge_gate"]
    assert eg["reference_baseline_visible"] == 440          # P1 修复基线，勿用旧 446
    assert eg["references_in_file"] == 499
    assert eg["references_hidden_below_conf"] == 59
    assert eg["references_hidden_uncertain"] == 0           # 存疑引用 M2 只入队


def test_fused_table_level_graph(run_once, G):
    stats = run_once["stats"]
    assert G.number_of_edges() == stats["table_pairs"] > 0
    assert stats["kept_records"] == len(run_once["kept"])
    for _, _, d in G.edges(data=True):
        assert d["weight"] > 0
        assert d["relates_to"] + d["references"] == d["n_edges"]


# ---------------------------------------------------------------- 2 入算法硬门
def test_low_conf_or_uncertain_in_algorithm_is_zero(result):
    assert result["ingestion"]["low_conf_or_uncertain_in_algorithm"] == 0


def test_every_kept_edge_passes_double_gate(kept):
    for r in kept:
        assert r["confidence"] >= CONFIDENCE_DEFAULT_MIN
        assert r["evidence_level"] != "unconfirmed"


def test_exclusion_counts_registered(result):
    ex = result["ingestion"]["excluded"]["excluded_by_reason"]
    assert ex.get("RELATES_TO:below_min_conf", 0) == 64       # 低置信表级边排除
    assert ex.get("REFERENCES:below_min_conf", 0) == 59       # 低置信(semantic)引用排除
    assert ex.get("RELATES_TO:uncertain_or_pending", 0) == 7  # 存疑(原文[待确认])排除
    assert ex.get("RELATES_TO:non_A_endpoint", 0) == 13       # 跨级 B 端点排除
    assert ex.get("RELATES_TO:self_loop", 0) == 14
    assert ex.get("REFERENCES:self_loop", 0) == 4


# ---------------------------------------------------------------- 3 不新建来源外实体
def test_community_members_are_A_tier_domain_tables(result, domain_map):
    for c in result["communities"]:
        for t in c["members"]:
            assert t in domain_map, f"社区出现非 A 级/无域表：{t}"


def test_kept_edges_are_traceable_to_permitted_source(kept):
    for r in kept:
        prov = r["provenance"]
        assert prov.get("quote_hash"), r
        f = str(prov.get("file", ""))
        # 允许来源（Agents.md §0）：er-model/*.md 或 test_erp.sql
        assert f.endswith(".md") or f.endswith("test_erp.sql"), r


def test_no_dangling_endpoint_in_graph(kept, G):
    for r in kept:
        assert G.has_node(r["src"]) and G.has_node(r["dst"])
        assert G.has_edge(r["src"], r["dst"])


def test_global_hub_and_coupling_conclusions_traceable(G, domain_map):
    from graphrag.community.global_analysis import global_hubs, tightest_couplings
    member_of = {n: i for i, c in enumerate(comm.detect_communities(G)) for n in c}
    hubs = global_hubs(G, member_of, domain_map, top=5)
    assert hubs == sorted(hubs, key=lambda h: (-h["weighted_degree"],
                                               -h["betweenness"], h["table"]))
    for h in hubs:
        assert h["table"] in G and domain_map.get(h["table"]) == h["domain"]
        assert h["sample_edge_provenance"]                      # 至少一条可回溯边
        assert G.degree(h["table"]) == h["degree"]
    coup = tightest_couplings(G, member_of, domain_map, top=5)
    for cp in coup["cross_community_coupling"]:
        for pair in cp["sample_table_pairs"]:
            assert G.has_edge(pair["src"], pair["dst"])         # 耦合表对真实存在


# ---------------------------------------------------------------- 4 NMI 正确/复现
def _brute_nmi(rows, cols):
    n = len(rows)
    rc, cc, joint = defaultdict(int), defaultdict(int), defaultdict(int)
    for r, c in zip(rows, cols):
        rc[r] += 1
        cc[c] += 1
        joint[(r, c)] += 1

    def H(cnts):
        return -sum((x / n) * math.log(x / n) for x in cnts if x > 0)
    mi = sum((nij / n) * math.log((nij / n) / ((rc[r] / n) * (cc[c] / n)))
             for (r, c), nij in joint.items())
    hr, hc = H(list(rc.values())), H(list(cc.values()))
    return 2 * mi / (hr + hc)


def _partition_labels(G, domain_map):
    comms = comm.detect_communities(G)
    member_of = {n: str(i) for i, c in enumerate(comms) for n in c}
    nodes = sorted(G.nodes)
    return [member_of[n] for n in nodes], [domain_map.get(n, "UNKNOWN") for n in nodes]


def test_nmi_matches_independent_recompute(G, domain_map):
    rows, cols = _partition_labels(G, domain_map)
    m = nmi_mod.nmi(rows, cols)
    assert m["nmi_arithmetic"] == pytest.approx(_brute_nmi(rows, cols), abs=1e-6)
    assert 0.0 <= m["nmi_arithmetic"] <= 1.0


def test_nmi_deterministic_same_input(G, domain_map):
    rows, cols = _partition_labels(G, domain_map)
    assert nmi_mod.nmi(rows, cols) == nmi_mod.nmi(rows, cols)


def test_nmi_identity_property():
    labels = ["a", "a", "b", "b", "c"]
    m = nmi_mod.nmi(labels, list(labels))
    assert m["nmi_arithmetic"] == pytest.approx(1.0, abs=1e-9)


def test_communities_detection_deterministic(G):
    assert comm.detect_communities(G) == comm.detect_communities(G)


# ---------------------------------------------------------------- 5 不覆盖 00 权威
def test_outputs_declare_analysis_view_only(result):
    assert result["community_role"] == "analysis_view_only"
    assert result["authoritative_domain_source"].startswith("00")


def test_baseline_domains_intact_and_sum_349(domain_map):
    counts = defaultdict(int)
    for dom in domain_map.values():
        counts[dom] += 1
    # 社区子图只含"有 conf≥0.45 边"的 A 表 → 每域成员 ≤ 00 声明数，键仍为 Dxx/OT
    assert set(counts) <= set(DOMAIN_DECLARED)
    for dom, declared in DOMAIN_DECLARED.items():
        assert counts.get(dom, 0) <= declared
    assert sum(DOMAIN_DECLARED.values()) == 349


def test_nmi_vs_baseline_does_not_relabel_domains(G, domain_map):
    out = nmi_mod.nmi_vs_baseline(write=False)
    assert out["ground_truth"].startswith("00")                  # 权威 = 00 手工分组
    assert out["analysis_view"].startswith("greedy_modularity")  # 社区仅分析视图
    assert out["diff_clusters_explainable"]["false_coupling_note"]


# ---------------------------------------------------------------- 6 依赖红线
def test_uses_networkx_greedy_modularity_only():
    from networkx.algorithms.community import greedy_modularity_communities
    assert greedy_modularity_communities.__module__.startswith("networkx")
    for banned in ("igraph", "leidenalg"):
        assert banned not in sys.modules                          # 未加载外部图库


def test_summarizer_disabled_by_default():
    from graphrag.community import summarizer
    assert summarizer.ENABLED is False
    with pytest.raises(NotImplementedError):
        summarizer.generate_global_narrative({})


# ---------------------------------------------------------------- 规模/结构合理
def test_coverage_accounting(result, run_once, domain_map):
    total_members = sum(c["size"] for c in result["communities"])
    assert total_members == run_once["stats"]["nodes_in_graph"]
    # 入图(有边) + 孤立(无边) = 全部有域 A 表(349)
    assert run_once["stats"]["nodes_in_graph"] + run_once["stats"]["isolated_a_tables"] \
        == len(domain_map) == 349
    assert result["community_count"] >= 2
