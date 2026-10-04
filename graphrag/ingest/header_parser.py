"""M1 · 03 逻辑模型解析器（表头归一 + 合并行拆回 + DDL 交叉校验）。

严格遵循 header-normalization.md（唯一依据）：
- §6.1 定位表头：首行 `|` 框定、次行 `|[-: |]+|` 分隔线。
- §6.2 归一：逐 cell strip、内空格不敏感；命中 §3 11 种字段签名→按规范列归一；
  命中 §4 8 种非字段辅助表→**不喂列解析**；未命中→记 unmapped_header 告警（禁静默丢弃）。
- §6.3 合并行拆回：`created_by/updated_by/...`、`审计四件套` 抽象→展开规范审计列；
  文档共享前缀缩写（`contract_close_person/time/reason`）→首段前缀回补后续裸段；
  共享后缀（`before/change/after_amount`）→末段后缀回补。
- §6.4 关联三态：内联 FK[..]/斜杠 `含义 / 关联`/独立列 → 产「键-线索」+「关联」。

**结构事实最终仲裁 = test_erp.sql**（schema §0/§1.3）：Column 节点由 DDL 直取，column_count=DDL 值；
本模块拆回的文档原子列仅用于**交叉校验信号**，残差诚实分级为
`matched`（精确命中 DDL）/`shared_prefix_abbrev`（文档缩写，DDL 有 `_tk` 列可解释）/
`shorthand_ambiguous`（散文式笛卡尔速记，无法仅凭文档无损反推，DDL 为准）/
`name_mismatch`（含下划线、形似真列名但 DDL 无 → 真实文档↔DDL 命名差，登记供 05 问题溯源）。
`（N 列）` 声明与 DDL column_count 差为文档口径，登记不吞。

依据：header-normalization.md §1–§6；schema.md §1.3/§3.3-2。
"""

from __future__ import annotations

import re

from .config import DOMAIN_03

_SEP_RE = re.compile(r"^\s*\|[\s:|-]+\|\s*$")
_AUDIT_SET = ["created_time", "created_by", "updated_time", "updated_by"]
_IDENT_RE = re.compile(r"[A-Za-z0-9_]+")


def _norm(cell: str) -> str:
    c = cell.strip().strip("`").strip()
    return c.replace(" ", "").replace("（", "(").replace("）", ")")


def _canon_of(norm_cell: str) -> str | None:
    x = norm_cell
    if x in ("#", "序号"):
        return "SEQ"
    if x.startswith("DDL行号") or "行号" in x:
        return "DDL_LINE"
    if x in ("字段", "字段名") or x.startswith("字段("):
        return "FIELD"
    if x == "类型":
        return "TYPE"
    if x == "可空":
        return "NULLABLE"
    if x in ("默认", "默认值"):
        return "DEFAULT"
    if x in ("键", "线索", "主/外键线索") or x.startswith("主/外键线索"):
        return "KEY"
    if "键/关联结论" in x:
        return "KEY_LINK"
    if x in ("关联", "与其他表的关联") or x.startswith("关联("):
        return "LINK"
    if x == "含义/关联":
        return "SEMANTIC_LINK"
    if x in ("含义", "字段含义") or x.startswith("字段含义("):
        return "SEMANTIC"
    return "OTHER"


_NONFIELD_FIRST = {"编号", "本域.字段", "族", "关键差异字段", "分组", "表"}


def _classify_header(cells: list[str]) -> dict:
    norms = [_norm(c) for c in cells]
    colmap: dict[str, int] = {}
    for i, n in enumerate(norms):
        canon = _canon_of(n)
        if canon and canon not in ("SEQ", "DDL_LINE") and canon not in colmap:
            colmap[canon] = i
        elif canon in ("SEQ", "DDL_LINE"):
            colmap[canon] = i
    if "FIELD" in colmap and "TYPE" in colmap:
        return {"kind": "field", "signature": "|".join(norms), "colmap": colmap}
    if norms and (norms[0] in _NONFIELD_FIRST):
        return {"kind": "nonfield", "signature": "|".join(norms), "colmap": colmap}
    return {"kind": "unmapped", "signature": "|".join(norms), "colmap": colmap}


