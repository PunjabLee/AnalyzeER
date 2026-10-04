"""M1 · DERIVED_FROM 解析器（04 → C 级 _bak_/test_ → 源表）。

两档取证（schema §2.2，审计 P2-2）：
- **显式登记 5**：04 §3.1「源表」列（2 jf_ 备份）+ §二测试表 3（`test_` 前缀剥离）
  → is_inferred=false, naming_rule_derived=false, evidence=comment_explicit
- **命名规则派生 115**：剥 `_bak_<14位时戳>` 与 `_{6hex}` 应用副本段派生
  → is_inferred=true, naming_rule_derived=true, evidence=comment_explicit
- 存在性门控（§2.2 末）：剥名后须命中真实表，**未命中 → 悬挂队列、不造边**（不得臆造）。
- copy1→主表（A 级保留 2）：naming_rule_derived=true, is_inferred=true。

**实测口径差（诚实标注）**：§2.2 纯剥名（strip_bak/strip_app）仅解析 lcap 51 + jf 2；
无前缀 64 的基名（customer/attributes…）在 DDL 无同名表，04 §3.3 明示其源「归入 A 级 jf_ 业务域」，
故增补 `add_jf_prefix` 推断档（仍 naming_rule_derived=true，evidence comment_explicit，可回溯 04）；
其中 9 张连 `jf_` 前缀也无法命中（remark_information/trace_quota/reconciliation 等，04/05 已标归属待确认）
→ 进悬挂队列、不造边。命名派生「分类计数=115」成立，但「有效源表边」= 106（115−9）。
"""

from __future__ import annotations

import re

from .config import FILE_04, BAK14_RE, TEST_PREFIX_RE

_EXPLICIT_JF_SECTION = "### 3.1"
_EXPLICIT_TEST_SECTION = "## 二"
_COPY1_CANDIDATES = [
    "jf_sales_order_copy1",
    "jf_statement_fee_category_copy1",
]


def _section(text: str, start_marker: str) -> str:
    idx = text.find(start_marker)
    if idx < 0:
        return ""
    rest = text[idx:]
    # 截到下一个 ## / ### 标题
    m = re.search(r"\n(#{2,3})\s", rest[len(start_marker):])
    end = len(start_marker) + (m.start() + 1 if m else len(rest))
    return rest[:end + len(start_marker)]


def _parse_explicit_31(text: str) -> list[dict]:
    """04 §3.1：jf_ 备份 → 源表（表格「源表」列）。"""
    sec = _section(text, _EXPLICIT_JF_SECTION)
    edges = []
    for line in sec.splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")] if line.strip().startswith("|") else []
        if len(cells) >= 2 and BAK14_RE.search(cells[0]):
            src = re.split(r"[（(]", cells[1])[0].strip()
            if src and not src.startswith("#"):
                edges.append({"table": cells[0], "source": src})
    return edges


def _parse_explicit_test(text: str) -> list[dict]:
    """04 §二：test_ 表 → 源表（`test_` 前缀剥离；理由列点名佐证）。"""
    sec = _section(text, _EXPLICIT_TEST_SECTION)
    edges = []
    for line in sec.splitlines():
        line = line.strip()
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        if len(cells) >= 1 and TEST_PREFIX_RE.match(cells[0]):
            tname = cells[0]
            src = tname[len("test_"):]
            edges.append({"table": tname, "source": src})
    return edges


def _resolve_base(name: str, tables: set[str]) -> tuple[str | None, str | None]:
    """命名派生：返回 (源表名, 命中规则)。规则 ∈ strip_bak/strip_app/add_jf_prefix/add_jf_strip_app。"""
    base = BAK14_RE.sub("", name)
    ladder: list[tuple[str, str]] = [(base, "strip_bak")]
    if re.search(r"_[0-9a-f]{6}$", base):
        ladder.append((re.sub(r"_[0-9a-f]{6}$", "", base), "strip_app"))
    ladder.append(("jf_" + base, "add_jf_prefix"))
    if re.search(r"_[0-9a-f]{6}$", base):
        ladder.append(("jf_" + re.sub(r"_[0-9a-f]{6}$", "", base), "add_jf_strip_app"))
    for cand, rule in ladder:
        if cand in tables:
            return cand, rule
    return None, None


def parse_derived(c_tables: set[str], tables: set[str], path=FILE_04) -> dict:
    """c_tables: 全部 C 级表名；tables: 全库 DDL 表名集。返回边/队列结构。"""
    text = path.read_text(encoding="utf-8")
    explicit = _parse_explicit_31(text) + _parse_explicit_test(text)
    explicit_names = {e["table"] for e in explicit}

    edges: list[dict] = []
    dangling: list[dict] = []

    # 显式 5（文档表格直接登记 → is_inferred=false）
    for e in explicit:
        if e["source"] in tables:
            edges.append({
                "table": e["table"], "source": e["source"],
                "is_inferred": False, "naming_rule_derived": False,
                "evidence_level": "comment_explicit", "confidence": 0.90,
                "resolution_rule": "explicit_registered",
                "source_ref": {"file": "er-model/04-C级备份与测试表清单.md",
                               "section": "§3.1" if BAK14_RE.search(e["table"]) else "§二"},
            })
        else:  # 显式源表竟不存在 → 悬挂（不臆造）
            dangling.append({"table": e["table"], "reason": "explicit_source_missing",
                             "expected_source": e["source"]})

    # 命名派生（其余 C 表）
    naming_derived_count = 0
    for name in sorted(c_tables):
        if name in explicit_names:
            continue
        naming_derived_count += 1
        if TEST_PREFIX_RE.match(name):
            # test_ 但非显式登记：剥 test_ 派生
            cand = name[len("test_"):]
            src, rule = (cand, "strip_test_prefix") if cand in tables else (None, None)
        else:
            src, rule = _resolve_base(name, tables)
        if src:
            edges.append({
                "table": name, "source": src,
                "is_inferred": True, "naming_rule_derived": True,
                "evidence_level": "comment_explicit", "confidence": 0.90,
                "resolution_rule": rule,
                "source_ref": {"file": "er-model/04-C级备份与测试表清单.md",
                               "section": "§3.2" if name.startswith("lcap_") else "§3.3"},
            })
        else:
            dangling.append({"table": name, "reason": "source_unresolvable",
                             "base": BAK14_RE.sub("", name)})

    # copy1 → 主表（A 级保留副本，额外派生边；非 C 级）
    copy1_edges = []
    for c in _COPY1_CANDIDATES:
        if c in tables:
            src = re.sub(r"_copy1$", "", c)
            if src in tables:
                copy1_edges.append({
                    "table": c, "source": src,
                    "is_inferred": True, "naming_rule_derived": True,
                    "evidence_level": "comment_explicit", "confidence": 0.90,
                    "resolution_rule": "strip_copy1",
                    "source_ref": {"file": "er-model/04-C级备份与测试表清单.md",
                                   "section": "§一-4/§六"},
                })

    return {
        "edges": edges,
        "copy1_edges": copy1_edges,
        "dangling": dangling,
        "explicit_count": len(explicit),
        "naming_derived_classified": naming_derived_count,
        "naming_derived_resolved": sum(1 for e in edges if e["is_inferred"] and e["table"] in c_tables),
    }
