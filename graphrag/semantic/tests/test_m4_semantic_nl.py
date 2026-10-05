"""M4 · 结构语义层 + 规则式 NL 编排前端 + M3 P2 承接 —— 契约/诚实性测试。

锁定要点（任务 §5 + 过门双门 + 终轮 CodeReview P1/P2）：
1. **语义元素 100% 可定位来源**：域分区 / 实体三分类 / 字段含义 / 枚举码表逐条挂来源；
   不可定位者一律 `[待确认]`（绝不臆造字段含义 / 实体绑定）。
2. **不虚构**：located 概念的 realized_by 必为图内真实 Table 节点；REALIZED_BY 边指向实物表，
   且 `run(write=True)` **实落盘** semantic_concept_graph.json（P2-5 承诺即产出）。
3. **指标语义层确未出现**：语义层 scope=structural，指标/KPI/术语表在 `out_of_scope` 显式声明。
4. **NL 只调 L1/L2**：意图答案仅来自 `search/l1` 与 `community`；不引血缘 M2 遍历 / LLM。
5. **NL 不绕置信 / 存疑门**：默认 conf≥0.45、show_uncertain=False、max_hops≤3（超限钳制）。
6. **NL 不生成无出处关系**：关系/遍历/耦合每条都有图内可回溯边（quote_hash / 真实邻接）。
7. **拒绝臆造兜底**：未定位实体 / 无路径 → refused，不编答案；**refused ⇒ sources==[]**
   （P2-3，u6-03 幻影域不再泄漏 Domain 出处）。
8. **LLM stub 默认禁用**：`ENABLED=False`，调用即抛 NotImplementedError。
9. **M3 P2 承接**：75 孤立 A 表名单落盘且覆盖等式闭合；9 混合簇逐条标 `对照观察(非发现物)`；
   golden 入口交接声明 6 意图 + LLM 禁用。
10. **P1-1 内联枚举**：显式分隔符 + 1~2 位整码 + 值键唯一；多位码不截断、日期数字不入选。
    **P2-④ 逐对(per-pair)**：不再整列连坐——通过的对逐个入 accepted（含单码注释），仅真正
    冲突/畸形单对被剔（记入该列 dropped_pairs 审计）；全列同码多义→needs_review；逐对后无一有效→declined。
11. **P2-4 方向词表**：上游（依赖/来自/属于→in）、下游（谁依赖/影响→out）；方向不可判 →
    UC4/UC5 回退 both + 反问提示（不默认 out）；`answer(intent=)` 非法 id → ValueError。
12. **P2-6 FTS 打开**：文件不存在/索引未建 → None；**索引损坏 → 抛出**（区分两种语义）。
13. **P1-2 指纹消费**：接住上游 `input_fingerprint`；与期望不一致 fail-fast；缺失放行留痕。
"""

from __future__ import annotations

import copy
import inspect
import json
import sqlite3

import pytest

from graphrag.ingest.config import (
    CONFIDENCE_DEFAULT_MIN, MAX_HOPS, L0_GRAPH_JSON, DOMAIN_DECLARED, DATA_META_DIR,
)
from graphrag.store.loader import load_graph_json
from graphrag.store.graph import PropertyGraph
from graphrag.semantic import semantic_layer as sl
from graphrag.semantic import m3_p2_handoff as p2
from graphrag.semantic import fingerprint as fpmod
from graphrag.nl import nl_router as nlr
from graphrag.nl import llm_synthesis


@pytest.fixture(autouse=True)
def _no_fingerprint_env(monkeypatch):
    """指纹期望基准环境变量不得影响测试（由编排/门禁注入才生效）。"""
    monkeypatch.delenv(fpmod.ENV_EXPECTED, raising=False)


# ---------------------------------------------------------------- fixtures
@pytest.fixture(scope="module")
def layer():
    return sl.SemanticLayer()


@pytest.fixture(scope="module")
def built(layer):
    return layer.build()


@pytest.fixture(scope="module")
def router():
    return nlr.NLRouter()


@pytest.fixture(scope="module")
def fresh_graph():
    """独立回载 M1 图（供“NL 不生成无出处关系”交叉校验）。"""
    return load_graph_json(L0_GRAPH_JSON)


@pytest.fixture(scope="module")
def kept_pairs():
    """M3 审计后的融合边集（RELATES_TO+REFERENCES 表级投影）→ 耦合对合法性基准。"""
    path = DATA_META_DIR / "community_edges.jsonl"
    pairs = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        r = json.loads(line)
        pairs.add(frozenset((r["src"], r["dst"])))
    return pairs


