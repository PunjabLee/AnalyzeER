"""M2 · REFERENCES（字段级引用/结构级血缘）推断器。

契约依据（graphrag/spec，M0 冻结）：
- schema.md §2：`(column)-[:REFERENCES {evidence_level, confidence, is_inferred:true}]->(column)`；
  全库 0 FK → 所有边 is_inferred=true，证据上限 comment_explicit，confidence 上限 0.95。
- evidence-confidence-map.md §1/§2：信号优先级 **COMMENT 点名目标表(comment_explicit)
  > `_id`/`_code` 列建 INDEX/UNIQUE(index_backed) > 命名/语义(name_inferred/semantic_inferred)**；
  复合取较高档并记 evidence_tags。
- 特殊处置：多态外键 → 不武断指单表（入待确认队列，unconfirmed 级）；
  同名列异指向(05 B-4) → 无法由 COMMENT/文档上下文定指向者不连通、入队列；
  外部系统引用(crm/oa 等) → 不建本库边，登记 external_reference。
- 边界：仅"引用即血缘"。**变换/ETL 数据流级（sum(x)→y）超本期范围，不建**（schema §4.3/R-9）。

信号源与防误挂策略（实测校准，明细见 lineage_manifest.calibration）：
1. comment_explicit —— 列 COMMENT 出现 DDL 真实物理表名（词边界；含 `a.x =/-> b.y` 显式列对）。
2. doc_relation    —— M1 表级 RELATES_TO 线的 via_column 归属唯一持有该列的一端（er-model 一等锚；
                       亦覆盖 `_no` 等文档明示列，如 exchange_order_no -> order_no 自关联）。
3. index_backed    —— `_id`/`_code` 命名 + 本表 INDEX/UNIQUE 佐证 + 目标列命中 PK/UK/同名/`code`。
4. name_inferred   —— 唯一规范命名解析（含固化拼写映射 catregory/datat；**歧义不猜**）。
5. semantic_inferred —— `_code` 列目标表无同名/`code` 列而只能指 PK 的"列级猜测"，降权+警示
                       （默认阈值 0.45 下自动隐藏）。

固化拼写：`jf_catregory`/`jf_datat_dict` 等 DDL 原样名（schema §1.2）。category↔catregory 双向
候选生成；当 `jf_category` 与 `jf_catregory` 并存（均 A 级）时属歧义 → 无 COMMENT/文档定指向
即入队列，绝不武断连通（B-4 同款处置）。
"""

from __future__ import annotations

import re

from ..ingest.config import EVIDENCE_ORDER, CONFIDENCE_DEFAULT_MIN, confidence_for
from .evidence import ddl_evidence, doc_evidence

REFERENCES_TYPE = "REFERENCES"

# 触发列（DDL 结构信号仅认 _id/_code；_no 等只接受 er-model 文档线明示的情形）
TRIG_SUFFIXES = ("_id", "_code")

# 固化拼写 ↔ 规范名（双向；仅用于候选生成，歧义仍入队列，DDL 物理名为最终仲裁）
MISSPELL_MAP = {"category": "catregory", "catregory": "category",
                "data": "datat", "datat": "data"}

# 外部系统引用（D15 crm / OA 流程等，00 复核批①）——命中且未点名库内表 → 不建本库边
_EXTERNAL_RE = re.compile(
    r"(?i)(\bcrm\b|外部系统|外部用户|第三方系统|\bsap\b|oauth"
    r"|oa流程|oa审批|oa编号|oa状态|oa编码|关联oa|oa单号)")

# 多态/判别列线索（05 A-3 文档登记 + 通用注释模式）
_POLY_DOCUMENTED = [
    {"table": "flow_change_record", "column": "related_order_id",
     "discriminant": "order_type",
     "enum_hint": "采购单、销售单、退款单、调账单等",
     "source": "er-model/05-跨域核心关系总览.md §三 A-3"},
    {"table": "jf_trial_cut_customer_mapping", "column": "other_id",
     "discriminant": None,
     "enum_hint": "客户等级id/指定客户id（一列两指向；05 A-3 权益 other_id 同款）",
     "source": "er-model/05-跨域核心关系总览.md §三 A-3"},
]
_POLY_COMMENT_RE = re.compile(
    r"(按|根据).{0,12}(类型|种类).{0,14}(区分|指向|对应)|等多表|等单据")

