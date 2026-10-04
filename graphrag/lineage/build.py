"""M2 · 血缘构建编排：M1 快照回载 → REFERENCES 抽取 → 增量合并 → 断言 → 导出。

单一事实源纪律（任务书 §5）：
- 图底座 = `graphrag/store/loader.load_graph_json(graphrag/data/l0_graph.json)`
  （M1 落盘 JSON 快照，**只读**；快照缺失时退回 pipeline.run(write=False) 内存重建，
  全程不写 `l0_*` 派生文件，避免与 M1 双源分叉）。
- M2 交付 = `graphrag/data/meta/lineage_edges.jsonl` + `lineage_manifest.json` +
  `graphrag/data/review_queue.json`；`graphrag/out/lineage/` 仅为本地镜像
  （`.gitignore:18 'out/'` 忽略，提交口径以 data/meta 镜像为准）。

断言（不过门不落盘，fail-fast）：
- REFERENCES 边两端 Column 节点必存在（悬挂=0；不定指向者只进队列不建边）；
- 逐边 evidence_src {file,table,column,quote_hash} 齐全（有出处率=100%）；
- 逐边 evidence_level ∈ 五级枚举、is_inferred=true、confidence≤0.95（全库 0 FK 上限）；
- 合并前后 M1 既有边计数不变（RELATES_TO/IS_COLUMN_OF/… 不被改写）。
"""

from __future__ import annotations

import json
import re
from pathlib import Path

from ..ingest.config import (
    GRAPH_DIR, DATA_META_DIR, L0_GRAPH_JSON, SQL_FILE, EVIDENCE_ORDER,
    CONFIDENCE_DEFAULT_MIN,
)
from ..ingest.ddl_parser import parse_ddl
from ..ingest.derived_parser import parse_derived
from ..ingest.relation_parser import parse_relations
from ..ingest.tiering import classify_tier
from ..store.loader import load_graph_json
from .evidence import build_ddl_line_index
from .extractor import LineageExtractor, REFERENCES_TYPE
from .impact import reference_traverse, table_lineage_profile


class LineageAssertionError(RuntimeError):
    pass


OUT_DIR = DATA_META_DIR.parents[1] / "out" / "lineage"   # graphrag/out/lineage（本地镜像，gitignored）
LINEAGE_EDGES = "lineage_edges.jsonl"
LINEAGE_MANIFEST = "lineage_manifest.json"
REVIEW_QUEUE = GRAPH_DIR / "review_queue.json"

_DISCLAIMER = ("血缘为逆向推断、非物理外键（全库 0 显式 FOREIGN KEY / ADD CONSTRAINT）；"
               "证据上限 comment_explicit、confidence≤0.95；范围=引用/结构级血缘，"
               "不含变换/ETL 数据流级血缘。[待确认] 队列默认隐藏（confidence<0.45 过滤）。")


def _base_graph():
    """优先复用 M1 JSON 快照（loader 回载）；缺失则内存重建（不写盘）。"""
    if Path(L0_GRAPH_JSON).exists():
        g = load_graph_json(L0_GRAPH_JSON)
        return g, {"mode": "loader_snapshot_reload", "path": str(L0_GRAPH_JSON)}
    from ..ingest import pipeline                      # 延迟导入，避免循环
    res = pipeline.run(write=False)
    return res["graph"], {"mode": "pipeline_in_memory_rebuild", "path": None}