def _rel_adj(g, table):
    """{邻居表名}：table 的全部 RELATES_TO 无向邻居（A 级可遍历主语料）。"""
    tid = f"table:{table}"
    nb = set()
    for _, e in g.neighbors(tid, "RELATES_TO", direction="out"):
        nb.add(e["dst"].split(":", 1)[1])
    for _, e in g.neighbors(tid, "RELATES_TO", direction="in"):
        nb.add(e["src"].split(":", 1)[1])
    return nb


def _edge_exists(g, a, b):
    return b in _rel_adj(g, a)


# ================================================================ 1 语义元素 100% 挂来源
def test_scope_is_structural_and_declared(built):
    assert built["semantic_layer_scope"] == "structural"
    assert "不含指标语义层" in built["scope_statement"]
    assert built["out_of_scope"] == sl.OUT_OF_SCOPE


def test_p2_5_input_sources_honest_no_community_overclaim(built):
    """P2-5：input_sources 不得虚报 community_*（本模块实际只读 l0_graph + 05）。"""
    srcs = " ".join(built["input_sources"])
    assert "community_" not in srcs
    assert "l0_graph.json" in srcs and "05-跨域核心关系总览.md" in srcs
    assert "P2-5" in built["input_sources_note"]
    import graphrag.semantic.semantic_layer as slmod
    assert not hasattr(slmod, "SQL_FILE")                   # 未用 import 已移除
    src = inspect.getsource(slmod)
    for forbidden in ("SQL_FILE", "community_result", "community_edges"):
        assert forbidden not in src, forbidden              # 语义层本体不碰 M3 产物文件


def test_every_domain_partition_has_source(built):
    assert len(built["domains"]) == len(DOMAIN_DECLARED) + 1   # 18 业务域+OT + B-FAMILY
    for d in built["domains"]:
        assert d["authoritative"] is True                       # 00 §四 权威，不被社区覆盖
        assert d["source"]["quote_hash"], d
        assert d["source"]["file"].endswith("file-domain-map.md")
    codes = {d["domain_id"] for d in built["domains"]}
    assert set(DOMAIN_DECLARED) <= codes and "B-FAMILY" in codes


def test_every_concept_has_source_and_locatable_or_pending(built, fresh_graph):
    table_names = {n["name"] for n in fresh_graph.nodes.values() if n["label"] == "Table"}
    ents = built["entity_classification"]["categories"]
    assert built["entity_classification"]["total"] == len(ents) == 36
    for e in ents:
        assert e["source"]["quote_hash"], e                     # 逐条挂 05 来源
        if e["status"] == "located":
            assert e["realized_by"] and all(t in table_names for t in e["realized_by"]), e
        else:
            assert "待确认" in e["status"], e
            assert e["realized_by"] == []                       # 不可定位 → 不绑表（不臆造）


def test_known_unlocatable_are_pending_not_fabricated(built):
    """诚实边界：05 点名但 DDL/图无同名表者 → [待确认]，绝不绑到近似表。"""
    wh = next(e for e in built["entity_classification"]["categories"]
              if any("warehouse" in t for t in e["identifiers"]))
    assert wh["status"] != "located"                            # jf_warehouse DDL 无表
    assert wh["realized_by"] == []
    # 该候选仍登记（binding=pending），但不臆造绑定
    assert any(b["binding"] == "pending" for b in wh["binding_detail"])


def test_b_tier_entities_bound_with_tier_and_non_traversable(built):
    """lcap_*（B 级影子）命中物理表 → 绑并登记 tier=B / traversable=False（非 [待确认]）。"""
    ent = next(e for e in built["entity_classification"]["categories"]
               if e["term"] == "lcap_department")
    assert ent["status"] == "located"
    assert set(ent["realized_by"]) == {"lcap_department", "lcap_user", "lcap_role"}
    for b in ent["binding_detail"]:
        assert b["tier"] == "B" and b["traversable"] is False, b


def test_field_meanings_tied_to_ddl_comment_or_pending(built):
    cov = built["field_meaning_coverage"]
    assert cov["a_level_columns"] == cov["with_ddl_comment"] + cov["without_comment_pending"]
    assert cov["a_level_columns"] == 6428 and cov["with_ddl_comment"] == 6331
    assert cov["without_comment_pending"] == 97
    bad = 0
    for t, rows in built["field_meanings"].items():
        for r in rows:
            if r["has_meaning"]:
                assert r["source"]["file"] == "test_erp.sql", (t, r)   # 含义=DDL COMMENT 原文
                assert r["meaning"]
            else:
                assert "待确认" in r["source"]["status"], (t, r)        # 无 COMMENT→[待确认]
                assert r["meaning"] is None                             # 不臆造含义
                bad += 1
    assert bad == cov["without_comment_pending"]