# `x.a -> y.b` / `x.a = y.b` 显式列对；`-> 裸词`
_PAIR_ARROW_RE = re.compile(
    r"([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)\s*(?:->|→|=)\s*"
    r"([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)")
_ARROW_RIGHT_RE = re.compile(r"(?:->|→)\s*([A-Za-z_][A-Za-z0-9_.]*)")

_RANK = {lvl: i for i, lvl in enumerate(EVIDENCE_ORDER)}
_CAP = _RANK["comment_explicit"]          # 证据硬上限（全库 0 FK）


def _rank(level: str) -> int:
    return _RANK.get(level, len(EVIDENCE_ORDER) - 1)


def _cid(table: str, column: str) -> str:
    return f"column:{table}.{column}"


def _tid(table: str) -> str:
    return f"table:{table}"


def _strip_id(node_id: str) -> str:
    return node_id.split(":", 1)[1]


def _strip_pfx(t: str) -> str:
    for p in ("jf_", "lcap_"):
        if t.startswith(p):
            return t[len(p):]
    return t


class LineageExtractor:
    """在 M1 图（快照回载）之上生成 REFERENCES 边与待确认队列项。

    不修改传入 graph 的既有节点/边（合并写入由 build.py 在图副本上执行）。
    """

    def __init__(self, graph, ddl: dict, line_index: dict):
        self.g = graph
        self.ddl = ddl
        self.line_index = line_index
        self.A: set[str] = {n["name"] for n in graph.nodes.values()
                            if n["label"] == "Table" and n.get("tier") == "A"}
        self.cols: dict[str, set[str]] = {
            t: {c.name for c in ddl[t].columns} for t in ddl}
        self.colobj: dict[str, dict[str, object]] = {
            t: {c.name: c for c in ddl[t].columns} for t in ddl}
        self.base_map: dict[str, list[str]] = {}
        for t in self.A:
            self.base_map.setdefault(_strip_pfx(t), []).append(t)
        _names = sorted(self.A, key=len, reverse=True)
        self._tbl_in_comment = re.compile(
            r"(?<![A-Za-z0-9_])(" + "|".join(re.escape(n) for n in _names) +
            r")(?![A-Za-z0-9_])") if _names else None
        self.rel_edges = [e for e in graph.edges if e["type"] == "RELATES_TO"]

        self.edges: dict[tuple[str, str], dict] = {}
        self.queue: list[dict] = []
        self.queued_cols: set[tuple[str, str]] = set()
        self.stats = {"dropped_naming_unresolved": 0, "doc_skipped": {},
                      "conflicts": []}

    # ------------------------------------------------------------------ 基础
    def _has_col_node(self, table: str, column: str) -> bool:
        return self.g.has_node(_cid(table, column))

    def _target_key_ok(self, table: str, column: str) -> bool:
        d = self.ddl[table]
        return (column in d.pk_columns or column in d.unique_columns
                or column == "id")

    def _pick_target_column(self, owner_table: str, col_name: str,
                            target_table: str, explicit_col: str | None = None):
        """目标列规则：explicit(pair) > pk(_id 标准) > same_name(_code) > code_col > pk_guess。
        rule=pk_guess ⇒ 列级猜测，调用方降权 semantic_inferred；missing ⇒ 入队列。"""
        if target_table not in self.ddl:
            return None, "missing"
        names = self.cols[target_table]
        if explicit_col and explicit_col in names:
            return explicit_col, "explicit_pair"
        is_code_like = col_name.endswith("_code") or col_name.endswith("_no")
        if not is_code_like:                                   # `_id` → 目标 PK
            pk = self.ddl[target_table].pk_columns
            if pk and pk[0] in names:
                return pk[0], "pk"
            if "id" in names:
                return "id", "pk"
            return None, "missing"
        if col_name in names:
            return col_name, "same_name"
        if "code" in names:
            return "code", "code_col"
        pk = self.ddl[target_table].pk_columns
        if pk and pk[0] in names:
            return pk[0], "pk_guess"
        if "id" in names:
            return "id", "pk_guess"
        return None, "missing"

    def _out_edges_of(self, table: str, column: str) -> list[dict]:
        return [e for e in self.edges.values()
                if e["src_table"] == table and e["src_column"] == column]

    def _mk_edge(self, st: str, sc: str, tt: str, tc: str, level: str,
                 signals: list[str], ev_primary: dict, tags: list[str],
                 target_col_rule: str) -> bool:
        src, dst = _cid(st, sc), _cid(tt, tc)
        if src == dst:
            return False
        if _rank(level) < _CAP:
            level = "comment_explicit"                        # 上限锁死
        conf = min(confidence_for(level), 0.95)
        key = (src, dst)
        if key in self.edges:                                 # 多边命中同一对列 → 归并取强
            e = self.edges[key]
            old = dict(e["evidence_src"])
            if _rank(level) < _rank(e["evidence_level"]):
                e["supporting_evidence"].append(old)
                e["evidence_src"], e["target_col_rule"] = ev_primary, target_col_rule
                e["evidence_level"], e["confidence"] = level, conf
            elif ev_primary not in e["supporting_evidence"]:
                e["supporting_evidence"].append(ev_primary)
            e["signals"] = sorted(set(e["signals"]) | set(signals))
            e["evidence_tags"] = sorted(set(e["evidence_tags"]) | set(tags))
            return False
        self.edges[key] = {
            "type": REFERENCES_TYPE, "src": src, "dst": dst,
            "src_table": st, "src_column": sc,
            "dst_table": tt, "dst_column": tc,
            "src_domain": self.g.nodes[_tid(st)].get("domain"),
            "dst_domain": self.g.nodes[_tid(tt)].get("domain"),
            "evidence_level": level, "confidence": conf,
            "is_inferred": True,
            "evidence_tags": sorted(set(tags)),
            "target_col_rule": target_col_rule,
            "polymorphic": False, "discriminant": None, "external_reference": False,
            "lineage_scope": "reference_or_structure_only",
            "signals": list(signals),
            "evidence_src": ev_primary,
            "supporting_evidence": [],
        }
        return True

    def _queue(self, kind: str, table: str, column: str, reason: str,
               evidence: dict | None = None, **extra):
        item = {"kind": kind, "table": table, "column": column,
                "reason": reason, "status": "[待确认]",
                "confidence": confidence_for("unconfirmed")}
        if evidence:
            item["evidence"] = evidence
        item.update(extra)
        self.queue.append(item)
        self.queued_cols.add((table, column))

    def _is_external(self, comment: str, col_name: str) -> bool:
        if comment and _EXTERNAL_RE.search(comment):
            return True
        return bool(re.search(r"(?:^|_)(?:crm|oa|sap)(?:_|$)", col_name))

    # -------------------------------------------------------- comment 解析
    def _resolve_comment(self, t: str, c) -> list[dict]:
        """COMMENT 点名目标（真实物理表名，词边界）。返回解析列表（空=未点名）。"""
        comment = c.semantic or ""
        out: list[dict] = []
        if self._tbl_in_comment is None or not comment:
            return out
        for m in _PAIR_ARROW_RE.finditer(comment):
            lt, lc, rt, rc = m.groups()
            if (lt, lc) == (t, c.name) and rt in self.A:
                out.append({"table": rt, "explicit_col": rc, "quote": m.group(0)})
            elif (rt, rc) == (t, c.name) and lt in self.A:
                out.append({"table": lt, "explicit_col": lc, "quote": m.group(0)})
        if out:
            return out
        named = sorted({x for x in self._tbl_in_comment.findall(comment) if x != t})
        if len(named) == 1:
            out.append({"table": named[0], "explicit_col": None, "quote": comment})
        elif len(named) > 1:
            out.append({"table": None, "ambiguous_tables": named, "quote": comment})
        return out

    # ------------------------------------------------------------------ 主流程
    def run(self) -> tuple[list[dict], list[dict], dict]:
        trigger_cols: set[tuple[str, str]] = set()
        for t in sorted(self.A):
            for c in self.colobj[t].values():
                if c.name != "id" and c.name.endswith(TRIG_SUFFIXES):
                    trigger_cols.add((t, c.name))

        # ---- 0) 多态（05 A-3 文档登记 + 注释模式扫描）：入队列，不武断建边 ----
        for p in _POLY_DOCUMENTED:
            t, col = p["table"], p["column"]
            if t in self.A and col in self.cols.get(t, set()):
                self._queue("polymorphic_fk", t, col,
                            "多态外键：一列按判别列/语义指向多表，目标不定（05 A-3）。"
                            "不武断指单表；候选仅为线索，留人工确认。",
                            ddl_evidence(self.line_index, t, col, "documented_poly"),
                            discriminant=p["discriminant"], enum_hint=p["enum_hint"],
                            source=p["source"],
                            candidates_hint=self._poly_candidates(p["enum_hint"]))
        for (t, col) in sorted(trigger_cols):
            if (t, col) in self.queued_cols:
                continue
            sem = self.colobj[t][col].semantic or ""
            if sem and _POLY_COMMENT_RE.search(sem):
                self._queue("polymorphic_fk", t, col,
                            "COMMENT 呈「按类型区分/等多表/等单据」式一列多指向特征",
                            ddl_evidence(self.line_index, t, col, "comment_poly_scan"),
                            discriminant=None, comment=sem)

        # ---- 1) comment_explicit（最强信号，优先定指向）----
        for (t, col) in sorted(trigger_cols):
            if (t, col) in self.queued_cols:
                continue
            c = self.colobj[t][col]
            if self._is_external(c.semantic or "", c.name) and \
                    not self._resolve_comment(t, c):
                self._queue("external_reference", t, col,
                            "COMMENT/列名指向外部系统（crm/OA 等），不建本库 REFERENCES 边"
                            "（schema §2.1；00 复核批①）",
                            ddl_evidence(self.line_index, t, col, "comment_external"))
                continue
            resolved = self._resolve_comment(t, c)
            if not resolved:
                continue
            if resolved[0].get("table") is None:
                self._queue("comment_multi_target", t, col,
                            "COMMENT 同时点名多张真实表，指向不定 → 不武断连通",
                            ddl_evidence(self.line_index, t, col, "comment_ambiguous"),
                            candidate_tables=resolved[0]["ambiguous_tables"])
                trigger_cols.discard((t, col))
                continue
            r = resolved[0]
            tt = r["table"]
            tc, rule = self._pick_target_column(t, col, tt, r.get("explicit_col"))
            if tc is None or not self._has_col_node(tt, tc):
                self._queue("target_column_missing", t, col,
                            f"COMMENT 点名表 {tt} 但目标列不可定位(rule={rule})",
                            ddl_evidence(self.line_index, t, col, "comment_explicit"),
                            candidate_tables=[tt])
                trigger_cols.discard((t, col))
                continue
            ev = ddl_evidence(self.line_index, t, col, "comment_explicit")
            ev["quote"] = r["quote"][:200]
            lvl = "comment_explicit" if rule != "pk_guess" else "name_inferred"
            tags = ["comment"] + (["index"] if c.indexed else [])
            self._mk_edge(t, col, tt, tc, lvl, ["comment"], ev, tags, rule)
            trigger_cols.discard((t, col))

        # ---- 2) doc_relation（M1 RELATES_TO via_column；er-model 一等锚；覆盖 _no 列）----
        for e in self.rel_edges:
            vc = e.get("via_column")
            if not vc:
                continue
            s, d = _strip_id(e["src"]), _strip_id(e["dst"])
            if s == d:
                owner, target = s, s
                if vc not in self.cols.get(owner, set()):
                    self._bump("via_col_not_in_owner")
                    continue
            else:
                owners = [x for x in (s, d) if vc in self.cols.get(x, set())]
                if len(owners) == 0:
                    self._bump("via_col_absent_both")
                    continue
                if len(owners) == 2:                # 两端同持此列 → 归属不定（不猜）
                    self._bump("owner_ambiguous_both_have_col")
                    continue
                owner = owners[0]
                target = d if owner == s else s
            if owner not in self.A or target not in self.A:
                self._bump("endpoint_not_traversable_A")     # B/C 影子无 Column 节点
                continue
            if (owner, vc) in self.queued_cols:              # 多态/外部等已定处置
                continue
            existing = self._out_edges_of(owner, vc)
            if any(_rank(x["evidence_level"]) == _CAP for x in existing) and \
                    all(x["dst_table"] != target for x in existing):
                self.stats["conflicts"].append(
                    {"table": owner, "column": vc, "kept": [x["dst_table"] for x in existing],
                     "not_added": target, "rule": "comment>doc>name"})
                continue
            desc = e.get("raw_desc") or ""
            if _EXTERNAL_RE.search(desc):
                self._queue("external_reference", owner, vc,
                            "er-model 关系线标注外部用户/外部系统 → 不建本库边",
                            doc_evidence(e, self.g.nodes))
                continue
            explicit = self._doc_target_col_hint(owner, vc, target, desc)
            tc, rule = self._pick_target_column(owner, vc, target, explicit)
            if rule == "pk_guess":
                lvl = "semantic_inferred"                    # 表级文档明示、列级仅猜测 → 降权
            else:
                lvl = e.get("evidence_level", "unconfirmed")
                if _rank(lvl) < _CAP:
                    lvl = "comment_explicit"
            if tc is None or not self._has_col_node(target, tc):
                self._queue("target_column_missing", owner, vc,
                            f"文档线目标列不可定位(target={target}, rule={rule})",
                            doc_evidence(e, self.g.nodes), candidate_tables=[target])
                continue
            ev = doc_evidence(e, self.g.nodes)
            ev["quote"] = desc[:160]
            tags = ["doc"]
            if e.get("evidence_tags") and "index" in e["evidence_tags"]:
                tags.append("index")
            self._mk_edge(owner, vc, target, tc, lvl, ["doc"], ev, tags, rule)

        # ---- 3) 命名/索引（仅处理未被 comment/doc 定性的触发列）----
        resolved_any = {(e["src_table"], e["src_column"]) for e in self.edges.values()}
        for (t, col) in sorted(trigger_cols):
            if (t, col) in self.queued_cols or (t, col) in resolved_any:
                continue
            c = self.colobj[t][col]
            stem = re.sub(r"_(id|code)$", "", col)
            keys = {stem, MISSPELL_MAP.get(stem, stem)}
            cands: set[str] = set()
            for k in keys:
                cands.update(self.base_map.get(k, []))
            cands_sorted = sorted(cands)
            if not cands_sorted:
                if c.indexed or c.key_role in ("UK",):
                    self._queue("target_unresolved", t, col,
                                "`_id/_code` + 本表建有 INDEX/UNIQUE，但规范命名无法定位目标表"
                                "（不连「最近」表）",
                                ddl_evidence(self.line_index, t, col, "index_without_target"),
                                key_role=c.key_role, indexed=bool(c.indexed),
                                comment=c.semantic)
                else:
                    self.stats["dropped_naming_unresolved"] += 1
                continue
            if len(cands_sorted) > 1:
                self._queue("same_name_or_typo_ambiguity", t, col,
                            "多张真实 A 级表同基名/固化拼写并存（05 B-4 同名异指向类），"
                            "无 COMMENT/文档定指向 → 不自动连通",
                            ddl_evidence(self.line_index, t, col, "ambiguous_stem"),
                            candidate_tables=cands_sorted, comment=c.semantic)
                continue
            tt = cands_sorted[0]
            tc, rule = self._pick_target_column(t, col, tt, None)
            if tc is None or not self._has_col_node(tt, tc):
                self._queue("target_column_missing", t, col,
                            f"命名解析表 {tt} 存在但目标列不可定位(rule={rule})",
                            ddl_evidence(self.line_index, t, col, "naming"),
                            candidate_tables=[tt])
                continue
            if rule == "pk_guess":
                lvl = "semantic_inferred"
            elif (c.indexed or c.key_role in ("UK",)) and self._target_key_ok(tt, tc):
                lvl = "index_backed"
            else:
                lvl = "name_inferred"
            tags = ["name"] + (["index"] if (c.indexed or c.key_role == "UK") else [])
            ev = ddl_evidence(self.line_index, t, col,
                              "index+name" if "index" in tags else "name_only")
            self._mk_edge(t, col, tt, tc, lvl, ["naming"], ev, tags, rule)

        # ---- 4) B-4 全景留痕：同名列跨表异指向（已连通者也要可见）----
        by_col: dict[str, set[str]] = {}
        for e in self.edges.values():
            by_col.setdefault(e["src_column"], set()).add(
                f"{e['dst_table']}.{e['dst_column']}")
        for col, targets in sorted(by_col.items()):
            if len(targets) > 1:
                srcs = sorted({f"{e['src_table']}.{e['src_column']}"
                               for e in self.edges.values()
                               if e["src_column"] == col})
                self.queue.append({
                    "kind": "same_name_divergent", "column": col,
                    "tables": srcs, "resolved_targets": sorted(targets),
                    "reason": "05 B-4 同名列跨表异指向；各端已由 COMMENT/文档/索引分别定指向"
                              "（连通=有上下文证据，非武断），留痕供人工复核",
                    "status": "[待确认]",
                    "confidence": confidence_for("unconfirmed")})

        edges = sorted(self.edges.values(), key=lambda e: (e["src"], e["dst"]))
        return edges, self.queue, self._stats(edges)

    # ---------------------------------------------------------------- 小工具
    def _bump(self, key: str):
        self.stats["doc_skipped"][key] = self.stats["doc_skipped"].get(key, 0) + 1

    def _doc_target_col_hint(self, owner: str, vc: str, target: str,
                             desc: str) -> str | None:
        """从 raw_desc 抽 `owner.col -> [alias.]col` 的目标列名（裸词须命中目标表列集）。"""
        names = self.cols.get(target, set())
        for m in _PAIR_ARROW_RE.finditer(desc):
            lt, lc, _rt, rc = m.groups()
            if (lt, lc) == (owner, vc) and rc in names:
                return rc
        m2 = _ARROW_RIGHT_RE.search(desc)
        if m2:
            tail = m2.group(1).split(".")[-1]
            if tail != vc and tail in names:
                return tail
        return None

    def _poly_candidates(self, enum_hint: str | None) -> list[str]:
        """按 05 A-3 枚举中文粗映射存在表（仅提示，不入边——避免武断）。"""
        cands: set[str] = set()
        hint = enum_hint or ""
        name_map = {"销售": "sales_order", "退款": "refund", "调账": "adjust",
                    "采购": "purchase", "客户等级": "customer_level", "客户": "customer"}
        for t2 in self.A:
            b = _strip_pfx(t2)
            for zh, en in name_map.items():
                if zh in hint and b == en:
                    cands.add(t2)
        return sorted(cands)

    def _stats(self, edges: list[dict]) -> dict:
        by_lvl: dict[str, int] = {}
        by_rule: dict[str, int] = {}
        self_ref = 0
        for e in edges:
            by_lvl[e["evidence_level"]] = by_lvl.get(e["evidence_level"], 0) + 1
            by_rule[e["target_col_rule"]] = by_rule.get(e["target_col_rule"], 0) + 1
            if e["src_table"] == e["dst_table"]:
                self_ref += 1
        kinds: dict[str, int] = {}
        for q in self.queue:
            kinds[q["kind"]] = kinds.get(q["kind"], 0) + 1
        return {
            "references_total": len(edges),
            "by_evidence_level": by_lvl,
            "by_target_col_rule": by_rule,
            "self_references": self_ref,
            "default_visible_ge_0.45": sum(
                1 for e in edges if e["confidence"] >= CONFIDENCE_DEFAULT_MIN),
            "queue_kinds": kinds,
            "queue_total_before_carryover": len(self.queue),
            "dropped_naming_unresolved": self.stats["dropped_naming_unresolved"],
            "doc_skipped": self.stats["doc_skipped"],
            "signal_conflicts_resolved": self.stats["conflicts"],
        }
