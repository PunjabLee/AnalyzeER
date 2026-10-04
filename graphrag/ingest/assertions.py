"""M1 · 落库前数量等式断言（eval-baseline.md §3 护栏；任一不闭合 → 阻断不交付）。

纯确定性检查，目标值全部来自 M0 实测（config.EQ / DOMAIN_DECLARED），非约定。
"""

from __future__ import annotations

import re
from collections import defaultdict
from dataclasses import dataclass, field

from .config import EQ, SQL_FILE
from .ddl_parser import ddl_metrics
from .tiering import tier_counts


@dataclass
class Check:
    name: str
    target: object
    actual: object
    ok: bool
    note: str = ""


@dataclass
class AssertReport:
    checks: list[Check] = field(default_factory=list)

    def add(self, name, target, actual, note=""):
        ok = (target == actual)
        self.checks.append(Check(name, target, actual, ok, note))
        return ok

    def add_le(self, name, cap, actual, note=""):
        ok = (actual <= cap)
        self.checks.append(Check(name, f"<= {cap}", actual, ok, note))
        return ok

    def add_info(self, name, actual, note=""):
        """信息项（非硬门），恒 ok，仅登记实测值（[待确认]/口径差等）。"""
        self.checks.append(Check(name, "info", actual, True, note))
        return True

    @property
    def all_pass(self) -> bool:
        return all(c.ok for c in self.checks)

    def failed(self) -> list[Check]:
        return [c for c in self.checks if not c.ok]

    def to_dict(self) -> dict:
        return {
            "all_pass": self.all_pass,
            "passed": sum(1 for c in self.checks if c.ok),
            "failed": sum(1 for c in self.checks if not c.ok),
            "checks": [{"name": c.name, "target": c.target, "actual": c.actual,
                        "ok": c.ok, "note": c.note} for c in self.checks],
        }


def _fk_counts() -> tuple[int, int]:
    text = SQL_FILE.read_text(encoding="utf-8", errors="replace")
    fk = len(re.findall(r"\bFOREIGN\s+KEY\b", text, re.IGNORECASE))
    ac = len(re.findall(r"\bADD\s+CONSTRAINT\b", text, re.IGNORECASE))
    return fk, ac