def test_enum_semantics_all_sourced(layer):
    en = layer.enum_semantics()
    for c in en["code_tables"]:
        assert c["source"]["quote_hash"] and "D14" in c["source"]["file"]
        assert "rows" in c["enum_values"]                       # 值域=表内数据行，不落常量（不臆造）
    for kv in en["kv_dictionaries"]:
        assert kv["source"]["quote_hash"]
    for ie in en["inline_enums_from_comment"]:
        assert ie["source"]["file"] == "test_erp.sql"
        # P2-④ 逐对：通过的对逐个入库，≥1 即成条目（不再要求整列 ≥2 对）
        assert ie["raw_comment"] and len(ie["values"]) >= 1
        vals = [p["value"] for p in ie["values"]]
        assert len(vals) == len(set(vals)), (ie["table"], ie["column"])  # 值键唯一
        for dp in ie.get("dropped_pairs", []):     # 被剔单对仅审计，附 kind/reason
            assert dp["kind"] in ("conflict", "malformed") and dp["reason"]
    for rv in en["inline_enums_needs_review"]:     # 全列多义 → 不落映射只留痕
        assert rv["conflicts"] and "待确认" in rv["status"]
    for dl in en["inline_enums_declined"]:         # 逐对后无一有效 → 列级审计
        assert dl["legacy_sample"] and "P1-1" in dl["reason"]


# ================================================================ P1-1 内联枚举修复
def test_p1_1_regex_multidigit_not_truncated():
    """多位码完整保留（旧版 "10:备坯" 被截成 "0:备坯" 致错映射）。"""
    assert sl._ENUM_PAIR_RE.findall("10:备坯 11:备纱 16:付运") == [
        ("10", "备坯"), ("11", "备纱"), ("16", "付运")]


def test_p1_1_regex_date_digits_not_selected():
    """日期数字/无分隔写法不得入选（旧版把 "16日"/纯数字臆造成枚举）。"""
    assert sl._ENUM_PAIR_RE.findall("生效日期 2024-01-01") == []
    assert sl._ENUM_PAIR_RE.findall("第16日结算") == []
    assert sl._ENUM_PAIR_RE.findall("状态(0正常 1停用)") == []   # 无显式分隔 → 不机读


def _synthetic_layer():
    g = PropertyGraph()
    g.add_node("domain:D01", "Domain", domain_id="D01", tier="A", table_count=1)
    g.add_node("table:jf_enumtest", "Table", name="jf_enumtest", tier="A", domain="D01",
               column_count=4)
    specs = {
        "multi": "10:备坯，11:备纱，16:付运",                    # 多位码 → accepted 不截断
        "dup": "1:草稿，2:已取消，1:拣货中",                     # 同码多义 → 冲突码逐对剔、2 留
        "nosep": "状态(0正常 1停用)",                            # 无分隔 → declined 审计
        "datey": "生效日期 2024-01-01 至 2024-12-31 为界",        # 日期 → 不落任何映射
        "single": "是否生产延期:1=是",                           # 单码注释 → P2-④ 逐对入库
        "mixed": "免收订金:1=是,0否",                            # 混排(1 好 0 畸)→ 逐对挽回
    }
    for c, txt in specs.items():
        g.add_node(f"column:jf_enumtest.{c}", "Column", name=c, table_id="jf_enumtest",
                   semantic=txt, data_type="tinyint", key_role=None)
    return sl.SemanticLayer(graph=g)


def test_p1_1_synthetic_three_way_split():
    """P2-④ 逐对：通过者入 accepted（含单码/混排挽回），真冲突码被剔，畸形/无分隔审计。"""
    acc, rev, dec = _synthetic_layer()._inline_enums()
    by_col = lambda rows: {r["column"]: r for r in rows}
    a, r, d = by_col(acc), by_col(rev), by_col(dec)
    assert set(a) == {"multi", "dup", "single", "mixed"}        # 逐对：通过者均入映射
    assert set(r) == set()                                      # 无整列全多义者
    assert set(d) == {"nosep"}                                  # 逐对后仍无一有效→列级审计
    assert [p["value"] for p in a["multi"]["values"]] == ["10", "11", "16"]  # 未截断
    # dup：冲突码 1(草稿/拣货中) 逐对剔除、有效码 2 保留（不再整列连坐）
    assert [p["value"] for p in a["dup"]["values"]] == ["2"]
    dc = next(x for x in a["dup"]["dropped_pairs"] if x["kind"] == "conflict")
    assert dc["value"] == "1" and set(dc["labels"]) == {"草稿", "拣货中"}
    # 单码注释如实入库
    assert [p["value"] for p in a["single"]["values"]] == ["1"]
    # 混排写法：挽回良好对 1=是，畸形对 0否 仅审计不落映射
    assert a["mixed"]["values"] == [{"value": "1", "label": "是"}]
    dm = next(x for x in a["mixed"]["dropped_pairs"] if x["kind"] == "malformed")
    assert dm["value"] == "0" and dm["label"] == "否"
    for col in ("multi", "dup", "single", "mixed"):            # 值键唯一硬约束
        vs = [p["value"] for p in a[col]["values"]]
        assert vs and len(vs) == len(set(vs))
    assert "datey" not in a and "datey" not in r and "datey" not in d  # 日期彻底不入选


