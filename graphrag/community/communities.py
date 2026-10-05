"""M3 · 社区检测核心：融合子图构建 + greedy_modularity（确定性、confidence≥0.45 硬门）。

数据流（单一事实源，全部只读消费 M1/M2）：
1. `load_graph_with_lineage(min_conf=0.0, show_uncertain=True)` 回载 M1 快照 + M2 全部
   REFERENCES（499 条），以便本层**自计**默认可见口径（440 conf≥0.45 / 59 低置信）与
   逐边排除；喂算法的边始终经本地 `confidence ≥ min_conf` + 存疑/跨级/自环 多重硬门。
   （M2 默认消费入口 `load_graph_with_lineage()` 即 440 可见、59 隐藏 —— 本层口径与之对齐。）
2. `ingest_edges`：把 RELATES_TO（表级）与 REFERENCES（列级→投影到表级）归一为
   (src_table, dst_table, confidence, evidence_level, provenance) 记录，逐边分类
   （纳入 / 低置信排除 / 存疑排除 / 跨级非 A 排除 / 自环排除）。
3. `build_fused_graph`：无向加权图；同一表对多来源边 `weight = Σ confidence`（融合加权）。
4. `detect_communities`：networkx `greedy_modularity_communities(weight='weight')`。

诚实边界：
- 自环（表→本表，如 jf_sales_order 换货自关联）不计入社区结构（不参与模块度连边），
  单独计数 `self_loop`；它们既非低置信也非存疑，是结构自指。
- 跨级端点（lcap B 级表）不入 A 级社区子图（承 schema §3 分级遍历：B/C 影子不参与）。
- 无任何 conf≥0.45 边的 A 表（孤立点）不入社区图，单独报告 `isolated_a_tables`。
- 落盘：`graphrag/data/meta/community_*`（可提交镜像；`graphrag/out/community/` 被
  .gitignore `out/` 忽略，故权威口径以 data/meta 镜像为准）。
"""

from __future__ import annotations

import json
from collections import defaultdict

import networkx as nx
from networkx.algorithms.community import greedy_modularity_communities

from ..ingest.config import CONFIDENCE_DEFAULT_MIN, DATA_META_DIR
from ..lineage import load_graph_with_lineage
from ..lineage.extractor import REFERENCES_TYPE
from . import source_fingerprint

RELATES_TO_TYPE = "RELATES_TO"
_COMMUNITY_TYPES = (REFERENCES_TYPE, RELATES_TO_TYPE)

# 落盘文件名（data/meta 镜像，可提交）
COMMUNITIES_EDGES = "community_edges.jsonl"
COMMUNITIES_RESULT = "community_result.json"

OUT_DIR = DATA_META_DIR.parents[1] / "out" / "community"   # 本地镜像（gitignored）


# ---------------------------------------------------------------- 归一工具
def _table_of_node(node_id: str) -> str:
    """`table:X` 或 `column:X.c` → 表名 X。"""
    return node_id.split(":", 1)[1].split(".")[0]


def _edge_conf(e: dict) -> float:
    return float(e.get("confidence", 0.0))


def _edge_uncertain(e: dict) -> bool:
    """存疑判据（与 M2 `build._edge_uncertain` / `impact._is_uncertain` 同族 + 表级兜底）。"""
    if e.get("has_uncertain") or e.get("evidence_level") == "unconfirmed":
        return True
    q = (e.get("evidence_src") or {}).get("quote") or e.get("raw_desc") or ""
    return "待确认" in q


def _provenance(e: dict) -> dict:
    """可回溯锚（quote_hash + file），供 §4.2/R6「不新建来源外关系」审计。"""
    if e["type"] == REFERENCES_TYPE:
        ev = e.get("evidence_src") or {}
        return {"file": ev.get("file"), "quote_hash": ev.get("quote_hash"),
                "rel": REFERENCES_TYPE}
    return {"file": e.get("source_file"), "quote_hash": e.get("quote_hash"),
            "rel": RELATES_TO_TYPE}


