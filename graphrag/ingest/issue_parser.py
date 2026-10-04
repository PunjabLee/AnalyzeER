"""M1 · Issue 解析器（05 §三 A–G 列表项 → Issue 节点 + HAS_ISSUE scope）。

入图 Issue 数 = 05 实测枚举 27（A3+B4+C4+D5+E3+F4+G4）。
口径差 C-δ：`00`/`Agents.md` 标 30 → **采实测 27**，禁止静默吞差（schema §1.5）。
scope：列表项文本中反引号点名且命中 DDL 表集的 → HAS_ISSUE（可定位才连，不臆造）。

依据：schema.md §1.5；eval-baseline.md §3。
"""

from __future__ import annotations

import re

from .config import FILE_05

_ITEM_RE = re.compile(r"^[ \t]*-[ \t]+\*\*(?P<cat>[A-G])-(?P<num>\d+)[^*]*\*\*(?P<body>.*)$")
_BOLD_TITLE_RE = re.compile(r"^[ \t]*-[ \t]+\*\*(?P<title>[^*]+)\*\*")
_BACKTICK_RE = re.compile(r"`([^`]+)`")
_ID_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def parse_issues(tables: set[str], path=FILE_05) -> dict:
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()

    # 类别标题（### A. 引用完整性）
    cat_names: dict[str, str] = {}
    for line in lines:
        m = re.match(r"^###\s+([A-G])\.\s+(.+?)\s*$", line)
        if m:
            cat_names[m.group(1)] = m.group(2)

    issues: list[dict] = []
    per_cat: dict[str, int] = {}
    for line in lines:
        m = _ITEM_RE.match(line)
        if not m:
            continue
        cat = m.group("cat")
        num = int(m.group("num"))
        title_m = _BOLD_TITLE_RE.match(line)
        title = title_m.group("title").strip() if title_m else f"{cat}-{num}"
        body = m.group("body")
        issue_id = f"{cat}-{num}"
        # scope：反引号内、命中 DDL 表集的表名
        scope = []
        for tok in _BACKTICK_RE.findall(line):
            cand = tok.strip().split(".")[0].strip()   # 去 `tbl.col` 的 tbl 部分
            if _ID_RE.match(cand) and cand in tables:
                scope.append(cand)
        scope = sorted(set(scope))
        issues.append({
            "issue_id": issue_id,
            "category": cat,
            "category_name": cat_names.get(cat, ""),
            "title": title,
            "severity": None,          # 05 未标 → [待确认]
            "scope": scope,
            "has_issue_edges": [{"table": t, "issue": issue_id} for t in scope],
        })
        per_cat[cat] = per_cat.get(cat, 0) + 1

    return {
        "issues": issues,
        "total": len(issues),
        "per_category": dict(sorted(per_cat.items())),
        "has_issue_edges": [e for i in issues for e in i["has_issue_edges"]],
    }