def test_p1_1_real_status_multidigit_fixed(layer):
    """点名缺陷回归：jf_sales_order.status 旧版 "1"→草稿/拣货中 碰撞，新版 10–16 完整。"""
    acc, rev, dec = layer._inline_enums()
    status = next((x for x in acc
                   if x["table"] == "jf_sales_order" and x["column"] == "status"), None)
    assert status is not None and not any(
        x["table"] == "jf_sales_order" and x["column"] == "status" for x in rev + dec)
    vmap = {p["value"]: p["label"] for p in status["values"]}
    assert vmap["1"] == "草稿"                    # "1" 不再与 "11"（拣货中）碰撞
    assert vmap["10"] == "已派单" and vmap["11"] == "拣货中"
    assert vmap["16"] == "付运"
    assert len({p["value"] for p in status["values"]}) == len(status["values"])


def test_p1_1_prepare_type_wrong_mapping_removed(layer):
    """点名缺陷回归：prepare_type "10备坯" 旧版截成 "0:备坯"（错映射）→ 已彻底移出映射。"""
    acc, rev, dec = layer._inline_enums()
    for t in ("jf_reservation_stock", "jf_reservation_stock_import"):
        assert not any(x["table"] == t and x["column"] == "prepare_type" for x in acc + rev)
        assert any(x["table"] == t and x["column"] == "prepare_type" for x in dec)
    # 0001 四位错误码同样不再被截成 "1"
    assert any(x["table"] == "jf_sales_order_detail" and x["column"] == "error_code"
               for x in dec)


def test_p2_4_per_pair_counts_and_reconciliation(built):
    """P2-④ 逐对：如实报告计数变化（不再整列连坐），并与旧 490 宇宙对账。

    旧口径（终轮 P1-1，整列原子）：accepted=477（严格对 ≥2 且值键唯一）+
    declined=13（严格对 <2 者整列降级）= 490（旧单位数正则入选列宇宙）。
    P2-④ 逐对：
      accepted 477 → 497 = 477（旧 accepted 全部保留、取值逐字不变）+
        2 混排列挽回（is_exempt `免收订金:1=是,0否` 取 `1=是`，`0否`→dropped_pairs）+
        18 单码注释列（如 `1=是`/`类型 0:无`）此前被 ≥2 门槛整列丢弃（既非 accepted
        亦非 declined，属漏采），现逐对如实入库（超旧 490 宇宙，1 值且无 dropped）。
      declined 13 → 11（2 混排列离开 declined）；needs_review 恒 0（无整列全多义者）。
    对账：497 - 18 单码新增 = 479（旧宇宙内 accepted）；479 + 11 declined = 490（旧宇宙全覆盖）。
    """
    es = built["enum_semantics"]
    assert es["inline_enum_count"] == 497
    assert es["inline_enum_needs_review_count"] == 0
    assert es["inline_enum_declined_count"] == 11
    acc = es["detail"]["inline_enums_from_comment"]
    single_code_new = [a for a in acc if len(a["values"]) == 1
                       and "dropped_pairs" not in a]
    assert len(single_code_new) == 18                       # 逐对挽回的单码新增列
    old_universe_acc = es["inline_enum_count"] - len(single_code_new)  # 属旧 490 宇宙
    assert old_universe_acc + es["inline_enum_declined_count"] == 490  # 旧宇宙全覆盖对账
    for a in acc:                                           # 每列值键唯一 + ≥1 码值
        vs = [p["value"] for p in a["values"]]
        assert vs and len(vs) == len(set(vs)), (a["table"], a["column"])


