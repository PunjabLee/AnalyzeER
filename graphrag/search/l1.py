"""M1 · L1 检索主干（确定性：SQLite FTS5/BM25 + 内存图邻接遍历）。

覆盖（eval-baseline.md §1 / §4「M1 跑 UC1–UC3 + 表级血缘基线」）：
- UC1 查表：BM25 全文 + tier/域 过滤（默认排除 C，`include_c=True` 开关）。
- UC2 查字段：给定表→全列（IS_COLUMN_OF）；反向→含该列的表集（列名精确 + 全文）。
- UC3 查关系：1 跳 RELATES_TO 邻居，附 cardinality/evidence_level/confidence/source_ref。
- 表级推断血缘/影响遍历：BFS ≤MAX_HOPS(3)，confidence≥min 剪枝，仅走 A 级可遍历主语料
  （B/C 影子不入遍历；schema §3）。方向 'out'=下游/影响，'in'=上游/血缘，'both'=无向邻域。

**通则**：所有关系边 is_inferred=true（全库 0 FK），回答须显式声明「逆向推断、非物理外键」。
默认只召回 confidence ≥ CONFIDENCE_DEFAULT_MIN(0.45)。fts 可传 None（仅图查询，跳过全文近似）。
"""

from __future__ import annotations

from collections import deque

from ..ingest.config import CONFIDENCE_DEFAULT_MIN, MAX_HOPS

_NO_FK_DISCLAIMER = (
    "关系为逆向推断、非物理外键（全库 0 显式外键）；最高证据仅至 comment_explicit。"
)


