"""M4 · 结构语义层 + 规则式 NL 编排前端 + M3 P2 承接 —— 契约/诚实性测试。

锁定要点（任务 §5 + 过门双门）：
1. **语义元素 100% 可定位来源**：域分区 / 实体三分类 / 字段含义 / 枚举码表逐条挂来源；
   不可定位者一律 `[待确认]`（绝不臆造字段含义 / 实体绑定）。
2. **不虚构**：located 概念的 realized_by 必为图内真实 Table 节点；REALIZED_BY 边指向实物表。
3. **指标语义层确未出现**：语义层 scope=structural，指标/KPI/术语表在 `out_of_scope` 显式声明。
4. **NL 只调 L1/L2**：意图答案仅来自 `search/l1` 与 `community`；不引血缘 M2 遍历 / LLM。
5. **NL 不绕置信 / 存疑门**：默认 conf≥0.45、show_uncertain=False、max_hops≤3（超限钳制）。
6. **NL 不生成无出处关系**：关系/遍历/耦合每条都有图内可回溯边（quote_hash / 真实邻接）。
7. **拒绝臆造兜底**：未定位实体 / 无路径 → refused，不编答案。
8. **LLM stub 默认禁用**：`ENABLED=False`，调用即抛 NotImplementedError。
9. **M3 P2 承接**：75 孤立 A 表名单落盘且覆盖等式闭合；9 混合簇逐条标 `对照观察(非发现物)`；
   golden 入口交接声明 6 意图 + LLM 禁用。
"""

from __future__ import annotations

import copy
import inspect
import json

import pytest

from graphrag.ingest.config import (
    CONFIDENCE_DEFAULT_MIN, MAX_HOPS, L0_GRAPH_JSON, DOMAIN_DECLARED, DATA_META_DIR,
)
from graphrag.store.loader import load_graph_json
from graphrag.semantic import semantic_layer as sl
from graphrag.semantic import m3_p2_handoff as p2
from graphrag.nl import nl_router as nlr
from graphrag.nl import llm_synthesis


# ---------------------------------------------------------------- fixtures
@pytest.fixture(scope="module")
def layer():
    return sl.SemanticLayer()


@pytest.fixture(scope="module")
def built(layer):
    return layer.build()


@pytest.fixture(scope="module")
def router():
    return nlr.NLRouter()


@pytest.fixture(scope="module")
def fresh_graph():
    """独立回载 M1 图（供“NL 不生成无出处关系”交叉校验）。"""
    return load_graph_json(L0_GRAPH_JSON)


@pytest.fixture(scope="module")
def kept_pairs():
    """M3 审计后的融合边集（RELATES_TO+REFERENCES 表级投影）→ 耦合对合法性基准。"""
    path = DATA_META_DIR / "community_edges.jsonl"
    pairs = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        r = json.loads(line)
        pairs.add(frozenset((r["src"], r["dst"])))
    return pairs


def _rel_adj(g, table):
    """{邻居表名}：table 的全部 RELATES_TO 无向邻居（A 级可遍历主语料）。"""
    tid = f"table:{table}"
    nb = set()
    for _, e in g.neighbors(tid, "RELATES_TO", direction="out"):
        nb.add(e["dst"].split(":", 1)[1])
    for _, e in g.neighbors(tid, "RELATES_TO", direction="in"):
        nb.add(e["src"].split(":", 1)[1])
    return nb


def _edge_exists(g, a, b):
    return b in _rel_adj(g, a)


# ================================================================ 1 语义元素 100% 挂来源
def test_scope_is_structural_and_declared(built):
    assert built["semantic_layer_scope"] == "structural"
    assert "不含指标语义层" in built["scope_statement"]
    assert built["out_of_scope"] == sl.OUT_OF_SCOPE


