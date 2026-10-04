"""M2 · 证据锚（EvidenceSrc 构造，schema.md §1.6）。

稳定锚 = {file, table, column, quote_hash}；`line_hint` 为尽力字段（承 M0 P1-2 定因：
行数两口径 28,339 vs 26,998，行号不得作唯一稳定身份）。

- DDL 直证（COMMENT 点名/列名/index）：file=test_erp.sql + 命中行的规范化 quote_hash。
- er-model 文档佐证（M1 RELATES_TO 线）：直接复用 M1 已建 EvidenceSrc
  （source_file/quote_hash/line_hint），**不另立第二套哈希**，避免与 M1 双源分叉。
"""

from __future__ import annotations

import hashlib
import re
from pathlib import Path

_TABLE_LINE_RE = re.compile(r"^CREATE TABLE `(?P<t>[^`]+)`")
_COL_LINE_RE = re.compile(r"^\s{2}`(?P<c>[A-Za-z0-9_]+)`\s+\S")


def norm_quote(text: str) -> str:
    """规范化引用片段：压空白，抗排版漂移。"""
    return re.sub(r"\s+", " ", (text or "").strip())


def quote_hash(text: str) -> str:
    return hashlib.sha1(norm_quote(text).encode("utf-8")).hexdigest()[:16]


def build_ddl_line_index(sql_path: Path) -> dict[str, dict[str, dict]]:
    """单遍扫描 test_erp.sql → {table: {column: {"line", "text"}}}。

    仅登记 CREATE TABLE 块内的列定义行（首个定义优先；同表重复名理论上不存在）。
    """
    index: dict[str, dict[str, dict]] = {}
    cur: str | None = None
    with sql_path.open(encoding="utf-8", errors="replace") as fh:
        for no, raw in enumerate(fh, start=1):
            mt = _TABLE_LINE_RE.match(raw)
            if mt:
                cur = mt.group("t")
                index.setdefault(cur, {})
                continue
            if cur is None:
                continue
            if raw.startswith(")"):
                cur = None
                continue
            mc = _COL_LINE_RE.match(raw)
            if mc:
                col = mc.group("c")
                slot = index[cur]
                if col not in slot:
                    slot[col] = {"line": no, "text": raw.strip()}
    return index


def ddl_evidence(line_index: dict, table: str, column: str,
                 signal: str) -> dict:
    """DDL 直证锚（file=实际出处的物理文件；结构事实仲裁者本身）。"""
    hit = line_index.get(table, {}).get(column)
    text = hit["text"] if hit else f"{table}.{column}"
    return {
        "file": "test_erp.sql",
        "section": f"CREATE TABLE `{table}`",
        "table": table,
        "column": column,
        "quote_hash": quote_hash(text),
        "line_hint": hit["line"] if hit else None,
        "signal": signal,
    }


def doc_evidence(rel_edge: dict, graph_nodes: dict) -> dict:
    """复用 M1 RELATES_TO 的 EvidenceSrc（er-model 一等锚，哈希同源自 M1，不双立）。"""
    ev_id = rel_edge.get("evidence_ref")
    ev = graph_nodes.get(ev_id) or {}
    return {
        "file": rel_edge.get("source_file") or ev.get("file"),
        "section": ev.get("section"),
        "table": ev.get("table") or rel_edge.get("src"),
        "column": rel_edge.get("via_column"),
        "quote_hash": rel_edge.get("quote_hash") or ev.get("quote_hash"),
        "line_hint": ev.get("line_hint"),
        "signal": "doc_relation_line",
        "via_relates_to": (rel_edge.get("src"), rel_edge.get("dst")),
    }