def _split_field_cell(cell: str) -> tuple[list[str], bool]:
    """拆回合并行 → 原子列名（含共享前/后缀回补）。返回 (列名, 是否命中审计四件套抽象)。"""
    audit = ("审计四件套" in cell) or ("审计字段" in cell)
    names: set[str] = set()
    # 先按 `+` 分成若干 `/`-组，各组内做前/后缀回补
    for grp in re.split(r"[+]", cell):
        segs = []
        for raw in grp.split("/"):
            s = re.split(r"[（(]", raw.strip())[0].strip().strip("`").strip("*").strip()
            if _IDENT_RE.fullmatch(s):
                segs.append(s)
        if not segs:
            continue
        if "_" in segs[0]:
            # 共享前缀：首段带 `_`、后续裸段（无 `_`）→ 用首段前缀回补
            prefix = segs[0].rsplit("_", 1)[0] + "_"
            names.add(segs[0])
            for s in segs[1:]:
                names.add(prefix + s if "_" not in s else s)
        elif "_" in segs[-1] and all("_" not in s for s in segs[:-1]) and len(segs) >= 2:
            # 共享后缀：末段带 `_`、前面皆裸单词 → 用末段后缀回补（before/change/after_amount）
            suffix = "_" + segs[-1].rsplit("_", 1)[1]
            for s in segs[:-1]:
                names.add(s + suffix)
            names.add(segs[-1])
        else:
            names.update(segs)
    if audit:
        names.update(_AUDIT_SET)
    if "delete_status" in cell:
        names.add("delete_status")
    return sorted(names), audit


def parse_logical_model(path=None) -> dict:
    files = [path] if path else sorted(DOMAIN_03.glob("*.md"))
    field_signatures: dict[str, int] = {}
    nonfield_signatures: dict[str, int] = {}
    unmapped: list[str] = []
    tables: dict[str, dict] = {}
    field_header_rows = 0
    nonfield_header_rows = 0
    merged_rows = 0

    section_re = re.compile(r"^#{2,4}\s+\d+\.\s+([A-Za-z0-9_]+)")
    declared_re = re.compile(r"[（(]\s*(\d+)\s*列")

    for f in files:
        lines = f.read_text(encoding="utf-8").splitlines()
        cur_table = None
        i = 0
        while i < len(lines):
            line = lines[i]
            sm = section_re.match(line)
            if sm:
                cur_table = sm.group(1)
                dm = declared_re.search(line)
                cur_declared = int(dm.group(1)) if dm else None
                if cur_table not in tables:
                    tables[cur_table] = {"declared_cols": cur_declared,
                                         "doc_atomic_cols": set(),
                                         "doc_literal_rows": 0,
                                         "audit_expanded": False,
                                         "file": f.name}
                elif cur_declared is not None:
                    tables[cur_table]["declared_cols"] = cur_declared
                i += 1
                continue
            if line.strip().startswith("|") and i + 1 < len(lines) and _SEP_RE.match(lines[i + 1]):
                cells = [c for c in line.strip().strip("|").split("|")]
                info = _classify_header(cells)
                if info["kind"] == "field":
                    field_header_rows += 1
                    field_signatures[info["signature"]] = field_signatures.get(info["signature"], 0) + 1
                    fi = info["colmap"].get("FIELD")
                    j = i + 2
                    while j < len(lines) and lines[j].strip().startswith("|"):
                        dcells = [c for c in lines[j].strip().strip("|").split("|")]
                        if fi is not None and fi < len(dcells):
                            fc = dcells[fi].strip()
                            if fc and not fc.startswith("字段"):
                                names, audit = _split_field_cell(fc)
                                if cur_table:
                                    tables[cur_table]["doc_literal_rows"] += 1
                                    tables[cur_table]["doc_atomic_cols"].update(names)
                                    if "/" in fc or "+" in fc or audit:
                                        merged_rows += 1
                                    if audit:
                                        tables[cur_table]["audit_expanded"] = True
                        j += 1
                    i = j
                    continue
                elif info["kind"] == "nonfield":
                    nonfield_header_rows += 1
                    nonfield_signatures[info["signature"]] = nonfield_signatures.get(info["signature"], 0) + 1
                else:
                    unmapped.append(f"{f.name}: {info['signature']}")
            i += 1

    return {
        "tables": {k: {"declared_cols": v["declared_cols"],
                       "doc_atomic_cols": sorted(v["doc_atomic_cols"]),
                       "doc_literal_rows": v["doc_literal_rows"],
                       "audit_expanded": v["audit_expanded"],
                       "file": v["file"]} for k, v in tables.items()},
        "field_signature_distinct": len(field_signatures),
        "field_signature_dist": field_signatures,
        "field_header_rows": field_header_rows,
        "nonfield_signature_distinct": len(nonfield_signatures),
        "nonfield_dist": nonfield_signatures,
        "nonfield_header_rows": nonfield_header_rows,
        "total_header_rows": field_header_rows + nonfield_header_rows + len(unmapped),
        "unmapped_headers": unmapped,
        "merged_rows": merged_rows,
    }


