"""M1 · 域归属解析器（00 §四 → Domain 成员 + 声明表数）。

键 = Dxx 编号（file-domain-map.md §1 权威；域名文字差异不影响键）。
枚举表名以顿号 `、` 分隔；`### Dxx …（N）` / `### OT …（N…）` 取声明数 N。

依据：file-domain-map.md；schema.md §1.1；eval-baseline.md §3（每域=00 声明）。
"""

from __future__ import annotations

import re

from .config import DOMAIN_DECLARED, FILE_00

_HEADER_RE = re.compile(r"^### (D\d{2}|OT)\b.*?（(\d+)")


def parse_domain_membership(path=FILE_00) -> dict:
    """返回 {domain_id: {"declared": int, "members": [table,...]}} 及逐域映射。"""
    lines = path.read_text(encoding="utf-8").splitlines()
    result: dict[str, dict] = {}
    cur_id: str | None = None
    buf: list[str] = []

    def flush():
        if cur_id is None:
            return
        text = "".join(buf)
        toks = [t.strip() for t in re.split(r"[、,，]", text) if t.strip()]
        # 过滤非表名 token（含空格/中文说明/括号的多为噪声，保留纯标识符）
        members = [t for t in toks if re.fullmatch(r"[A-Za-z0-9_]+", t)]
        result[cur_id]["members"] = members

    for line in lines:
        hm = _HEADER_RE.match(line)
        if hm:
            flush()
            cur_id = hm.group(1)
            result[cur_id] = {"declared": int(hm.group(2)), "members": []}
            buf = []
            continue
        # 段落结束：下一个 ### / ## 标题、或说明行 `>`、或表格 `|`
        if cur_id is not None:
            if line.startswith("### ") or line.startswith("## "):
                flush()
                cur_id = None
                buf = []
            elif line.startswith(">") or line.startswith("|") or line.startswith("-"):
                # 说明/表头行：跳过但仍在当前域内（枚举行已在此之前）
                continue
            else:
                buf.append(line.strip())
    flush()

    return result


def domain_counts_report(membership: dict, ddl_names: set[str]) -> dict:
    """逐域：声明数 / 枚举成员数 / 命中 DDL 数 / 缺失成员。声明数以 file-domain-map 为准。"""
    report = {}
    for dom, spec_declared in DOMAIN_DECLARED.items():
        entry = membership.get(dom, {"declared": 0, "members": []})
        members = entry["members"]
        hit = [m for m in members if m in ddl_names]
        missing = [m for m in members if m not in ddl_names]
        report[dom] = {
            "filedomain_declared": spec_declared,
            "doc_00_declared": entry["declared"],
            "enumerated": len(members),
            "in_ddl": len(hit),
            "missing_from_ddl": missing,
            "match": len(members) == spec_declared and len(missing) == 0,
        }
    return report
