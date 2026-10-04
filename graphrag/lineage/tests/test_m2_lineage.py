"""M2 · 引用/结构级血缘 契约测试（graphrag/lineage）。

断言（任务 §9）：
- 引用边两端存在（悬挂=0）；逐边带证据级+置信+100% 出处（EvidenceSrc 四要素）；
- 多态/同名异指向入队列；外部引用不建本库边；变换/ETL 级=超范围不建；
- M1 既有边/节点计数守恒（RELATES_TO 483 / A 349 / Issue 27，ξ=456 口径以实物为准）；
- jf_sales_order 上下游（UC4/UC5）端到端可复现（同输入同输出）。
"""

from __future__ import annotations

import json

import pytest

from graphrag.ingest.config import EVIDENCE_ORDER, CONFIDENCE_DEFAULT_MIN
from graphrag.lineage.build import build
from graphrag.lineage.extractor import REFERENCES_TYPE
from graphrag.lineage.impact import reference_traverse, table_lineage_profile


@pytest.fixture(scope="module")
def res():
    return build(write=False)


@pytest.fixture(scope="module")
def edges(res):
    return res["edges"]


@pytest.fixture(scope="module")
def graph(res):
    return res["graph"]


# ---------------------------------------------------------------- 边完整性
def test_all_edges_are_references_and_inferred(edges):
    assert edges, "应生成非空 REFERENCES 边集"
    for e in edges:
        assert e["type"] == REFERENCES_TYPE
        assert e["is_inferred"] is True            # 全库 0 FK，无物理外键


def test_edge_endpoints_exist_no_dangling(edges, graph):
    for e in edges:
        assert graph.has_node(e["src"]), e
        assert graph.has_node(e["dst"]), e


def test_every_edge_carries_evidence_100pct(edges):
    for e in edges:
        ev = e["evidence_src"]
        for k in ("file", "table", "column", "quote_hash"):
            assert ev.get(k), (e, k)          # 有出处率必须 100%


def test_evidence_level_and_confidence_bounds(edges):
    for e in edges:
        assert e["evidence_level"] in EVIDENCE_ORDER
        assert 0.0 <= e["confidence"] <= 0.95     # 上限锁 0.95，永不宣称约束
        # 全库 0 FK → 证据永不超过 comment_explicit
        assert EVIDENCE_ORDER.index(e["evidence_level"]) >= \
               EVIDENCE_ORDER.index("comment_explicit")


def test_no_physical_fk_claim(edges):
    for e in edges:
        assert e["evidence_level"] != "explicit_fk"
        assert e.get("external_reference") is not True   # 外部引用不建本库边


def test_manifest_and_queue_carry_disclaimer(res):
    """schema.md §0/C-6：血缘口径必须显式声明『逆向推断、非物理外键；不含变换/ETL 级』。"""
    d = res["manifest"]["disclaimer"]
    assert "非物理外键" in d and "不含变换/ETL" in d


def test_export_roundtrip_and_loader():
    """落盘交付：lineage_edges.jsonl / manifest / review_queue；loader 回载合并无缝。"""
    built = build(write=True)
    assert built["manifest"]["assertions"]["all_pass"]
    from graphrag.ingest.config import DATA_META_DIR, GRAPH_DIR
    from graphrag.lineage.build import (LINEAGE_EDGES, LINEAGE_MANIFEST, REVIEW_QUEUE)
    lines = [x for x in (DATA_META_DIR / LINEAGE_EDGES).read_text(
        encoding="utf-8").splitlines() if x.strip()]
    assert len(lines) == len(built["edges"])
    json.loads((DATA_META_DIR / LINEAGE_MANIFEST).read_text(encoding="utf-8"))
    rq = json.loads(REVIEW_QUEUE.read_text(encoding="utf-8"))
    assert rq["counts"]["total"] == len(built["queue"])
    # 消费侧只读入口：M1 快照 + M2 边回载
    from graphrag.lineage.build import load_graph_with_lineage
    g2, meta = load_graph_with_lineage()
    assert meta["references_loaded"] == len(built["edges"])
    assert sum(1 for e in g2.edges if e["type"] == REFERENCES_TYPE) == \
        len(built["edges"])


# ---------------------------------------------------------------- 范围红线
def test_no_etl_dataflow_lineage(edges):
    """变换/ETL 数据流级血缘（sum(a)→b 类）超本期范围：不得出现此类边。"""
    for e in edges:
        # 引用级边必须是 列→列 的结构引用，两端都是 column: 节点
        assert e["src"].startswith("column:")
        assert e["dst"].startswith("column:")
        # 不含任何变换表达式痕迹（本方案无 transformation/aggregation 字段）
        assert "transformation" not in e
        assert "aggregation" not in e
        assert e["lineage_scope"] == "reference_or_structure_only"


# ---------------------------------------------------------------- 队列处置
def test_polymorphic_queued_not_asserted_single(edges, res):
    q = res["queue"]
    poly = [x for x in q if x["kind"] == "polymorphic_fk"
            and x["column"] == "related_order_id"]
    assert poly, "flow_change_record.related_order_id 应入多态队列"
    assert poly[0]["discriminant"] == "order_type"
    # 未武断指向单一表
    assert not any(e["src_column"] == "related_order_id" for e in edges)


