"""M1 · 解析 + 数量等式 + L1 检索 最小回归单测。

运行：仓库根 `python -m pytest graphrag/ingest/tests -q`
"""

from __future__ import annotations

import os
import tempfile

import pytest

from graphrag.ingest.ddl_parser import parse_ddl, ddl_metrics
from graphrag.ingest.domain_parser import parse_domain_membership, domain_counts_report
from graphrag.ingest.relation_parser import parse_relations
from graphrag.ingest.issue_parser import parse_issues
from graphrag.ingest.header_parser import parse_logical_model, crosscheck_with_ddl
from graphrag.ingest.tiering import tier_counts, classify_tier, is_c_level
from graphrag.ingest.pipeline import run
from graphrag.store.fts import FTSIndex
from graphrag.search.l1 import L1Search


@pytest.fixture(scope="module")
def ddl():
    return parse_ddl()


@pytest.fixture(scope="module")
def loaded():
    """一次性跑装载（不落盘）。"""
    return run(write=False)


# ---------------------------------------------------------------- 归级 N-2
def test_no_pk_and_total(ddl):
    dm = ddl_metrics(ddl)
    assert dm["ddl_table_total"] == 1322
    assert dm["no_pk_tables"] == 11
    assert dm["pk_declared_tables"] == 1311


def test_c_level_precedence(ddl):
    # _bak_ / test_ 必先归 C，即便带 jf_/lcap_ 前缀
    assert classify_tier("jf_application_nature_bak_20251122110927") == "C"
    assert classify_tier("lcap_app_cache_bak_20251204102553") == "C"
    assert classify_tier("test_jf_sales_order") == "C"
    assert is_c_level("jf_x_bak_20251122110927") is True


def test_tier_counts(ddl):
    tc = tier_counts(set(ddl))
    assert (tc["A"], tc["B"], tc["C"]) == (349, 853, 120)
    assert tc["jf_a"] == 332 and tc["ot_a"] == 17
    assert (tc["b_lcap"], tc["b_quartz"], tc["b_activiti"]) == (450, 275, 128)
    assert (tc["c_bak"], tc["c_test"]) == (117, 3)
    assert tc["registered_total"] == 1322


# ---------------------------------------------------------------- 表头归一
def test_header_normalization_counts():
    doc = parse_logical_model()
    assert doc["field_signature_distinct"] == 11
    assert doc["field_header_rows"] == 292
    assert doc["nonfield_signature_distinct"] == 8
    assert doc["nonfield_header_rows"] == 17
    assert doc["total_header_rows"] == 309
    assert len(doc["unmapped_headers"]) == 0


def test_header_crosscheck_audit_merged(ddl):
    doc = parse_logical_model()
    cc = crosscheck_with_ddl(doc, ddl)
    # 规范合并行（审计四件套）展开列须全部命中 DDL
    assert cc["audit_columns_present_in_ddl"] == cc["audit_expanded_tables"]
    assert cc["audit_expanded_tables"] > 0


# ---------------------------------------------------------------- 关系线 census
def test_relation_census_counts(loaded):
    s01 = loaded["rel_stats"]["01"]
    assert s01["connector_total"] == 456
    assert s01["distinct_shapes"] == 12
    assert s01["malformed_count"] == 13
    assert loaded["rel_stats"]["05"]["connector_total"] == 33


def test_malformed_flagged_not_dropped(loaded):
    edges = loaded["graph"].edges
    mal = [e for e in edges if e["type"] == "RELATES_TO" and e.get("malformed_connector")]
    assert len(mal) == 13
    assert all(e["left_cardinality"] == "missing" for e in mal)
    assert all(e["is_inferred"] is True for e in mal)


# ---------------------------------------------------------------- Issue
def test_issue_total(ddl):
    iss = parse_issues(set(ddl))
    assert iss["total"] == 27
    assert iss["per_category"] == {"A": 3, "B": 4, "C": 4, "D": 5, "E": 3, "F": 4, "G": 4}


# ---------------------------------------------------------------- 域闭合
def test_domain_declared_eq_enumerated(ddl):
    mem = parse_domain_membership()
    rep = domain_counts_report(mem, set(ddl))
    for dom, r in rep.items():
        assert r["match"], f"{dom} 不闭合: {r}"


# ---------------------------------------------------------------- 数量等式总闸
def test_assertions_all_pass(loaded):
    rep = loaded["report"]
    assert rep.all_pass, [f"{c.name}:{c.target}!={c.actual}" for c in rep.failed()]


def test_graph_integrity(loaded):
    g = loaded["graph"]
    # 结构性悬挂为 0
    assert len(loaded["build_report"]["rel_dangling"]) == 0
    assert len(loaded["build_report"]["a_no_domain"]) == 0
    # 有出处率 100%：每条 RELATES_TO 均有 evidence_ref 指向存在的 EvidenceSrc
    rels = [e for e in g.edges if e["type"] == "RELATES_TO"]
    assert rels, "应有关系边"
    for e in rels:
        assert g.has_node(e["evidence_ref"])
    # 全部关系边 is_inferred=true，证据级 ≤ comment_explicit
    for e in rels:
        assert e["is_inferred"] is True
        assert e["confidence"] <= 0.95
    # IS_COLUMN_of 数 == A 级列节点数
    isco = sum(1 for e in g.edges if e["type"] == "IS_COLUMN_OF")
    coln = sum(1 for n in g.nodes.values() if n["label"] == "Column")
    assert isco == coln


