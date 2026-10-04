"""M1 · DDL 解析器（test_erp.sql = 结构事实最终仲裁）。

产出每表权威结构事实：列（名/类型/可空/默认/键/含义/字符集/排序）、主键列、有无 PK、
列数、索引列集、表级字符集/注释。全库 0 外键 → 不解析 FOREIGN KEY（实测 0）。

依据：schema.md §0/§1.2/§1.3；eval-baseline.md §3（1322/1311/0/11）。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

from .config import SQL_FILE

# CREATE TABLE 块（body 到 `) ENGINE ... ;` 前）
_BLOCK_RE = re.compile(
    r"CREATE TABLE `(?P<name>[^`]+)`  \(\n(?P<body>.*?)\n\) ENGINE(?P<tail>[^;]*);",
    re.DOTALL,
)

_TYPE_RE = re.compile(
    r"^(?P<type>[A-Za-z]+(?:\s*\([\d\s,]+\))?(?:\s+unsigned)?(?:\s+zerofill)?)"
    r"(?:\s+character|(?=[\s,]|$))",
    re.IGNORECASE,
)
_NAME_RE = re.compile(r"^`(?P<name>[^`]+)`\s+(?P<rest>.*)$", re.DOTALL)
_CHARSET_RE = re.compile(r"CHARACTER SET\s+(\w+)", re.IGNORECASE)
_COLLATE_RE = re.compile(r"COLLATE\s*=?\s*(\w+)", re.IGNORECASE)
_DEFAULT_RE = re.compile(
    r"DEFAULT\s+(?P<val>NULL|'[^']*'|\"[^\"]*\"|[0-9.]+(?:e[0-9+-]+)?|CURRENT_TIMESTAMP(?:\(\d*\))?)",
    re.IGNORECASE,
)
_COMMENT_RE = re.compile(r"COMMENT\s+'(?P<c>[\s\S]*)'\s*,?\s*$")
_PK_LINE_RE = re.compile(r"^PRIMARY KEY\s*\((?P<cols>[^)]*)\)", re.IGNORECASE)
_UNIQ_LINE_RE = re.compile(r"^UNIQUE (?:INDEX|KEY)\s*`[^`]+`\s*\((?P<cols>[^)]*)\)", re.IGNORECASE)
_IDX_LINE_RE = re.compile(r"^(?:INDEX|KEY)\s*`[^`]+`\s*\((?P<cols>[^)]*)\)", re.IGNORECASE)
_BACKTICK_RE = re.compile(r"`([^`]+)`")
_TABLE_CHARSET_RE = re.compile(r"CHARACTER SET\s*=\s*(\w+)", re.IGNORECASE)
_TABLE_COLLATE_RE = re.compile(r"COLLATE\s*=\s*(\w+)", re.IGNORECASE)
_TABLE_COMMENT_RE = re.compile(r"COMMENT\s*=\s*'(?P<c>[^']*)'")


@dataclass
class Column:
    name: str
    data_type: str
    nullable: bool
    default: object | None
    key_role: str            # PK | UK | none
    semantic: str            # DDL COMMENT（权威含义）
    charset: str | None = None
    collation: str | None = None
    auto_increment: bool = False
    indexed: bool = False    # 出现在普通 INDEX 列集


@dataclass
class DDLTable:
    name: str
    columns: list[Column] = field(default_factory=list)
    pk_columns: list[str] = field(default_factory=list)
    has_pk: bool = False
    column_count: int = 0
    table_charset: str | None = None
    table_collation: str | None = None
    table_comment: str | None = None
    indexed_columns: set[str] = field(default_factory=set)
    unique_columns: set[str] = field(default_factory=set)


def _split_cols(spec: str) -> list[str]:
    return _BACKTICK_RE.findall(spec)


def _parse_column_line(line: str) -> Column | None:
    m = _NAME_RE.match(line)
    if not m:
        return None
    name = m.group("name")
    rest = m.group("rest")
    rest_clean = rest.rstrip().rstrip(",")

    tm = re.match(
        r"^(?P<type>[A-Za-z]+(?:\s*\([\d\s,]+\))?(?:\s+unsigned)?(?:\s+zerofill)?)",
        rest_clean,
    )
    data_type = tm.group("type").strip() if tm else ""

    nullable = not re.search(r"\bNOT\s+NULL\b", rest_clean, re.IGNORECASE)
    dm = _DEFAULT_RE.search(rest_clean)
    default = dm.group("val") if dm else None
    auto_increment = bool(re.search(r"AUTO_INCREMENT", rest_clean, re.IGNORECASE))
    cm = _COMMENT_RE.search(rest_clean)
    semantic = cm.group("c") if cm else ""
    cs = _CHARSET_RE.search(rest_clean)
    co = _COLLATE_RE.search(rest_clean)
    return Column(
        name=name,
        data_type=data_type,
        nullable=nullable,
        default=default,
        key_role="none",
        semantic=semantic,
        charset=cs.group(1) if cs else None,
        collation=co.group(1) if co else None,
        auto_increment=auto_increment,
    )


def parse_block(name: str, body: str, tail: str) -> DDLTable:
    t = DDLTable(name=name)
    cols: list[Column] = []
    for raw in body.split("\n"):
        line = raw.strip()
        if not line:
            continue
        if _PK_LINE_RE.match(line):
            pk = _split_cols(_PK_LINE_RE.match(line).group("cols"))
            t.pk_columns = pk
            t.has_pk = True
            continue
        mu = _UNIQ_LINE_RE.match(line)
        if mu:
            t.unique_columns.update(_split_cols(mu.group("cols")))
            continue
        mi = _IDX_LINE_RE.match(line)
        if mi:
            t.indexed_columns.update(_split_cols(mi.group("cols")))
            continue
        if line.startswith("`"):
            c = _parse_column_line(line)
            if c:
                cols.append(c)
    # 键角色
    pk_set = set(t.pk_columns)
    for c in cols:
        if c.name in pk_set:
            c.key_role = "PK"
        elif c.name in t.unique_columns:
            c.key_role = "UK"
        if c.name in t.indexed_columns:
            c.indexed = True
    t.columns = cols
    t.column_count = len(cols)
    if tail:
        cs = _TABLE_CHARSET_RE.search(tail)
        co = _TABLE_COLLATE_RE.search(tail)
        cm = _TABLE_COMMENT_RE.search(tail)
        t.table_charset = cs.group(1) if cs else None
        t.table_collation = co.group(1) if co else None
        t.table_comment = cm.group("c") if cm else None
    return t


def parse_ddl(sql_path=SQL_FILE) -> dict[str, DDLTable]:
    """解析全量 CREATE TABLE → {table_name: DDLTable}。"""
    text = sql_path.read_text(encoding="utf-8", errors="replace")
    out: dict[str, DDLTable] = {}
    for m in _BLOCK_RE.finditer(text):
        t = parse_block(m.group("name"), m.group("body"), m.group("tail"))
        out[t.name] = t
    return out


def ddl_metrics(tables: dict[str, DDLTable]) -> dict:
    total = len(tables)
    no_pk = sum(1 for t in tables.values() if not t.has_pk)
    with_pk = total - no_pk
    total_cols = sum(t.column_count for t in tables.values())
    return {
        "ddl_table_total": total,
        "pk_declared_tables": with_pk,
        "no_pk_tables": no_pk,
        "total_columns": total_cols,
    }
