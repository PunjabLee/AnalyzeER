"""M4 · 黄金集评测 harness（rag-eval-gate 唯一持有；执行专家只读）。

依据 `graphrag/spec/eval-baseline.md` §2（确定性基线测法）+ §2.2（度量口径）。
经 `graphrag.nl.nl_router.NLRouter().answer(...)` 端到端跑六类 UC + 全局 + 超范围守卫，
记录 **Top-3 命中率 / 综合命中率 / 拒答正确率 / 单-多跳 P95 时延 / 幻觉（无出处关系）计数**。

口径纪律（不擅自设标）：
- 阈值（Top-3≥90%、P95<2s/<8s）均 `[待确认]` → 本 harness **只报实测值，不判达标**。
- 命中口径见 `golden/manifest.json.metric_taxonomies`：top3 / reachable(≤3 跳) / coverage / refused / oos_refused。
- **独立回查**：UC3/UC4/UC5 返回的每条关系/路径边必须在 M1 图 `l0_graph.json` 真实邻接存在
  且带 `source_file+quote_hash`；否则计入幻觉（期望 0）。
- **全局耦合对的回查基准（终轮 CodeReview P1-2 整改，双源已消除）**：不再读磁盘
  `community_edges.jsonl` 当判据（旧法＝"实时算的耦合对 比 可能陈旧的磁盘 kept 集"，磁盘那份
  一旦未随上游重跑就静默失真：假 FAIL，或更糟的假 PASS）。现由 `kept_source.resolve(router)`
  供给三档**实时同源**基准：①与 `router.l2()` **同一次** `build_subgraph()` 捕获的 kept 集
  （判据）＋②独立重算交叉相等（自洽佐证）＋③eval 自行从原始 M1 `l0_graph.json` RELATES_TO
  邻接与 M2 `lineage_edges.jsonl` REFERENCES 重推的**独立实物出处集**（防"聚合层自洽却无出处"）。
  磁盘那份降为**陈旧对照**（只报 drift），其指纹/摘要由 `graphrag/eval/kept_pairs_sidecar.json`
  承载（JSONL 不得内嵌顶层键，M3 护栏锁定）。
- **P1-2 指纹门**：消费 `community_*` JSON 产物处调用 `semantic.fingerprint.verify_json_file`
  /`extract_fingerprint`（见 `kept_source.fingerprint_gate`）。**strict-on-enable 保持 dormant**：
  默认不注入 expected 基准 → `observed_no_baseline` 放行留痕；`export
  GRAPHRAG_EXPECTED_INPUT_FINGERPRINT=<digest>` 后，字段缺失或不一致即 `InputFingerprintMismatch`
  中断评测（由编排者/门禁激活，本 agent 不擅自打开）。另有**恒开**的 freshness 判定：拿产物
  声明的输入清单独立重算 sha256 比对，不需基准即可判"磁盘那份是否出自当前输入"。

复现：`python -m graphrag.eval.run_golden`（两次逐 item 命中向量应完全一致 → 确定性）。
附加开关：`-q` 静默逐题表；`--no-sidecar` 不落 `kept_pairs_sidecar.json`（内容确定，两次逐字节同）。
"""

from __future__ import annotations

import json
import time
from pathlib import Path

from ..ingest.config import L0_GRAPH_JSON, CONFIDENCE_DEFAULT_MIN, MAX_HOPS
from ..store.loader import load_graph_json
from ..nl.nl_router import NLRouter
from . import kept_source

GOLDEN = Path(__file__).resolve().parent / "golden" / "golden_set.jsonl"

SINGLE_HOP_INTENTS = {"find_table", "describe_column", "find_relations"}


def load_items() -> list[dict]:
    return [json.loads(ln) for ln in GOLDEN.read_text(encoding="utf-8").splitlines() if ln.strip()]


def _rel_adj(g, table):
    tid = f"table:{table}"
    nb = set()
    for _, e in g.neighbors(tid, "RELATES_TO", direction="out"):
        nb.add(e["dst"].split(":", 1)[1])
    for _, e in g.neighbors(tid, "RELATES_TO", direction="in"):
        nb.add(e["src"].split(":", 1)[1])
    return nb