# ---------------------------------------------------------------- 边摄取
def ingest_edges(graph, a_tables: set[str], min_conf: float = CONFIDENCE_DEFAULT_MIN) \
        -> tuple[list[dict], dict]:
    """把 RELATES_TO + REFERENCES 投影为表级记录，并做纳入/排除分类。

    返回 (kept_records, exclusion_report)。kept = 两端 A 级、conf≥min_conf、非存疑、非自环。
    每条 record 携带 per-edge 溯源（quote_hash/file），供融合后回溯。
    """
    kept: list[dict] = []
    excl: dict[str, int] = defaultdict(int)
    uncertain_sample: list[dict] = []

    for e in graph.edges:
        etype = e["type"]
        if etype not in _COMMUNITY_TYPES:
            continue
        st = _table_of_node(e["src"])
        dt = _table_of_node(e["dst"])
        conf = _edge_conf(e)
        prov = _provenance(e)

        # 分类顺序：置信门 → 存疑 → 跨级 → 自环 → 纳入（低置信/存疑入算法必须=0）
        if conf < min_conf:
            excl[f"{etype}:below_min_conf"] += 1
            continue
        if _edge_uncertain(e):
            excl[f"{etype}:uncertain_or_pending"] += 1
            uncertain_sample.append({"src": st, "dst": dt, **prov})
            continue
        if st not in a_tables or dt not in a_tables:
            excl[f"{etype}:non_A_endpoint"] += 1
            continue
        if st == dt:
            excl[f"{etype}:self_loop"] += 1
            continue
        kept.append({"src": st, "dst": dt, "type": etype,
                     "confidence": conf, "evidence_level": e.get("evidence_level"),
                     "cross_domain": bool(e.get("cross_domain", False)),
                     "provenance": prov})

    return kept, {
        "excluded_by_reason": dict(sorted(excl.items())),
        "excluded_total": sum(excl.values()),
        "uncertain_excluded_sample": uncertain_sample[:50],
        "min_conf_gate": min_conf,
    }


# ---------------------------------------------------------------- 融合加权图
def build_fused_graph(kept: list[dict], a_tables: set[str]) -> tuple[nx.Graph, dict]:
    """无向加权图：同一表对多来源边 → weight=Σ confidence；保留 per-pair 溯源清单。

    仅纳入在 kept 中出现过的 A 表节点（孤立点不入，另由 `isolated_a_tables` 报告）。
    """
    G = nx.Graph()
    pair_rels: dict[tuple[str, str], dict] = {}
    for r in kept:
        a, b = (r["src"], r["dst"]) if r["src"] <= r["dst"] else (r["dst"], r["src"])
        key = (a, b)
        pr = pair_rels.setdefault(key, {"weight": 0.0, "n_edges": 0, "relates_to": 0,
                                        "references": 0, "evidence_levels": set(),
                                        "cross_domain": False, "provenance": []})
        pr["weight"] += r["confidence"]
        pr["n_edges"] += 1
        pr["relates_to"] += 1 if r["type"] == RELATES_TO_TYPE else 0
        pr["references"] += 1 if r["type"] == REFERENCES_TYPE else 0
        if r["evidence_level"]:
            pr["evidence_levels"].add(r["evidence_level"])
        pr["cross_domain"] = pr["cross_domain"] or r["cross_domain"]
        pr["provenance"].append(r["provenance"])

    touched: set[str] = set()
    for (a, b), pr in pair_rels.items():
        G.add_edge(a, b, weight=round(pr["weight"], 6),
                   n_edges=pr["n_edges"], relates_to=pr["relates_to"],
                   references=pr["references"],
                   evidence_levels=sorted(pr["evidence_levels"]),
                   cross_domain=pr["cross_domain"],
                   provenance=pr["provenance"])
        touched.add(a)
        touched.add(b)

    isolated = sorted(a_tables - touched)
    stats = {
        "nodes_in_graph": G.number_of_nodes(),
        "table_pairs": G.number_of_edges(),
        "kept_records": len(kept),
        "isolated_a_tables": len(isolated),
        "isolated_a_list": isolated,
    }
    return G, stats