# ---------------------------------------------------------------- P2-④ 逐对判定
def test_p2_4_real_data_recovers_mixed_writing_columns(layer):
    """P2-④ 点名：两列 `免收订金:1=是,0否` 由整列 declined 挽回为 accepted（取 1=是）。"""
    acc, rev, dec = layer._inline_enums()
    for t in ("jf_reservation_stock", "jf_reservation_stock_import"):
        rec = next(x for x in acc if x["table"] == t and x["column"] == "is_exempt_deposit")
        # 良好码对逐个入映射；本列仅 1 个通过（1=是）
        assert rec["values"] == [{"value": "1", "label": "是"}], (t, rec["values"])
        # 畸形单对 0否 逐对剔除并入 dropped_pairs 审计（不再整列连坐丢 1=是）
        dm = [d for d in rec["dropped_pairs"] if d["kind"] == "malformed"]
        assert {"value": "0", "label": "否"} in [{k: d[k] for k in ("value", "label")} for d in dm]
        # 该列已离开 declined
        assert not any(x["table"] == t and x["column"] == "is_exempt_deposit" for x in dec)


def test_p2_4_no_multidigit_truncation_regression(layer):
    """P2-④ 硬约束：逐对放宽不得回流多位码截断（10→0 / 0001→1）。"""
    acc, rev, dec = layer._inline_enums()
    for t in ("jf_reservation_stock", "jf_reservation_stock_import"):
        # prepare_type `8客订、9米样板、10备坯、11备纱` 无显式分隔 → 逐对后无一有效 → 仍 declined
        assert not any(x["table"] == t and x["column"] == "prepare_type" for x in acc)
        assert any(x["table"] == t and x["column"] == "prepare_type" for x in dec)
    # 任何 accepted 记录都不得含"截断产物"值键 0=备坯 / 1=备纱 / 1=库存不足
    banned = {("0", "备坯"), ("1", "备纱"), ("1", "库存不足"), ("2", "需手工对色")}
    for a in acc:
        for v in a["values"]:
            assert (v["value"], v["label"]) not in banned, (a["table"], a["column"], v)


def test_p2_4_all_conflict_column_goes_to_needs_review():
    """真冲突仍拒：某列**全部**码值同码多义（无一可留）→ 整列 needs_review，不落映射。"""
    g = PropertyGraph()
    g.add_node("domain:D01", "Domain", domain_id="D01", tier="A", table_count=1)
    g.add_node("table:jf_allconf", "Table", name="jf_allconf", tier="A", domain="D01",
               column_count=1)
    g.add_node("column:jf_allconf.st", "Column", name="st", table_id="jf_allconf",
               semantic="1=启用，1=停用", data_type="tinyint", key_role=None)
    acc, rev, dec = sl.SemanticLayer(graph=g)._inline_enums()
    assert acc == [] and dec == []                             # 无一有效 → 不 accepted/declined
    assert len(rev) == 1 and rev[0]["column"] == "st"
    assert set(rev[0]["conflicts"]["1"]) == {"启用", "停用"}
    assert "待确认" in rev[0]["status"]


# ================================================================ 2 REALIZED_BY 图不虚构
def test_concept_graph_realized_by_points_to_real_tables(layer, fresh_graph):
    tbl_names = {n["name"] for n in fresh_graph.nodes.values() if n["label"] == "Table"}
    g, stat = layer.build_concept_graph()
    assert stat["concepts"] > 0 and stat["realized_by_edges"] > 0
    seen = 0
    for e in g.edges:
        if e["type"] == "REALIZED_BY":
            dst = e["dst"].split(":", 1)[1]
            assert dst in tbl_names, e                          # 只绑实物表
            assert e["quote_hash"] and e["source_file"], e      # 每条边可回溯来源
            assert e["is_inferred"] is True                     # 承全库推断、非物理约束
            seen += 1
    assert seen == stat["realized_by_edges"]


def test_p2_5_concept_graph_actually_materialized(tmp_path, monkeypatch):
    """P2-5：交付承诺必须实落盘——run(write=True) 写出 semantic_concept_graph.json。"""
    monkeypatch.setattr(sl, "DATA_META_DIR", tmp_path)          # 隔离输出（承 audit-M2 P2-5）
    monkeypatch.setattr(sl, "OUT_DIR", tmp_path / "out")
    r = sl.run(write=True)
    f = tmp_path / sl.CONCEPT_GRAPH_ARTIFACT
    assert f.exists() and (tmp_path / "out" / sl.CONCEPT_GRAPH_ARTIFACT).exists()
    cg = json.loads(f.read_text(encoding="utf-8"))
    assert cg["counts"]["concepts"] == 36
    assert cg["counts"]["realized_by_edges"] == 67              # 与 eval-M4 §2 锚一致
    assert r["concept_realized_by"]["counts"]["realized_by_edges"] == 67
    assert "semantic_concept_graph.json" in r["concept_realized_by"]["materialization"]
    tbl = {n["name"] for n in load_graph_json(L0_GRAPH_JSON).nodes.values()
           if n["label"] == "Table"}
    rb = [e for e in cg["edges"] if e["type"] == "REALIZED_BY"]
    assert len(rb) == 67
    for e in rb:
        assert e["dst"].split(":", 1)[1] in tbl and e["quote_hash"]   # 落盘边仍可回溯
    assert all(n["label"] in ("Concept", "EvidenceSrc") for n in cg["nodes"])