def get_view(ans, view):
    """把 NLRouter 结构化答案映射为该意图的相关性排序列表。"""
    if not isinstance(ans, dict):
        return []
    if view == "table_results":
        return [x["name"] for x in ans.get("results", [])]
    if view == "relation_others":
        return [x["other"] for x in ans.get("relations", [])]
    if view == "reached_tables":
        d = ans.get("direction", "out")
        seq, seen = [], set()
        for p in ans.get("paths", []):
            n = p["to"]                      # 发现邻居恒在 to（out=下游 / in=上游）
            if n not in seen:
                seen.add(n)
                seq.append(n)
        return seq
    if view == "column_names":
        return [c["name"] for c in ans.get("columns", [])]
    if view == "exact_tables":
        return list(ans.get("exact_tables", []))
    if view == "domain_tables":
        s = (ans.get("l1_domain_summary") or {})
        return [t["name"] for t in s.get("tables", [])]
    if view == "hub_tables":
        return [h["table"] for h in ans.get("top_hubs", [])]
    if view == "coupling_pairs":
        tc = ans.get("tightest_couplings", {})
        return ["|".join(sorted(c["domains"])) for c in tc.get("cross_domain_coupling", [])]
    return []


def evaluate(item, res, g, adj_cache, kept_pairs):
    """返回 dict(hit, reason)。hit 口径按 item['metric']。"""
    metric = item["metric"]
    ans = res.get("answer")
    a = res.get("answer") if isinstance(res.get("answer"), dict) else {}

    if metric == "oos_refused":
        ok = res["intent"] == "out_of_scope" and res["refused"] is True and res["answer"] is None
        return {"hit": ok, "reason": f"intent={res['intent']} refused={res['refused']}"}

    if metric == "refused":
        ok = res["refused"] is True and res["sources"] == []
        return {"hit": ok, "reason": f"refused={res['refused']} n_src={len(res['sources'])}"}

    view = get_view(a, item["view"])

    if metric == "top3":
        top3 = view[:3]
        hit = any(x in top3 for x in item["gold"])
        return {"hit": hit, "reason": f"top3={top3} gold={item['gold']}"}

    if metric == "reachable":
        # gold ∈ 可达集且实际跳数 ≤ max_hops（预算护栏，eval-baseline §2.2）
        hops = {}
        for p in a.get("paths", []):
            n = p["to"]
            hops.setdefault(n, p["hop"])
        budget = item["assert"].get("max_hops", MAX_HOPS)
        hit = all(n in hops and hops[n] <= budget for n in item["gold"])
        maxh = max((p["hop"] for p in a.get("paths", [])), default=0)
        goldhops = {n: hops.get(n) for n in item["gold"]}
        return {"hit": hit, "reason": f"gold@hop={goldhops} max_hop={maxh}<={budget}"}

    if metric == "coverage":
        aset = set(view)
        gold_ok = all(x in aset for x in item["gold"])
        cnt_ok = True
        if "count" in item["assert"]:
            cnt_ok = a.get("column_count") == item["assert"]["count"]
        if "table_count" in item["assert"]:
            cnt_ok = (a.get("l1_domain_summary") or {}).get("table_count") == item["assert"]["table_count"]
        iss_ok = True
        if "domain_issues" in item["assert"]:
            iss_ok = set(item["assert"]["domain_issues"]) <= set((a.get("l1_domain_summary") or {}).get("issues", []))
        mc_ok = True
        if "min_count" in item["assert"]:
            mc_ok = len(view) >= item["assert"]["min_count"]
        hit = gold_ok and cnt_ok and iss_ok and mc_ok
        return {"hit": hit, "reason": f"gold_in_set={gold_ok} count_ok={cnt_ok} issues_ok={iss_ok}"}

    return {"hit": False, "reason": f"未知 metric={metric}"}


