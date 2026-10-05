"""M2 · 引用级血缘的遍历/影响分析（BFS + 置信加权 + 深度预算剪枝）。

方向语义（列级）：
- `upstream`（血缘，UC4）：沿 REFERENCES **出边** —— 本列引用了谁（值从谁那里"被引用"）。
- `downstream`（影响，UC5）：沿 REFERENCES **入边**（反向可达）—— 谁引用了本列；
  变更本列值域将波及这些列/表。

口径（evidence-confidence-map §1.1；多跳策略与 M1 一致）：
- **准入硬门 = 逐边 `confidence ≥ min_conf`**（默认 0.45；semantic/unconfirmed 默认隐藏）。
- **路径连乘仅作 `path_score`**（排序 / 优先展开，同列取最优分 first-best 去重），
  **不再**充当准入门 —— 以免长链被连乘误杀（旧实现把 `score*conf < min_conf` 当硬门，
  与批准口径不符；本轮 P1 对齐）。
- 存疑门：`show_uncertain=False`（默认）时，`unconfirmed` / `has_uncertain` / 引文带
  `[待确认]` 的边不参与遍历（查询期过滤，不删边）。多跳默认 ≤3、超限截断可复现（truncated 计数）。
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


def _is_uncertain(e: dict) -> bool:
    """边是否"存疑"（与 M1 `search/l1._is_uncertain` 同判据 + 引文兜底）。"""
    if e.get("has_uncertain") or e.get("evidence_level") == "unconfirmed":
        return True
    return "待确认" in ((e.get("evidence_src") or {}).get("quote") or "")


def build_reference_adj(graph, min_conf: float = 0.0,
                        show_uncertain: bool = False):
    """从图内 REFERENCES 边构建列级正/反向邻接（仅列节点存在者，防御性复检）。

    min_conf 为**逐边**准入门（默认 0.0=不在此层过滤，由遍历侧统一把关）。
    """
    out: dict[str, list[tuple[str, dict]]] = defaultdict(list)
    into: dict[str, list[tuple[str, dict]]] = defaultdict(list)
    skipped = 0
    for e in graph.edges:
        if e["type"] != REFERENCES_TYPE:
            continue
        if e.get("confidence", 0.0) < min_conf:
            continue
        if not show_uncertain and _is_uncertain(e):
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
                       min_conf: float = CONFIDENCE_DEFAULT_MIN,
                       show_uncertain: bool = False) -> dict:
    """给定表/字段返回上/下游引用链（加权 BFS）。

    column=None ⇒ 表枢纽模式：以该表**全部列**为种子（UC5 表级→字段级推断血缘入口）。
    direction: downstream=反向可达(谁引用我) / upstream=我引用谁。
    """
    assert direction in ("downstream", "upstream")
    adj_out, adj_in, skipped = build_reference_adj(graph, min_conf, show_uncertain)
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
            conf = e.get("confidence", 0.0)
            if conf < min_conf:               # 准入硬门：**逐边**置信（非路径连乘）
                continue
            w = score * conf                  # path_score：仅排序/优先展开用
            rec = {
                "from": node, "to": nb, "hop": hop + 1,
                "path": path + [nb],
                "path_score": round(w, 6),
                "path_confidence": round(w, 6),      # 旧字段名保留（排序值，非准入门）
                "evidence_level": e["evidence_level"],
                "confidence": conf,
                "is_inferred": e["is_inferred"],
                "signals": e.get("signals", []),
                "source_file": (e.get("evidence_src") or {}).get("file"),
                "quote_hash": (e.get("evidence_src") or {}).get("quote_hash"),
            }
            records.append(rec)
            if w > best_score.get(nb, 0.0):
                best_score[nb] = w
                heapq.heappush(heap, (-w, hop + 1, nb, path + [nb], []))
    records.sort(key=lambda r: (-r["path_score"], r["path"][-1], r["hop"]))
    reached = {r["path"][-1] for r in records}
    tables = {x.split(":", 1)[1].split(".")[0] for x in reached}
    return {"uc": "UC4/UC5(field-level lineage)" if column else "UC4/UC5(table-pivot)",
            "table": table, "column": column, "exists": True,
            "direction": direction, "max_hops": max_hops,
            "min_confidence": min_conf, "show_uncertain": show_uncertain,
            "hop_gate": "per-edge confidence>=min_conf（硬门）；path_score=连乘，仅排序",
            "seed_count": len(seeds), "reached_columns": len(reached),
            "reached_tables": len(tables), "edges_traversed": len(records),
            "adjacency_skipped_dangling": skipped,
            "truncated_at_budget": truncated,
            "paths": records, "disclaimer": _DISCLAIMER}


def table_impact(graph, table: str, max_hops: int = MAX_HOPS,
                 min_conf: float = CONFIDENCE_DEFAULT_MIN,
                 show_uncertain: bool = False) -> dict:
    """UC5 影响面：变更该表（全部列）沿 REFERENCES 反向可达波及的下游列/表。"""
    r = reference_traverse(graph, table, None, "downstream", max_hops, min_conf,
                           show_uncertain)
    r["view"] = "table_impact"
    return r


def table_lineage_profile(graph, table: str, min_conf: float = 0.0,
                          show_uncertain: bool = False) -> dict:
    """UC4 表级画像：该表列的 REFERENCES 出入明细（字段级推断血缘清单，按置信排序）。

    全量盘点口径（默认 min_conf=0.0 不截断，低置信者自带标记）；需与检索默认口径一致时
    传 `min_conf=CONFIDENCE_DEFAULT_MIN`（P1-3 消费门）与 `show_uncertain`。
    """
    pre = f"column:{table}."
    out_edges, in_edges = [], []
    for e in graph.edges:
        if e["type"] != REFERENCES_TYPE:
            continue
        if e.get("confidence", 0.0) < min_conf:
            continue
        if not show_uncertain and _is_uncertain(e):
            continue
        if e["src"].startswith(pre):
            out_edges.append(e)
        if e["dst"].startswith(pre):
            in_edges.append(e)
    fmt = lambda e: {"src_column": e["src_column"], "dst_table": e["dst_table"],
                     "dst_column": e["dst_column"], "evidence_level": e["evidence_level"],
                     "confidence": e["confidence"], "signals": e["signals"],
                     "self_reference": e["src_table"] == e["dst_table"],
                     "target_col_rule": e.get("target_col_rule"),
                     "source_file": e["evidence_src"]["file"],
                     "quote_hash": e["evidence_src"]["quote_hash"]}
    out_edges.sort(key=lambda e: (-e["confidence"], e["src_column"], e["dst"]))
    in_edges.sort(key=lambda e: (-e["confidence"], e["src"], e["dst_column"]))
    return {"uc": "UC4(table-profile)", "table": graph.nodes.get(f"table:{table}") and table,
            "exists": graph.has_node(f"table:{table}"),
            "min_confidence": min_conf, "show_uncertain": show_uncertain,
            "outgoing_references": [fmt(e) for e in out_edges],
            "incoming_reference_count": len(in_edges),
            "incoming_by_level": _group_levels(in_edges),
            "disclaimer": _DISCLAIMER}


def _group_levels(in_edges: list[dict]) -> dict:
    g: dict[str, int] = defaultdict(int)
    for e in in_edges:
        g[e["evidence_level"]] += 1
    return dict(sorted(g.items()))
