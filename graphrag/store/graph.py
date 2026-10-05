"""M1 · 内存属性图（schema.md §1/§2 冻结口径的可序列化投影）。

- 节点：Domain / Table / Column / Issue / EvidenceSrc（Concept 属 M4 种子，本期不建）。
- 边：BELONGS_TO_DOMAIN / IS_COLUMN_OF / RELATES_TO / HAS_ISSUE / DERIVED_FROM /
      SAME_FAMILY_AS / SUPPORTED_BY（EvidenceSrc 反查）。
- 分级建点（schema §3 / 任务 N-2）：
  · A(349)：Table + 全 Column（DDL 直取）+ IS_COLUMN_OF + RELATES_TO，可遍历。
  · B(853)：仅 Table 影子（expanded=false），不建 Column、不参与遍历；族归约 member_count。
  · C(120)：Table 影子，DERIVED_FROM 源表，不遍历，include_c 才可见。
- **有出处率 100%**：每条 RELATES_TO 挂 evidence_ref → EvidenceSrc 节点（quote_hash 稳定锚）。
- **端点守恒**：跨域桩已在解析层归一到规范节点；仍不可解析者 → pending_confirmation 队列
  （`[待确认]`，不臆建造点）；结构性 rel_dangling 应为 0。

MVP 落地：内存结构 + 稳定 JSON 导出（stack-options.md）；不绑特定图库。
"""

from __future__ import annotations

import json
from collections import defaultdict
from dataclasses import dataclass, field

from ..ingest.config import (
    DOMAIN_DECLARED,
    OT_SUSPECTED_LEGACY,
    CONFIDENCE_DEFAULT_MIN,
)
from ..ingest.tiering import (
    classify_tier,
    family_of,
    family_base,
    prefix_family,
    is_c_level,
)


def _tid(name: str) -> str:
    return f"table:{name}"


def _did(dom: str) -> str:
    return f"domain:{dom}"


def _cid(table: str, col: str) -> str:
    return f"column:{table}.{col}"


def _iid(iid: str) -> str:
    return f"issue:{iid}"


def _evd(qhash: str) -> str:
    return f"evsrc:{qhash}"


@dataclass
class PropertyGraph:
    nodes: dict[str, dict] = field(default_factory=dict)
    edges: list[dict] = field(default_factory=list)
    _out: dict[str, list[int]] = field(default_factory=lambda: defaultdict(list))
    _in: dict[str, list[int]] = field(default_factory=lambda: defaultdict(list))

    # ---- 构建原语 ----------------------------------------------------
    def add_node(self, node_id: str, label: str, **props) -> dict:
        if node_id in self.nodes:
            self.nodes[node_id].update(props)
            return self.nodes[node_id]
        n = {"node_id": node_id, "label": label}
        n.update(props)
        self.nodes[node_id] = n
        return n

    def add_edge(self, src: str, dst: str, etype: str, **props) -> dict:
        e = {"src": src, "dst": dst, "type": etype}
        e.update(props)
        idx = len(self.edges)
        self.edges.append(e)
        self._out[src].append(idx)
        self._in[dst].append(idx)
        return e

    def has_node(self, node_id: str) -> bool:
        return node_id in self.nodes

    # ---- 查询/遍历 ---------------------------------------------------
    def neighbors(self, node_id: str, etype: str | None = None,
                  direction: str = "out", min_conf: float = 0.0,
                  traverse_tier: str | None = "A") -> list[tuple[str, dict]]:
        """返回 (邻接节点 id, 边) 列表；可按边型/置信度过滤。
        遍历仅走 A 级可遍历主语料（B/C 影子不入血缘/影响遍历）。
        """
        bucket = self._out if direction == "out" else self._in
        if direction == "both":
            idxs = self._out.get(node_id, []) + self._in.get(node_id, [])
        else:
            idxs = bucket.get(node_id, [])
        out = []
        for i in idxs:
            e = self.edges[i]
            if etype and e["type"] != etype:
                continue
            if min_conf and e.get("confidence", 1.0) < min_conf:
                continue
            other = e["dst"] if e["src"] == node_id else e["src"]
            if traverse_tier is not None:
                nb = self.nodes.get(other)
                if nb and nb.get("label") == "Table" and nb.get("tier") != traverse_tier:
                    continue
            out.append((other, e))
        return out

    def counts(self) -> dict:
        lc: dict[str, int] = defaultdict(int)
        ec: dict[str, int] = defaultdict(int)
        tierc: dict[str, int] = defaultdict(int)
        for n in self.nodes.values():
            lc[n["label"]] += 1
            if n["label"] == "Table":
                tierc[n.get("tier", "?")] += 1
        for e in self.edges:
            ec[e["type"]] += 1
        return {"labels": dict(lc), "edge_types": dict(ec), "table_tiers": dict(tierc)}

    # ---- 导出（稳定 JSON）--------------------------------------------
    def to_graph_json(self) -> dict:
        nodes = [self.nodes[k] for k in sorted(self.nodes)]
        edges = sorted(
            self.edges,
            key=lambda e: (e["type"], e["src"], e["dst"], e.get("quote_hash", "")),
        )
        return {"nodes": nodes, "edges": edges, "summary": self.counts()}


