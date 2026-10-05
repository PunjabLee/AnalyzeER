"""M1 · ER 关系线解析器（01-ER图/* 主源 + 05 交叉锚）。

严格遵循 relation-symbol-census.md（唯一依据）：
- 正则 = 左基数可选 (?:\\|\\||\\|o|\\}o)?(?:--|\\.\\.)(?:\\|\\||o\\||o\\{|\\|\\{)；行结构锚定防截尾假阳性。
- cardinality / evidence / via_column **一律取自引号描述文本**，**不得**由连接符字形反推（§5）。
- 13 条缺左基数标记：容错接受，打 malformed_connector=true + left_cardinality=missing；
  其描述文本实测均无基数 token → cardinality 走缺失队列，**不反推**（§2.1）。
- **跨域桩** `X_Dxx_stub` → 解析到规范表 X（不重复建节点），cross_domain=true（schema §2.1）。
- **外部引用**：描述含 `[外部系统]` 或端点名 `*_EXTERNAL_SYSTEM` → external_reference=true，
  **不建本库边**（schema §2.1，避免虚假关系）。

依据：schema.md §2/§2.1；relation-symbol-census.md §2/§2.1/§5/§6。
"""

from __future__ import annotations

import hashlib
import re
from pathlib import Path

from .config import (
    DOMAIN_01,
    FILE_05,
    RELATION_LINE_RE,
    LEFT_MISSING_RE,
    CARDINALITY_RE,
    map_evidence,
    confidence_for,
)

_ARROW_RE = re.compile(
    r"[A-Za-z_][A-Za-z0-9_]*\.([A-Za-z_][A-Za-z0-9_]*)\s*(?:->|→)\s*"
    r"[A-Za-z_][A-Za-z0-9_]*\.([A-Za-z_][A-Za-z0-9_]*)"
)
_VIA_DOT_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*\.([A-Za-z_][A-Za-z0-9_]*)")
_VIA_SUFFIX_RE = re.compile(r"\b([A-Za-z0-9_]+(?:_id|_no|_code))\b")
_SECTION_RE = re.compile(r"^#{2,4}\s+(.+?)\s*$")

# 跨域桩：<规范表>_Dxx_stub（D15 复核批用法）
_STUB_RE = re.compile(r"^(?P<base>.+?)_D\d{2}_stub$")
# 外部系统端点名（真外部表在 DDL 为 P*_ACT_RU_EXTERNAL_JOB，此处 *_EXTERNAL_SYSTEM 为示意桩）
_EXTERNAL_NAME_RE = re.compile(r"_EXTERNAL_SYSTEM$")


def _domain_from_filename(name: str) -> str:
    if name.startswith("B级-"):
        return "B-FAMILY"
    if name.startswith("OT-"):
        return "OT"
    m = re.match(r"^(D\d{2})-", name)
    return m.group(1) if m else name


def _resolve_endpoint(name: str) -> tuple[str, bool]:
    """跨域桩名 → 规范表名。返回 (canonical, was_stub)。"""
    m = _STUB_RE.match(name)
    if m:
        return m.group("base"), True
    return name, False


def _quote_hash(left: str, conn: str, right: str, desc: str) -> str:
    norm = f"{left}|{conn}|{right}|{' '.join(desc.split())}"
    return hashlib.sha1(norm.encode("utf-8")).hexdigest()[:16]


def _via_column(desc: str) -> str | None:
    am = _ARROW_RE.search(desc)
    if am:
        return am.group(1)          # 源侧 FK 列（箭头左）
    dm = _VIA_DOT_RE.search(desc)
    if dm:
        return dm.group(1)
    sm = _VIA_SUFFIX_RE.search(desc)
    if sm:
        return sm.group(1)
    return None