# ================================================================ 3 指标语义层确未实现
def test_out_of_scope_metrics_declared(built):
    items = " ".join(o["item"] for o in built["out_of_scope"])
    for kw in ("指标", "KPI", "术语表", "计算口径"):
        assert kw in items
    # 语义层未产出任何“指标口径/公式/术语定义”结构体
    payload = str(built)
    assert "计算公式" not in payload and "KPI定义" not in payload


# ================================================================ 4 NL 只调 L1/L2、不引血缘/LLM
def test_router_source_uses_only_l1_and_community():
    src = inspect.getsource(nlr)
    for forbidden in ("load_graph_with_lineage", "reference_traverse", "table_impact(",
                      ".synthesize(", ".compose_multi(", "openai", "anthropic",
                      "requests", "urllib.request"):
        assert forbidden not in src, forbidden
    for required in ("self.l1.", "global_analysis"):
        assert required in src


# ================================================================ 5 默认门 + 不绕置信/存疑
def test_default_gates_are_strict(router):
    a = router.answer("jf_customer 关联哪些表？")
    assert a["gates"]["min_confidence"] == CONFIDENCE_DEFAULT_MIN == 0.45
    assert a["gates"]["show_uncertain"] is False
    assert a["gates"]["max_hops"] <= MAX_HOPS == 3


def test_max_hops_clamped_to_budget(router):
    a = router.answer("改了 jf_product 会影响哪些表？", max_hops=99)
    assert a["gates"]["max_hops"] == 3                          # 超限钳制


def test_relations_never_leak_below_conf_or_uncertain(router):
    for t in ("jf_customer", "jf_sales_order", "jf_trader", "jf_goods"):
        a = router.answer(f"{t} 关联哪些表？")
        for r in a["answer"]["relations"]:
            assert r["confidence"] >= CONFIDENCE_DEFAULT_MIN
            assert r["has_uncertain"] is False                  # 存疑边默认隐藏（不绕门）


def test_traverse_edges_all_above_conf(router):
    a = router.answer("改了 jf_product 会影响哪些表？")
    assert a["intent"] == "impact_lineage"
    for p in a["answer"]["paths"]:
        assert p["confidence"] >= CONFIDENCE_DEFAULT_MIN


# ================================================================ 6 NL 不生成无出处关系
RELATION_QUERY_TABLES = ["jf_customer", "jf_sales_order", "jf_product", "jf_trader"]


@pytest.mark.parametrize("t", RELATION_QUERY_TABLES)
def test_uc3_relations_exist_in_graph_and_have_provenance(router, fresh_graph, t):
    a = router.answer(f"{t} 关联哪些表？")
    for r in a["answer"]["relations"]:
        assert _edge_exists(fresh_graph, t, r["other"]), (t, r)  # 图内真实边，非臆造
        assert r["quote_hash"] and r["source_file"], r           # 100% 有出处


@pytest.mark.parametrize("t,dirkw", [("jf_product", "影响"), ("jf_receivable_write_off", "血缘")])
def test_uc4_uc5_traverse_paths_are_real_adjacency_with_provenance(router, fresh_graph, t, dirkw):
    q = f"改了 {t} 会{dirkw}哪些表？" if dirkw == "影响" else f"{t} 的{dirkw}来自哪些表？"
    a = router.answer(q)
    for p in a["answer"]["paths"]:
        assert _edge_exists(fresh_graph, p["from"], p["to"]), (t, p)
        assert p["quote_hash"] and p["source_file"], p


def test_hub_and_coupling_from_real_edges(router, fresh_graph, kept_pairs):
    hubs = router.answer("全库最枢纽的表是哪些？")["answer"]["top_hubs"]
    assert hubs
    for h in hubs:
        assert fresh_graph.has_node(f"table:{h['table']}")       # 枢纽=图内实物表
        assert h["sample_edge_provenance"]                       # 逐条带可回溯样本边
        for pv in h["sample_edge_provenance"]:
            f = str(pv["file"])
            assert f.endswith(".md") or f.endswith("test_erp.sql"), pv
    coup = router.answer("哪些社区/域之间耦合最紧？")["answer"]
    for cp in coup["tightest_couplings"]["cross_community_coupling"]:
        for pair in cp["sample_table_pairs"]:
            # 耦合对必为 M3 审计 kept 融合边（RELATES_TO/REFERENCES），非臆造
            assert frozenset((pair["src"], pair["dst"])) in kept_pairs, pair