# ---------------------------------------------------------------- 社区检测
def detect_communities(G: nx.Graph, resolution: float = 1.0) -> list[frozenset[str]]:
    """networkx greedy_modularity_communities（Louvain 目标退化实现，零新依赖）。

    weight='weight' → 按融合置信加权。结果按最小成员名稳定排序，保证可复现。
    """
    if G.number_of_edges() == 0:
        return []
    comms = greedy_modularity_communities(G, weight="weight", resolution=resolution)
    return sorted(comms, key=lambda c: min(c))


# ---------------------------------------------------------------- 社区指标
def community_metrics(G: nx.Graph, communities: list[frozenset[str]]) -> list[dict]:
    """每社区：规模、内/外部边数、内外边比、密度。"""
    member_of: dict[str, int] = {}
    for i, c in enumerate(communities):
        for n in c:
            member_of[n] = i

    internal_w = defaultdict(float)
    internal_n = defaultdict(int)
    external_w = defaultdict(float)
    external_n = defaultdict(int)
    for u, v, d in G.edges(data=True):
        w = d.get("weight", 1.0)
        cu, cv = member_of[u], member_of[v]
        if cu == cv:
            internal_w[cu] += w
            internal_n[cu] += 1
        else:
            external_w[cu] += w
            external_w[cv] += w
            external_n[cu] += 1
            external_n[cv] += 1

    out = []
    for i, c in enumerate(communities):
        size = len(c)
        int_n, ext_n = internal_n[i], external_n[i]
        out.append({
            "community_id": i,
            "size": size,
            "members": sorted(c),
            "internal_edges": int_n,
            "external_edges": ext_n,
            "internal_weight": round(internal_w[i], 6),
            "external_weight": round(external_w[i], 6),
            "internal_external_ratio": (round(int_n / ext_n, 4) if ext_n else None),
            "density": (round(2 * int_n / (size * (size - 1)), 4) if size > 1 else 0.0),
        })
    return out


# ---------------------------------------------------------------- 主入口
def build_subgraph(min_conf: float = CONFIDENCE_DEFAULT_MIN):
    """加载 + 摄取 + 融合，返回 (G, kept, exclusion, stats, gate_stats, domain_map)。

    喂算法的边经本地 `conf≥min_conf` + 存疑/跨级/自环 多重硬门（低置信/存疑入算法=0）。
    `gate_stats` 记录默认口径下的 REFERENCES 可见(440)/隐藏(59)基线（P1 修复后，非旧 446）。
    """
    graph, _meta = load_graph_with_lineage(min_conf=0.0, show_uncertain=True)  # 全量，本层自计门
    a_tables = {n["name"] for n in graph.nodes.values()
                if n["label"] == "Table" and n.get("tier") == "A" and n.get("domain")}
    domain_map = {n["name"]: n["domain"] for n in graph.nodes.values()
                  if n["label"] == "Table" and n["name"] in a_tables}

    # 默认可见口径（与 M2 load_graph_with_lineage() 一致）
    ref_edges = [e for e in graph.edges if e["type"] == REFERENCES_TYPE]
    ref_below = sum(1 for e in ref_edges if _edge_conf(e) < min_conf)
    ref_unc = sum(1 for e in ref_edges if _edge_conf(e) >= min_conf and _edge_uncertain(e))
    ref_visible = len(ref_edges) - ref_below - ref_unc
    gate_stats = {
        "min_confidence": min_conf,
        "references_in_file": len(ref_edges),                          # 499
        "references_default_visible": ref_visible,                     # 440
        "reference_baseline_visible": ref_visible,                     # 440（别名）
        "references_loaded": ref_visible,                              # 440（对齐 M2 消费口径）
        "references_hidden_below_conf": ref_below,                     # 59
        "references_hidden_uncertain": ref_unc,                        # 0（P1-2 起只入队）
    }

    kept, exclusion = ingest_edges(graph, a_tables, min_conf=min_conf)
    G, stats = build_fused_graph(kept, a_tables)
    return G, kept, exclusion, stats, gate_stats, domain_map