def _build_domains(g: PropertyGraph, membership: dict) -> None:
    for dom, declared in DOMAIN_DECLARED.items():
        entry = membership.get(dom, {})
        g.add_node(_did(dom), "Domain", domain_id=dom,
                   table_count=declared,
                   enumerated=len(entry.get("members", [])),
                   tier="A",
                   kind="leftover-ot" if dom == "OT" else "business-domain")
    # B-FAMILY（tier B 社区）
    g.add_node(_did("B-FAMILY"), "Domain", domain_id="B-FAMILY",
               table_count=None, tier="B", kind="family")


def build_graph(ddl_tables: dict, membership: dict, edges_01: list[dict],
                edges_05: list[dict], derived: dict, issues: dict) -> tuple[PropertyGraph, dict]:
    """装配 L0 属性图。返回 (graph, build_report)。"""
    g = PropertyGraph()
    names = set(ddl_tables)
    # name → domain（A 级成员，来自 00 §四 枚举）
    name_domain: dict[str, str] = {}
    for dom, entry in membership.items():
        for m in entry.get("members", []):
            name_domain[m] = dom

    _build_domains(g, membership)

    report = {
        "a_no_domain": [],            # A 级但 00 未枚举域（应为 0）
        "rel_dangling": [],           # 结构性悬挂（应为 0，桩/外部已在解析层处理）
        "pending_confirmation": [],   # RELATES_TO 端点不可解析 → [待确认]（不臆造）
        "derived_dangling": derived.get("dangling", []),
    }

    # ---- Table + Column（分级建点）----------------------------------
    b_family_members: dict[tuple[str, str], list[str]] = defaultdict(list)
    for name in sorted(names):
        tier = classify_tier(name)
        t = ddl_tables[name]
        props = dict(
            name=name, table_id=name, tier=tier,
            prefix_family=prefix_family(name),
            pk_columns=t.pk_columns, has_pk=t.has_pk,
            column_count=t.column_count,
            table_charset=t.table_charset, table_collation=t.table_collation,
            table_comment=t.table_comment,
        )
        if tier == "A":
            dom = name_domain.get(name, "OT" if prefix_family(name) == "none" else None)
            props["domain"] = dom
            props["expanded"] = True
            props["suspected_legacy"] = name in OT_SUSPECTED_LEGACY
            g.add_node(_tid(name), "Table", **props)
            if dom:
                g.add_edge(_tid(name), _did(dom), "BELONGS_TO_DOMAIN",
                           is_inferred=False)
            else:
                report["a_no_domain"].append(name)
            # Column 节点（DDL 直取，权威）
            for c in t.columns:
                g.add_node(_cid(name, c.name), "Column",
                           column_id=f"{name}.{c.name}", table_id=name, name=c.name,
                           data_type=c.data_type, nullable=c.nullable, default=c.default,
                           key_role=c.key_role, semantic=c.semantic,
                           charset=c.charset, collation=c.collation)
                g.add_edge(_cid(name, c.name), _tid(name), "IS_COLUMN_OF",
                           is_inferred=False)
        elif tier == "B":
            fam = family_of(name)
            props["domain"] = "B-FAMILY"
            props["expanded"] = False
            props["family"] = fam
            props["representative_of"] = None   # 稍后回填
            g.add_node(_tid(name), "Table", **props)
            g.add_edge(_tid(name), _did("B-FAMILY"), "BELONGS_TO_DOMAIN",
                       is_inferred=False)
            b_family_members[(fam, family_base(name))].append(name)
        else:  # C
            props["domain"] = None
            props["expanded"] = False
            props["source_table"] = None        # DERIVED_FROM 回填
            g.add_node(_tid(name), "Table", **props)

    # ---- B 级族归约（representative + member_count + SAME_FAMILY_AS）--
    for (fam, base), members in b_family_members.items():
        rep = sorted(members)[0]
        for m in members:
            node = g.nodes[_tid(m)]
            node["family_base"] = base
            node["member_count"] = len(members)
            node["representative_of"] = None if m == rep else _tid(rep).split(":", 1)[1]
            if m != rep:
                g.add_edge(_tid(m), _tid(rep), "SAME_FAMILY_AS",
                           family=fam, family_base=base, member_count=len(members),
                           is_inferred=True)

    # ---- RELATES_TO（A 级表间，100% 挂 EvidenceSrc）------------------
    for e in list(edges_01) + list(edges_05):
        src, dst = e["src"], e["dst"]
        s_id, d_id = _tid(src), _tid(dst)
        if not g.has_node(s_id) or not g.has_node(d_id):
            missing = [x for x, ok in ((src, g.has_node(s_id)), (dst, g.has_node(d_id))) if not ok]
            report["pending_confirmation"].append({
                "src": src, "dst": dst, "missing_endpoints": missing,
                "raw": f"{e.get('src_raw', src)}->{e.get('dst_raw', dst)}",
                "file": e.get("source_file"), "reason": "endpoint_not_in_ddl_pending"})
            continue
        ev_id = _evd(e["quote_hash"])
        if not g.has_node(ev_id):
            g.add_node(ev_id, "EvidenceSrc",
                       file=e["source_file"], section=e.get("source_section"),
                       table=src, column=e.get("via_column"),
                       quote_hash=e["quote_hash"], line_hint=e.get("line_hint"))
        g.add_edge(s_id, d_id, "RELATES_TO",
                   cardinality=e["cardinality"], direction=e["direction"],
                   via_column=e["via_column"], evidence_level=e["evidence_level"],
                   evidence_tags=e["evidence_tags"], confidence=e["confidence"],
                   is_inferred=True, cross_domain=e["cross_domain"],
                   polymorphic=e["polymorphic"], discriminant=e["discriminant"],
                   has_uncertain=e.get("has_uncertain", False),
                   malformed_connector=e["malformed_connector"],
                   left_cardinality=e["left_cardinality"], connector=e["connector"],
                   source_domain=e["source_domain"], source_file=e["source_file"],
                   quote_hash=e["quote_hash"], evidence_ref=ev_id,
                   raw_desc=e["raw_desc"])
        g.add_edge(s_id, ev_id, "SUPPORTED_BY", rel_kind="RELATES_TO", dst_table=dst)

    # ---- DERIVED_FROM（C 级 / copy1）--------------------------------
    for e in derived["edges"]:
        s_id, d_id = _tid(e["table"]), _tid(e["source"])
        if g.has_node(s_id) and g.has_node(d_id):
            g.nodes[s_id]["source_table"] = e["source"]
            g.add_edge(s_id, d_id, "DERIVED_FROM",
                       is_inferred=e["is_inferred"],
                       naming_rule_derived=e["naming_rule_derived"],
                       evidence_level=e["evidence_level"], confidence=e["confidence"],
                       resolution_rule=e["resolution_rule"],
                       source_ref=e["source_ref"])
    for e in derived.get("copy1_edges", []):
        s_id, d_id = _tid(e["table"]), _tid(e["source"])
        if g.has_node(s_id) and g.has_node(d_id):
            g.add_edge(s_id, d_id, "DERIVED_FROM",
                       is_inferred=e["is_inferred"], naming_rule_derived=True,
                       evidence_level="comment_explicit", confidence=0.90,
                       resolution_rule="strip_copy1", source_ref=e["source_ref"])

    # ---- Issue + HAS_ISSUE ------------------------------------------
    for iss in issues["issues"]:
        g.add_node(_iid(iss["issue_id"]), "Issue",
                   issue_id=iss["issue_id"], category=iss["category"],
                   category_name=iss["category_name"], title=iss["title"],
                   severity=iss["severity"], scope=iss["scope"])
    for edge in issues["has_issue_edges"]:
        s_id, d_id = _tid(edge["table"]), _iid(edge["issue"])
        if g.has_node(s_id) and g.has_node(d_id):
            g.add_edge(s_id, d_id, "HAS_ISSUE", is_inferred=False)

    return g, report


def export_graph(g: PropertyGraph, path) -> dict:
    data = g.to_graph_json()
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2, sort_keys=False),
                    encoding="utf-8")
    return data


def export_edges_jsonl(g: PropertyGraph, path) -> int:
    n = 0
    with path.open("w", encoding="utf-8") as fh:
        for e in sorted(g.edges, key=lambda e: (e["type"], e["src"], e["dst"], e.get("quote_hash", ""))):
            fh.write(json.dumps(e, ensure_ascii=False, sort_keys=True) + "\n")
            n += 1
    return n