def test_every_domain_partition_has_source(built):
    assert len(built["domains"]) == len(DOMAIN_DECLARED) + 1   # 18 业务域+OT + B-FAMILY
    for d in built["domains"]:
        assert d["authoritative"] is True                       # 00 §四 权威，不被社区覆盖
        assert d["source"]["quote_hash"], d
        assert d["source"]["file"].endswith("file-domain-map.md")
    codes = {d["domain_id"] for d in built["domains"]}
    assert set(DOMAIN_DECLARED) <= codes and "B-FAMILY" in codes


def test_every_concept_has_source_and_locatable_or_pending(built, fresh_graph):
    table_names = {n["name"] for n in fresh_graph.nodes.values() if n["label"] == "Table"}
    ents = built["entity_classification"]["categories"]
    assert built["entity_classification"]["total"] == len(ents) == 36
    for e in ents:
        assert e["source"]["quote_hash"], e                     # 逐条挂 05 来源
        if e["status"] == "located":
            assert e["realized_by"] and all(t in table_names for t in e["realized_by"]), e
        else:
            assert "待确认" in e["status"], e
            assert e["realized_by"] == []                       # 不可定位 → 不绑表（不臆造）


def test_known_unlocatable_are_pending_not_fabricated(built):
    """诚实边界：05 点名但 DDL/图无同名表者 → [待确认]，绝不绑到近似表。"""
    wh = next(e for e in built["entity_classification"]["categories"]
              if any("warehouse" in t for t in e["identifiers"]))
    assert wh["status"] != "located"                            # jf_warehouse DDL 无表
    assert wh["realized_by"] == []
    # 该候选仍登记（binding=pending），但不臆造绑定
    assert any(b["binding"] == "pending" for b in wh["binding_detail"])


def test_b_tier_entities_bound_with_tier_and_non_traversable(built):
    """lcap_*（B 级影子）命中物理表 → 绑并登记 tier=B / traversable=False（非 [待确认]）。"""
    ent = next(e for e in built["entity_classification"]["categories"]
               if e["term"] == "lcap_department")
    assert ent["status"] == "located"
    assert set(ent["realized_by"]) == {"lcap_department", "lcap_user", "lcap_role"}
    for b in ent["binding_detail"]:
        assert b["tier"] == "B" and b["traversable"] is False, b


def test_field_meanings_tied_to_ddl_comment_or_pending(built):
    cov = built["field_meaning_coverage"]
    assert cov["a_level_columns"] == cov["with_ddl_comment"] + cov["without_comment_pending"]
    assert cov["a_level_columns"] == 6428 and cov["with_ddl_comment"] == 6331
    assert cov["without_comment_pending"] == 97
    bad = 0
    for t, rows in built["field_meanings"].items():
        for r in rows:
            if r["has_meaning"]:
                assert r["source"]["file"] == "test_erp.sql", (t, r)   # 含义=DDL COMMENT 原文
                assert r["meaning"]
            else:
                assert "待确认" in r["source"]["status"], (t, r)        # 无 COMMENT→[待确认]
                assert r["meaning"] is None                             # 不臆造含义
                bad += 1
    assert bad == cov["without_comment_pending"]


def test_enum_semantics_all_sourced(layer):
    en = layer.enum_semantics()
    for c in en["code_tables"]:
        assert c["source"]["quote_hash"] and "D14" in c["source"]["file"]
        assert "rows" in c["enum_values"]                       # 值域=表内数据行，不落常量（不臆造）
    for kv in en["kv_dictionaries"]:
        assert kv["source"]["quote_hash"]
    for ie in en["inline_enums_from_comment"]:
        assert ie["source"]["file"] == "test_erp.sql"
        assert ie["raw_comment"] and len(ie["values"]) >= 2     # 逐字可回溯 + ≥2 值义对


# ================================================================ 2 REALIZED_BY 图不虚构
def test_concept_graph_realized_by_points_to_real_tables(layer, fresh_graph):
    tbl_names = {n["name"] for n in fresh_graph.nodes.values() if n["label"] == "Table"}
    g, stat = layer.build_concept_graph()
    assert stat["concepts"] > 0 and stat["realized_by_edges"] > 0
    seen = 0
    for e in g.edges:
        if e["type"] == "REALIZED_BY":
            dst = e["dst"].split(":", 1)[1]
            assert dst in tbl_names, e                          # 只绑实物表
            assert e["quote_hash"] and e["source_file"], e      # 每条边可回溯来源
            assert e["is_inferred"] is True                     # 承全库推断、非物理约束
            seen += 1
    assert seen == stat["realized_by_edges"]


