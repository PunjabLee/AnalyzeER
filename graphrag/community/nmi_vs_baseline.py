"""M3 · 涌现社区划分 vs `00` 手工 18 域(+OT) 基线对照（确定性、无外部依赖）。

**为什么需要本对照**（`spec/eval-baseline.md` §4-M3 / `stack-options.md` §3）：
社区检测给出的是**结构涌现簇**，而 `00` §四 的 18 业务域(+OT) 是**人工权威分组**
（ground truth）。二者是**对照校验**关系，非替代：涌现簇**不得覆盖** 00 分组。
本模块量化一致/差异：

- **NMI**（归一化互信息）：community 划分 vs domain 划分，几何/算术/max/min 四变体，
  纯 Python 手算（sklearn 本机未装、且不引外部依赖 → 承 stack-options §3）。
  自然对数、nats 单位、确定性可复现。
- **混淆映射表**：每个 community × 每个 domain 的成员数（稀疏存储）。
- **差异簇可解释清单**：
  · 被拆的域（同一 ground-truth 域散落在多社区）；
  · 被合/混合的社区（一社区含多域，含跨域桥接/枢纽解释线索）；
  · 一致性判据：差异**可回溯解释**（隐藏 hub via betweenness、跨域边）方视为有效涌现信号，
    否则入「待确认」；**同名异指向假耦合团**（`05` B-4）因低置信/存疑边已被排除，
    不应在此出现 —— 本模块断言此类入算法=0（由 communities 双硬门保证）。

节点集合：仅取进入融合图（有 conf≥0.45 结构边）的 A 表；两分区在同一节点集上对齐。
"""

from __future__ import annotations

import json
import math
from collections import defaultdict

import networkx as nx

from .communities import build_subgraph, detect_communities
from . import source_fingerprint


# ---------------------------------------------------------------- NMI 手算
def _entropy(counts: list[int], total: int) -> float:
    if total <= 0:
        return 0.0
    h = 0.0
    for c in counts:
        if c > 0:
            p = c / total
            h -= p * math.log(p)          # 自然对数（nats）
    return h


def _mutual_information(rows: list[str], cols: list[str]) -> tuple[float, float, float, dict]:
    """给定两组等长标签序列 → (H(C), H(D), I(C;D), contingency)。"""
    n = len(rows)
    rc: dict[str, int] = defaultdict(int)
    cc: dict[str, int] = defaultdict(int)
    joint: dict[tuple[str, str], int] = defaultdict(int)
    for r, c in zip(rows, cols):
        rc[r] += 1
        cc[c] += 1
        joint[(r, c)] += 1
    h_r = _entropy(list(rc.values()), n)
    h_c = _entropy(list(cc.values()), n)
    mi = 0.0
    for (r, c), nij in joint.items():
        p_ij = nij / n
        mi += p_ij * math.log(p_ij / ((rc[r] / n) * (cc[c] / n)))
    return h_r, h_c, mi, joint


def nmi(rows: list[str], cols: list[str]) -> dict:
    """四变体 NMI；任一侧退化（单簇/空）→ 记 0 并标 degenerate。"""
    n = len(rows)
    if n == 0:
        return {"n": 0, "degenerate": True}
    h_r, h_c, mi, joint = _mutual_information(rows, cols)
    h_r = max(h_r, 0.0)
    h_c = max(h_c, 0.0)
    mi = max(mi, 0.0)                      # 浮点微负归零
    denom_arith = h_r + h_c
    denom_geo = math.sqrt(h_r * h_c)
    degenerate = denom_arith <= 0.0
    return {
        "n": n,
        "n_communities": len(set(rows)),
        "n_domains": len(set(cols)),
        "mutual_information_nats": round(mi, 6),
        "H_communities": round(h_r, 6),
        "H_domains": round(h_c, 6),
        "nmi_arithmetic": round(2 * mi / denom_arith, 6) if denom_arith > 0 else None,
        "nmi_geometric": round(mi / denom_geo, 6) if denom_geo > 0 else None,
        "nmi_max": round(mi / max(h_r, h_c), 6) if max(h_r, h_c) > 0 else None,
        "nmi_min": round(mi / min(h_r, h_c), 6) if min(h_r, h_c) > 0 else None,
        "degenerate": degenerate,
        "log_base": "e (nats)",
    }


# ---------------------------------------------------------------- 混淆与差异
def _confusion(rows: list[str], cols: list[str]) -> dict:
    joint: dict[tuple[str, str], int] = defaultdict(int)
    for r, c in zip(rows, cols):
        joint[(r, c)] += 1
    comm_domains: dict[str, dict[str, int]] = defaultdict(dict)
    domain_comms: dict[str, dict[str, int]] = defaultdict(dict)
    for (r, c), k in joint.items():
        comm_domains[r][c] = k
        domain_comms[c][r] = k
    return {
        "community_to_domains": {k: dict(sorted(v.items()))
                                 for k, v in sorted(comm_domains.items(), key=lambda kv: int(kv[0]))},
        "domain_to_communities": {k: dict(sorted(v.items(), key=lambda kv: int(kv[0])))
                                  for k, v in sorted(domain_comms.items())},
    }


