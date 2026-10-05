"""P1-2（**消费侧**）· L2 耦合对回查基准的同源供给 + 上游产物陈旧门。

唯一写入者：`rag-eval-gate`（`graphrag/eval/**`，RUNBOOK §4）。本模块只读消费 M3/M1/M2 产物。

## 它要消灭的缺陷（终轮 CodeReview P1-2 点名）
旧 `run_golden.load_kept_pairs()` 拿**磁盘** `community_edges.jsonl` 当耦合对的回查基准，
而答案走 `NLRouter.l2()` **实时**计算 → **双源**：磁盘那份一旦陈旧（上游 M1/M2 重生成、
门参数变更、算法改版而忘重跑 M3），就是"实时算的结论 比 可能陈旧的基准"，两种失真都静默：
① 假 FAIL（把合法耦合记成幻觉）；② 更糟的**假 PASS**（陈旧文件里恰好还留着那条边）。

## 现在的判据（三档，全实时；磁盘那份降为"陈旧对照"）
| 档 | 来源 | 用途 |
|---|---|---|
| ①**权威** | 与 `router.l2()` **同一次** `build_subgraph()` 调用捕获的 kept 记录 | 耦合对 `∈ kept` 判定（结构一致性） |
| ②自洽佐证 | 独立再调一次 `build_subgraph()`（不借探针） | ①必须与②**集合相等**，否则 `SameSourceViolation` |
| ③**独立出处** | eval 自行从**原始上游**重推：M1 `l0_graph.json` 的 `RELATES_TO` 邻接 ＋ M2 `lineage_edges.jsonl` 的 `REFERENCES`（表级投影），逐条带 `source_file/quote_hash` | 耦合对必须**可回溯到实物边**（防"聚合层自洽但无出处"） |
| （对照） | 磁盘 `community_edges.jsonl` + 其 `input_fingerprint` 陈旧门 | **不作判据**；只报 drift / stale |

## JSONL 的指纹怎么带（不得内嵌顶层键）
M3 护栏测试 `test_m3_fingerprint.py::test_community_edges_jsonl_not_polluted` 锁定
`community_edges.jsonl` 每行必须是纯边记录（混入顶层指纹 → 消费端 `KeyError`）。
故本 harness 用 **sidecar**：`graphrag/eval/kept_pairs_sidecar.json`（本 agent 自有路径、
**无时间戳**、逐字节确定），承载 live/disk 两侧的 `pairs_sha256`、磁盘文件 `file_sha256`
与上游 `input_fingerprint` 新鲜度判定；同时保留**重建比对**（②③两路）作为不依赖磁盘那份
的更强口径。

## strict-on-enable 保持 dormant（红线）
所有 `verify_json_file/extract_fingerprint` 调用**不注入** expected 基准 → 默认只留痕
（`observed_no_baseline`）。激活方式（由编排者/门禁注入，不由本 agent 擅自打开）：
```
export GRAPHRAG_EXPECTED_INPUT_FINGERPRINT=<生产者嵌入的 digest>
python3 -m graphrag.eval.run_golden          # 此后：字段缺失或不一致 → InputFingerprintMismatch 直接中断
```
注意 `fingerprint_check`（基准比对，dormant）与 `freshness`（陈旧门，**恒开**）是两件事：
后者拿产物**声明的输入文件清单**独立重算 sha256 并与内嵌 digest 比对，不需要任何基准
即可判定"磁盘这份是否出自当前输入"——这正是取代双源的那道门。
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

from ..ingest.config import CONFIDENCE_DEFAULT_MIN, DATA_META_DIR, L0_GRAPH_JSON, ROOT
from ..semantic.fingerprint import (FIELD, expected_from_env, extract_fingerprint,
                                    verify_json_file)
from ..store.loader import load_graph_json

EDGES_JSONL = "community_edges.jsonl"
LINEAGE_JSONL = "lineage_edges.jsonl"
#: M3 自有 JSON 派生产物（顶层可嵌指纹者）——eval 消费点逐一过指纹门
COMMUNITY_JSON_ARTIFACTS = (
    "community_result.json",
    "community_global_analysis.json",
    "community_profiles.json",
    "community_nmi_vs_baseline.json",
)
#: JSONL 不能内嵌顶层键 → 指纹/摘要改由本 agent 自有 sidecar 承载
SIDECAR_NAME = "kept_pairs_sidecar.json"

RELATES_TO = "RELATES_TO"
REFERENCES = "REFERENCES"


class SameSourceViolation(RuntimeError):
    """同源前提被打破（探针未捕获 / 三档实时集互不相等）→ harness 不可信，fail-fast。"""


# ------------------------------------------------------------------ 稳定摘要
def _sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def pairs_sha256(pairs) -> str:
    """表对集合的稳定摘要（与记录顺序无关；无向 → 端点排序后 join）。"""
    canon = "\n".join("|".join(sorted(p)) for p in sorted(pairs, key=lambda p: sorted(p)))
    return _sha256(canon)


def _rel(path) -> str:
    pp = Path(path).resolve()
    try:
        return pp.relative_to(Path(ROOT).resolve()).as_posix()
    except ValueError:
        return pp.as_posix()


# ------------------------------------------------------------------ ①同一次调用捕获
def _capture_l2_kept(router):
    """在 `router.l2()` 的那一次计算上装**只读探针**，捕获它实际喂给算法的 kept 记录。

    仅运行期 monkeypatch、`finally` 立即还原，**不改任何 M3 文件**。之所以要 patch
    `global_analysis` 模块属性：它用 `from .communities import build_subgraph` 已把函数
    绑进自己的命名空间，只 patch `communities` 接不到那一发。
    """
    import importlib
    import sys

    # ⚠ 必须走 importlib：`graphrag.community.__init__` 里
    # `from .global_analysis import global_analysis` 用**同名函数**遮蔽了子模块属性，
    # `from ..community import global_analysis` 拿到的是函数对象（没有 build_subgraph 属性，
    # 探针会静默 0 捕获）。以模块对象为准才可靠。
    _comm = importlib.import_module("graphrag.community.communities")
    _ga = importlib.import_module("graphrag.community.global_analysis")

    targets = [(m, getattr(m, "build_subgraph")) for m in (_comm, _ga)
               if callable(getattr(m, "build_subgraph", None))]
    saved = {m.__name__: orig for m, orig in targets}
    captured: list = []
    # l2() 已被别处预热过 → 强制失效重算，确保"捕获的那一次"就是后续答案真正使用的那一次
    # （否则探针 0 捕获，会被误判成调用链改动）。resolve() 必须在评测循环**之前**调用。
    cache_reset = getattr(router, "_l2", None) is not None
    if cache_reset:
        router._l2 = None

    def _make_spy(orig):
        def spy(*a, **kw):
            out = orig(*a, **kw)
            captured.append(out)
            return out
        return spy

    for mod, orig in targets:
        setattr(mod, "build_subgraph", _make_spy(orig))
    try:
        l2 = router.l2()                       # 与答案同一次计算（write=False，不落盘）
    finally:
        for name, orig in saved.items():
            setattr(sys.modules[name], "build_subgraph", orig)

    if not captured:
        raise SameSourceViolation(
            "探针未在 router.l2() 期间捕获到 build_subgraph() 调用：无法证明"
            "「答案与回查基准同源」。请检查 M3 调用链是否改动（禁止退回读磁盘 kept 集）。")
    info_capture = {"captures": len(captured), "cache_reset_before_capture": cache_reset}
    keeps = [c[1] for c in captured]           # build_subgraph() → (G, kept, ...)
    sets = [{frozenset((r["src"], r["dst"])) for r in k} for k in keeps]
    if any(s != sets[0] for s in sets[1:]):
        raise SameSourceViolation(
            f"同一次 l2() 内多次 build_subgraph() 给出的 kept 集不一致：{[len(s) for s in sets]}")
    return keeps[0], sets[0], l2, info_capture


# ------------------------------------------------------------------ ②独立重算
def recompute_kept() -> tuple[set, int]:
    """不借探针，独立再走一次 M3 `build_subgraph()`（同一确定性函数、同一磁盘输入）。"""
    from ..community.communities import build_subgraph

    _G, kept, _excl, _stats, _gate, _dm = build_subgraph()
    return {frozenset((r["src"], r["dst"])) for r in kept}, len(kept)


# ------------------------------------------------------------------ ③原始上游独立出处
def _uncertain(extra_quote: str | None = None, **e) -> bool:
    if e.get("has_uncertain") or e.get("evidence_level") == "unconfirmed":
        return True
    q = extra_quote or e.get("raw_desc") or ""
    return "待确认" in q


def independent_grounding(meta_dir=DATA_META_DIR, l0_json=L0_GRAPH_JSON,
                          min_conf: float = CONFIDENCE_DEFAULT_MIN) -> dict:
    """eval **自行**从原始 M1/M2 实物重推可入图的表级表对（不复用 M3 的 `ingest_edges`）。

    门与 M3 同族但**独立实现**：`conf≥0.45` ∧ 非存疑 ∧ 两端 A 级且有域 ∧ 非自环；
    出处取 `source_file/quote_hash`（RELATES_TO）或 `evidence_src.file/quote_hash`（REFERENCES）。
    这一档才是"防幻觉"的独立证据：聚合层自洽不够，必须能落到一条实物边。
    """
    g = load_graph_json(l0_json)
    tables = {n["name"]: n for n in g.nodes.values() if n["label"] == "Table"}
    a_domain = {name for name, v in tables.items() if v.get("tier") == "A" and v.get("domain")}

    prov: dict[frozenset, list[dict]] = {}
    counts = {RELATES_TO: 0, REFERENCES: 0}

    def _add(src, dst, etype, conf, file, qhash):
        if not (file and qhash):
            return False
        key = frozenset((src, dst))
        prov.setdefault(key, []).append({"rel": etype, "confidence": conf,
                                         "file": _rel(file) if isinstance(file, Path) else file,
                                         "quote_hash": qhash})
        return True

    for e in g.edges:
        if e.get("type") != RELATES_TO:
            continue
        conf = float(e.get("confidence") or 0.0)
        if conf < min_conf or _uncertain(**e):
            continue
        s = e["src"].split(":", 1)[1]
        d = e["dst"].split(":", 1)[1]
        if s not in a_domain or d not in a_domain or s == d:
            continue
        if _add(s, d, RELATES_TO, conf, e.get("source_file"), e.get("quote_hash")):
            counts[RELATES_TO] += 1

    lin = Path(meta_dir) / LINEAGE_JSONL
    if lin.exists():
        for ln in lin.read_text(encoding="utf-8").splitlines():
            if not ln.strip():
                continue
            r = json.loads(ln)
            if r.get("type") != REFERENCES:
                continue
            conf = float(r.get("confidence") or 0.0)
            quote = (r.get("evidence_src") or {}).get("quote")
            if conf < min_conf or _uncertain(quote, **r):
                continue
            s, d = r.get("src_table"), r.get("dst_table")
            if not s or not d or s not in a_domain or d not in a_domain or s == d:
                continue
            ev = r.get("evidence_src") or {}
            if _add(s, d, REFERENCES, conf, ev.get("file"), ev.get("quote_hash")):
                counts[REFERENCES] += 1

    return {"pairs": set(prov), "provenance": prov, "records_grounded": counts,
            "a_domain_tables": len(a_domain)}


# ------------------------------------------------------------------ 磁盘那份（仅对照）
def read_disk_kept(path=None) -> dict:
    """读磁盘 `community_edges.jsonl` —— **只作陈旧/drift 对照，绝不作判据**。

    行形状仍假定纯边记录（M3 护栏锁定，不得混入顶层指纹），故这里取 `r["src"]/r["dst"]`
    是安全的；文件缺席 → `exists=False` 留痕（不静默当空集通过）。
    """
    p = Path(path) if path else Path(DATA_META_DIR) / EDGES_JSONL
    out = {"path": _rel(p), "exists": p.exists(), "lines": 0, "pairs": set(),
           "file_sha256": None, "pairs_sha256": None, "malformed_lines": 0}
    if not p.exists():
        return out
    raw = p.read_text(encoding="utf-8")
    out["file_sha256"] = _sha256(raw)
    for ln in raw.splitlines():
        if not ln.strip():
            continue
        try:
            r = json.loads(ln)
            s, d = r["src"], r["dst"]
        except (KeyError, ValueError):
            out["malformed_lines"] += 1
            continue
        out["lines"] += 1
        out["pairs"].add(frozenset((s, d)))
    out["pairs_sha256"] = pairs_sha256(out["pairs"]) if out["pairs"] else None
    return out


# ------------------------------------------------------------------ 指纹门
def _recompute_input_digest(files, root=ROOT) -> str | None:
    """按生产者**声明**的输入清单独立重算聚合摘要。

    配方（`graphrag/community/source_fingerprint.py` 文档冻结）：逐文件
    `sha256(原始字节)` → 按相对路径升序 → `sha256("\\n".join(f"{rel}\\t{sha}"))`。
    任一声明文件读不到 → None（无从校验，不伪造）。
    """
    rows = []
    for f in files or ():
        rel, sha = f.get("path"), f.get("sha256")
        p = Path(root) / rel if rel else None
        if not rel or p is None or not p.exists():
            return None
        if _sha256_bytes(p) != sha:
            return f"DRIFT:{rel}"
        rows.append(f"{rel}\t{sha}")
    if not rows:
        return None
    return _sha256("\n".join(sorted(rows)))


def _sha256_bytes(p) -> str:
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def fingerprint_gate(meta_dir=DATA_META_DIR, root=ROOT) -> dict:
    """消费 community_*/global 产物处的 P1-2 门（dormant 基准比对 + 恒开新鲜度判定）。

    - `fingerprint_check`：走 `semantic.fingerprint.verify_json_file`（**不注入** expected →
      `observed_no_baseline`；一旦 env `GRAPHRAG_EXPECTED_INPUT_FINGERPRINT` 声明基准，
      字段缺失或不一致即 `InputFingerprintMismatch` 冒泡中断评测 = strict-on-enable 生效）。
    - `freshness`：拿产物内嵌 digest 与**独立重算**值比对 → `fresh`/`stale`/`no_files_declared`。
      该判定不需要基准，专治"磁盘那份是不是出自当前输入"。
    """
    checks: dict[str, dict] = {}
    for name in COMMUNITY_JSON_ARTIFACTS:
        p = Path(meta_dir) / name
        rec = {"verify": verify_json_file(p, name=name)}     # expected=None → dormant
        body = json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}
        fp = extract_fingerprint(body)                      # 只读接住，不改上游
        field = body.get(FIELD)
        declared = field.get("files") if isinstance(field, dict) else None
        recomputed = _recompute_input_digest(declared, root=root) if fp else None
        if fp is None:
            rec["freshness"] = "absent_fingerprint"          # 生产者未嵌入
        elif recomputed is None:
            rec["freshness"] = "unverifiable_inputs_missing"
        elif recomputed.startswith("DRIFT:"):
            rec["freshness"] = f"stale_declared_input_changed({recomputed[5:]})"
        else:
            rec["freshness"] = "fresh" if recomputed == fp else "stale_digest_mismatch"
        rec["declared_inputs"] = sorted(f.get("path") for f in (declared or ()) if f.get("path"))
        checks[name] = rec
    return checks


# ------------------------------------------------------------------ 汇总
def resolve(router, *, meta_dir=DATA_META_DIR, root=ROOT,
            write_sidecar: bool = False) -> dict:
    """产出「与 l2() 同源」的耦合对回查基准 + 陈旧门留痕。

    返回 dict 关键键：
    - `kept_pairs`：**判据**（同一次 l2() 计算的 kept 表对集）；
    - `grounding_pairs` / `grounding_provenance`：独立实物出处集（第二道判据）；
    - `agreement`：①②③三档一致性 + 磁盘 drift（`disk_used_for_judging=False`）；
    - `fingerprint_gate` / `dormant`：产物门状态与是否已激活 strict-on-enable。
    """
    live_kept, live_pairs, l2, capture = _capture_l2_kept(router)
    rc_pairs, rc_records = recompute_kept()
    if rc_pairs != live_pairs:
        raise SameSourceViolation(
            f"同一次 l2() 与独立重算的 kept 表对集不一致："
            f"live={len(live_pairs)} recompute={len(rc_pairs)} "
            f"diff={sorted('|'.join(sorted(p)) for p in (live_pairs ^ rc_pairs))[:5]}")
    grounding = independent_grounding(meta_dir=meta_dir)
    g_pairs = grounding["pairs"]
    disk = read_disk_kept(Path(meta_dir) / EDGES_JSONL)

    info = {
        "authoritative_source": "live_same_invocation(NLRouter.l2() → global_analysis → build_subgraph)",
        "capture": capture,
        "live": {"kept_records": len(live_kept), "pairs": len(live_pairs),
                 "pairs_sha256": pairs_sha256(live_pairs)},
        "recompute_independent_build_subgraph": {"pairs": len(rc_pairs),
                                                 "records": rc_records,
                                                 "pairs_sha256": pairs_sha256(rc_pairs),
                                                 "agree_with_live": rc_pairs == live_pairs},
        "grounding_from_raw_upstream": {
            "pairs": len(g_pairs), "records_grounded": grounding["records_grounded"],
            "a_domain_tables": grounding["a_domain_tables"],
            "pairs_sha256": pairs_sha256(g_pairs),
            "agree_with_live": g_pairs == live_pairs,
            "live_only_pairs": len(live_pairs - g_pairs),
            "grounding_only_pairs": len(g_pairs - live_pairs)},
        "disk_snapshot_for_drift_only": {
            "path": disk["path"], "exists": disk["exists"], "lines": disk["lines"],
            "pairs": len(disk["pairs"]), "file_sha256": disk["file_sha256"],
            "pairs_sha256": disk["pairs_sha256"], "malformed_lines": disk["malformed_lines"],
            "drift_live_only": len(live_pairs - disk["pairs"]),
            "drift_disk_only": len(disk["pairs"] - live_pairs),
            "identical_to_live": disk["pairs"] == live_pairs,
            "used_for_judging": False},
        "fingerprint_gate": fingerprint_gate(meta_dir=meta_dir, root=root),
        "dormant": {"expected_baseline_declared": bool(expected_from_env()),
                    "env": "GRAPHRAG_EXPECTED_INPUT_FINGERPRINT",
                    "note": ("默认未注入基准 → 只留痕不判脏（渐进上线现状）；"
                             "注入基准后 run_golden 将在产物字段缺失/不一致处 fail-fast。")},
        "kept_pairs": live_pairs,
        "grounding_pairs": g_pairs,
        "grounding_provenance": grounding["provenance"],
    }
    if write_sidecar:
        info["sidecar"] = write_sidecar_file(info, meta_dir=meta_dir)
    return info


def sidecar_payload(info: dict) -> dict:
    """sidecar 内容（**无时间戳**：同一输入两次落盘逐字节相同 → 可提交、可 diff）。"""
    return {
        "artifact": SIDECAR_NAME,
        "owner": "rag-eval-gate（graphrag/eval/** 唯一写入者）",
        "generator": "graphrag/eval/kept_source.py",
        "purpose": ("P1-2 消费侧：JSONL `community_edges.jsonl` 不得内嵌顶层指纹键，"
                    "故由本 sidecar 承载其 kept 集摘要与上游 input_fingerprint 新鲜度判定；"
                    "判据本身走实时同源（本文件不替代实时重建）。"),
        "authoritative_source": info["authoritative_source"],
        "capture": info["capture"],
        "live": info["live"],
        "recompute_independent_build_subgraph":
            info["recompute_independent_build_subgraph"],
        "grounding_from_raw_upstream": info["grounding_from_raw_upstream"],
        "disk_snapshot_for_drift_only": info["disk_snapshot_for_drift_only"],
        "fingerprint_gate": {
            name: {"verify_status": rec["verify"]["status"],
                   "baseline_declared": rec["verify"]["baseline_declared"],
                   "freshness": rec["freshness"],
                   "declared_inputs": rec["declared_inputs"]}
            for name, rec in info["fingerprint_gate"].items()},
        "dormant": info["dormant"],
    }


def write_sidecar_file(info: dict, *, meta_dir=DATA_META_DIR, path=None) -> dict:
    p = Path(path) if path else Path(__file__).resolve().parent / SIDECAR_NAME
    text = json.dumps(sidecar_payload(info), ensure_ascii=False, indent=2, sort_keys=True)
    p.write_text(text + "\n", encoding="utf-8")
    return {"path": _rel(p), "sha256": _sha256(text + "\n")}