def test_same_name_divergent_queued_not_arbitrary(res):
    """05 B-4 同名异指向：jf_trader.category_id 无 COMMENT/文档定指向 → 入队列不连通。"""
    amb = [x for x in res["queue"]
           if x["kind"] == "same_name_or_typo_ambiguity"
           and x["column"] == "category_id"]
    assert amb, "catregory/category 双候选应登记为歧义"
    assert set(amb[0]["candidate_tables"]) >= {"jf_category", "jf_catregory"}


def test_b4_divergence_kept_with_evidence_and_traced(edges, res):
    """shipping_mode_id：各端由 COMMENT/文档分别定指向（连通=有据），并留痕队列。"""
    # address 端 COMMENT 明示 → delivery_method
    a = next((e for e in edges if e["src_table"] == "jf_delivery_address"
              and e["src_column"] == "shipping_mode_id"), None)
    assert a and a["dst_table"] == "jf_delivery_method"
    assert a["evidence_level"] == "comment_explicit"
    # 同名异指向留痕
    div = [x for x in res["queue"] if x["kind"] == "same_name_divergent"
           and x["column"] == "shipping_mode_id"]
    assert div, "B-4 shipping_mode_id 跨表异指向应在队列留痕"


def test_external_reference_queued_and_not_built(edges, res):
    ext = {(x["table"], x["column"]) for x in res["queue"]
           if x["kind"] == "external_reference"}
    # D15 CRM 客诉编号是外部系统
    assert ("jf_customer_complaints_bak", "crm_complaint_code") in ext
    # 且未建任何本库 REFERENCES 边指向这些列
    built_src = {(e["src_table"], e["src_column"]) for e in edges}
    assert not (ext & built_src)


# ---------------------------------------------------------------- 固化拼写
def test_typo_mapping_resolved_when_unambiguous(edges):
    """catregory 固化拼写：mapping.category_id 有文档线定指向 catregory → 正确挂 catregory.id。"""
    m = next((e for e in edges if e["src_table"] == "jf_trader_category_mapping"
              and e["src_column"] == "category_id"), None)
    assert m and m["dst_table"] == "jf_catregory", \
        "应命中固化拼写 jf_catregory（文档 mapping.category_id -> catregory.id）"


# ---------------------------------------------------------------- M1 守恒
def test_m1_edges_and_nodes_intact(res):
    failed = [c for c in res["checks"] if not c["ok"]]
    assert not failed, failed
    g = res["graph"]
    counts = g.counts()
    assert counts["edge_types"]["RELATES_TO"] == 483       # M1 建边（456线-2外部+33_05）
    a_tabs = sum(1 for n in g.nodes.values()
                 if n["label"] == "Table" and n.get("tier") == "A")
    assert a_tabs == 349
    assert sum(1 for n in g.nodes.values() if n["label"] == "Issue") == 27


def test_xi_and_anchors_conserved(res):
    anchors = res["manifest"]["anchors_conserved"]
    assert anchors["relation_lines_xi"] == 456             # 关系线口径 ξ
    assert anchors["traversable_A"] == 349


# ---------------------------------------------------------------- 承接 M1 悬挂
def test_carryover_m1_pending_registered(res):
    pend = [x for x in res["queue"]
            if x["kind"] == "m1_carryover_pending_relates_to"]
    assert len(pend) == 4, "M1 4 条 pending（端点非 DDL 表）应承接登记"
    # jf_strategic_agreement → 唯一后缀命中 DDL jf_contract_strategic_agreement（定级信息）
    sa = next(x for x in pend if x["src"] == "jf_strategic_agreement")
    assert sa["ddl_structure_finding"]["suggested_alias_table"] == \
        "jf_contract_strategic_agreement"


def test_carryover_m1_derived_dangling_registered(res):
    der = [x for x in res["queue"]
           if x["kind"] == "m1_carryover_derived_dangling"]
    assert len(der) == 9, "M1 9 张 derived 悬挂应承接（未武断补边）"


# ---------------------------------------------------------------- UC4/UC5 可复现
def test_uc4_uc5_jf_sales_order_reproducible(graph):
    r1 = reference_traverse(graph, "jf_sales_order", "id", "downstream")
    r2 = reference_traverse(graph, "jf_sales_order", "id", "downstream")
    assert r1["paths"] == r2["paths"], "UC5 影响面须确定性可复现"
    assert r1["reached_tables"] >= 5
    assert all(p["confidence"] >= CONFIDENCE_DEFAULT_MIN for p in r1["paths"])
    up = reference_traverse(graph, "jf_sales_order", "customer_id", "upstream")
    assert any(p["to"] == "column:jf_customer.id" for p in up["paths"])


def test_uc4_table_pivot_jf_sales_order(graph):
    prof = table_lineage_profile(graph, "jf_sales_order")
    assert prof["exists"]
    assert prof["outgoing_references"], "jf_sales_order 应有出向引用（customer_id 等）"
    assert prof["incoming_reference_count"] >= 5, "作为枢纽应有入向引用"


def test_uc4_exchange_self_relation(graph):
    """自关联（换货）：exchange_order_no -> order_no 同表两端（01/D01 L176）。"""
    prof = table_lineage_profile(graph, "jf_sales_order")
    selfref = [e for e in prof["outgoing_references"]
               if e["dst_table"] == "jf_sales_order"]
    assert selfref, "换货自关联应落为列级 REFERENCES"


def test_disclaimer_in_traverse(graph):
    r = reference_traverse(graph, "jf_sales_order", "id", "downstream")
    assert "非物理外键" in r["disclaimer"]
    assert "不含变换/ETL" in r["disclaimer"]