# ================================================================ 3 指标语义层确未实现
def test_out_of_scope_metrics_declared(built):
    items = " ".join(o["item"] for o in built["out_of_scope"])
    for kw in ("指标", "KPI", "术语表", "计算口径"):
        assert kw in items
    # 语义层未产出任何“指标口径/公式/术语定义”结构体
    payload = str(built)
    assert "计算公式" not in payload and "KPI定义" not in payload


# ================================================================ 4 NL 只调 L1/L2、不引血缘/LLM
def test_router_source_uses_only_l1_and_community():
    src = inspect.getsource(nlr)
    for forbidden in ("load_graph_with_lineage", "reference_traverse", "table_impact(",
                      ".synthesize(", ".compose_multi(", "openai", "anthropic",
                      "requests", "urllib.request"):
        assert forbidden not in src, forbidden
    for required in ("self.l1.", "global_analysis"):
        assert required in src


# ================================================================ 5 默认门 + 不绕置信/存疑
def test_default_gates_are_strict(router):
    a = router.answer("jf_customer 关联哪些表？")
    assert a["gates"]["min_confidence"] == CONFIDENCE_DEFAULT_MIN == 0.45
    assert a["gates"]["show_uncertain"] is False
    assert a["gates"]["max_hops"] <= MAX_HOPS == 3


def test_max_hops_clamped_to_budget(router):
    a = router.answer("改了 jf_product 会影响哪些表？", max_hops=99)
    assert a["gates"]["max_hops"] == 3                          # 超限钳制


def test_relations_never_leak_below_conf_or_uncertain(router):
    for t in ("jf_customer", "jf_sales_order", "jf_trader", "jf_goods"):
        a = router.answer(f"{t} 关联哪些表？")
        for r in a["answer"]["relations"]:
            assert r["confidence"] >= CONFIDENCE_DEFAULT_MIN
            assert r["has_uncertain"] is False                  # 存疑边默认隐藏（不绕门）


def test_traverse_edges_all_above_conf(router):
    a = router.answer("改了 jf_product 会影响哪些表？")
    assert a["intent"] == "impact_lineage"
    for p in a["answer"]["paths"]:
        assert p["confidence"] >= CONFIDENCE_DEFAULT_MIN


# ================================================================ 6 NL 不生成无出处关系
RELATION_QUERY_TABLES = ["jf_customer", "jf_sales_order", "jf_product", "jf_trader"]


@pytest.mark.parametrize("t", RELATION_QUERY_TABLES)
def test_uc3_relations_exist_in_graph_and_have_provenance(router, fresh_graph, t):
    a = router.answer(f"{t} 关联哪些表？")
    for r in a["answer"]["relations"]:
        assert _edge_exists(fresh_graph, t, r["other"]), (t, r)  # 图内真实边，非臆造
        assert r["quote_hash"] and r["source_file"], r           # 100% 有出处


@pytest.mark.parametrize("t,dirkw", [("jf_product", "影响"), ("jf_receivable_write_off", "血缘")])
def test_uc4_uc5_traverse_paths_are_real_adjacency_with_provenance(router, fresh_graph, t, dirkw):
    q = f"改了 {t} 会{dirkw}哪些表？" if dirkw == "影响" else f"{t} 的{dirkw}来自哪些表？"
    a = router.answer(q)
    for p in a["answer"]["paths"]:
        assert _edge_exists(fresh_graph, p["from"], p["to"]), (t, p)
        assert p["quote_hash"] and p["source_file"], p