def _merge_into_graph(graph, edges: list[dict]) -> dict:
    """把 REFERENCES + EvidenceSrc + SUPPORTED_BY 增量写入回载图（内存）。"""
    added_ev = 0
    for e in edges:
        ev = e["evidence_src"]
        ev_id = f"evsrc:{ev['quote_hash']}"
        if not graph.has_node(ev_id):
            graph.add_node(ev_id, "EvidenceSrc", file=ev["file"],
                           section=ev.get("section"), table=ev["table"],
                           column=ev["column"], quote_hash=ev["quote_hash"],
                           line_hint=ev.get("line_hint"))
            added_ev += 1
        graph.add_edge(e["src"], e["dst"], REFERENCES_TYPE,
                       **{k: v for k, v in e.items() if k not in ("src", "dst", "type")})
        graph.add_edge(e["src"], ev_id, "SUPPORTED_BY", rel_kind=REFERENCES_TYPE,
                       dst_col=e["dst"])
        for sup in e["supporting_evidence"]:
            sid = f"evsrc:{sup['quote_hash']}"
            if not graph.has_node(sid):
                graph.add_node(sid, "EvidenceSrc", file=sup["file"],
                               section=sup.get("section"), table=sup["table"],
                               column=sup["column"], quote_hash=sup["quote_hash"],
                               line_hint=sup.get("line_hint"))
                added_ev += 1
            graph.add_edge(e["src"], sid, "SUPPORTED_BY", rel_kind=REFERENCES_TYPE,
                           dst_col=e["dst"], role="corroboration")
    return {"evidence_nodes_added": added_ev}


def _assertions(graph, edges: list[dict], pre_counts: dict) -> list[dict]:
    checks = []

    def chk(name, target, actual, note=""):
        ok = target == actual if isinstance(target, int) or isinstance(actual, int) \
            else target == actual
        checks.append({"name": name, "target": target, "actual": actual,
                       "ok": bool(ok), "note": note})

    dangling = [e for e in edges
                if not (graph.has_node(e["src"]) and graph.has_node(e["dst"]))]
    chk("reference_edge_dangling", 0, len(dangling), "边两端 Column 必须已在图")
    bad_ev = [e for e in edges
              if not all((e.get("evidence_src") or {}).get(k)
                         for k in ("file", "table", "column", "quote_hash"))]
    chk("evidence_coverage", 0, len(bad_ev), "有出处率必须 100%")
    bad_lvl = [e for e in edges if e["evidence_level"] not in EVIDENCE_ORDER]
    chk("evidence_level_enum", 0, len(bad_lvl))
    over_cap = [e for e in edges if e["confidence"] > 0.95]
    chk("confidence_cap_0.95", 0, len(over_cap), "全库 0 FK，上限锁 0.95")
    non_inf = [e for e in edges if e["is_inferred"] is not True]
    chk("all_is_inferred", 0, len(non_inf))
    bad_scope = [e for e in edges if e["evidence_level"] == "explicit_fk"]
    chk("no_physical_fk_claim", 0, len(bad_scope))
    post = graph.counts()["edge_types"]
    for etype in ("RELATES_TO", "IS_COLUMN_OF", "DERIVED_FROM", "HAS_ISSUE",
                  "BELONGS_TO_DOMAIN", "SAME_FAMILY_AS", "SUPPORTED_BY"):
        chk(f"m1_edge_intact:{etype}", pre_counts.get(etype, 0),
            post.get(etype, 0) - (len(edges) * 1 + sum(
                len(e["supporting_evidence"]) for e in edges) if etype == "SUPPORTED_BY" else 0),
            "M1 既有边不被改写（SUPPORTED_BY 仅增不减）" if etype == "SUPPORTED_BY" else "")
    a_tabs = sum(1 for n in graph.nodes.values()
                 if n["label"] == "Table" and n.get("tier") == "A")
    chk("m1_traversable_A", 349, a_tabs, "ξ/A349/Issue27 口径守恒（以实物为准）")
    issues = sum(1 for n in graph.nodes.values() if n["label"] == "Issue")
    chk("m1_issue_nodes", 27, issues)
    return checks