class L1Search:
    def __init__(self, graph, fts=None):
        self.g = graph
        self.fts = fts

    # ------------------------------------------------------------ UC1
    def find_tables(self, query: str, domain: str | None = None,
                    include_c: bool = False, include_b: bool = True,
                    limit: int = 10) -> dict:
        if self.fts is None:
            return {"uc": "UC1", "query": query, "results": [], "count": 0,
                    "note": "未接全文索引（FTS）"}
        tiers = ("A", "B", "C") if include_c else (("A", "B") if include_b else ("A",))
        hits = self.fts.search(query, label="Table", tiers=tiers, limit=limit * 3)
        if domain:
            hits = [h for h in hits if h.get("domain") == domain]
        return {"uc": "UC1", "query": query,
                "results": [{**h, "tier_scope": list(tiers)} for h in hits[:limit]],
                "count": len(hits[:limit])}

    # ------------------------------------------------------------ UC2
    def columns_of(self, table: str) -> dict:
        tid = f"table:{table}"
        if not self.g.has_node(tid):
            return {"uc": "UC2", "table": table, "exists": False, "columns": [],
                    "note": "表未在登记集（检查物理名/大小写）"}
        cols = []
        for _, e in self.g.neighbors(tid, "IS_COLUMN_OF", direction="in"):
            cnode = self.g.nodes[e["src"]]
            cols.append({
                "name": cnode["name"], "data_type": cnode["data_type"],
                "nullable": cnode["nullable"], "default": cnode["default"],
                "key_role": cnode["key_role"], "semantic": cnode["semantic"],
            })
        cols.sort(key=lambda c: c["name"])
        return {"uc": "UC2", "table": table, "exists": True,
                "column_count": len(cols), "columns": cols}

    def tables_with_column(self, column: str, limit: int = 20) -> dict:
        """反向：含该列名（精确）的 A 级表集（列节点仅 A 级建）+ 语义全文近似表集。"""
        exact = [n["table_id"] for n in self.g.nodes.values()
                 if n["label"] == "Column" and n["name"] == column]
        fuzzy_tabs = []
        if self.fts is not None:
            fuzzy = self.fts.search(column, label="Column", limit=limit * 3)
            fuzzy_tabs = sorted({h["name"].split(".")[0] for h in fuzzy})
        return {"uc": "UC2", "column": column,
                "exact_tables": sorted(exact), "exact_count": len(exact),
                "semantic_tables": fuzzy_tabs[:limit]}

    # ------------------------------------------------------------ UC3
    def relations_of(self, table: str, min_conf: float = CONFIDENCE_DEFAULT_MIN,
                     both: bool = True) -> dict:
        tid = f"table:{table}"
        if not self.g.has_node(tid):
            return {"uc": "UC3", "table": table, "exists": False, "relations": []}
        rels = []
        seen = set()
        dirs = ["out", "in"] if both else ["out"]
        for d in dirs:
            for _, e in self.g.neighbors(tid, "RELATES_TO", direction=d):
                key = (e["src"], e["dst"], e.get("quote_hash"))
                if key in seen:
                    continue
                seen.add(key)
                if e.get("confidence", 0) < min_conf:
                    continue
                other = e["dst"] if e["src"] == tid else e["src"]
                rels.append({
                    "src": e["src"].split(":", 1)[1], "dst": e["dst"].split(":", 1)[1],
                    "other": other.split(":", 1)[1], "direction": "out" if d == "out" else "in",
                    "cardinality": e["cardinality"], "evidence_level": e["evidence_level"],
                    "confidence": e["confidence"], "via_column": e["via_column"],
                    "is_inferred": e["is_inferred"], "cross_domain": e["cross_domain"],
                    "malformed_connector": e["malformed_connector"],
                    "source_file": e["source_file"], "quote_hash": e.get("quote_hash"),
                })
        rels.sort(key=lambda r: -r["confidence"])
        return {"uc": "UC3", "table": table, "exists": True,
                "min_confidence": min_conf, "count": len(rels),
                "relations": rels, "disclaimer": _NO_FK_DISCLAIMER}

    # --------------------------------------------- 表级血缘/影响遍历
    def traverse(self, table: str, direction: str = "out",
                 max_hops: int = MAX_HOPS,
                 min_conf: float = CONFIDENCE_DEFAULT_MIN) -> dict:
        """BFS（带深度上限与置信剪枝）。仅走 A 级（neighbors traverse_tier='A'）。"""
        start = f"table:{table}"
        if not self.g.has_node(start):
            return {"uc": "UC4/UC5(table-level)", "table": table, "exists": False, "paths": []}
        assert direction in ("out", "in", "both")
        visited = {start}
        queue = deque([(start, 0, [])])
        records = []
        frontier_at_budget = 0
        while queue:
            node, depth, path = queue.popleft()
            if depth >= max_hops:
                frontier_at_budget += len(self._rel_edges(node, direction, min_conf))
                continue
            for nb, e in self._iter_rel(node, direction, min_conf):
                records.append({
                    "from": node.split(":", 1)[1], "to": nb.split(":", 1)[1],
                    "hop": depth + 1, "path": path + [nb.split(":", 1)[1]],
                    "cardinality": e["cardinality"], "evidence_level": e["evidence_level"],
                    "confidence": e["confidence"], "via_column": e["via_column"],
                    "source_file": e["source_file"], "quote_hash": e.get("quote_hash"),
                })
                if nb not in visited:
                    visited.add(nb)
                    queue.append((nb, depth + 1, path + [nb.split(":", 1)[1]]))
        return {"uc": "UC4/UC5(table-level)", "table": table, "exists": True,
                "direction": direction, "max_hops": max_hops, "min_confidence": min_conf,
                "reached_tables": len(visited) - 1, "edge_traversed": len(records),
                "truncated_at_budget": frontier_at_budget > 0,
                "frontier_edges_beyond_budget": frontier_at_budget,
                "paths": records, "disclaimer": _NO_FK_DISCLAIMER}

    def _iter_rel(self, node, direction, min_conf):
        dirs = ["out", "in"] if direction == "both" else [direction]
        for d in dirs:
            for nb, e in self.g.neighbors(node, "RELATES_TO", direction=d,
                                           min_conf=min_conf, traverse_tier="A"):
                yield nb, e

    def _rel_edges(self, node, direction, min_conf):
        return list(self._iter_rel(node, direction, min_conf))

    # ------------------------------------------------------------ UC6
    def domain_summary(self, domain: str) -> dict:
        dnode = f"domain:{domain}"
        tables = []
        for _, e in self.g.neighbors(dnode, "BELONGS_TO_DOMAIN", direction="in"):
            t = self.g.nodes[e["src"]]
            tables.append({"name": t["name"], "tier": t.get("tier"),
                           "column_count": t.get("column_count")})
        issue_ids: set[str] = set()
        rel_count = 0
        for tb in tables:
            tid = f"table:{tb['name']}"
            for _, e in self.g.neighbors(tid, "HAS_ISSUE", direction="out"):
                issue_ids.add(self.g.nodes[e["dst"]]["issue_id"])
            rel_count += len(self.g.neighbors(tid, "RELATES_TO", direction="out"))
        tables.sort(key=lambda x: x["name"])
        return {"uc": "UC6", "domain": domain, "exists": self.g.has_node(dnode),
                "table_count": len(tables), "tables": tables,
                "relates_to_edges": rel_count,
                "issues": sorted(issue_ids), "issue_count": len(issue_ids),
                "disclaimer": _NO_FK_DISCLAIMER}