def crosscheck_with_ddl(doc: dict, ddl_tables: dict) -> dict:
    """拆回原子列 ⊆ DDL 列集；残差诚实分级（matched/shared_prefix_abbrev/shorthand_ambiguous/name_mismatch）。

    注：Column 节点由 DDL 直取（结构事实最终仲裁），本交叉校验仅作文档一致性信号。
    """
    per_table = {}
    tot_matched = 0
    tot_shared = 0
    tot_ambig = 0
    tot_mismatch = 0
    audit_ok_tables = 0
    audit_checked_tables = 0
    expansion_ok = 0          # 拆回后原子列数 ≥ 文档字面行数（合并行确实被展开）
    for tname, info in doc["tables"].items():
        ddl = ddl_tables.get(tname)
        if not ddl:
            continue
        ddl_cols = {c.name for c in ddl.columns}
        atomic = set(info["doc_atomic_cols"])
        matched = sorted(t for t in atomic if t in ddl_cols)
        shared: list[str] = []
        ambig: list[str] = []
        mismatch: list[str] = []
        for tk in sorted(atomic - set(matched)):
            if "_" not in tk:
                # 裸段：若 DDL 存在以 `_tk` 结尾的列 → 可解释缩写；否则散文速记（无法无损反推）
                if any(dc.endswith("_" + tk) for dc in ddl_cols):
                    shared.append(tk)
                else:
                    ambig.append(tk)
            else:
                mismatch.append(tk)
        tot_matched += len(matched)
        tot_shared += len(shared)
        tot_ambig += len(ambig)
        tot_mismatch += len(mismatch)
        if len(atomic) >= info["doc_literal_rows"]:
            expansion_ok += 1
        if info["audit_expanded"]:
            audit_checked_tables += 1
            if {"created_by", "updated_by"} & ddl_cols:
                audit_ok_tables += 1
        per_table[tname] = {
            "ddl_column_count": ddl.column_count,
            "declared_cols": info["declared_cols"],
            "doc_literal_rows": info["doc_literal_rows"],
            "doc_atomic_count": len(atomic),
            "matched": matched,
            "shared_prefix_abbrev": shared,
            "shorthand_ambiguous": ambig,
            "name_mismatch": mismatch,
            "audit_expanded": info["audit_expanded"],
            "declared_matches_ddl": info["declared_cols"] == ddl.column_count,
        }
    return {
        "tables_checked": len(per_table),
        "atomic_matched_total": tot_matched,
        "shared_prefix_abbrev_total": tot_shared,
        "shorthand_ambiguous_total": tot_ambig,
        "name_mismatch_total": tot_mismatch,
        "expansion_ok_tables": expansion_ok,
        "audit_expanded_tables": audit_checked_tables,
        "audit_columns_present_in_ddl": audit_ok_tables,
        "declared_eq_ddl_count": sum(1 for v in per_table.values() if v["declared_matches_ddl"]),
        "per_table": per_table,
    }
