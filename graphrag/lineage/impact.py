"""M2 · 引用级血缘的遍历/影响分析（BFS + 置信加权 + 深度预算剪枝）。

方向语义（列级）：
- `upstream`（血缘，UC4）：沿 REFERENCES **出边** —— 本列引用了谁（值从谁那里"被引用"）。
- `downstream`（影响，UC5）：沿 REFERENCES **入边**（反向可达）—— 谁引用了本列；
  变更本列值域将波及这些列/表。

口径（evidence-confidence-map §1.1）：默认 `confidence ≥ 0.45` 过滤（semantic/unconfirmed
默认隐藏），多跳默认 ≤3、超限截断可复现（truncated 计数）；路径分 = 边置信度连乘，
同列取最优分（first-best 去重），按 (score 降序, dst 升序) 稳定输出。
"""

from __future__ import annotations

import heapq
from collections import defaultdict

from ..ingest.config import CONFIDENCE_DEFAULT_MIN, MAX_HOPS
from .extractor import REFERENCES_TYPE

_DISCLAIMER = ("血缘为逆向推断、非物理外键（全库 0 显式外键）；证据上限 comment_explicit；"
               "仅引用/结构级，不含变换/ETL 数据流级血缘。")


def _cid(table: str, column: str) -> str:
    return f"column:{table}.{column}"


def build_reference_adj(graph, min_conf: float = 0.0):
    """从图内 REFERENCES 边构建列级正/反向邻接（仅列节点存在者，防御性复检）。"""
    out: dict[str, list[tuple[str, dict]]] = defaultdict(list)
    into: dict[str, list[tuple[str, dict]]] = defaultdict(list)
    skipped = 0
    for e in graph.edges:
        if e["type"] != REFERENCES_TYPE:
            continue
        if e.get("confidence", 0.0) < min_conf:
            continue
        if not (graph.has_node(e["src"]) and graph.has_node(e["dst"])):
            skipped += 1
            continue
        out[e["src"]].append((e["dst"], e))
        into[e["dst"]].append((e["src"], e))
    return out, into, skipped


