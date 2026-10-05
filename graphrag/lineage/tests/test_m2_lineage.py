"""M2 · 引用/结构级血缘 契约测试（graphrag/lineage）。

断言（任务 §9 + CodeReview P1 修复）：
- 引用边两端存在（悬挂=0）；逐边带证据级+置信+100% 出处（EvidenceSrc 四要素）；
- 多态/同名异指向入队列；外部引用不建本库边；变换/ETL 级=超范围不建；
- M1 既有边/节点计数守恒（RELATES_TO 483 / A 349 / Issue 27，ξ=456 口径以实物为准）；
- jf_sales_order 上下游（UC4/UC5）端到端可复现（同输入同输出）。
- **P1-1** 自环记法（src==dst 文档线）不得降级成"本表自引用"错边：点名唯一真实他表者连真目标、
  解不出者入队 `self_loop_annotation`；收口负向断言：`src_table==dst_table` ⇒ 100% explicit_pair。
- **P1-2** 存疑文档线（`unconfirmed` / `has_uncertain` / 原文 `[待确认]`）不入可见边集，
  入队 `doc_line_uncertain`（带 quote_hash）。
- **P1-3** 消费入口默认 `min_conf=CONFIDENCE_DEFAULT_MIN(0.45)` + `show_uncertain=False`。
- **多跳策略** 逐边 `conf≥min_conf` 为硬门；路径连乘仅作 path_score 排序。
"""

from __future__ import annotations

import inspect
import json

import pytest

from graphrag.ingest.config import EVIDENCE_ORDER, CONFIDENCE_DEFAULT_MIN
from graphrag.lineage.build import build, build_lineage
from graphrag.lineage.extractor import REFERENCES_TYPE
from graphrag.lineage.impact import (
    build_reference_adj, reference_traverse, table_lineage_profile,
)
from graphrag.store.graph import PropertyGraph


@pytest.fixture(scope="module")
def res():
    return build(write=False)


@pytest.fixture(scope="module")
def edges(res):
    return res["edges"]


@pytest.fixture(scope="module")
def graph(res):
    return res["graph"]


@pytest.fixture(scope="module")
def exported():
    """真实落盘一次（写 data/meta + review_queue），供交付/回载类断言复用。"""
    built = build(write=True)
    assert built["manifest"]["assertions"]["all_pass"]
    return built


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


def test_export_roundtrip_and_loader(exported):
    """落盘交付：lineage_edges.jsonl / manifest / review_queue；loader 回载合并无缝。"""
    from graphrag.ingest.config import DATA_META_DIR
    from graphrag.lineage.build import (LINEAGE_EDGES, LINEAGE_MANIFEST, REVIEW_QUEUE)
    lines = [x for x in (DATA_META_DIR / LINEAGE_EDGES).read_text(
        encoding="utf-8").splitlines() if x.strip()]
    assert len(lines) == len(exported["edges"])
    json.loads((DATA_META_DIR / LINEAGE_MANIFEST).read_text(encoding="utf-8"))
    rq = json.loads(REVIEW_QUEUE.read_text(encoding="utf-8"))
    assert rq["counts"]["total"] == len(exported["queue"])
    # 全量口径回载（显式关掉两道默认门）与构建态逐边等值
    from graphrag.lineage.build import load_graph_with_lineage
    g2, meta = load_graph_with_lineage(min_conf=0.0, show_uncertain=True)
    assert meta["references_loaded"] == len(exported["edges"])
    assert sum(1 for e in g2.edges if e["type"] == REFERENCES_TYPE) == \
        len(exported["edges"])


# ---------------------------------------------------------------- 范围红线
def test_no_etl_dataflow_lineage(edges):
    """变换/ETL 数据流级血缘（sum(a)→b 类）超本期范围：不得出现此类边。"""
    for e in edges:
        # 引用级边必须是 列→列 的结构引用，两端都是 column: 节点
        assert e["src"].startswith("column:")
        assert e["dst"].startswith("column:")   # 两端皆列节点（无表级/变换节点）
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
    a = next((e for e in edges if e["src_table"] == "jf_delivery_address"
              and e["src_column"] == "shipping_mode_id"), None)
    assert a and a["dst_table"] == "jf_delivery_method"
    assert a["evidence_level"] == "comment_explicit"


