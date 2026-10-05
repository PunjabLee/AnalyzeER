"""M1 · SQLite FTS5（porter unicode61）+ BM25 全文索引 + 中文兜底（trigram）。

对应 eval-baseline.md §2.1 档 1（关键词/全文）；不引入向量库。为 UC1 查表 / UC2 查字段 /
UC6 按域检索提供确定性全文召回，排序取 FTS5 内置 bm25()（rank 越小越相关）。

P1-3（CodeReview 修复）：
- `porter unicode61` 不做中日韩分词——纯中文查询此前恒 0 命中且失败被静默吞掉。
- 现新增 **中文兜底索引 `nodes_fts_cjk`（tokenize='trigram'）**，`search()` 走
  「拉丁/BM25 路 + 中文 trigram 路（+ 短 CJK LIKE 兜底）」双路合并去重。
- `except OperationalError: return []` 改为 **显式抛 `FTSQueryError`**，严格区分
  「无命中（正常返回 []）」与「索引缺失/损坏 或 查询非法（抛错）」。

物理形态：单文件 SQLite（graphrag/data/l0_index.db）。
- rebuild=True：装载侧重建表结构（DROP+CREATE）。
- rebuild=False：检索侧只读打开已存在的库（不破坏数据）。
"""

from __future__ import annotations

import re
import sqlite3
from pathlib import Path

_LABELS_INDEXED = ("Domain", "Table", "Column", "Issue")

# CJK 运行串（基本汉字 + 扩展 A + 兼容表意文字）；用于抽取中文检索片段。
_CJK_RUN_RE = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]+")
_TRIGRAM_MIN = 3          # FTS5 trigram 需 ≥3 字符才能构成一个三元组


