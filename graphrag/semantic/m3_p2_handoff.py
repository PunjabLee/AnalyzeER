"""M4 · 承接 M3 三项 P2（非阻断改进项，`reports/eval-M3.md` §3/§7）。

唯一写入者：`rag-semantic-orchestrator`（M4）。只读复用 M3 `graphrag/community/*`
（不改动 M3 自有文件），把三项 P2 落成可提交产物：

- **P2-A**：`graphrag/data/meta/community_isolated_a.json` —— 75 张「无任何 conf≥0.45
  结构边」的孤立 A 表**单列名单**。M3 `_export()` 仅存计数与每域分布、把 `isolated_a_list`
  从 `community_result.json` 过滤掉；本模块从**同一确定性来源**（`build_subgraph()` →
  `stats["isolated_a_list"]`）复算并落盘，且断言与 M3 计数一致。
  （文件名依 M4 任务显式指定；为**承接产物**，非改写 M3 的 `community_result/edges` 文件。）
- **P2-B**：`graphrag/data/meta/semantic_mixed_cluster_note.json` —— 把 M3 NMI 对照下的
  9 个**混合社区**逐簇显式降级标注 `note="对照观察(非发现物)"`（承 §7 P2-B：量化线索 +
  模板句，不作业务发现物、不覆盖 `00 §四` 权威分组）。
- **P2-C**：`graphrag/data/meta/semantic_eval_handoff.json` —— golden **入口就绪交接**：
  声明 `graphrag/nl/` 自然语言入口 API（六类 UC 确定性作答 + 黄金集适配签名），把题目实例
  与阈值判定留给唯一写入者 `rag-eval-gate`（`graphrag/eval/golden/`，本模块**不**出题）。

诚实纪律：三项均只读复用 M3 计算，不新建来源外实体/关系；产物标 `role=handoff_only`。
"""

from __future__ import annotations

import json
from pathlib import Path

from ..ingest.config import CONFIDENCE_DEFAULT_MIN, DATA_META_DIR
from .semantic_layer import OUT_DIR   # graphrag/out/semantic（gitignored 本地镜像）

# 落盘文件名（data/meta 可提交镜像 + out 镜像）
ISOLATED_A = "community_isolated_a.json"          # 任务显式指定名（P2-A）
MIXED_NOTE = "semantic_mixed_cluster_note.json"   # P2-B（semantic_* 命名域）
EVAL_HANDOFF = "semantic_eval_handoff.json"       # P2-C

MIXED_CLUSTER_NOTE = "对照观察(非发现物)"


def _write_dual(name: str, payload: dict) -> None:
    """落 data/meta 可提交镜像 + out 本地镜像（二者字节一致；out 被 gitignore）。"""
    DATA_META_DIR.mkdir(parents=True, exist_ok=True)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    text = json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=False)
    (DATA_META_DIR / name).write_text(text, encoding="utf-8")
    (OUT_DIR / name).write_text(text, encoding="utf-8")


# ---------------------------------------------------------------- P2-A 孤立 A 表名单
def isolated_a_tables(write: bool = True) -> dict:
    """复算并落 75 张无 conf≥0.45 结构边的孤立 A 表名单（承 M3 P2-A）。"""
    from ..community.communities import build_subgraph, COMMUNITIES_RESULT

    G, kept, exclusion, stats, gate_stats, domain_map = build_subgraph(
        min_conf=CONFIDENCE_DEFAULT_MIN)
    isolated = sorted(stats["isolated_a_list"])
    count = stats["isolated_a_tables"]

    # 交叉校验：与 M3 已落盘 community_result.json 的计数一致（同源、非改写）
    m3_ref = None
    cr = DATA_META_DIR / COMMUNITIES_RESULT
    if cr.exists():
        m3 = json.loads(cr.read_text(encoding="utf-8"))
        m3_ref = m3["graph_stats"]["isolated_a_tables"]
        assert m3_ref == count, f"孤立 A 表计数与 M3 不一致：{count} vs {m3_ref}"
    # 覆盖等式：入图节点 + 孤立 = 全部有域 A 表
    assert G.number_of_nodes() + count == len(domain_map)

    payload = {
        "milestone": "M4",
        "artifact": "P2-A（承接 M3）",
        "generator": "graphrag/semantic/m3_p2_handoff.py",
        "role": "handoff_only",
        "definition": ("无任何 confidence≥0.45 结构边（RELATES_TO/REFERENCES，双硬门后）"
                       "的 A 级(有域)表；不入社区融合图，另计。"),
        "edge_gate": CONFIDENCE_DEFAULT_MIN,
        "isolated_a_count": count,
        "in_graph_nodes": G.number_of_nodes(),
        "a_domain_tables_total": len(domain_map),
        "coverage_equation": f"{G.number_of_nodes()} + {count} == {len(domain_map)}",
        "m3_crosscheck_count": m3_ref,
        "recompute_source": "graphrag/community/communities.build_subgraph() "
                            "→ stats['isolated_a_list']",
        "disclaimer": ("社区=分析视图，00 §四 手工 18 域(+OT) 为权威；孤立 A 表仍属其 00 域，"
                       "仅因无高置信结构边未入社区图（非被移除域归属）。"),
        "isolated_a_tables": isolated,
    }
    if write:
        _write_dual(ISOLATED_A, payload)
    return payload