def _carryover(graph, ddl_names: set[str], edges: list[dict]) -> dict:
    """承接 M1 登记的 4 pending + 9 derived 悬挂：能由 DDL 结构定级则定级，否则留队列。

    定级口径：该悬挂表级关系若其 via_column 已在 DDL 两侧唯一定位、且 M2 已建对应
    REFERENCES 列边 → 标记 `absorbed_by_column_edge`（列级证据已可回溯，表级悬挂不改图，
    RELATES_TO 属 M1 写域）；否则原样入队。derived 9 悬挂：剥名后在 DDL 中查找
    唯一 A 级命中（含 jf_ 前缀）→ 给出 suggested_source（信息性），仍留队列。
    """
    built = {(e["src_table"], e["src_column"]): e for e in edges}
    built |= {(e["dst_table"], e["dst_column"]): e for e in edges}
    edges_01, edges_05, _ = parse_relations()
    pending = []
    for e in list(edges_01) + list(edges_05):
        src, dst = e["src"], e["dst"]
        if src in ddl_names and dst in ddl_names:
            continue
        missing = [x for x in (src, dst) if x not in ddl_names]
        vc = e.get("via_column")
        absorbed = None
        if vc:
            for t in (src, dst):
                if t in ddl_names and (t, vc) in built:
                    be = built[(t, vc)]
                    absorbed = {"edge": f"{be['src']}->{be['dst']}",
                                "evidence_level": be["evidence_level"],
                                "quote_hash": be["evidence_src"]["quote_hash"]}
        # DDL 结构线索：缺失名在真实表集中的唯一后缀命中（仅建议，不改 M1 图）
        sugg = [t for t in graph.nodes.values()
                if t["label"] == "Table" and t.get("tier") == "A"
                and any(m.split("_", 1)[-1] in t["name"] and t["name"] != m for m in missing)]
        unique = sugg if len(sugg) == 1 else []
        pending.append({
            "kind": "m1_carryover_pending_relates_to",
            "src": src, "dst": dst, "missing_endpoints": missing,
            "via_column": vc, "source_file": e.get("source_file"),
            "reason": "表级端点非 DDL 真实表（M1 未臆建造点）；RELATES_TO 属 M1 写域，M2 不补表级边",
            "status": "[待确认]",
            "ddl_structure_finding": ({
                "suggested_alias_table": unique[0]["name"],
                "note": "唯一后缀命中，列级已由 DDL COMMENT/文档定级"
                        if absorbed else "唯一后缀命中（信息性，不自动连通）"}
                if unique else None),
            "absorbed_by_column_edge": absorbed,
        })
    c_tables = {n for n in ddl_names if classify_tier(n) == "C"}
    derived = parse_derived(c_tables, ddl_names)
    dangling = []
    for d in derived.get("dangling", []):
        name = d["table"]
        stem = re.sub(r"_bak_\d{14}$", "", name)
        hits = sorted({t["name"] for t in graph.nodes.values()
                       if t["label"] == "Table" and t.get("tier") == "A"
                       and (t["name"] == stem or t["name"] == "jf_" + stem or
                            t["name"].endswith("_" + stem))})
        dangling.append({
            "kind": "m1_carryover_derived_dangling",
            "table": name, "stripped_source": stem,
            "reason": "C 级派生源表不可解析（M1 登记不造边）",
            "status": "[待确认]",
            "ddl_structure_finding": ({"unique_a_hit": hits[0]}
                                      if len(hits) == 1 else
                                      {"candidates": hits if hits else None}),
            "note": "命中仅作复核线索；DERIVED_FROM 属 M1 写域，M2 不补边",
        })
    return {"pending_items": pending, "derived_items": dangling,
            "pending_total": len(pending), "derived_total": len(dangling)}