def test_external_reference_queued_and_not_built(edges, res):
    ext = {(x["table"], x["column"]) for x in res["queue"]
           if x["kind"] == "external_reference"}
    assert ("jf_customer_complaints_bak", "crm_complaint_code") in ext
    built_src = {(e["src_table"], e["src_column"]) for e in edges}
    assert not (ext & built_src)


# ---------------------------------------------------------------- 固化拼写
def test_typo_mapping_resolved_when_unambiguous(edges):
    """catregory 固化拼写：mapping.category_id 有文档线定指向 catregory → 正确挂 catregory.id。"""
    m = next((e for e in edges if e["src_table"] == "jf_trader_category_mapping"
              and e["src_column"] == "category_id"), None)
    assert m and m["dst_table"] == "jf_catregory", \
        "应命中固化拼写 jf_catregory（文档 mapping.category_id -> catregory.id）"


# ============================================== P1-1 自环记法消解（CodeReview 必修）
_SELF_LOOP_WRONGLY_DEFAULTED = [          # 评审点名：曾被降级成"本表自引用"的错边
    ("jf_basic_craft", "finish_product_id"),
    ("jf_shipping_progress", "income_bill_id"),
    ("jf_merge_shipment_log", "sales_order_id"),
    ("jf_user_show_page_table", "user_id"),
]


def test_self_loop_annotation_no_longer_defaulted_to_self(edges, res):
    """src==dst 的自环文档线不再武断连成"本表自引用"；解不出者入队 self_loop_annotation。"""
    srcs = {(e["src_table"], e["src_column"]) for e in edges
            if e["src_table"] == e["dst_table"]}
    for t, col in _SELF_LOOP_WRONGLY_DEFAULTED:
        assert (t, col) not in srcs, f"{t}.{col} 不应再是本表自引用边"
    q = {(x["table"], x["column"]) for x in res["queue"]
         if x["kind"] == "self_loop_annotation"}
    # 点名/列对皆解不出者 → 队列（3 条）
    for t, col in _SELF_LOOP_WRONGLY_DEFAULTED[1:]:
        assert (t, col) in q, f"{t}.{col} 应入 self_loop_annotation 队列"
    # 队列项必须带可回溯出处（quote_hash）与信息性命名线索（不自动连通）
    inc = next(x for x in res["queue"]
               if x["kind"] == "self_loop_annotation"
               and x["table"] == "jf_shipping_progress")
    assert inc.get("quote_hash"), inc
    assert "不默认本表自引用" in inc["reason"]
    assert inc["naming_candidates"] == ["jf_income_bill"], \
        "naming_candidates 仅为复核线索（不得据此自动建边）"
    assert not any(e["src_table"] == "jf_shipping_progress"
                   and e["src_column"] == "income_bill_id" for e in edges)


def test_self_loop_with_named_target_uses_real_table(edges):
    """描述文本点名唯一真实他表 → 连"真实目标"（`成品(D09 jf_product)` → jf_product，而非本表）。"""
    e = next((x for x in edges if x["src_table"] == "jf_basic_craft"
              and x["src_column"] == "finish_product_id"), None)
    assert e, "点名成功者应保留列边"
    assert e["dst_table"] == "jf_product" and e["dst_column"] == "id"
    assert e["src_table"] != e["dst_table"]


def test_naming_path_self_reference_queued_not_built(edges, res):
    """第 3 路命名解析指向本表（自指）→ 无显式列对则入队，不建自引用边。"""
    assert not any(e["src_table"] == "jf_trader_month_sales"
                   and e["src_column"] == "trader_month_sales_id"
                   and e["dst_table"] == "jf_trader_month_sales" for e in edges)
    q = [x for x in res["queue"] if x["kind"] == "self_loop_annotation"
         and x["table"] == "jf_trader_month_sales"]
    assert q and q[0]["candidate_tables"] == ["jf_trader_month_sales"]


