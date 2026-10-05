"""M4 · 端到端六类 UC 自然语言入口演示（确定性作答；供 rag-eval-gate 消费入口）。

运行：`python3 -m graphrag.nl.demo`（仓库根）。对每条自然语言查询打印：
意图/UC、命中的门、抽取到的参数、是否拒绝、结构化要点、出处样本，并显式声明
LLM 合成默认禁用 + 关系为推断非物理外键。**不生成任何未 grounding 的关系。**

黄金集题目实例与阈值判定归 `rag-eval-gate`（`graphrag/eval/golden/`）；本模块只保证
`NLRouter().answer(query, **overrides)` 入口稳定可复现（同输入同输出）。
"""

from __future__ import annotations

import json

from .nl_router import NLRouter
from . import llm_synthesis

# 六类 UC（+ 全局枢纽/耦合）自然语言入口样例
SAMPLES = [
    ("UC1 查表", "哪些表存贸易商额度？"),
    ("UC2 查字段", "jf_sales_order 有哪些字段？"),
    ("UC3 查关系", "jf_customer 关联哪些表？"),
    ("UC4 影响", "改了 jf_product 会影响哪些表？"),
    ("UC5 血缘", "jf_receivable_write_off 的血缘来自哪些表？"),
    ("UC6 社区/域综述", "D14 域的概览与质量问题？"),
    ("全局 枢纽", "全库最枢纽的表是哪些？"),
    ("全局 耦合", "哪些社区/域之间耦合最紧？"),
    ("边界(拒绝)", "查询不存在表 zzz_not_a_table 的关系"),
    ("超范围(指标)", "本月 GMV 指标的口径是什么？"),
]


def _summary(ans: dict) -> dict:
    """把结构化答案压缩成可读要点（确定性字段；不引入自由文本）。"""
    a = ans.get("answer")
    out = {}
    if a is None:
        out["answer"] = ans.get("note", "无")
        return out
    if ans["intent"] == "find_table":
        out["tables"] = [h["name"] for h in a["results"][:5]]
    elif ans["intent"] == "describe_column":
        if "columns" in a:
            out["table"] = a.get("table")
            out["column_count"] = a.get("column_count")
            out["sample"] = [c["name"] for c in a.get("columns", [])[:6]]
        elif "detail" in a:
            out["column"] = a.get("column")
            out["meaning"] = (a["detail"] or {}).get("semantic")
        else:
            out["exact_tables"] = a.get("exact_tables", [])[:8]
    elif ans["intent"] == "find_relations":
        out["table"] = a.get("table")
        out["relation_count"] = a.get("count")
        out["sample"] = [(r["other"], r["confidence"], r["evidence_level"])
                         for r in a.get("relations", [])[:5]]
    elif ans["intent"] == "impact_lineage":
        out["table"] = a.get("table")
        out["direction"] = ans.get("direction_semantics")
        out["reached_tables"] = a.get("reached_tables")
        out["edges"] = a.get("edge_traversed")
        out["sample"] = [(p["to"], p["hop"], p["confidence"])
                         for p in a.get("paths", [])[:5]]
    elif ans["intent"] == "community_rollup":
        s = a["l1_domain_summary"]
        r = a.get("l2_community_rollup") or {}
        out["domain"] = a["domain"]
        out["domain_name"] = a["domain_name"]
        out["table_count"] = s.get("table_count")
        out["issue_count"] = s.get("issue_count")
        out["community_distribution"] = r.get("community_distribution")
        out["isolated"] = r.get("isolated_tables")
    elif ans["intent"] == "hub_coupling":
        if "top_hubs" in a:
            out["top_hubs"] = [(h["table"], h["weighted_degree"])
                               for h in a["top_hubs"][:5]]
        tc = a.get("tightest_couplings")
        if tc:
            out["cross_domain_top"] = tc["cross_domain_coupling"][:3]
            out["cross_community_top"] = [
                (c["communities"], c["total_weight"])
                for c in tc["cross_community_coupling"][:3]]
    return out


def main() -> None:
    router = NLRouter()
    print("=" * 78)
    print("M4 · 规则式 NL 编排前端 端到端六类查询（确定性作答）")
    print("门默认：confidence≥0.45 / show_uncertain=False / max_hops≤3")
    print(f"LLM 合成：ENABLED={llm_synthesis.ENABLED}（[待确认·需 LLM]，直接作答不生成关系）")
    print("免责声明：关系/血缘为逆向推断、非物理外键（全库 0 FK）；证据级最高 comment_explicit")
    print("=" * 78)
    results = []
    for label, q in SAMPLES:
        ans = router.answer(q)
        summ = _summary(ans)
        rec = {
            "label": label, "query": q, "intent": ans["intent"], "uc": ans["uc"],
            "params": {k: v for k, v in ans["matched_params"].items() if v},
            "gates": ans["gates"], "refused": ans["refused"],
            "n_sources": len(ans["sources"]), "summary": summ,
        }
        results.append(rec)
        print(f"\n[{label}] “{q}”")
        print(f"  → intent={ans['intent']}  UC={ans['uc']}  "
              f"refused={ans['refused']}  sources={len(ans['sources'])}")
        if ans.get("matched_params", {}).get("tables"):
            print(f"  · 命中表={ans['matched_params']['tables']}")
        if ans.get("resolved_via"):
            print(f"  · 表定位方式={ans['resolved_via']}")
        if ans.get("direction_semantics"):
            print(f"  · 方向={ans['direction_semantics']}")
        print(f"  · 要点={json.dumps(summ, ensure_ascii=False)}")
        if ans["refused"]:
            print(f"  · [拒绝臆造] {ans.get('answer') if isinstance(ans.get('answer'), str) else ans.get('note','未定位/无路径 → [待确认]')}")

    # 指标/KPI 超范围检查（入口不识别、不作答关系/血缘，仅可能落到 find_table 且无实体）
    metric = results[-1]
    print("\n" + "=" * 78)
    print("超范围声明（诚实边界）：")
    print("  · 语义层=结构语义（域/实体三分类/字段 COMMENT/D14 枚举），**不含指标语义层**。")
    print("  · 指标/KPI/术语表：er-model+DDL 无来源 → 不实现、[待确认·需放宽输入]。")
    print(f"  · 指标类查询落点：intent={metric['intent']} refused={metric['refused']}"
          "（不生成指标口径，仅关键词兜底，无出处不编造）。")
    print("=" * 78)
    print(f"完成：{len(results)} 条自然语言查询全部经规则式 L1/L2 确定性作答。")
    return results


if __name__ == "__main__":
    main()