def build(write: bool = True) -> dict:
    """M2 主入口。返回 {graph, edges, queue, manifest, checks}。"""
    graph, graph_mode = _base_graph()
    pre_counts = graph.counts()["edge_types"]

    ddl = parse_ddl()
    line_index = build_ddl_line_index(SQL_FILE)

    ex = LineageExtractor(graph, ddl, line_index)
    edges, queue, stats = ex.run()

    carry = _carryover(graph, set(ddl), edges)
    queue = queue + carry["pending_items"] + carry["derived_items"]

    merge_info = _merge_into_graph(graph, edges)
    checks = _assertions(graph, edges, pre_counts)
    failed = [c for c in checks if not c["ok"]]

    # UC4/UC5 实测样本（枢纽 jf_sales_order）——写进 manifest 供复现对照
    uc5 = reference_traverse(graph, "jf_sales_order", "id", "downstream")
    uc4 = reference_traverse(graph, "jf_sales_order", "customer_id", "upstream")
    uc5t = table_impact_summary(graph, "jf_sales_order")
    uc4p = table_lineage_profile(graph, "jf_sales_order")

    manifest = {
        "milestone": "M2",
        "generator": "graphrag/lineage/build.py",
        "contract": "graphrag/spec (M0 冻结)；M1 快照 graphrag/data/l0_graph.json 只读回载",
        "graph_base": graph_mode,
        "disclaimer": _DISCLAIMER,
        "scope": {
            "includes": ["字段级引用/结构级 REFERENCES（列→列）",
                         "反向可达影响分析（加权 BFS，≤3 跳，默认 confidence≥0.45）",
                         "多态/同名异指向/外部引用的队列化处置",
                         "M1 4 pending + 9 derived 悬挂承接（登记与定级信息）"],
            "excludes": ["变换/ETL 数据流级血缘（如 sum(order.amount)→invoice.total）"
                         " —— **超本期范围**（schema §4.3/R-9），不建、不宣称",
                         "指标/KPI 语义层（R-10）",
                         "物理外键约束（全库实测 0，不存在）"],
        },
        "calibration": {
            "signal_priority": ["comment_explicit(DDL COMMENT 点名真实表名)",
                                "doc_relation(M1 RELATES_TO via_column，er-model 一等锚)",
                                "index_backed(_id/_code + INDEX/UNIQUE + 目标键佐证)",
                                "name_inferred(唯一规范命名，含 catregory/datat 固化拼写映射)",
                                "semantic_inferred(_code→pk_guess 列级猜测，降权)"],
            "misspell_map": {"category": "catregory", "data": "datat"},
            "ambiguity_policy": "多候选/同名列异指向/COMMENT 多点名 → 一律入队列，不武断连通；"
                                "后缀模糊匹配（如 cus_id→*cus）因误挂风险**不采用**",
        },
        "lineage_stats": stats,
        "carryover": {k: v for k, v in carry.items() if not k.endswith("_items")},
        "uc_samples": {
            "UC5_downstream_jf_sales_order.id": {
                k: uc5[k] for k in ("reached_columns", "reached_tables",
                                    "edges_traversed", "truncated_at_budget")},
            "UC4_upstream_jf_sales_order.customer_id": {
                k: uc4[k] for k in ("reached_columns", "edges_traversed")},
            "UC5_table_pivot_jf_sales_order": uc5t,
            "UC4_profile_outgoing": len(uc4p["outgoing_references"]),
            "note": "默认 min_conf=0.45 / max_hops=3；确定性可复现（同输入同输出）",
        },
        "anchors_conserved": {
            "traversable_A": 349, "relation_lines_xi": 456,
            "relates_to_edges_built_m1": pre_counts.get("RELATES_TO"),
            "issue_nodes": 27,
        },
        "assertions": {"all_pass": not failed,
                       "passed": sum(1 for c in checks if c["ok"]),
                       "failed": len(failed), "checks": checks},
        "evidence_node_stats": merge_info,
        "outputs": {
            "committed": ["graphrag/lineage/*", "graphrag/data/review_queue.json",
                          f"graphrag/data/meta/{LINEAGE_EDGES}",
                          f"graphrag/data/meta/{LINEAGE_MANIFEST}"],
            "local_mirror": "graphrag/out/lineage/*（.gitignore:18 'out/' 忽略，不入库）",
        },
        "honest_unfinished": [
            "变换/ETL 数据流级血缘：超本期范围（M0 §4.3/R-9 既定排除），未实现。",
            "后缀模糊命名（raw_fabric_id→jf_goods_raw_fabric 一类 55 处）无 COMMENT/文档佐证者"
            "一律不建边（防误挂），已计入 dropped_naming_unresolved / target_unresolved 队列。",
            "M1 RELATES_TO 两端同持 via_column 的归属歧义（owner_ambiguous）跳过列边生成，"
            "相关高危已由各自由 COMMENT/命名链路独立定指向（见 B-4 留痕队列项）。",
            "4 pending / 9 derived 悬挂的表级补边属 M1 写域，M2 仅承接登记 + DDL 结构定级信息，"
            "不代改 M1 产物。",
            "`_code`→目标 PK 的列级猜测（pk_guess）降为 semantic_inferred，默认阈值下隐藏，"
            "待人工确认后可升级。",
            "er-model 03 逻辑模型「关联」列的 FK[..] 线索未消费（M1 header_parser 归一即弃），"
            "后续可作为第六路佐证信号，本期未实现。",
        ],
    }

    if write:
        if failed:
            raise LineageAssertionError(
                "M2 断言未闭合，阻断交付：\n  " +
                "; ".join(f"{c['name']}: target={c['target']} actual={c['actual']}"
                          for c in failed))
        _export(graph, edges, queue, manifest)
    return {"graph": graph, "edges": edges, "queue": queue,
            "manifest": manifest, "checks": checks, "stats": stats}