# ================================================================ 7 拒绝臆造兜底
def test_refuses_unknown_table(router):
    a = router.answer("zzz_not_a_table 关联哪些表？")
    assert a["refused"] is True
    assert a["sources"] == []


def test_metric_query_refused_out_of_scope(router):
    a = router.answer("本月 GMV 指标的口径是什么？")
    assert a["intent"] == "out_of_scope" and a["uc"].startswith("N/A")
    assert a["refused"] is True
    assert a["answer"] is None and a["sources"] == []
    assert "指标" in a["matched_params"]["out_of_scope_terms"][0] or \
        any("指标" in x or "口径" in x for x in a["matched_params"]["out_of_scope_terms"])


def test_p2_3_u6_03_phantom_domain_refused_with_empty_sources(router):
    """P2-3（u6-03 根因翻转）：域不存在 → refused=True 且**不追加 Domain 出处**。"""
    a = router.answer("D99 域概览与质量问题？")
    assert a["intent"] == "community_rollup"
    assert a["refused"] is True
    assert a["sources"] == []                                   # 兑现 refused⇒sources==[]
    assert [s for s in a["sources"] if s.get("kind") == "Domain"] == []
    assert a["answer"]["l1_domain_summary"]["exists"] is False


def test_p2_3_existing_domain_still_carries_domain_source(router):
    a = router.answer("D14 域的概览与质量问题？")
    assert a["refused"] is False
    assert [s for s in a["sources"] if s["kind"] == "Domain" and s["domain"] == "D14"]


# ================================================================ P2-4 方向词表 + intent 白名单
@pytest.mark.parametrize("q,d", [
    ("jf_customer 依赖哪些表？", "in"),                 # 高频上游词
    ("jf_invoice 的血缘来自哪些表？", "in"),
    ("jf_x 属于哪个上游？", "in"),
    ("谁依赖 jf_customer？", "out"),                    # 最长匹配：谁依赖(out) 压 依赖(in)
    ("改了 jf_product 影响哪些表？", "out"),
    ("jf_customer 关联哪些表？", None),                  # 无方向词
])
def test_p2_4_direction_vocab(router, q, d):
    assert router.extract(q)["direction"] == d


def test_p2_4_no_direction_falls_back_both_not_default_out(router):
    a = router.answer("jf_product", intent="impact_lineage")
    assert a["matched_params"]["direction"] is None
    assert a["answer"]["direction"] == "both"                  # 不默认 out
    assert "回退" in a["note"] and "默认下游" in a["note"]      # 反问式提示


def test_p2_4_ambiguous_direction_falls_back_both(router):
    a = router.answer("jf_product 影响哪些表又依赖哪些表？")
    assert a["intent"] == "impact_lineage"
    assert a["matched_params"]["direction"] == "ambiguous"
    assert a["answer"]["direction"] == "both"
    assert "歧义" in a["note"]


def test_p2_4_explicit_direction_still_exact(router, fresh_graph):
    a = router.answer("谁依赖 jf_customer？")
    assert a["answer"]["direction"] == "out"
    for p in a["answer"]["paths"]:                             # 回退不牺牲正确方向
        assert _edge_exists(fresh_graph, p["from"], p["to"])


def test_p2_4_intent_whitelist_rejects_unknown(router):
    with pytest.raises(ValueError):
        router.answer("jf_product 关联哪些表？", intent="not_an_intent")
    with pytest.raises(ValueError):                            # 内部守卫态不可手工注入
        router.answer("jf_product 关联哪些表？", intent="out_of_scope")
    ok = router.answer("jf_product 关联哪些表？", intent="find_relations")
    assert ok["intent"] == "find_relations"


# ================================================================ P2-6 FTS 打开收窄
def test_p2_6_open_fts_missing_file_none(tmp_path, monkeypatch):
    monkeypatch.setattr(nlr, "FTS_DB", tmp_path / "absent.db")
    assert nlr.NLRouter._open_fts() is None                    # 无索引（文件不存在）


def test_p2_6_open_fts_unbuilt_db_none(tmp_path, monkeypatch):
    p = tmp_path / "empty.db"
    sqlite3.connect(str(p)).close()                            # 合法 sqlite 但未建索引表
    monkeypatch.setattr(nlr, "FTS_DB", p)
    assert nlr.NLRouter._open_fts() is None                    # 无索引（未建表）