def provenance_check(item, res, g, adj_cache, base):
    """独立回查 UC3/UC4/UC5/全局 返回的关系是否为图内真实边且有出处（幻觉计数）。

    `base` = `kept_source.resolve()` 的产物：`kept_pairs`（与 l2() 同源实时集）＋
    `grounding_pairs`/`grounding_provenance`（原始 M1/M2 独立实物出处）。磁盘快照不参与判定。
    """
    violations = []
    a = res.get("answer")
    if not isinstance(a, dict):
        return violations
    if item["metric"] in ("top3", "reachable") and item["uc"] in ("UC3", "UC4", "UC5"):
        if item["uc"] == "UC3":
            for r in a.get("relations", []):
                if r["confidence"] < CONFIDENCE_DEFAULT_MIN or r["has_uncertain"]:
                    violations.append(("gate", r["other"]))
                if not (r["source_file"] and r["quote_hash"]):
                    violations.append(("no_src", r["other"]))
                # 边存在性按真实端点 (src,dst) 回查（other 是查询表的对端，方向无关）
                if r["dst"] not in adj_cache.setdefault(r["src"], _rel_adj(g, r["src"])):
                    violations.append(("dangling", (r["src"], r["dst"])))
        else:  # UC4/UC5 paths
            for p in a.get("paths", []):
                if p["confidence"] < CONFIDENCE_DEFAULT_MIN:
                    violations.append(("gate", (p["from"], p["to"])))
                if not (p["source_file"] and p["quote_hash"]):
                    violations.append(("no_src", (p["from"], p["to"])))
                if p["to"] not in adj_cache.setdefault(p["from"], _rel_adj(g, p["from"])):
                    violations.append(("dangling", (p["from"], p["to"])))
    if item["uc"] == "GLOBAL":
        for h in a.get("top_hubs", []):
            for pv in h.get("sample_edge_provenance", []):
                if not pv.get("file"):
                    violations.append(("hub_no_src", h["table"]))
        kept_pairs = base["kept_pairs"]
        grounded = base.get("grounding_pairs", set())
        prov_map = base.get("grounding_provenance", {})
        tc = a.get("tightest_couplings", {})
        for cp in tc.get("cross_community_coupling", []):
            for pair in cp.get("sample_table_pairs", []):
                key = frozenset((pair["src"], pair["dst"]))
                if key not in kept_pairs:
                    # 与答案同源的 kept 集都不认这条耦合 → 真·无据（非陈旧文件误报）
                    violations.append(("coupling_not_in_live_kept", (pair["src"], pair["dst"])))
                if key not in grounded:
                    # 聚合层自洽还不够：必须能落到一条原始 M1/M2 实物边
                    violations.append(("coupling_ungrounded", (pair["src"], pair["dst"])))
                elif not any(p.get("file") and p.get("quote_hash") for p in prov_map.get(key, [])):
                    violations.append(("coupling_no_provenance", (pair["src"], pair["dst"])))
    return violations