def test_self_reference_edges_are_100pct_explicit_pair(edges):
    """P1-1 收口负向断言：REFERENCES 中 src_table==dst_table 者必须 100% 带 explicit_pair。"""
    self_edges = [e for e in edges if e["src_table"] == e["dst_table"]]
    assert self_edges, "应保留显式列对可证的自引用（如 exchange_order_no→order_no）"
    bad = [e for e in self_edges if e["target_col_rule"] != "explicit_pair"]
    assert not bad, bad
    assert all(e["src_column"] != e["dst_column"] for e in self_edges)


def test_assertion_gate_blocks_non_explicit_self_ref(res):
    names = {c["name"]: c for c in res["checks"]}
    assert names["self_reference_explicit_pair_only"]["ok"], names["self_reference_explicit_pair_only"]
    assert names["self_reference_explicit_pair_only"]["target"] == 0


# ============================================== P1-2 uncertain 一等门（不建边、只入队）
_NAMED_UNCERTAIN = [("jf_receivable_claim", "payment_method"),
                    ("jf_sales_order_wide", "sales_type")]


def test_uncertain_doc_lines_are_queued_not_built(edges, res):
    """原文自带 `[待确认]`／明说"不成立"的文档线 → 入队 doc_line_uncertain，不建边。"""
    for t, col in _NAMED_UNCERTAIN:
        assert not any(e["src_table"] == t and e["src_column"] == col for e in edges), \
            f"{t}.{col} 存疑线不得成边"
    q = [x for x in res["queue"] if x["kind"] == "doc_line_uncertain"]
    assert q, "应有存疑文档线入队"
    for t, col in _NAMED_UNCERTAIN:
        hit = next((x for x in q if x["table"] == t and x["column"] == col), None)
        assert hit, (t, col, "未入 doc_line_uncertain 队列")
        assert hit.get("quote_hash"), hit          # 带 quote_hash 可回溯
        assert "待确认" in (hit.get("quote") or "") or hit["reason"]
        assert hit["status"] == "[待确认]"


def test_no_uncertain_edge_in_visible_set(edges):
    """成边集合内不得出现 unconfirmed / has_uncertain / 引文 [待确认]（P1-2 不变式）。"""
    for e in edges:
        assert e["evidence_level"] != "unconfirmed"
        assert e.get("has_uncertain") is False
        assert "待确认" not in ((e.get("evidence_src") or {}).get("quote") or "")


def test_uncertain_gate_does_not_erase_clean_evidence(edges, res):
    """存疑线只否"本条线"：同列另有干净 COMMENT/文档线时，那条边必须保留（顺序无关）。"""
    # jf_project_initiation_analysis.project_category_id：一条无标签线入队，
    # 同名带 [字段命名] 的干净线仍建边
    assert any(e["src_table"] == "jf_project_initiation_analysis"
               and e["src_column"] == "project_category_id"
               and e["dst_table"] == "jf_project_category" for e in edges)
    unc = {(x["table"], x["column"]) for x in res["queue"]
           if x["kind"] == "doc_line_uncertain"}
    assert ("jf_project_initiation_analysis", "project_category_id") in unc


def test_uncertain_query_gate_hides_but_does_not_delete(exported):
    """已在图的历史存疑边：show_uncertain=False 默认隐藏、True 才暴露，文件不被删边。"""
    from graphrag.lineage.build import load_graph_with_lineage
    off_g, off = load_graph_with_lineage(min_conf=0.0, show_uncertain=False)
    on_g, on = load_graph_with_lineage(min_conf=0.0, show_uncertain=True)
    assert off["references_in_file"] == len(exported["edges"])     # 不删边
    assert on["references_loaded"] == len(exported["edges"])
    assert off["references_loaded"] + off["hidden_uncertain"] == on["references_loaded"]
    assert sum(1 for e in on_g.edges if e["type"] == REFERENCES_TYPE) == \
        sum(1 for e in off_g.edges if e["type"] == REFERENCES_TYPE)  # 本期成边无存疑者


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
    sa = next(x for x in pend if x["src"] == "jf_strategic_agreement")
    assert sa["ddl_structure_finding"]["suggested_alias_table"] == \
        "jf_contract_strategic_agreement"