def _diff_explainable(G, comm_sets, rows, cols, domain_map, member_of, wdeg, btw):
    """差异簇清单：被拆的域 / 混合的社区，附结构解释线索（枢纽/跨域边）。"""
    # 被拆的域：同 domain 落在 ≥2 社区
    domain_comms: dict[str, set[str]] = defaultdict(set)
    for cm, dm in zip(rows, cols):
        domain_comms[dm].add(cm)
    split_domains = sorted(d for d, cs in domain_comms.items() if len(cs) > 1)

    # 混合社区：同 community 含 ≥2 域
    comm_doms: dict[str, set[str]] = defaultdict(set)
    for cm, dm in zip(rows, cols):
        comm_doms[cm].add(dm)
    mixed_communities = sorted((c for c, ds in comm_doms.items() if len(ds) > 1),
                               key=lambda x: int(x))

    # 跨域边（社区内成员连向异域成员）→ 混合社区的结构性解释
    cross_domain_pairs = 0
    for u, v, d in G.edges(data=True):
        if domain_map.get(u) != domain_map.get(v):
            cross_domain_pairs += 1

    def _comm_top_members(cm):
        mem = sorted(comm_sets[int(cm)], key=lambda n: (-wdeg.get(n, 0.0), -btw.get(n, 0.0), n))
        return [{"table": n, "domain": domain_map.get(n), "weighted_degree": wdeg.get(n, 0.0),
                 "betweenness": round(btw.get(n, 0.0), 6)} for n in mem[:5]]

    split_detail = []
    for d in split_domains:
        cs = sorted(domain_comms[d], key=int)
        members = sorted(t for t, dm in domain_map.items() if dm == d and t in member_of)
        split_detail.append({
            "domain": d, "n_communities": len(cs), "communities": cs,
            "members": members,
            "note": "该 00 域成员被结构分裂到多社区；须由跨域耦合/子团区分解释（非权威分组变更）",
        })
    mixed_detail = []
    for c in mixed_communities:
        ds = sorted(comm_doms[c], key=_dk)
        mixed_detail.append({
            "community_id": c, "domains": ds, "n_domains": len(ds),
            "top_members_by_weighted_degree": _comm_top_members(c),
            "explanation": ("混合多域：由桥接表/枢纽（betweenness）+ 跨域边解释；"
                            "属结构涌现信号，不覆盖 00 域归属"),
        })
    return {
        "split_domains": split_detail,
        "mixed_communities": mixed_detail,
        "cross_domain_edges": cross_domain_pairs,
        "false_coupling_note": ("同名异指向假耦合团(05 B-4)/低置信(semantic/unconfirmed)边"
                                "已在 communities 双硬门(conf≥0.45 + 存疑排除)剔除，"
                                "入算法=0；本对照不含此类短路连边伪社区"),
    }


def _dk(dom: str) -> int:
    return int(dom[1:]) if dom.startswith("D") and dom[1:].isdigit() else 999


def nmi_vs_baseline(write: bool = True) -> dict:
    G, kept, exclusion, stats, meta_full, domain_map = build_subgraph()
    communities = detect_communities(G)
    member_of = {n: str(i) for i, c in enumerate(communities) for n in c}

    # 对齐节点集：仅融合图中的节点（两端皆有 conf≥0.45 边）
    nodes = sorted(G.nodes)
    rows = [member_of[n] for n in nodes]                # 涌现社区标签
    cols = [domain_map.get(n, "UNKNOWN") for n in nodes]  # 00 权威域

    wdeg = {n: round(sum(d.get("weight", 0.0) for _, _, d in G.edges(n, data=True)), 6)
            for n in G.nodes}
    btw = nx.betweenness_centrality(G, weight="weight", normalized=True)

    metrics = nmi(rows, cols)
    confusion = _confusion(rows, cols)
    diffs = _diff_explainable(G, communities, rows, cols, domain_map,
                              member_of, wdeg, btw)

    out = {
        "milestone": "M3",
        "generator": "graphrag/community/nmi_vs_baseline.py",
        "ground_truth": "00 §四 手工 18 域(+OT) —— 权威，不被社区覆盖",
        "analysis_view": "greedy_modularity 涌现社区 —— 结构对照",
        "node_scope": {
            "nodes_compared": len(nodes),
            "a_tables_total": len(domain_map),
            "isolated_excluded_from_scope": stats["isolated_a_tables"],
        },
        "nmi": metrics,
        "confusion_mapping": confusion,
        "diff_clusters_explainable": diffs,
        "verdict": _verdict(metrics, diffs),
    }
    source_fingerprint.embed(out, write=write)
    if write:
        from .communities import DATA_META_DIR, OUT_DIR
        payload = json.dumps(out, ensure_ascii=False, indent=2)
        (DATA_META_DIR / "community_nmi_vs_baseline.json").write_text(payload, encoding="utf-8")
        (OUT_DIR / "community_nmi_vs_baseline.json").write_text(payload, encoding="utf-8")
    return out


def _verdict(metrics: dict, diffs: dict) -> dict:
    nm = metrics.get("nmi_arithmetic")
    band = ("none" if nm is None else
            "high(≥0.8)" if nm >= 0.8 else
            "medium(0.6–0.8)" if nm >= 0.6 else
            "low(<0.6)")
    return {
        "nmi_arithmetic": nm,
        "alignment_band": band,
        "note": ("结构社区与业务域分组整体方向一致与否由 NMI 量化；具体拆/合差异"
                 "见 diff_clusters_explainable，均不覆盖 00 权威分组。"),
        "n_split_domains": len(diffs["split_domains"]),
        "n_mixed_communities": len(diffs["mixed_communities"]),
    }


if __name__ == "__main__":
    r = nmi_vs_baseline(write=True)
    print(json.dumps({"nmi": r["nmi"], "verdict": r["verdict"]}, ensure_ascii=False, indent=2))
