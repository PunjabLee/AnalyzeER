"""M3 · 全局 map-reduce 问答的**确定性版**（无 LLM）。

对应 `design-plan.md` §8.1-M3 的全局 sensemaking 三类问题，以**纯结构遍历**回答，
每条结论可回溯到具体表/边 + `er-model` 出处（§4.2/R6：绝不生成来源外的实体/关系）：

1. **全库最枢纽实体 TopN**：加权度（Σ 边 weight=Σ confidence）+ betweenness 排序，
   附所在社区/域、邻接样本与边溯源（quote_hash/file）。
2. **域间最紧耦合对**：跨社区边按「社区对总权重」聚合排序（含成员表对清单回溯）。
   另给「跨域耦合」视角：按域对聚合的跨域边权重。
3. **按域(UC6)结构化综述**：每个 00 域 → 表数/入图数、所属社区分布、内/外边、
   域内枢纽、关联 Issue 数（经 HAS_ISSUE），全部结构化字段、确定性。

map-reduce 语义：map=逐社区/逐域聚合；reduce=跨社区排序/归并。全程无自由文本生成。
"""

from __future__ import annotations

import json
from collections import defaultdict

import networkx as nx

from .communities import build_subgraph, detect_communities, community_metrics


# ---------------------------------------------------------------- 枢纽 TopN
def global_hubs(G: nx.Graph, member_of: dict[str, int], domain_map: dict[str, str],
                top: int = 10) -> list[dict]:
    wdeg = {n: round(sum(d.get("weight", 0.0) for _, _, d in G.edges(n, data=True)), 6)
            for n in G.nodes}
    btw = nx.betweenness_centrality(G, weight="weight", normalized=True)
    order = sorted(G.nodes, key=lambda n: (-wdeg[n], -btw[n], n))[:top]
    out = []
    for n in order:
        prov = []
        for _, _, d in G.edges(n, data=True):
            for p in d.get("provenance", [])[:1]:
                prov.append({"rel": p.get("rel"), "file": p.get("file"),
                             "quote_hash": p.get("quote_hash")})
            if len(prov) >= 3:
                break
        out.append({
            "table": n, "domain": domain_map.get(n),
            "community_id": member_of.get(n),
            "weighted_degree": wdeg[n], "degree": G.degree(n),
            "betweenness": round(btw[n], 6),
            "sample_edge_provenance": prov,
        })
    return out


# ---------------------------------------------------------------- 最紧耦合对
def tightest_couplings(G: nx.Graph, member_of: dict[str, int],
                       domain_map: dict[str, str], top: int = 10) -> dict:
    """跨社区边 → (社区对总权重) + (域对跨域权重)，各取 Top。"""
    pair_w: dict[tuple[int, int], float] = defaultdict(float)
    pair_edges: dict[tuple[int, int], list[dict]] = defaultdict(list)
    dom_w: dict[tuple[str, str], float] = defaultdict(float)
    for u, v, d in G.edges(data=True):
        cu, cv = member_of[u], member_of[v]
        key = (min(cu, cv), max(cu, cv))
        if cu != cv:                                   # 跨社区
            pair_w[key] += d.get("weight", 0.0)
            pair_edges[key].append({"src": u, "dst": v, "weight": d.get("weight"),
                                    "cross_domain": d.get("cross_domain", False)})
        du, dv = domain_map.get(u), domain_map.get(v)
        if du != dv:                                   # 跨域
            dkey = (min(du, dv, key=_dk), max(du, dv, key=_dk))
            dom_w[dkey] += d.get("weight", 0.0)

    cross_comm = [{"communities": list(k), "total_weight": round(w, 6),
                   "n_bridge_edges": len(pair_edges[k]),
                   "sample_table_pairs": sorted(pair_edges[k],
                                                key=lambda x: -(x["weight"] or 0))[:5]}
                  for k, w in sorted(pair_w.items(), key=lambda kv: (-kv[1], kv[0]))[:top]]
    cross_dom = [{"domains": list(k), "cross_domain_weight": round(w, 6)}
                 for k, w in sorted(dom_w.items(), key=lambda kv: (-kv[1], kv[0]))[:top]]
    return {"cross_community_coupling": cross_comm, "cross_domain_coupling": cross_dom}


def _dk(dom: str) -> int:
    return int(dom[1:]) if dom.startswith("D") and dom[1:].isdigit() else 999