def test_carryover_m1_derived_dangling_registered(res):
    der = [x for x in res["queue"]
           if x["kind"] == "m1_carryover_derived_dangling"]
    assert len(der) == 9, "M1 9 张 derived 悬挂应承接（未武断补边）"


# ==================================== P1-3 / P2 消费入口默认值与回载一致性
def test_loader_defaults_are_search_gate():
    """`load_graph_with_lineage` 默认 min_conf=0.45 且 show_uncertain=False（对齐 spec §1.1）。"""
    from graphrag.lineage.build import load_graph_with_lineage
    sig = inspect.signature(load_graph_with_lineage)
    assert sig.parameters["min_conf"].default == CONFIDENCE_DEFAULT_MIN == 0.45
    assert sig.parameters["show_uncertain"].default is False


def test_loader_default_filters_low_confidence(exported):
    from graphrag.lineage.build import load_graph_with_lineage
    g_def, meta = load_graph_with_lineage()                    # 默认门
    g_all, meta_all = load_graph_with_lineage(min_conf=0.0)    # 全量口径
    low = sum(1 for e in exported["edges"] if e["confidence"] < CONFIDENCE_DEFAULT_MIN)
    assert meta["references_loaded"] == len(exported["edges"]) - low
    assert meta["hidden_below_min_conf"] == low > 0
    assert meta_all["references_loaded"] == len(exported["edges"])
    assert sum(1 for e in g_def.edges if e["type"] == REFERENCES_TYPE) == \
        meta["references_loaded"]
    for e in g_def.edges:
        if e["type"] == REFERENCES_TYPE:
            assert e["confidence"] >= CONFIDENCE_DEFAULT_MIN


def test_reload_equals_build_state(exported):
    """P2：回载与构建态的 SUPPORTED_BY 必须同属性（rel_kind/dst_col/role），且余证齐备。"""
    from graphrag.lineage.build import load_graph_with_lineage
    g_build = exported["graph"]
    g_load, _ = load_graph_with_lineage(min_conf=0.0, show_uncertain=True)

    def sb(g):
        return sorted((e["src"], e["dst"], e.get("rel_kind"), e.get("dst_col"),
                       e.get("role")) for e in g.edges if e["type"] == "SUPPORTED_BY"
                      and e.get("rel_kind") == REFERENCES_TYPE)
    assert sb(g_load), "回载应重建 REFERENCES 的 SUPPORTED_BY"
    assert sb(g_load) == sb(g_build), "回载≠构建态：SUPPORTED_BY 属性/余证不一致"
    # EvidenceSrc 节点亦须齐备（此前 loader 不重建余证节点）
    ids_b = {n for n in g_build.nodes if n.startswith("evsrc:")}
    ids_l = {n for n in g_load.nodes if n.startswith("evsrc:")}
    assert ids_l >= {i for i in ids_b if any(
        e["dst"] == i for e in g_build.edges if e["type"] == "SUPPORTED_BY"
        and e.get("rel_kind") == REFERENCES_TYPE)}


def test_public_entry_name_is_build_lineage():
    """P2：`__init__` 承诺的入口名与实际一致（M3/M4 照 `build_lineage` 集成不会踩空）。"""
    import graphrag.lineage as L
    assert L.build_lineage is L.build is build_lineage is build
    assert callable(L.load_graph_with_lineage)
    assert set(L.__all__) <= set(dir(L))


