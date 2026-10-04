"""M1 · 装载编排：parse → build → assert → store → export（确定性，无 LLM）。

用法：`python -m graphrag.ingest.pipeline`（仓库根）或从测试调用 `run()`。
落库前跑数量等式断言；任一不闭合 → 抛 AssertionFailure，不产出快照（不交付）。
"""

from __future__ import annotations

import json
from pathlib import Path

from .config import (
    GRAPH_DIR, OUT_META_DIR, DATA_META_DIR,
    L0_GRAPH_JSON, L0_EDGES_JSON, FTS_DB, CONFIDENCE_DEFAULT_MIN, DOMAIN_DECLARED,
)
from .ddl_parser import parse_ddl, ddl_metrics
from .domain_parser import parse_domain_membership, domain_counts_report
from .relation_parser import parse_relations
from .derived_parser import parse_derived
from .issue_parser import parse_issues
from .header_parser import parse_logical_model, crosscheck_with_ddl
from .tiering import tier_counts, classify_tier
from .assertions import run_assertions, AssertReport
from ..store.graph import build_graph, export_graph, export_edges_jsonl
from ..store.fts import FTSIndex


class AssertionFailure(RuntimeError):
    def __init__(self, report: AssertReport):
        self.report = report
        bad = "; ".join(f"{c.name}: target={c.target} actual={c.actual}" for c in report.failed())
        super().__init__("数量等式不闭合，阻断交付：\n  " + bad)


def run(write: bool = True, force: bool = False) -> dict:
    """执行装载。write=False 仅返回内存产物不落盘；force=True 断言失败仍落盘（仅调试）。"""
    ddl = parse_ddl()
    names = set(ddl)
    membership = parse_domain_membership()
    domain_report = domain_counts_report(membership, names)

    edges_01, edges_05, rel_stats = parse_relations()

    c_tables = {n for n in names if classify_tier(n) == "C"}
    derived = parse_derived(c_tables, names)

    issues = parse_issues(names)

    header_doc = parse_logical_model()
    header_cc = crosscheck_with_ddl(header_doc, ddl)

    graph, build_report = build_graph(ddl, membership, edges_01, edges_05, derived, issues)

    report = run_assertions(ddl, domain_report, graph, build_report,
                            rel_stats, issues, header_doc, header_cc)

    if not report.all_pass and not force:
        raise AssertionFailure(report)

    result = {
        "graph": graph,
        "report": report,
        "rel_stats": rel_stats,
        "derived": derived,
        "issues": issues,
        "header_doc": header_doc,
        "header_cc": header_cc,
        "domain_report": domain_report,
        "build_report": build_report,
        "tier_counts": tier_counts(names),
        "ddl_metrics": ddl_metrics(ddl),
        "fts_db": str(FTS_DB),
    }

    if write:
        _export(result)
    return result


def _ensure_dirs():
    for d in (GRAPH_DIR, OUT_META_DIR, DATA_META_DIR):
        Path(d).mkdir(parents=True, exist_ok=True)


def _export(res: dict) -> dict:
    _ensure_dirs()
    graph = res["graph"]
    report: AssertReport = res["report"]

    export_graph(graph, L0_GRAPH_JSON)
    edge_lines = export_edges_jsonl(graph, L0_EDGES_JSON)

    # FTS（重建）
    fts = FTSIndex(FTS_DB, rebuild=True)
    indexed = fts.build(graph)
    fts.close()

    meta = build_meta(res, indexed=indexed, edge_lines=edge_lines)
    meta_json = json.dumps(meta, ensure_ascii=False, indent=2)
    assert_json = json.dumps(report.to_dict(), ensure_ascii=False, indent=2)

    # 核心交付机读元数据：规范路径 out/meta（可能被 .gitignore:18 out/ 忽略）
    (OUT_META_DIR / "l0_manifest.json").write_text(meta_json, encoding="utf-8")
    (OUT_META_DIR / "assertions.json").write_text(assert_json, encoding="utf-8")
    # 可提交镜像：data/meta（graphrag/data 未被忽略）
    (DATA_META_DIR / "l0_manifest.json").write_text(meta_json, encoding="utf-8")
    (DATA_META_DIR / "assertions.json").write_text(assert_json, encoding="utf-8")
    res["fts_indexed_nodes"] = indexed
    return meta


