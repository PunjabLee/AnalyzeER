"""M1 · 前缀归级（铁律 N-2）+ B 级同构族归约。

**归级顺序（关键，否则 A 虚增为 351、lcap 虚增为 501）**：
1. 先剔 C 级：`*_bak_<14位时戳>`（117）与 `test_*`（3）→ tier=C。
2. 再按前缀归 A/B：
   - `lcap_`/`N{hex}_`(Quartz)/`P{hex}_`(Activiti) → tier=B（450/275/128）。
   - 其余（`jf_*` 业务表 + 无前缀遗留表 OT）→ tier=A（332 jf + 17 OT = 349）。

依据：schema.md §3（前缀归级铁律）/§1.2；file-domain-map.md §1/§3；eval-baseline.md §3。
"""

from __future__ import annotations

import re

from .config import (
    BAK14_RE,
    TEST_PREFIX_RE,
    JF_PREFIX_RE,
    LCAP_PREFIX_RE,
    QUARTZ_PREFIX_RE,
    ACTIVITI_PREFIX_RE,
    APPCODE_SUFFIX_RE,
)


def is_c_level(name: str) -> bool:
    """C 级判定（第一步，先于 A/B）：_bak_<14时戳> 或 test_ 前缀。"""
    return bool(BAK14_RE.search(name) or TEST_PREFIX_RE.match(name))


def classify_tier(name: str) -> str:
    """返回 'A' | 'B' | 'C'（严格执行 N-2：先剔 C，再归 A/B）。"""
    if is_c_level(name):
        return "C"
    if LCAP_PREFIX_RE.match(name) or QUARTZ_PREFIX_RE.match(name) or ACTIVITI_PREFIX_RE.match(name):
        return "B"
    return "A"


def family_of(name: str) -> str | None:
    """B 级同构族：quartz | activiti | lcap（A/C 返回 None）。"""
    if QUARTZ_PREFIX_RE.match(name):
        return "quartz"
    if ACTIVITI_PREFIX_RE.match(name):
        return "activiti"
    if LCAP_PREFIX_RE.match(name) and not is_c_level(name):
        return "lcap"
    return None


def family_base(name: str) -> str:
    """族结构基名（同构归约键）：
    - Quartz/Activiti：剥 `N{hex}_`/`P{hex}_` 应用 schema 前缀 → 结构基名（如 BLOB_TRIGGERS）。
    - lcap：剥尾部 `_{6hex}` 应用副本段 → 结构基名（如 lcap_account）。
    - 其他：原样。
    """
    if QUARTZ_PREFIX_RE.match(name):
        return re.sub(r"^N[0-9A-Fa-f]{6,}_", "", name)
    if ACTIVITI_PREFIX_RE.match(name):
        return re.sub(r"^P[0-9A-Fa-f]{6,}_", "", name)
    if LCAP_PREFIX_RE.match(name):
        return APPCODE_SUFFIX_RE.sub("", name)
    return name


def prefix_family(name: str) -> str:
    """Table.prefix_family（schema §1.2）：jf|lcap|N{hex}|P{hex}|none|bak|test。"""
    if TEST_PREFIX_RE.match(name):
        return "test"
    if BAK14_RE.search(name):
        return "bak"
    if JF_PREFIX_RE.match(name):
        return "jf"
    if LCAP_PREFIX_RE.match(name):
        return "lcap"
    if QUARTZ_PREFIX_RE.match(name):
        return "N{hex}"
    if ACTIVITI_PREFIX_RE.match(name):
        return "P{hex}"
    return "none"


def tier_counts(names) -> dict:
    """按 N-2 归级并统计（含 B/C 细分），用于闭合断言。"""
    c = {"A": 0, "B": 0, "C": 0,
         "jf_a": 0, "ot_a": 0,
         "b_lcap": 0, "b_quartz": 0, "b_activiti": 0,
         "c_bak": 0, "c_test": 0}
    for n in names:
        t = classify_tier(n)
        c[t] += 1
        if t == "C":
            if BAK14_RE.search(n):
                c["c_bak"] += 1
            else:
                c["c_test"] += 1
        elif t == "B":
            fam = family_of(n)
            c["b_" + fam] += 1
        else:  # A
            if JF_PREFIX_RE.match(n):
                c["jf_a"] += 1
            else:
                c["ot_a"] += 1
    c["registered_total"] = c["A"] + c["B"] + c["C"]
    return c