def run(verbose: bool = True, write_sidecar: bool = True) -> dict:
    t_start = time.perf_counter()
    items = load_items()
    router = NLRouter()
    g = load_graph_json(L0_GRAPH_JSON)
    # P1-2：耦合对回查基准＝与 router.l2() **同源**的实时 kept 集（+ 独立实物出处集）；
    # 磁盘 community_edges.jsonl 只作陈旧/drift 对照，且其摘要落 sidecar 留痕。
    # ⚠ 时延口径变更（如实登记）：l2() 现于**评测循环前**预热（同源供给所需），故逐题时延
    #   不再含 M3 全局分析的一次性开销；该开销改由 `l2_warmup_ms` 单独披露（见 latency 块）。
    t_w0 = time.perf_counter()
    base = kept_source.resolve(router, write_sidecar=write_sidecar)
    l2_warmup_ms = round((time.perf_counter() - t_w0) * 1000, 2)
    kept_pairs = base["kept_pairs"]
    adj_cache: dict = {}

    rows = []
    lat_single, lat_multi = [], []
    hall_total = 0
    for it in items:
        t0 = time.perf_counter()
        res = router.answer(it["query_nl"])       # 端到端：不强制 intent，测真实 NL→结构化
        dt = time.perf_counter() - t0
        ev = evaluate(it, res, g, adj_cache, kept_pairs)
        viol = provenance_check(it, res, g, adj_cache, base)
        hall_total += len(viol)
        if it["uc"] in ("UC4", "UC5", "UC6", "GLOBAL"):
            lat_multi.append(dt)
        else:
            lat_single.append(dt)
        rows.append({
            "id": it["id"], "uc": it["uc"], "intent": res["intent"],
            "metric": it["metric"], "gold": it["gold"],
            "refused": res["refused"], "hit": ev["hit"], "reason": ev["reason"],
            "intent_match": (res["intent"] == it["intent"]),
            "prov_violations": len(viol), "lat_ms": round(dt * 1000, 2),
        })

    # —— 汇总 ——
    def p95(xs):
        if not xs:
            return None
        xs = sorted(xs)
        import math
        return round(xs[min(len(xs) - 1, math.ceil(0.95 * len(xs)) - 1)] * 1000, 1)

    top3_items = [r for r in rows if r["metric"] == "top3"]
    nonboundary = [r for r in rows if r["metric"] in ("top3", "reachable", "coverage")]
    boundary = [r for r in rows if r["metric"] in ("refused", "oos_refused")]

    by_uc = {}
    for r in nonboundary:
        b = by_uc.setdefault(r["uc"], {"n": 0, "hit": 0})
        b["n"] += 1
        b["hit"] += int(r["hit"])

    summary = {
        "items": len(rows),
        "top3_strict": {
            "n": len(top3_items),
            "hit": sum(int(r["hit"]) for r in top3_items),
            "rate_pct": _pct(sum(int(r["hit"]) for r in top3_items), len(top3_items)),
            "threshold": ">=90% [待确认]（只报实测，不判达标）",
        },
        "overall_hit": {
            "n": len(nonboundary),
            "hit": sum(int(r["hit"]) for r in nonboundary),
            "rate_pct": _pct(sum(int(r["hit"]) for r in nonboundary), len(nonboundary)),
            "note": "含 top3+reachable+coverage 三类命中口径",
        },
        "refusal_accuracy": {
            "n": len(boundary),
            "hit": sum(int(r["hit"]) for r in boundary),
            "rate_pct": _pct(sum(int(r["hit"]) for r in boundary), len(boundary)),
        },
        "per_uc_hit": {uc: {**v, "rate_pct": _pct(v["hit"], v["n"])} for uc, v in sorted(by_uc.items())},
        "hallucination": {
            "ungrounded_or_gate_violations": hall_total,
            "expected": 0,
            "note": ("UC3/UC4/UC5 关系/路径逐条回查 M1 图邻接 + quote_hash + conf≥0.45；"
                     "全局耦合逐条回查**与 l2() 同源实时 kept 集** ∧ **原始 M1/M2 独立实物出处**"
                     "（磁盘快照已降为陈旧对照，不作判据 → P1-2 双源消除）"),
        },
        "coupling_recheck_baseline": {
            k: v for k, v in base.items()
            if k not in ("kept_pairs", "grounding_pairs", "grounding_provenance")},
        "latency_ms_p95": {"single_hop": p95(lat_single), "multi_hop_global": p95(lat_multi),
                           "threshold": "单跳<2000 / 多跳<8000 [待确认]",
                           "l2_warmup_ms": l2_warmup_ms,
                           "harness_total_ms": round((time.perf_counter() - t_start) * 1000, 2),
                           "scope_note": ("逐题时延**不含** l2() 一次性预热（P1-2 同源供给需在"
                                          "循环前完成，与旧版「首题含全局分析」口径不同）；"
                                          "l2_warmup_ms/harness_total_ms 为一次性/整体开销披露。"
                                          "本块三个字段均为**观测值**（非判定量），两次运行会因"
                                          "缓存冷热浮动，命中向量与判定项不受影响。")},
        "intent_classification": {
            "n": len(rows),
            "match": sum(int(r["intent_match"]) for r in rows),
            "mismatches": [r["id"] for r in rows if not r["intent_match"]],
        },
    }
    if verbose:
        _print(rows, summary)
    return {"rows": rows, "summary": summary}


def _pct(a, b):
    return round(100.0 * a / b, 1) if b else None


def _print(rows, s):
    print(f"{'id':8s} {'uc':6s} {'intent':16s} {'metric':10s} {'hit':4s} reason")
    for r in rows:
        print(f"{r['id']:8s} {r['uc']:6s} {r['intent']:16s} {r['metric']:10s} "
              f"{'HIT' if r['hit'] else 'MISS':4s} {r['reason']}")
    print("\n=== COUPLING RECHECK BASELINE (P1-2 同源) ===")
    b = s["coupling_recheck_baseline"]
    print(f"authoritative : {b['authoritative_source']}")
    print(f"live          : {b['live']}")
    print(f"recompute     : {b['recompute_independent_build_subgraph']}")
    print(f"grounding     : {b['grounding_from_raw_upstream']}")
    print(f"disk(对照)    : {b['disk_snapshot_for_drift_only']}")
    print(f"fingerprint   : " + json.dumps(
        {n: f"{r['verify']['status']}/{r['freshness']}"
         for n, r in b["fingerprint_gate"].items()}, ensure_ascii=False))
    print(f"dormant       : {b['dormant']}")
    if "sidecar" in b:
        print(f"sidecar       : {b['sidecar']}")
    print("\n=== SUMMARY ===")
    print(json.dumps(s, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    import sys
    res = run(verbose="-q" not in sys.argv, write_sidecar="--no-sidecar" not in sys.argv)
    # 供复现：把逐 item 命中向量打印到 stdout（两次运行 diff 应空）
    vec = "".join("1" if r["hit"] else "0" for r in res["rows"])
    print("\nHIT_VECTOR:", vec)