def run_assertions(ddl_tables: dict, domain_report: dict,
                   graph, build_report: dict, rel_stats: dict, issues: dict,
                   header_doc: dict, header_cc: dict) -> AssertReport:
    r = AssertReport()
    names = set(ddl_tables)

    # ---- DDL / FK / PK ------------------------------------------------
    dm = ddl_metrics(ddl_tables)
    r.add("ddl_table_total", EQ["ddl_table_total"], dm["ddl_table_total"])
    fk, ac = _fk_counts()
    r.add("explicit_fk(FOREIGN KEY)", EQ["explicit_fk"], fk, "全库 0 外键")
    r.add("explicit_fk(ADD CONSTRAINT)", EQ["explicit_fk"], ac)
    r.add("pk_declared_tables", EQ["pk_declared"], dm["pk_declared_tables"])
    r.add("no_pk_tables", EQ["no_pk"], dm["no_pk_tables"])

    # ---- 分级（N-2）---------------------------------------------------
    tc = tier_counts(names)
    r.add("A_level", EQ["a_level"], tc["A"])
    r.add("A.jf", EQ["jf_a"], tc["jf_a"])
    r.add("A.OT", EQ["ot_a"], tc["ot_a"])
    r.add("B_level", EQ["b_level"], tc["B"])
    r.add("B.lcap", EQ["b_lcap"], tc["b_lcap"])
    r.add("B.quartz", EQ["b_quartz"], tc["b_quartz"])
    r.add("B.activiti", EQ["b_activiti"], tc["b_activiti"])
    r.add("C_level", EQ["c_level"], tc["C"])
    r.add("C.bak", EQ["c_bak"], tc["c_bak"])
    r.add("C.test", EQ["c_test"], tc["c_test"])
    r.add("registered_total", EQ["registered_total"], tc["registered_total"],
          "A+B+C=349+853+120=1322")

    # ---- 图内可遍历主语料 = A ----------------------------------------
    trav = sum(1 for n in graph.nodes.values()
               if n["label"] == "Table" and n.get("tier") == "A" and n.get("expanded"))
    r.add("traversable_A(图内)", EQ["a_level"], trav)

    # ---- 每域声明 == 枚举 == 命中 DDL --------------------------------
    bad_domains = [d for d, rep in domain_report.items() if not rep["match"]]
    r.add("domain_declared_eq_enumerated(D01..D18+OT)", 0, len(bad_domains),
          ("不闭合域：" + ",".join(bad_domains)) if bad_domains else "逐域=00 声明")

    # ---- Issue -------------------------------------------------------
    r.add("issue_total", EQ["issue_total"], issues["total"],
          "05 实测（口径差 C-δ：非 30）")
    r.add("issue_nodes(图内)", EQ["issue_total"],
          sum(1 for n in graph.nodes.values() if n["label"] == "Issue"))

    # ---- 关系线（census）---------------------------------------------
    s01 = rel_stats["01"]
    r.add("rel_01_total(口径ξ全量)", EQ["rel_01_total"], s01["connector_total"])
    r.add("rel_01_distinct_shapes", 12, s01["distinct_shapes"])
    r.add("rel_01_malformed", EQ["rel_01_malformed"], s01["malformed_count"])
    r.add_le("rel_01_edges_after_external(≤全量)", EQ["rel_01_total"],
             s01["edges_after_external"], "外部引用剔除后的建边数 ≤ 456（census §子口径）")
    r.add("rel_05_total", EQ["rel_05_total"], rel_stats["05"]["connector_total"], "全 ||..o{")

    # ---- 表头归一（header-normalization）-----------------------------
    r.add("field_header_variants", EQ["field_header_variants"],
          header_doc["field_signature_distinct"])
    r.add("field_header_rows", EQ["field_header_rows"], header_doc["field_header_rows"])
    r.add("nonfield_header_variants", EQ["nonfield_header_variants"],
          header_doc["nonfield_signature_distinct"])
    r.add("nonfield_header_rows", EQ["nonfield_header_rows"], header_doc["nonfield_header_rows"])
    r.add("total_header_rows", EQ["total_header_rows"], header_doc["total_header_rows"])
    r.add("unmapped_headers", 0, len(header_doc["unmapped_headers"]))

    # ---- 合并行拆回 ⊳ DDL 交叉校验（DDL 为最终仲裁）------------------
    r.add("audit_merged_expansion_in_DDL", header_cc["audit_expanded_tables"],
          header_cc["audit_columns_present_in_ddl"],
          f"{header_cc['audit_columns_present_in_ddl']}/{header_cc['audit_expanded_tables']} "
          "表审计四件套展开命中 DDL")
    # Column 节点数（A 级建点）逐表回读 == DDL column_count（预聚合，O(n)）
    per_table_cols: dict[str, int] = defaultdict(int)
    for n in graph.nodes.values():
        if n["label"] == "Column":
            per_table_cols[n["table_id"]] += 1
    col_mismatch = sum(
        1 for n in graph.nodes.values()
        if n["label"] == "Table" and n.get("tier") == "A"
        and per_table_cols.get(n["name"], 0) != n["column_count"]
    )
    r.add("column_count_readback_vs_DDL(A级)", 0, col_mismatch,
          "图内 Column 节点数 == DDL column_count（逐表回读）")
    r.add_info("header_doc_name_mismatch(信号)", header_cc["name_mismatch_total"],
               f"文档↔DDL 命名差 {header_cc['name_mismatch_total']} / "
               f"shorthand {header_cc['shorthand_ambiguous_total']} / "
               f"shared_prefix {header_cc['shared_prefix_abbrev_total']}；"
               f"matched {header_cc['atomic_matched_total']}。结构事实以 DDL 为准（Column 节点直取 DDL）")

    # ---- 边无悬挂（结构性 rel_dangling；桩/外部已解析层剔除）----------
    r.add("rel_dangling(structural)", 0, len(build_report["rel_dangling"]))
    r.add("a_tables_without_domain", 0, len(build_report["a_no_domain"]))
    r.add_info("rel_pending_confirmation([待确认])", len(build_report["pending_confirmation"]),
               "RELATES_TO 端点非 DDL 表（未臆建造点，登记 [待确认]；schema 断言豁免此类）")

    # ---- DERIVED_FROM 两档（5 显式 + 115 命名派生；悬挂登记不造边）----
    derived_edges = [e for e in graph.edges if e["type"] == "DERIVED_FROM"]
    explicit = sum(1 for e in derived_edges if not e.get("is_inferred"))
    naming_valid = sum(1 for e in derived_edges
                       if e.get("is_inferred") and e.get("resolution_rule") != "strip_copy1")
    copy1 = sum(1 for e in derived_edges if e.get("resolution_rule") == "strip_copy1")
    r.add("derived_explicit_edges(is_inferred=false)", 5, explicit, "04 §3.1(2)+§二(3)")
    r.add("derived_naming_edges_built(is_inferred=true)", 106, naming_valid,
          f"命名派生分类 115，有效建边 106，悬挂 {len(build_report['derived_dangling'])}"
          "（源表不可解析→登记不造边，schema §2.2）")
    r.add("derived_copy1_edges", 2, copy1, "A 级保留副本 copy1→主表（04 §一-4/§六）")

    return r