def table_impact_summary(graph, table: str) -> dict:
    r = reference_traverse(graph, table, None, "downstream")
    return {k: r[k] for k in ("seed_count", "reached_columns", "reached_tables",
                              "edges_traversed", "truncated_at_budget")}


def _export(graph, edges, queue, manifest) -> None:
    DATA_META_DIR.mkdir(parents=True, exist_ok=True)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    edges_json = [json.dumps(e, ensure_ascii=False, sort_keys=True) for e in edges]
    (DATA_META_DIR / LINEAGE_EDGES).write_text(
        "\n".join(edges_json) + "\n", encoding="utf-8")
    (DATA_META_DIR / LINEAGE_MANIFEST).write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    REVIEW_QUEUE.write_text(json.dumps(
        {"milestone": "M2", "policy": "默认隐藏：confidence<0.45 / unconfirmed 不参与召回；"
                                      "人工确认可上调证据级（evidence-confidence-map §2）",
         "disclaimer": _DISCLAIMER,
         "counts": _queue_counts(queue),
         "items": queue},
        ensure_ascii=False, indent=2), encoding="utf-8")
    # 本地镜像（out/ 被忽略）
    (OUT_DIR / LINEAGE_EDGES).write_text("\n".join(edges_json) + "\n", encoding="utf-8")
    (OUT_DIR / LINEAGE_MANIFEST).write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")


def _queue_counts(queue: list[dict]) -> dict:
    kinds: dict[str, int] = {}
    for q in queue:
        kinds[q["kind"]] = kinds.get(q["kind"], 0) + 1
    return {"total": len(queue), "by_kind": dict(sorted(kinds.items()))}


def load_graph_with_lineage(min_conf: float = 0.0):
    """检索/消费侧只读入口：M1 快照 + M2 已交付 REFERENCES（单一事实源=两份文件，
    合并发生在内存，不回写 l0_graph.json）。返回 (graph, edges_meta_loaded)。"""
    graph = load_graph_json(L0_GRAPH_JSON)
    path = DATA_META_DIR / LINEAGE_EDGES
    n = 0
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            if not line.strip():
                continue
            e = json.loads(line)
            if e["confidence"] < min_conf:
                continue
            ev = e["evidence_src"]
            ev_id = f"evsrc:{ev['quote_hash']}"
            if not graph.has_node(ev_id):
                graph.add_node(ev_id, "EvidenceSrc", file=ev["file"],
                               section=ev.get("section"), table=ev["table"],
                               column=ev["column"], quote_hash=ev["quote_hash"],
                               line_hint=ev.get("line_hint"))
            graph.add_edge(e["src"], e["dst"], REFERENCES_TYPE,
                           **{k: v for k, v in e.items()
                              if k not in ("src", "dst", "type")})
            graph.add_edge(e["src"], ev_id, "SUPPORTED_BY", rel_kind=REFERENCES_TYPE)
            n += 1
    return graph, {"references_loaded": n, "source": str(path)}


if __name__ == "__main__":
    res = build(write=True)
    st = res["manifest"]["lineage_stats"]
    print(json.dumps({
        "all_pass": res["manifest"]["assertions"]["all_pass"],
        "references": st["references_total"],
        "by_evidence_level": st["by_evidence_level"],
        "queue": res["manifest"]["carryover"] | {"kinds": st["queue_kinds"],
                                                 "extractor_total":
                                                     st["queue_total_before_carryover"]},
    }, ensure_ascii=False, indent=2))
