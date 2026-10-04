"""M1 · SQLite FTS5（porter unicode61）+ BM25 全文索引。

对应 eval-baseline.md §2.1 档 1（关键词/全文）；不引入向量库。为 UC1 查表 / UC2 查字段 /
UC6 按域检索提供确定性全文召回，排序取 FTS5 内置 bm25()（rank 越小越相关）。

物理形态：单文件 SQLite（graphrag/data/l0_index.db）。
- rebuild=True：装载侧重建表结构（DROP+CREATE）。
- rebuild=False：检索侧只读打开已存在的库（不破坏数据）。
"""

from __future__ import annotations

import sqlite3
from pathlib import Path

_LABELS_INDEXED = ("Domain", "Table", "Column", "Issue")


class FTSIndex:
    def __init__(self, db_path, rebuild: bool = False):
        self.db_path = str(db_path)
        Path(self.db_path).parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(self.db_path)
        if rebuild:
            self._schema()

    def _schema(self):
        cur = self.conn
        cur.executescript(
            """
            DROP TABLE IF EXISTS nodes;
            DROP TABLE IF EXISTS nodes_fts;
            CREATE TABLE nodes (
                node_id  TEXT PRIMARY KEY,
                label    TEXT,
                name     TEXT,
                domain   TEXT,
                tier     TEXT
            );
            CREATE VIRTUAL TABLE nodes_fts USING fts5(
                node_id UNINDEXED,
                label   UNINDEXED,
                name,
                text,
                tokenize='porter unicode61'
            );
            """
        )
        cur.commit()

    def _doc_text(self, node: dict) -> str:
        lab = node["label"]
        if lab == "Table":
            parts = [node["name"]]
            if node.get("table_comment"):
                parts.append(node["table_comment"])
            if node.get("domain"):
                parts.append(str(node["domain"]))
            return " ".join(p for p in parts if p)
        if lab == "Column":
            return " ".join(filter(None, [
                node.get("semantic") or "",
                node.get("name") or "",
                node.get("table_id") or "",
            ]))
        if lab == "Issue":
            return " ".join(filter(None, [
                node.get("title") or "", node.get("category_name") or "",
            ]))
        if lab == "Domain":
            return node.get("domain_id") or node.get("name") or ""
        return node.get("name") or ""

    def build(self, graph) -> int:
        cur = self.conn
        n = 0
        rows_fts = []
        rows_meta = []
        for node in graph.nodes.values():
            lab = node["label"]
            if lab not in _LABELS_INDEXED:
                continue
            # B/C 级 Table 亦登记可搜，但带 tier 供过滤（默认检索降权由此支撑）
            name = node.get("name") or node.get("domain_id") or node.get("issue_id") or node["node_id"]
            domain = node.get("domain") or node.get("domain_id")
            tier = node.get("tier")
            if lab == "Column":
                domain = node.get("table_id")
            rows_meta.append((node["node_id"], lab, name, domain, tier))
            rows_fts.append((node["node_id"], lab, name, self._doc_text(node)))
            n += 1
        cur.executemany("INSERT OR REPLACE INTO nodes VALUES (?,?,?,?,?)", rows_meta)
        cur.executemany("INSERT INTO nodes_fts (node_id,label,name,text) VALUES (?,?,?,?)", rows_fts)
        cur.commit()
        return n

    def search(self, query: str, label: str | None = None,
               tiers=None, limit: int = 10) -> list[dict]:
        """BM25 全文检索。tiers=None → 默认排除 C（A+B 可见）；可传 ('A',) 仅 A。"""
        q = self._sanitize(query)
        if not q:
            return []
        cur = self.conn
        sql = (
            "SELECT f.node_id, f.label, f.name, m.domain, m.tier, bm25(nodes_fts) AS score "
            "FROM nodes_fts f JOIN nodes m ON m.node_id = f.node_id "
            "WHERE nodes_fts MATCH ? "
        )
        params: list = [q]
        if label:
            sql += "AND f.label = ? "
            params.append(label)
        if tiers:
            ph = ",".join("?" * len(tiers))
            sql += f"AND m.tier IN ({ph}) "
            params.extend(tiers)
        else:
            sql += "AND (m.tier IS NULL OR m.tier <> 'C') "
        sql += "ORDER BY score LIMIT ?"
        params.append(limit)
        try:
            rows = cur.execute(sql, params).fetchall()
        except sqlite3.OperationalError:
            return []
        return [
            {"node_id": r[0], "label": r[1], "name": r[2], "domain": r[3],
             "tier": r[4], "bm25": r[5]}
            for r in rows
        ]

    @staticmethod
    def _sanitize(query: str) -> str:
        """去 FTS5 特殊字符，按空白/标点切词，OR 连接（porter 词干化在各词生效）。"""
        toks = [t for t in (
            w.strip('",()').replace("*", "") for w in query.replace("/", " ").split()
        ) if t]
        # 仅保留可搜索词元（含 CJK/字母数字）
        toks = [t for t in toks if any(ch.isalnum() for ch in t)]
        return " OR ".join(toks)

    def close(self):
        self.conn.close()