# ============================================== 多跳策略：逐边硬门 + path_score 仅排序
def _synthetic_graph():
    g = PropertyGraph()
    for nid in ("column:a.id", "column:b.parent_a_id",
                "column:c.ref_b_id", "column:d.blocked_ref"):
        g.add_node(nid, "Column", name=nid)
    ev = {"file": "test_erp.sql", "table": "a", "column": "id", "quote_hash": "h1"}
    g.add_edge("column:b.parent_a_id", "column:a.id", REFERENCES_TYPE,
               confidence=0.5, evidence_level="name_inferred", is_inferred=True,
               signals=["naming"], evidence_src=ev, has_uncertain=False,
               src_table="b", src_column="parent_a_id", dst_table="a", dst_column="id")
    g.add_edge("column:c.ref_b_id", "column:b.parent_a_id", REFERENCES_TYPE,
               confidence=0.5, evidence_level="name_inferred", is_inferred=True,
               signals=["naming"], evidence_src=ev, has_uncertain=False,
               src_table="c", src_column="ref_b_id", dst_table="b",
               dst_column="parent_a_id")
    g.add_edge("column:d.blocked_ref", "column:a.id", REFERENCES_TYPE,
               confidence=0.1, evidence_level="unconfirmed", is_inferred=True,
               signals=["doc"], evidence_src={**ev, "quote": "x [待确认] y"},
               has_uncertain=True,
               src_table="d", src_column="blocked_ref", dst_table="a", dst_column="id")
    return g


def test_multi_hop_per_edge_gate_not_path_product():
    """逐边 conf≥min_conf 为准入硬门；连乘仅作 path_score（长链不得被连乘误杀）。"""
    g = _synthetic_graph()
    r = reference_traverse(g, "a", "id", "downstream", max_hops=3,
                           min_conf=CONFIDENCE_DEFAULT_MIN)
    hops = {p["hop"] for p in r["paths"]}
    assert 2 in hops, "2 跳链（0.5×0.5=0.25<0.45）必须仍在结果内——连乘只作排序"
    two = [p for p in r["paths"] if p["hop"] == 2]
    assert all(p["confidence"] >= CONFIDENCE_DEFAULT_MIN for p in two)   # 逐边硬门
    assert all(p["path_score"] < CONFIDENCE_DEFAULT_MIN for p in two)     # 排序分可低于阈值
    assert all(p["path_score"] <= p["confidence"] + 1e-9 for p in r["paths"])


def test_traverse_and_adj_honour_uncertain_gate():
    """存疑边默认不参与遍历（查询门）；show_uncertain=True 才暴露。"""
    g = _synthetic_graph()
    off = reference_traverse(g, "a", "id", "downstream", min_conf=0.0)
    on = reference_traverse(g, "a", "id", "downstream", min_conf=0.0,
                            show_uncertain=True)
    assert "column:d.blocked_ref" not in {p["to"] for p in off["paths"]}
    assert "column:d.blocked_ref" in {p["to"] for p in on["paths"]}
    _, _, _ = build_reference_adj(g, min_conf=0.0, show_uncertain=True)
    out_adj, _into, _sk = build_reference_adj(g, min_conf=0.0)
    assert not any(dst == "column:a.id" and e["evidence_level"] == "unconfirmed"
                   for dst, e in out_adj["column:d.blocked_ref"])


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
    """自关联（换货）：exchange_order_no -> order_no 同表两端（01/D01 L176，显式列对）。"""
    prof = table_lineage_profile(graph, "jf_sales_order")
    selfref = [e for e in prof["outgoing_references"]
               if e["dst_table"] == "jf_sales_order"]
    assert selfref, "换货自关联应落为列级 REFERENCES"
    assert all(e["target_col_rule"] == "explicit_pair" for e in selfref)  # P1-1 收口


def test_disclaimer_in_traverse(graph):
    r = reference_traverse(graph, "jf_sales_order", "id", "downstream")
    assert "非物理外键" in r["disclaimer"]
    assert "不含变换/ETL" in r["disclaimer"]