def _count_list(it) -> dict:
    c: dict = defaultdict(int)
    for x in it:
        c[x] += 1
    return c


def _algo_leak_check(kept: list[dict]) -> int:
    """喂算法的 kept 边里低置信/存疑的计数（恒应为 0，双门已在 ingest_edges 拦截）。"""
    return sum(1 for r in kept
               if r["confidence"] < CONFIDENCE_DEFAULT_MIN
               or r["evidence_level"] == "unconfirmed")


def run(resolution: float = 1.0, write: bool = True) -> dict:
    """执行社区检测全流程，产出结构化结果（可选落盘 data/meta 镜像）。"""
    G, kept, exclusion, stats, gate_stats, domain_map = build_subgraph()
    communities = detect_communities(G, resolution=resolution)
    metrics = community_metrics(G, communities)
    for m in metrics:
        m["domain_profile"] = dict(
            sorted(_count_list(domain_map[t] for t in m["members"]).items()))

    result = {
        "milestone": "M3",
        "generator": "graphrag/community/communities.py",
        "contract": "graphrag/spec (M0)；底座 load_graph_with_lineage（M1+M2）只读",
        "algorithm": "networkx.greedy_modularity_communities (Louvain 目标退化实现)",
        "dependency_note": "networkx 3.7 内置；未装 python-louvain/igraph/leidenalg"
                           "（stack-options §3 硬约束）",
        "disclaimer": ("关系/血缘为逆向推断、非物理外键（全库 0 FK）；社区=分析视图，"
                       "00 §四 手工 18 域(+OT) 仍为权威 ground truth，不被覆盖。"),
        "edge_gate": gate_stats,
        "ingestion": {
            "kept_edges": len(kept),
            "excluded": exclusion,
            "low_conf_or_uncertain_in_algorithm": _algo_leak_check(kept),  # 必须=0
        },
        "graph_stats": {k: v for k, v in stats.items() if k != "isolated_a_list"},
        "community_count": len(communities),
        "communities": metrics,
        "size_distribution": dict(sorted(_count_list(m["size"] for m in metrics).items())),
        "authoritative_domain_source": "00 §四 (M1 graph node.domain)",
        "community_role": "analysis_view_only",
    }

    source_fingerprint.embed(result, write=write)
    if write:
        _export(kept, result)
    return {"graph": G, "kept": kept, "result": result, "domain_map": domain_map,
            "communities": communities, "exclusion": exclusion, "stats": stats,
            "gate_stats": gate_stats}


def _export(kept: list[dict], result: dict) -> None:
    DATA_META_DIR.mkdir(parents=True, exist_ok=True)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    edge_lines = [json.dumps(r, ensure_ascii=False, sort_keys=True) for r in kept]
    payload = json.dumps(result, ensure_ascii=False, indent=2)
    for base in (DATA_META_DIR, OUT_DIR):
        (base / COMMUNITIES_EDGES).write_text("\n".join(edge_lines) + "\n", encoding="utf-8")
        (base / COMMUNITIES_RESULT).write_text(payload, encoding="utf-8")


if __name__ == "__main__":
    res = run(write=True)
    r = res["result"]
    print(json.dumps({
        "community_count": r["community_count"],
        "kept_edges": r["ingestion"]["kept_edges"],
        "table_pairs": r["graph_stats"]["table_pairs"],
        "nodes_in_graph": r["graph_stats"]["nodes_in_graph"],
        "isolated_a": r["graph_stats"]["isolated_a_tables"],
        "edge_gate": r["edge_gate"],
        "leak": r["ingestion"]["low_conf_or_uncertain_in_algorithm"],
        "excluded": r["ingestion"]["excluded"]["excluded_by_reason"],
        "size_dist": r["size_distribution"],
    }, ensure_ascii=False, indent=2))