def reference_traverse(graph, table: str, column: str | None = None,
                       direction: str = "downstream",
                       max_hops: int = MAX_HOPS,
                       min_conf: float = CONFIDENCE_DEFAULT_MIN) -> dict:
    """给定表/字段返回上/下游引用链（加权 BFS）。

    column=None ⇒ 表枢纽模式：以该表**全部列**为种子（UC5 表级→字段级推断血缘入口）。
    direction: downstream=反向可达(谁引用我) / upstream=我引用谁。
    """
    assert direction in ("downstream", "upstream")
    adj_out, adj_in, skipped = build_reference_adj(graph, min_conf)
    adj = adj_in if direction == "downstream" else adj_out
    if column is not None:
        seeds = [_cid(table, column)]
        if not graph.has_node(seeds[0]):
            return {"uc": "UC4/UC5(field-level)", "table": table, "column": column,
                    "exists": False, "paths": [], "disclaimer": _DISCLAIMER}
    else:
        tnode = f"table:{table}"
        if not graph.has_node(tnode):
            return {"uc": "UC4/UC5(table-pivot)", "table": table, "exists": False,
                    "paths": [], "disclaimer": _DISCLAIMER}
        seeds = [cid for cid in adj if cid.startswith(f"column:{table}.")] \
            if direction == "downstream" else \
            [cid for cid in adj_out if cid.startswith(f"column:{table}.")]
        if not seeds:
            return {"uc": "UC4/UC5(table-pivot)", "table": table, "exists": True,
                    "direction": direction, "seeds": 0, "paths": [],
                    "disclaimer": _DISCLAIMER}
    best_score: dict[str, float] = {}
    for s in seeds:
        best_score[s] = 1.0
    # (-score, hop, node, path, edges) ；heapq 天然按 score 降序展开（负分）
    heap = [(-1.0, 0, s, [s], []) for s in seeds]
    records: list[dict] = []
    truncated = 0
    while heap:
        neg, hop, node, path, _ = heapq.heappop(heap)
        score = -neg
        if hop >= max_hops:
            if adj.get(node):
                truncated += len(adj[node])
            continue
        if score < best_score.get(node, score) - 1e-12:
            continue                          # 已有更优分到达本节点
        for nb, e in sorted(adj.get(node, []), key=lambda x: (x[0])):
            w = score * e["confidence"]
            if w < min_conf:                  # 加权累计亦不得低于阈值（防低置信短路连边）
                continue
            rec = {
                "from": node, "to": nb, "hop": hop + 1,
                "path": path + [nb],
                "path_confidence": round(w, 6),
                "evidence_level": e["evidence_level"],
                "confidence": e["confidence"],
                "is_inferred": e["is_inferred"],
                "signals": e.get("signals", []),
                "source_file": (e.get("evidence_src") or {}).get("file"),
                "quote_hash": (e.get("evidence_src") or {}).get("quote_hash"),
            }
            records.append(rec)
            if w > best_score.get(nb, 0.0):
                best_score[nb] = w
                heapq.heappush(heap, (-w, hop + 1, nb, path + [nb], []))
    records.sort(key=lambda r: (-r["path_confidence"], r["path"][-1], r["hop"]))
    reached = {r["path"][-1] for r in records}
    tables = {x.split(":", 1)[1].split(".")[0] for x in reached}
    return {"uc": "UC4/UC5(field-level lineage)" if column else "UC4/UC5(table-pivot)",
            "table": table, "column": column, "exists": True,
            "direction": direction, "max_hops": max_hops,
            "min_confidence": min_conf,
            "seed_count": len(seeds), "reached_columns": len(reached),
            "reached_tables": len(tables), "edges_traversed": len(records),
            "adjacency_skipped_dangling": skipped,
            "truncated_at_budget": truncated,
            "paths": records, "disclaimer": _DISCLAIMER}


def table_impact(graph, table: str, max_hops: int = MAX_HOPS,
                 min_conf: float = CONFIDENCE_DEFAULT_MIN) -> dict:
    """UC5 影响面：变更该表（全部列）沿 REFERENCES 反向可达波及的下游列/表。"""
    r = reference_traverse(graph, table, None, "downstream", max_hops, min_conf)
    r["view"] = "table_impact"
    return r


def table_lineage_profile(graph, table: str) -> dict:
    """UC4 表级画像：该表列的 REFERENCES 出入明细（字段级推断血缘清单，按置信排序）。"""
    pre = f"column:{table}."
    out_edges, in_edges = [], []
    for e in graph.edges:
        if e["type"] != REFERENCES_TYPE:
            continue
        if e["src"].startswith(pre):
            out_edges.append(e)
        if e["dst"].startswith(pre):
            in_edges.append(e)
    fmt = lambda e: {"src_column": e["src_column"], "dst_table": e["dst_table"],
                     "dst_column": e["dst_column"], "evidence_level": e["evidence_level"],
                     "confidence": e["confidence"], "signals": e["signals"],
                     "source_file": e["evidence_src"]["file"],
                     "quote_hash": e["evidence_src"]["quote_hash"]}
    out_edges.sort(key=lambda e: (-e["confidence"], e["src_column"], e["dst"]))
    in_edges.sort(key=lambda e: (-e["confidence"], e["src"], e["dst_column"]))
    return {"uc": "UC4(table-profile)", "table": graph.nodes.get(f"table:{table}") and table,
            "exists": graph.has_node(f"table:{table}"),
            "outgoing_references": [fmt(e) for e in out_edges],
            "incoming_reference_count": len(in_edges),
            "incoming_by_level": _group_levels(in_edges),
            "disclaimer": _DISCLAIMER}


def _group_levels(in_edges: list[dict]) -> dict:
    g: dict[str, int] = defaultdict(int)
    for e in in_edges:
        g[e["evidence_level"]] += 1
    return dict(sorted(g.items()))