# ---------------------------------------------------------------- P2-B 混合簇降级声明
def mixed_cluster_note(write: bool = True) -> dict:
    """把 M3 NMI 对照的混合社区逐簇标 `note=对照观察(非发现物)`（承 M3 P2-B）。"""
    from ..community.nmi_vs_baseline import nmi_vs_baseline

    out = nmi_vs_baseline(write=False)
    raw_mixed = out["diff_clusters_explainable"]["mixed_communities"]
    downgraded = []
    for m in raw_mixed:
        rec = dict(m)
        rec["note"] = MIXED_CLUSTER_NOTE
        rec["authoritative_domain_source"] = "00 §四（不被社区覆盖）"
        downgraded.append(rec)

    payload = {
        "milestone": "M4",
        "artifact": "P2-B（承接 M3）",
        "generator": "graphrag/semantic/m3_p2_handoff.py",
        "role": "handoff_only",
        "statement": ("M3 涌现社区 vs 00 手工域的**混合簇**（一社区含 ≥2 域）统一降级为"
                      f"**“{MIXED_CLUSTER_NOTE}”**：为结构对照观察，非业务发现物，"
                      "不覆盖 00 权威分组，不据此新建实体/关系。"),
        "ground_truth": out["ground_truth"],
        "analysis_view": out["analysis_view"],
        "nmi_alignment_band": out["verdict"]["alignment_band"],
        "n_mixed_communities": len(downgraded),
        "mixed_communities": downgraded,
        "false_coupling_note": out["diff_clusters_explainable"]["false_coupling_note"],
    }
    if write:
        _write_dual(MIXED_NOTE, payload)
    return payload


# ---------------------------------------------------------------- P2-C golden 入口交接
def eval_handoff(write: bool = True) -> dict:
    """声明六类 UC 自然语言入口 API；题目实例与阈值判定归 rag-eval-gate（P2-C）。"""
    from ..nl.nl_router import NLRouter, INTENTS   # 延迟导入避免环

    entry = NLRouter()
    sample = entry.answer("jf_customer 关联哪些表？")
    payload = {
        "milestone": "M4",
        "artifact": "P2-C（交接 M3 → eval-gate）",
        "generator": "graphrag/semantic/m3_p2_handoff.py",
        "role": "handoff_only",
        "golden_set_owner": "rag-eval-gate（graphrag/eval/golden/，唯一写入者；"
                            "本模块不出题、不判阈值）",
        "nl_entry_api": {
            "callable": "graphrag.nl.nl_router.NLRouter().answer(query, **overrides)",
            "returns": "结构化答案 {query,intent,uc,matched_params,gates,answer,"
                       "sources,refused,disclaimer,llm_synthesis}",
            "golden_hook_signature": ("answer(query, min_confidence=0.45, "
                                      "show_uncertain=False, max_hops=3, ...) → dict；"
                                      "对每题 gold 断言由 eval-gate 施加"),
            "intent_coverage": [{"intent": i["id"], "uc": i["uc"]} for i in INTENTS],
        },
        "defaults": {"min_confidence": CONFIDENCE_DEFAULT_MIN,
                     "show_uncertain": False, "max_hops": 3},
        "llm_synthesis": {"enabled": False, "stage": "接口 stub（graphrag/nl/llm_synthesis.py）",
                          "status": "[待确认·需 LLM]"},
        "boundary": ("指标语义层/KPI/术语表超范围未实现；NL 只调 L1/L2、不绕置信/存疑门、"
                     "不生成无出处关系（graphrag/nl 与 semantic 测试锁定）。"),
        "smoke_sample": {
            "query": sample["query"], "intent": sample["intent"], "uc": sample["uc"],
            "refused": sample["refused"],
        },
    }
    if write:
        _write_dual(EVAL_HANDOFF, payload)
    return payload


def run(write: bool = True) -> dict:
    a = isolated_a_tables(write=write)
    b = mixed_cluster_note(write=write)
    c = eval_handoff(write=write)
    return {"isolated_a": a, "mixed_cluster_note": b, "eval_handoff": c}


if __name__ == "__main__":
    r = run(write=True)
    print(json.dumps({
        "P2-A isolated_a_count": r["isolated_a"]["isolated_a_count"],
        "P2-A coverage": r["isolated_a"]["coverage_equation"],
        "P2-A m3_crosscheck": r["isolated_a"]["m3_crosscheck_count"],
        "P2-B mixed_communities": r["mixed_cluster_note"]["n_mixed_communities"],
        "P2-B note": MIXED_CLUSTER_NOTE,
        "P2-C intents": len(r["eval_handoff"]["nl_entry_api"]["intent_coverage"]),
        "P2-C llm_enabled": r["eval_handoff"]["llm_synthesis"]["enabled"],
    }, ensure_ascii=False, indent=2))