def build_meta(res: dict, indexed: int, edge_lines: int) -> dict:
    graph = res["graph"]
    report: AssertReport = res["report"]
    tc = res["tier_counts"]
    dm = res["ddl_metrics"]
    rel = res["rel_stats"]
    hdr = res["header_doc"]
    cc = res["header_cc"]
    counts = graph.counts()
    derived = res["derived"]
    return {
        "milestone": "M1",
        "generator": "graphrag/ingest/pipeline",
        "input_sources": ["er-model/*", "test_erp.sql"],
        "contract": "graphrag/spec (M0 冻结)",
        "stack": "Python3 + SQLite3.50 FTS5(porter)/BM25 + 内存属性图/JSON（无向量/LLM/重型图库）",
        "graph_summary": counts,
        "edge_lines_exported": edge_lines,
        "fts_indexed_nodes": indexed,
        "tiering": {
            "rule": "N-2 先剔 _bak_/test_ 归 C，再按前缀归 A/B",
            "counts": tc,
            "traversable": "A 级 349（含 OT 17，suspected_legacy 5）",
        },
        "ddl_metrics": dm,
        "relations": {
            "01_total_connectors": rel["01"]["connector_total"],
            "01_distinct_shapes": rel["01"]["distinct_shapes"],
            "01_edges_after_external": rel["01"]["edges_after_external"],
            "01_malformed": rel["01"]["malformed_count"],
            "01_per_domain": rel["01"]["per_domain"],
            "05_total": rel["05"]["connector_total"],
            "external_skipped_01": len(rel["01"]["external_skipped"]),
            "stub_resolved_01": len(rel["01"]["stub_resolved"]),
            "pending_confirmation": len(res["build_report"]["pending_confirmation"]),
            "all_is_inferred": True,
            "max_evidence_level": "comment_explicit",
            "disclaimer": "全库 0 外键，关系为逆向推断",
        },
        "derived_from": {
            "explicit_count": derived["explicit_count"],
            "naming_derived_classified": derived["naming_derived_classified"],
            "valid_edges": len(derived["edges"]) - derived["explicit_count"],
            "copy1_edges": len(derived["copy1_edges"]),
            "dangling": len(derived["dangling"]),
            "dangling_tables": [d["table"] for d in derived["dangling"]],
            "note": "9 张源表不可解析（04/05 标归属待确认）→ 登记不造边（schema §2.2）",
        },
        "issues": {
            "total": res["issues"]["total"],
            "per_category": res["issues"]["per_category"],
            "caliber_delta": "00/Agents 标 30 → 实测采 27（C-δ，不吞差）",
        },
        "header_normalization": {
            "field_variants": hdr["field_signature_distinct"],
            "field_rows": hdr["field_header_rows"],
            "nonfield_variants": hdr["nonfield_signature_distinct"],
            "nonfield_rows": hdr["nonfield_header_rows"],
            "total_header_rows": hdr["total_header_rows"],
            "unmapped": len(hdr["unmapped_headers"]),
            "crosscheck_with_ddl": {
                "tables_checked": cc["tables_checked"],
                "atomic_matched_total": cc["atomic_matched_total"],
                "shared_prefix_abbrev_total": cc["shared_prefix_abbrev_total"],
                "shorthand_ambiguous_total": cc["shorthand_ambiguous_total"],
                "name_mismatch_total": cc["name_mismatch_total"],
                "audit_expanded_tables": cc["audit_expanded_tables"],
                "audit_columns_present_in_ddl": cc["audit_columns_present_in_ddl"],
                "arbiter": "test_erp.sql（Column 节点由 DDL 直取；文档残差仅信号，DDL 为准）",
            },
        },
        "domain_declared": DOMAIN_DECLARED,
        "search_contract": {
            "uc1_find_tables": "FTS/BM25 + tier/域 过滤（默认排除 C）",
            "uc2_columns_of / tables_with_column": "IS_COLUMN_OF 正反查",
            "uc3_relations_of": "1 跳 RELATES_TO + 四要素",
            "table_level_lineage_traverse": "BFS ≤3 跳，confidence>=0.45 剪枝，仅 A 级",
            "confidence_default_min": CONFIDENCE_DEFAULT_MIN,
            "max_hops_default": 3,
        },
        "assertions": report.to_dict(),
        "honest_unfinished": [
            "Concept 节点（M4 语义种子）本期未建；REALIZED_BY 无。",
            "字段级 REFERENCES 血缘（M2）本期未建，仅表级 RELATES_TO。",
            "B 级结构族代表展开（每族 1 代表建字段）延后 M3；本期 B 仅登记+族归约，不建字段/不遍历（遵循任务约束）。",
            "9 张 C 级源表归属 [待确认]（remark_information/trace_quota/reconciliation 等）→ DERIVED_FROM 悬挂队列，未造边。",
            "64 张无前缀 C 表源经 add_jf_prefix 推断（04 §3.3 支持），超出 §2.2 字面 strip 口径，已在 derived_parser 顶部诚实标注。",
            "4 条 RELATES_TO 端点非 DDL 表（jf_product_color/jf_strategic_agreement/P_act_ru_task）→ pending_confirmation [待确认]，未臆建造点。",
            "文档↔DDL 列名残差 name_mismatch（见 header_normalization.crosscheck）为文档口径发现，DDL 为结构事实仲裁。",
            "graphrag/out/meta 受 .gitignore:18 'out/' 忽略；同时镜像到可提交的 graphrag/data/meta。",
        ],
    }


if __name__ == "__main__":
    out = run(write=True)
    rep = out["report"]
    print(json.dumps({"all_pass": rep.all_pass,
                      "passed": rep.to_dict()["passed"],
                      "failed": rep.to_dict()["failed"]}, ensure_ascii=False))
    for c in rep.failed():
        print("FAIL:", c.name, "target", c.target, "actual", c.actual)