def _parse_file(path: Path, source_label: str) -> tuple[list[dict], dict]:
    edges: list[dict] = []
    connector_counter: dict[str, int] = {}
    malformed_lines: list[dict] = []
    card_missing: list[dict] = []
    external_skipped: list[dict] = []
    stub_resolved: list[dict] = []

    text = path.read_text(encoding="utf-8")
    section = None
    domain = _domain_from_filename(path.name)
    for lineno, line in enumerate(text.splitlines(), 1):
        sm = _SECTION_RE.match(line)
        if sm:
            section = sm.group(1)
        rm = RELATION_LINE_RE.match(line)
        if not rm:
            continue
        left0, conn, right0, desc = (
            rm.group("left"), rm.group("conn"), rm.group("right"), rm.group("desc")
        )
        # census 计数：每条匹配关系线都计入字形分布（全量口径 ξ）
        connector_counter[conn] = connector_counter.get(conn, 0) + 1

        malformed = bool(LEFT_MISSING_RE.match(conn))
        left_card = "missing" if malformed else "present"

        cm = CARDINALITY_RE.search(desc)
        cardinality = cm.group(1) if cm else None

        ev = map_evidence(desc)

        # 端点解析（跨域桩 → 规范表）
        left, l_stub = _resolve_endpoint(left0)
        right, r_stub = _resolve_endpoint(right0)
        if l_stub or r_stub:
            stub_resolved.append({"file": path.name, "line": lineno,
                                  "raw": f"{left0}->{right0}", "resolved": f"{left}->{right}"})

        # 外部引用（描述 [外部系统] 或端点名 *_EXTERNAL_SYSTEM）→ 不建本库边
        by_name = bool(_EXTERNAL_NAME_RE.search(left0) or _EXTERNAL_NAME_RE.search(right0))
        if ev["external_reference"] or by_name:
            external_skipped.append({
                "file": path.name, "line": lineno, "left": left0, "right": right0,
                "by": ("name" if by_name else "desc"), "desc": desc,
            })
            continue

        direction = "self" if left == right else (
            "child" if (cardinality and cardinality.startswith("N")) else "parent"
        )

        edge = {
            "src": left,
            "dst": right,
            "src_raw": left0,
            "dst_raw": right0,
            "cardinality": cardinality,
            "direction": direction,
            "via_column": _via_column(desc),
            "evidence_level": ev["evidence_level"],
            "evidence_tags": ev["evidence_tags"],
            "confidence": confidence_for(ev["evidence_level"]),
            "is_inferred": True,                     # 全库 0 FK → 恒 true
            "cross_domain": ev["cross_domain"] or l_stub or r_stub,
            "polymorphic": ev["polymorphic"],
            "discriminant": ev["discriminant"],
            # P1-2：has_uncertain 随边落库（供查询门默认隐藏，不再"算完即弃"）
            "has_uncertain": ev["has_uncertain"],
            "malformed_connector": malformed,
            "left_cardinality": left_card,
            "connector": conn,
            "source_domain": domain,
            "source_file": f"er-model/{source_label}/{path.name}" if source_label != "05" else "er-model/05-跨域核心关系总览.md",
            "source_section": section,
            "line_hint": lineno,
            "quote_hash": _quote_hash(left0, conn, right0, desc),
            "raw_desc": desc,
        }
        edges.append(edge)

        if malformed:
            malformed_lines.append({"file": path.name, "line": lineno, "conn": conn,
                                     "left": left, "right": right, "cardinality": cardinality})
            if cardinality is None:
                card_missing.append({"file": path.name, "line": lineno, "edge": f"{left}->{right}"})

    stats = {
        "file": path.name,
        "domain": domain,
        "edge_count": len(edges),
        "connector_counter": connector_counter,
        "malformed": malformed_lines,
        "cardinality_missing": card_missing,
        "external_skipped": external_skipped,
        "stub_resolved": stub_resolved,
    }
    return edges, stats


def parse_relations():
    """解析 01/ 全部 + 05，返回 (edges_01, edges_05, stats)。"""
    files_01 = sorted(p for p in DOMAIN_01.glob("*.md"))
    all_edges_01: list[dict] = []
    conn_01: dict[str, int] = {}
    malformed_01: list[dict] = []
    card_missing_01: list[dict] = []
    external_01: list[dict] = []
    stub_01: list[dict] = []
    per_file: dict[str, int] = {}
    for p in files_01:
        edges, st = _parse_file(p, "01-ER图")
        all_edges_01.extend(edges)
        for c, n in st["connector_counter"].items():
            conn_01[c] = conn_01.get(c, 0) + n
        malformed_01.extend(st["malformed"])
        card_missing_01.extend(st["cardinality_missing"])
        external_01.extend(st["external_skipped"])
        stub_01.extend(st["stub_resolved"])
        per_file[st["domain"]] = per_file.get(st["domain"], 0) + sum(st["connector_counter"].values())

    edges_05, st_05 = _parse_file(FILE_05, "05")
    stats = {
        "01": {
            "connectors": conn_01,
            "connector_total": sum(conn_01.values()),
            "distinct_shapes": len(conn_01),
            "edges_after_external": len(all_edges_01),
            "malformed_count": len(malformed_01),
            "malformed": malformed_01,
            "cardinality_missing": card_missing_01,
            "external_skipped": external_01,
            "stub_resolved": stub_01,
            "per_domain": per_file,
        },
        "05": {
            "connectors": st_05["connector_counter"],
            "connector_total": sum(st_05["connector_counter"].values()),
            "edges": len(edges_05),
            "external_skipped": st_05["external_skipped"],
            "stub_resolved": st_05["stub_resolved"],
        },
    }
    return all_edges_01, edges_05, stats