# ---------------------------------------------------------------- L1 检索
def test_uc1_find_tables(tmp_path, loaded):
    g = loaded["graph"]
    fts = FTSIndex(tmp_path / "idx.db", rebuild=True)
    fts.build(g)
    s = L1Search(g, fts)
    r = s.find_tables("trader quota", limit=5)
    names = [x["name"] for x in r["results"]]
    assert "jf_trader_quota" in names
    # 默认排除 C 级
    assert all(x["tier"] != "C" for x in r["results"])
    # include_c 开关可见 test_
    rc = s.find_tables("test jf sales order", include_c=True, limit=5)
    assert any(x["name"] == "test_jf_sales_order" for x in rc["results"])
    fts.close()


def test_uc2_columns(ddl, loaded):
    g = loaded["graph"]
    s = L1Search(g, None)
    c = s.columns_of("jf_sales_order")
    assert c["column_count"] == ddl["jf_sales_order"].column_count == 90
    rc = s.tables_with_column("sales_order_id")
    assert rc["exact_count"] > 0


def test_uc3_relations(loaded):
    g = loaded["graph"]
    s = L1Search(g, None)
    r = s.relations_of("jf_customer")
    assert r["count"] > 0
    top = r["relations"][0]
    # 四要素齐备
    assert {"cardinality", "evidence_level", "confidence", "source_file"} <= set(top)
    assert "disclaimer" in r


def test_traverse_lineage(loaded):
    g = loaded["graph"]
    s = L1Search(g, None)
    t = s.traverse("jf_sales_order", "out", max_hops=3)
    assert t["exists"] is True
    assert t["reached_tables"] >= 1
    # 遍历仅走 A 级
    tiers = {g.nodes[f"table:{p['to']}"].get("tier") for p in t["paths"]}
    assert tiers <= {"A"}


@pytest.fixture(scope="module")
def l1_with_fts(loaded):
    """带中文兜底 FTS 的 L1 检索入口（模块级复用）。"""
    fts = FTSIndex(os.path.join(tempfile.mkdtemp(), "idx.db"), rebuild=True)
    fts.build(loaded["graph"])
    yield L1Search(loaded["graph"], fts)
    fts.close()


# -------------------------------------------------- P1-3 中文检索（此前恒 0 命中）
def test_fts_cjk_search_hits(loaded, l1_with_fts):
    hits = l1_with_fts.fts.search("销售订单", label="Table", limit=10)
    names = {h["name"] for h in hits}
    assert hits, "中文『销售订单』必须有命中（双路 trigram 兜底）"
    # 表注释含『销售订单』的 A 级表须被召回
    assert names & {"jf_sales_order_cus", "jf_sales_order_wide"}, names
    assert all(h["tier"] != "C" for h in hits)


# -------------------------------------------------- P1-4 UC2 反查：返回真实表名
def test_uc2_tables_with_column_returns_real_tables(loaded, l1_with_fts):
    g = loaded["graph"]
    r = l1_with_fts.tables_with_column("sales_order_id")
    assert r["exact_count"] > 0
    # 语义近似表集必须是真实存在的表节点，且不得把裸列名当表名
    for t in r["semantic_tables"]:
        assert g.has_node(f"table:{t}"), f"semantic_tables 混入非表名: {t}"
    assert "sales_order_id" not in r["semantic_tables"]


# -------------------------------------------------- P2 traverse both 去重 + 不自环
def test_traverse_both_dedup_and_no_self_neighbor(loaded, l1_with_fts):
    t = l1_with_fts.traverse("jf_sales_order", "both", max_hops=3)
    assert t["exists"] is True
    # 不出现「1 跳邻居是自己」
    assert all(p["to"] != p["from"] for p in t["paths"])
    # 同一无向边只计一次（按 {from,to}+quote_hash 去重）
    keys = [(tuple(sorted((p["from"], p["to"]))), p.get("quote_hash")) for p in t["paths"]]
    assert len(keys) == len(set(keys)), "both 方向存在重复计入的同一条边"


# -------------------------------------------------- 置信门 show_uncertain 默认隐藏
def test_show_uncertain_default_hides(loaded, l1_with_fts):
    # jf_customer 有一条 name_inferred(0.575) 但 [待确认] 的表级边
    off = l1_with_fts.relations_of("jf_customer")
    on = l1_with_fts.relations_of("jf_customer", show_uncertain=True)
    assert not any(x["has_uncertain"] for x in off["relations"]), "默认视图不应泄露存疑边"
    assert on["count"] > off["count"], "show_uncertain=True 应暴露被默认隐藏的低置信存疑边"
    # 遍历同样默认过滤存疑边
    tro = l1_with_fts.traverse("jf_customer", "both", show_uncertain=False)
    trn = l1_with_fts.traverse("jf_customer", "both", show_uncertain=True)
    assert trn["edge_traversed"] >= tro["edge_traversed"]