def test_p2_6_open_fts_corrupt_raises(tmp_path, monkeypatch):
    p = tmp_path / "junk.db"
    p.write_bytes(b"definitely-not-a-sqlite-file")
    monkeypatch.setattr(nlr, "FTS_DB", p)
    with pytest.raises(sqlite3.DatabaseError):                 # 损坏 ≠ 无索引：不再静默 None
        nlr.NLRouter._open_fts()


def test_p2_6_real_index_opens(router):
    assert router.fts is not None                              # 仓库现状：索引存在且已建


# ================================================================ P1-2 指纹消费校验
def test_fp_interface_statuses():
    assert fpmod.verify_input_fingerprint({}, name="x")[
        "status"] == "absent_upstream_not_embedded"            # 上游未嵌入 → 放行留痕
    rec = fpmod.verify_input_fingerprint({"input_fingerprint": "abc"}, name="x")
    assert rec["status"] == "observed_no_baseline" and rec["fingerprint"] == "abc"
    assert fpmod.verify_input_fingerprint({"input_fingerprint": {"digest": "abc"}},
                                          name="x", expected="abc")["status"] == "ok"
    with pytest.raises(fpmod.InputFingerprintMismatch):        # 不一致 → fail-fast
        fpmod.verify_input_fingerprint({"input_fingerprint": "abc"}, name="x",
                                       expected="zzz")


def test_p1_2_handoff_carries_fingerprint_check(handoff):
    c = handoff["isolated_a"]["fingerprint_check"]
    assert c["artifact"] == "community_result.json"
    assert c["status"] in ("absent_upstream_not_embedded", "observed_no_baseline", "ok")


def test_p1_2_router_l2_check_and_failfast():
    r = nlr.NLRouter()
    r.l2()
    assert r.input_fingerprint_checks["global_analysis"][
        "status"] in ("absent_upstream_not_embedded", "observed_no_baseline", "ok")
    bad = nlr.NLRouter(expected_input_fingerprint="deadbeef-not-a-real-digest")
    with pytest.raises(fpmod.InputFingerprintMismatch):
        bad.l2()                                               # 期望基准不一致 → 拒绝消费


# ================================================================ NL 确定性（同输入同输出）
def test_answer_deterministic(router):
    q = "jf_customer 关联哪些表？"
    assert copy.deepcopy(router.answer(q)) == copy.deepcopy(router.answer(q))
    q2 = "D14 域的概览与质量问题？"
    assert router.answer(q2) == router.answer(q2)


# ================================================================ 8 LLM stub 默认禁用
def test_llm_synthesis_disabled():
    assert llm_synthesis.ENABLED is False
    with pytest.raises(NotImplementedError):
        llm_synthesis.synthesize({"answer": {}})
    with pytest.raises(NotImplementedError):
        llm_synthesis.compose_multi([])
    st = llm_synthesis.status()
    assert st["enabled"] is False and "SUPPORTED_BY" in " ".join(st["hard_constraints"])


# ================================================================ 9 M3 P2 承接
@pytest.fixture(scope="module")
def handoff():
    return p2.run(write=False)


def test_p2a_isolated_a_list_75_and_coverage(handoff):
    a = handoff["isolated_a"]
    assert a["isolated_a_count"] == 75
    assert len(a["isolated_a_tables"]) == a["isolated_a_count"]
    assert a["in_graph_nodes"] + a["isolated_a_count"] == a["a_domain_tables_total"] == 349
    assert a["m3_crosscheck_count"] == 75                       # 与 M3 计数一致（同源）
    assert a["role"] == "handoff_only"


def test_p2b_mixed_clusters_downgraded(handoff):
    b = handoff["mixed_cluster_note"]
    assert b["n_mixed_communities"] == len(b["mixed_communities"]) == 9
    for m in b["mixed_communities"]:
        assert m["note"] == p2.MIXED_CLUSTER_NOTE == "对照观察(非发现物)"
    assert b["ground_truth"].startswith("00")                   # 不覆盖权威分组


def test_p2c_eval_handoff_entry_ready(handoff):
    c = handoff["eval_handoff"]
    assert c["role"] == "handoff_only"
    assert c["golden_set_owner"].startswith("rag-eval-gate")
    ids = [i["intent"] for i in c["nl_entry_api"]["intent_coverage"]]
    assert len(ids) == 6
    assert c["defaults"] == {"min_confidence": 0.45, "show_uncertain": False, "max_hops": 3}
    assert c["llm_synthesis"]["enabled"] is False
    assert c["smoke_sample"]["refused"] is False                # 入口可作答（非异常）
