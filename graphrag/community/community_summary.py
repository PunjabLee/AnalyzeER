"""M3 · 结构化社区画像（确定性生成，**无 LLM 自由文本**）。

对每个涌现社区产出机器可校验的结构化字段（供 UC6「按业务域检索」与全局综述消费）：
- 成员表、规模；
- 主导域（by 成员 domain 众数）+ 域纯度 + 域构成；
- top 关系/枢纽表：by **加权度**（Σ 边 weight=Σ confidence）与 **betweenness**（介数中心性，
  networkx 确定性，normalized、无随机采样）；
- 跨域桥接表：把社区连向其它社区的成员（外联权重排序）+ 跨域边样本。

**溯源纪律（§4.2/R6）**：所有表/关系均直接来自融合图节点与 `kept` 边的 provenance
（quote_hash/file），不新建来源没有的实体/关系；无法定位者不写（不臆造）。
"""

from __future__ import annotations

import json
from collections import defaultdict

import networkx as nx

from .communities import build_subgraph, detect_communities, community_metrics
from . import source_fingerprint

_BRIDGE_TOP = 8      # 桥接表展示上限（确定性截尾）
_HUB_TOP = 5         # 每社区枢纽展示上限


def _member_of(communities: list[frozenset[str]]) -> dict[str, int]:
    m: dict[str, int] = {}
    for i, c in enumerate(communities):
        for n in c:
            m[n] = i
    return m


def _weighted_degree(G: nx.Graph) -> dict[str, float]:
    return {n: round(sum(d.get("weight", 0.0) for _, d in G.adj[n].items()), 6)
            for n in G.nodes}


def _top_hubs(G: nx.Graph, members: frozenset[str], wdeg: dict[str, float],
              btw: dict[str, float]) -> list[dict]:
    """社区内枢纽候选：加权度降序、介数次之、表名稳定打破。"""
    rows = sorted(members, key=lambda n: (-wdeg.get(n, 0.0), -btw.get(n, 0.0), n))[:_HUB_TOP]
    return [{"table": n,
             "weighted_degree": wdeg.get(n, 0.0),
             "degree": G.degree(n),
             "betweenness": round(btw.get(n, 0.0), 6)} for n in rows]


def _dom_sort_key(dom: str) -> int:
    """众数并列时按域号稳定打破（D01<…<D18<OT）。"""
    if dom.startswith("D") and dom[1:].isdigit():
        return int(dom[1:])
    return 999


def profile_communities(G: nx.Graph, communities: list[frozenset[str]],
                        metrics: list[dict], domain_map: dict[str, str]) -> list[dict]:
    """基于已构建的融合图与社区划分，产出结构化画像列表（纯函数、可复现）。"""
    member_of = _member_of(communities)
    wdeg = _weighted_degree(G)
    btw = nx.betweenness_centrality(G, weight="weight", normalized=True)

    profiles: list[dict] = []
    for m in metrics:
        cid = m["community_id"]
        members = frozenset(m["members"])

        # 主导域 + 纯度 + 构成
        dom_counts: dict[str, int] = defaultdict(int)
        for t in members:
            dom_counts[domain_map.get(t, "UNKNOWN")] += 1
        dominant = max(dom_counts.items(), key=lambda kv: (kv[1], -_dom_sort_key(kv[0])))
        purity = round(dominant[1] / len(members), 4)

        # 跨社区/跨域桥接（把本社区连向其它社区的成员）
        bridge_rows: dict[str, dict] = {}
        cross_domain_edges = 0
        for u in members:
            for v, d in G.adj[u].items():
                if member_of[v] == cid:
                    continue
                rec = bridge_rows.setdefault(
                    u, {"ext_weight": 0.0, "ext_edges": 0,
                        "to_communities": set(), "to_domains": set(), "sample_prov": []})
                rec["ext_weight"] += d.get("weight", 0.0)
                rec["ext_edges"] += d.get("n_edges", 1)
                rec["to_communities"].add(member_of[v])
                rec["to_domains"].add(domain_map.get(v, "UNKNOWN"))
                if d.get("cross_domain"):
                    cross_domain_edges += 1
                for p in d.get("provenance", [])[:1]:
                    rec["sample_prov"].append(p)
        bridges = sorted(
            ({"table": u, "ext_weight": round(r["ext_weight"], 6),
              "ext_edges": r["ext_edges"],
              "to_communities": sorted(r["to_communities"]),
              "to_domains": sorted(r["to_domains"]),
              "sample_provenance": r["sample_prov"][:3]}
             for u, r in bridge_rows.items()),
            key=lambda x: (-x["ext_weight"], x["table"]))[:_BRIDGE_TOP]

        profiles.append({
            "community_id": cid,
            "size": m["size"],
            "members": sorted(members),
            "dominant_domain": dominant[0],
            "domain_purity": purity,
            "domain_profile": dict(sorted(dom_counts.items())),
            "internal_edges": m["internal_edges"],
            "external_edges": m["external_edges"],
            "internal_external_ratio": m["internal_external_ratio"],
            "density": m["density"],
            "cross_domain_edges": cross_domain_edges,
            "top_hubs": _top_hubs(G, members, wdeg, btw),
            "bridges": bridges,
        })
    return profiles


def run(write: bool = True) -> dict:
    G, kept, exclusion, stats, meta_full, domain_map = build_subgraph()
    communities = detect_communities(G)
    metrics = community_metrics(G, communities)
    profiles = profile_communities(G, communities, metrics, domain_map)
    out = {
        "milestone": "M3",
        "generator": "graphrag/community/community_summary.py",
        "note": "结构化社区画像（确定性，无 LLM 自由文本）；LLM 叙述见 summarizer.py（默认禁用）",
        "community_count": len(profiles),
        "profiles": profiles,
        "authoritative_domain_source": "00 §四 (M1 graph node.domain)",
        "community_role": "analysis_view_only",
    }
    source_fingerprint.embed(out, write=write)
    if write:
        from .communities import DATA_META_DIR, OUT_DIR
        payload = json.dumps(out, ensure_ascii=False, indent=2)
        (DATA_META_DIR / "community_profiles.json").write_text(payload, encoding="utf-8")
        (OUT_DIR / "community_profiles.json").write_text(payload, encoding="utf-8")
    return out


if __name__ == "__main__":
    r = run(write=True)
    print(json.dumps({"community_count": r["community_count"],
                      "profiles_written": True}, ensure_ascii=False))