class FTSQueryError(RuntimeError):
    """FTS 检索失败：索引缺失/损坏，或 MATCH 查询非法。

    与「合法查询但 0 命中」严格区分——后者正常返回 []，不抛此错。
    """


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
            DROP TABLE IF EXISTS nodes_fts_cjk;
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
            CREATE VIRTUAL TABLE nodes_fts_cjk USING fts5(
                node_id UNINDEXED,
                label   UNINDEXED,
                name,
                text,
                tokenize='trigram'
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
        rows_cjk = []
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
            doc = self._doc_text(node)
            rows_meta.append((node["node_id"], lab, name, domain, tier))
            rows_fts.append((node["node_id"], lab, name, doc))
            rows_cjk.append((node["node_id"], lab, name, doc))
            n += 1

        # P2 幂等保护：build() 总是写入完整图，先清空三表再全量插入，
        # 使「同一实例重复 build」不累加 FTS 行（nodes_fts 裸 INSERT 不再有历史残留）。
        cur.execute("DELETE FROM nodes")
        cur.execute("DELETE FROM nodes_fts")
        cur.execute("DELETE FROM nodes_fts_cjk")
        cur.executemany("INSERT OR REPLACE INTO nodes VALUES (?,?,?,?,?)", rows_meta)
        cur.executemany("INSERT INTO nodes_fts (node_id,label,name,text) VALUES (?,?,?,?)", rows_fts)
        cur.executemany(
            "INSERT INTO nodes_fts_cjk (node_id,label,name,text) VALUES (?,?,?,?)", rows_cjk)
        cur.commit()
        return n

    # ---------------------------------------------------------------- 检索入口
    def search(self, query: str, label: str | None = None,
               tiers=None, limit: int = 10) -> list[dict]:
        """双路全文检索并合并去重（P1-3）。

        tiers=None → 默认排除 C（A+B 可见）；可传 ('A',) 仅 A。
        - 拉丁/词干路：porter unicode61 + bm25。
        - 中文路：trigram 兜底（≥3 字）；对 <3 字的短 CJK 追加 LIKE 兜底。
        任一底层查询遇索引缺失/坏或 MATCH 非法 → 抛 FTSQueryError（不静默吞）。
        """
        if not query or not query.strip():
            return []

        candidates: list[dict] = []
        ascii_q = self._sanitize(query)
        if ascii_q:
            candidates += self._run_match("nodes_fts", ascii_q, label, tiers, limit)
        tri_q = self._trigram_query(query)
        if tri_q:
            candidates += self._run_match("nodes_fts_cjk", tri_q, label, tiers, limit)
        short_runs = [r for r in _CJK_RUN_RE.findall(query) if len(r) < _TRIGRAM_MIN]
        if short_runs:
            candidates += self._run_like(short_runs, label, tiers, limit)

        best: dict[str, dict] = {}
        for r in candidates:
            key = r["node_id"]
            prev = best.get(key)
            if prev is None or self._eff(r) < self._eff(prev):
                best[key] = r
        merged = sorted(best.values(), key=lambda r: (self._eff(r), r["node_id"]))
        return merged[:limit]

    @staticmethod
    def _eff(row: dict) -> float:
        """有效排序键：bm25 越小越相关；LIKE 兜底（bm25=None）置于末位。"""
        b = row.get("bm25")
        return float("inf") if b is None else b

    # ---------------------------------------------------------------- 腿执行
    def _run_match(self, table: str, match_q: str, label, tiers, limit) -> list[dict]:
        sql = (
            f"SELECT f.node_id, f.label, f.name, m.domain, m.tier, bm25({table}) AS score "
            f"FROM {table} f JOIN nodes m ON m.node_id = f.node_id "
            f"WHERE {table} MATCH ? "
        )
        params: list = [match_q]
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
            rows = self.conn.execute(sql, params).fetchall()
        except sqlite3.OperationalError as exc:   # P1-3：索引缺失/坏 或 查询非法 → 显式抛错
            raise FTSQueryError(
                f"FTS 检索失败（table={table}, match={match_q!r}）：索引缺失/损坏或查询非法"
            ) from exc
        return [
            {"node_id": r[0], "label": r[1], "name": r[2], "domain": r[3],
             "tier": r[4], "bm25": r[5]}
            for r in rows
        ]

    def _run_like(self, runs: list[str], label, tiers, limit) -> list[dict]:
        """短 CJK（<3 字，trigram 无法成元）的 LIKE 兜底路（bm25=None，排序末位）。"""
        conds = " OR ".join(["(f.name LIKE ? OR f.text LIKE ?)"] * len(runs))
        sql = (
            "SELECT f.node_id, f.label, f.name, m.domain, m.tier "
            "FROM nodes_fts f JOIN nodes m ON m.node_id = f.node_id "
            f"WHERE {conds} "
        )
        params: list = []
        for r in runs:
            like = f"%{r}%"
            params += [like, like]
        if label:
            sql += "AND f.label = ? "
            params.append(label)
        if tiers:
            ph = ",".join("?" * len(tiers))
            sql += f"AND m.tier IN ({ph}) "
            params.extend(tiers)
        else:
            sql += "AND (m.tier IS NULL OR m.tier <> 'C') "
        sql += "ORDER BY f.node_id LIMIT ?"
        params.append(limit)
        try:
            rows = self.conn.execute(sql, params).fetchall()
        except sqlite3.OperationalError as exc:
            raise FTSQueryError(
                f"FTS LIKE 兜底检索失败（runs={runs!r}）：索引缺失/损坏"
            ) from exc
        return [
            {"node_id": r[0], "label": r[1], "name": r[2], "domain": r[3],
             "tier": r[4], "bm25": None}
            for r in rows
        ]

    # ---------------------------------------------------------------- 查询构造
    @staticmethod
    def _sanitize(query: str) -> str:
        """去 FTS5 特殊字符，按空白/标点切词，OR 连接（porter 词干化在各词生效）。

        CJK 词也保留（unicode61 视作整词，通常 0 命中，交由中文路兜底）。
        """
        toks = [t for t in (
            w.strip('",()').replace("*", "") for w in query.replace("/", " ").split()
        ) if t]
        # 仅保留可搜索词元（含 CJK/字母数字）
        toks = [t for t in toks if any(ch.isalnum() for ch in t)]
        return " OR ".join(toks)

    @staticmethod
    def _trigram_query(query: str) -> str:
        """抽取中文运行串，长度 ≥3 者作引号短语，OR 连接（trigram 短语=连续三元组）。"""
        phrases = [f'"{run}"' for run in _CJK_RUN_RE.findall(query)
                   if len(run) >= _TRIGRAM_MIN]
        return " OR ".join(phrases)

    def close(self):
        self.conn.close()