# ---------------------------------------------------------------- 按域综述(UC6)
def domain_rollup(G: nx.Graph, communities, metrics, domain_map: dict[str, str],
                  issue_map: dict[str, list[str]]) -> list[dict]:
    """每 00 域的结构化综述：成员、社区分布、内/外边、域内枢纽、Issue。"""
    member_of = {n: i for i, c in enumerate(communities) for n in c}
    internal, external = defaultdict(int), defaultdict(int)
    wdeg = {n: sum(d.get("weight", 0.0) for _, _, d in G.edges(n, data=True)) for n in G.nodes}
    for u, v, d in G.edges(data=True):
        du, dv = domain_map.get(u), domain_map.get(v)
        if du == dv:
            internal[du] += d.get("n_edges", 1)
        else:
            external[du] += d.get("n_edges", 1)
            external[dv] += d.get("n_edges", 1)

    # 域 → 成员（含孤立点：有域但不在 G）
    dom_members: dict[str, list[str]] = defaultdict(list)
    for t, dm in domain_map.items():
        dom_members[dm].append(t)

    out = []
    for dm in sorted(dom_members, key=_dk):
        members = sorted(dom_members[dm])
        in_graph = [t for t in members if t in G]
        comm_dist = defaultdict(int)
        for t in in_graph:
            comm_dist[str(member_of[t])] += 1
        hubs = sorted(in_graph, key=lambda n: (-wdeg[n], n))[:3]
        issues = sorted({i for t in members for i in issue_map.get(t, [])})
        out.append({
            "domain": dm,
            "tables_declared": len(members),
            "tables_in_community_graph": len(in_graph),
            "isolated_tables": len(members) - len(in_graph),
            "community_distribution": dict(sorted(comm_dist.items(), key=lambda kv: int(kv[0]))),
            "internal_edges": internal[dm],
            "external_edges": external[dm],
            "top_hub_tables": [{"table": h, "weighted_degree": round(wdeg[h], 6)} for h in hubs],
            "issue_ids": issues,
            "issue_count": len(issues),
        })
    return out


# ---------------------------------------------------------------- 主入口
def _issue_map_from(graph_nodes) -> dict[str, list[str]]:
    """从 l0 快照读 HAS_ISSUE（table→issue）。社区子图不含 Issue 节点，单独回载。"""
    from ..store.loader import load_graph_json
    from ..ingest.config import L0_GRAPH_JSON
    g = load_graph_json(L0_GRAPH_JSON)
    tab = {nid: n["name"] for nid, n in g.nodes.items() if n["label"] == "Table"}
    iss = {nid: n["issue_id"] for nid, n in g.nodes.items() if n["label"] == "Issue"}
    m: dict[str, list[str]] = defaultdict(list)
    for e in g.edges:
        if e["type"] == "HAS_ISSUE" and e["src"] in tab and e["dst"] in iss:
            m[tab[e["src"]]].append(iss[e["dst"]])
    return dict(m)


def global_analysis(write: bool = True, top: int = 10) -> dict:
    G, kept, exclusion, stats, meta_full, domain_map = build_subgraph()
    communities = detect_communities(G)
    metrics = community_metrics(G, communities)
    member_of = {n: i for i, c in enumerate(communities) for n in c}
    im = _issue_map_from(None)

    result = {
        "milestone": "M3",
        "generator": "graphrag/community/global_analysis.py",
        "method": "确定性 map-reduce（结构遍历，无 LLM 自由文本；结论可回溯到表/边）",
        "disclaimer": ("关系/血缘为逆向推断、非物理外键；社区=分析视图，00 手工 18 域为权威。"),
        "edge_gate": {"min_confidence": 0.45,
                      "references_visible_baseline": meta_full["references_loaded"],
                      "low_conf_or_uncertain_in_algorithm":
                          sum(1 for r in kept if r["confidence"] < 0.45
                              or r["evidence_level"] == "unconfirmed")},
        "q1_top_hubs": global_hubs(G, member_of, domain_map, top=top),
        "q2_tightest_couplings": tightest_couplings(G, member_of, domain_map, top=top),
        "q3_domain_rollup_uc6": domain_rollup(G, communities, metrics, domain_map, im),
        "authoritative_domain_source": "00 §四 (M1 graph node.domain)",
        "community_role": "analysis_view_only",
    }
    if write:
        from .communities import DATA_META_DIR, OUT_DIR
        payload = json.dumps(result, ensure_ascii=False, indent=2)
        (DATA_META_DIR / "community_global_analysis.json").write_text(payload, encoding="utf-8")
        (OUT_DIR / "community_global_analysis.json").write_text(payload, encoding="utf-8")
    return result


if __name__ == "__main__":
    r = global_analysis(write=True)
    print(json.dumps({
        "top_hubs": [(h["table"], h["weighted_degree"]) for h in r["q1_top_hubs"][:5]],
        "cross_domain_top": r["q2_tightest_couplings"]["cross_domain_coupling"][:5],
        "leak": r["edge_gate"]["low_conf_or_uncertain_in_algorithm"],
    }, ensure_ascii=False, indent=2))