def test_hub_and_coupling_from_real_edges(router, fresh_graph, kept_pairs):
    hubs = router.answer("全库最枢纽的表是哪些？")["answer"]["top_hubs"]
    assert hubs
    for h in hubs:
        assert fresh_graph.has_node(f"table:{h['table']}")       # 枢纽=图内实物表
        assert h["sample_edge_provenance"]                       # 逐条带可回溯样本边
        for pv in h["sample_edge_provenance"]:
            f = str(pv["file"])
            assert f.endswith(".md") or f.endswith("test_erp.sql"), pv
    coup = router.answer("哪些社区/域之间耦合最紧？")["answer"]
    for cp in coup["tightest_couplings"]["cross_community_coupling"]:
        for pair in cp["sample_table_pairs"]:
            # 耦合对必为 M3 审计 kept 融合边（RELATES_TO/REFERENCES），非臆造
            assert frozenset((pair["src"], pair["dst"])) in kept_pairs, pair




# ================================================================ 7 拒绝臆造兜底
def test_refuses_unknown_table(router):
    a = router.answer("zzz_not_a_table 关联哪些表？")
    assert a["refused"] is True
    assert a["sources"] == []


def test_metric_query_refused_out_of_scope(router):
    a = router.answer("本月 GMV 指标的口径是什么？")
    assert a["intent"] == "out_of_scope" and a["uc"].startswith("N/A")
    assert a["refused"] is True
    assert a["answer"] is None and a["sources"] == []
    assert "指标" in a["matched_params"]["out_of_scope_terms"][0] or \
        any("指标" in x or "口径" in x for x in a["matched_params"]["out_of_scope_terms"])


# ================================================================ NL 确定性（同输入同输出）
def test_answer_deterministic(router):
    q = "jf_customer 关联哪些表？"
    assert copy.deepcopy(router.answer(q)) == copy.deepcopy(router.answer(q))
    q2 = "D14 域的概览与质量问题？"
    assert router.answer(q2) == router.answer(q2)


# ================================================================ 8 LLM stub 默认禁用
def test_llm_synthesis_disabled():
    assert llm_synthesis.ENABLED is False
    with pytest.raises(NotImplementedError):
        llm_synthesis.synthesize({"answer": {}})
    with pytest.raises(NotImplementedError):
        llm_synthesis.compose_multi([])
    st = llm_synthesis.status()
    assert st["enabled"] is False and "SUPPORTED_BY" in " ".join(st["hard_constraints"])


# ================================================================ 9 M3 P2 承接
@pytest.fixture(scope="module")
def handoff():
    return p2.run(write=False)


def test_p2a_isolated_a_list_75_and_coverage(handoff):
    a = handoff["isolated_a"]
    assert a["isolated_a_count"] == 75
    assert len(a["isolated_a_tables"]) == a["isolated_a_count"]
    assert a["in_graph_nodes"] + a["isolated_a_count"] == a["a_domain_tables_total"] == 349
    assert a["m3_crosscheck_count"] == 75                       # 与 M3 计数一致（同源）
    assert a["role"] == "handoff_only"


def test_p2b_mixed_clusters_downgraded(handoff):
    b = handoff["mixed_cluster_note"]
    assert b["n_mixed_communities"] == len(b["mixed_communities"]) == 9
    for m in b["mixed_communities"]:
        assert m["note"] == p2.MIXED_CLUSTER_NOTE == "对照观察(非发现物)"
    assert b["ground_truth"].startswith("00")                   # 不覆盖权威分组


def test_p2c_eval_handoff_entry_ready(handoff):
    c = handoff["eval_handoff"]
    assert c["role"] == "handoff_only"
    assert c["golden_set_owner"].startswith("rag-eval-gate")
    ids = [i["intent"] for i in c["nl_entry_api"]["intent_coverage"]]
    assert len(ids) == 6
    assert c["defaults"] == {"min_confidence": 0.45, "show_uncertain": False, "max_hops": 3}
    assert c["llm_synthesis"]["enabled"] is False
    assert c["smoke_sample"]["refused"] is False                # 入口可作答（非异常）
