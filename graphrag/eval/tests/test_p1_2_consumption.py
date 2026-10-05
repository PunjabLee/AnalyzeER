"""P1-2（消费侧）· run_golden 双源消除 + 同源供给 + 指纹门 回归锁。

唯一写入者：`rag-eval-gate`（`graphrag/eval/**`）。

锁定行为（终轮 CodeReview P1-2 整改验收口径）：
1. 三档**实时同源**基准互相一致（l2() 同一次调用 / 独立重算 / 原始 M1+M2 独立重推）；
   磁盘 `community_edges.jsonl` **不作判据**（`used_for_judging=False`），只做 drift 对照。
2. **陈旧磁盘 → 不再假 FAIL**：磁盘缺一条真实耦合时，旧判据报幻觉、新判据 0 违例且 drift 可见。
3. **磁盘被塞臆造对 → 不再假 PASS**：新判据同时报 `coupling_not_in_live_kept` 与
   `coupling_ungrounded`。
4. 指纹门 **strict-on-enable 保持 dormant**：默认 `observed_no_baseline`（放行留痕）；
   声明基准后一致→`ok`、不一致→`InputFingerprintMismatch`（fail-fast）。
5. freshness（恒开）能独立重算并识别"声明的输入已漂移"。
6. sidecar **逐字节确定**且只落 `graphrag/eval/`（不污染 JSONL / 不写 M3 目录）。
7. 黄金集回归：拒答 8/8、幻觉 0、Top-3 6/7、综合 12/13、意图 21/21、唯一 MISS=u1-03。
"""

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
from pathlib import Path

import pytest

from graphrag.eval import kept_source as ks
from graphrag.eval import run_golden as rg
from graphrag.ingest.config import DATA_META_DIR, ROOT
from graphrag.semantic import fingerprint as fp


@pytest.fixture(scope="module")
def base():
    """真实数据上的同源供给（模块级共享，避免每例重算）。"""
    from graphrag.nl.nl_router import NLRouter
    return ks.resolve(NLRouter(), write_sidecar=False)


@pytest.fixture(scope="module")
def graph():
    from graphrag.store.loader import load_graph_json
    from graphrag.ingest.config import L0_GRAPH_JSON
    return load_graph_json(L0_GRAPH_JSON)


@pytest.fixture(autouse=True)
def _dormant(monkeypatch):
    monkeypatch.delenv(fp.ENV_EXPECTED, raising=False)


def _global_res(pairs):
    return {"answer": {"tightest_couplings": {"cross_community_coupling": [
        {"sample_table_pairs": [{"src": s, "dst": d, "weight": 0.9} for s, d in pairs]}]}}}


GLOBAL_ITEM = {"id": "g-02", "uc": "GLOBAL", "metric": "top3", "assert": {}}


# ------------------------------------------------------------------ ①三档同源一致
def test_three_live_tiers_agree(base):
    assert base["capture"]["captures"] == 1          # 探针确实在 l2() 那一次里命中
    assert base["recompute_independent_build_subgraph"]["agree_with_live"] is True
    assert base["grounding_from_raw_upstream"]["agree_with_live"] is True
    assert base["grounding_from_raw_upstream"]["live_only_pairs"] == 0
    assert base["grounding_from_raw_upstream"]["grounding_only_pairs"] == 0
    # 三档摘要逐字节同（同一集合，非仅等长）
    dig = {base["live"]["pairs_sha256"],
           base["recompute_independent_build_subgraph"]["pairs_sha256"],
           base["grounding_from_raw_upstream"]["pairs_sha256"]}
    assert len(dig) == 1


def test_anchors_tie_to_runbook_A5(base):
    """承 RUNBOOK §A-5 实测锚：kept 记录 821 / 加权表对 477 / 有域 A 表 349。"""
    assert base["live"]["kept_records"] == 821
    assert base["live"]["pairs"] == 477
    assert base["grounding_from_raw_upstream"]["a_domain_tables"] == 349


def test_disk_snapshot_is_not_the_authority(base):
    d = base["disk_snapshot_for_drift_only"]
    assert d["used_for_judging"] is False            # 双源已消除：磁盘那份只对照
    assert d["path"].endswith("community_edges.jsonl")


# ------------------------------------------------------------------ ②陈旧磁盘
def test_stale_disk_snapshot_no_false_hallucination(base, graph, tmp_path):
    """模拟 M1/M2 重生成后忘重跑 M3（磁盘那份少一条真实耦合）。"""
    real = next(iter(base["kept_pairs"]))
    s, d = sorted(real)
    lines = [ln for ln in (DATA_META_DIR / ks.EDGES_JSONL)
             .read_text(encoding="utf-8").splitlines() if ln.strip()]
    kept_lines = [ln for ln in lines
                  if {json.loads(ln)["src"], json.loads(ln)["dst"]} != {s, d}]
    (tmp_path / ks.EDGES_JSONL).write_text("\n".join(kept_lines) + "\n", encoding="utf-8")
    disk = ks.read_disk_kept(tmp_path / ks.EDGES_JSONL)

    # 旧口径（拿磁盘当判据）：这条合法耦合会被记成幻觉
    assert frozenset((s, d)) not in disk["pairs"]
    # 新口径（同源实时集）：0 违例
    viol = rg.provenance_check(GLOBAL_ITEM, _global_res([(s, d)]), graph, {}, base)
    assert viol == []
    # 而且**不静默**：drift 计数器把陈旧暴露出来
    from graphrag.nl.nl_router import NLRouter
    stale = ks.resolve(NLRouter(), meta_dir=tmp_path, write_sidecar=False)
    dd = stale["disk_snapshot_for_drift_only"]
    assert dd["drift_live_only"] == 1 and dd["drift_disk_only"] == 0
    assert dd["identical_to_live"] is False
    assert dd["used_for_judging"] is False


# ------------------------------------------------------------------ ③臆造磁盘对
def test_fabricated_pair_in_disk_is_rejected(base, graph, tmp_path):
    """磁盘那份被塞进一条既不在 kept、也无实物出处的臆造对 → 旧口径会放行（假 PASS）。"""
    lines = [ln for ln in (DATA_META_DIR / ks.EDGES_JSONL)
             .read_text(encoding="utf-8").splitlines() if ln.strip()]
    fab = ("jf_customer", "jf_goods")
    assert frozenset(fab) not in base["kept_pairs"]
    assert frozenset(fab) not in base["grounding_pairs"]
    lines.append(json.dumps({"src": fab[0], "dst": fab[1], "type": "RELATES_TO",
                             "confidence": 0.9, "evidence_level": "comment_explicit",
                             "cross_domain": False,
                             "provenance": {"file": "er-model/_bogus.md",
                                            "quote_hash": "deadbeef",
                                            "rel": "RELATES_TO"}},
                            ensure_ascii=False, sort_keys=True))
    (tmp_path / ks.EDGES_JSONL).write_text("\n".join(lines) + "\n", encoding="utf-8")
    disk = ks.read_disk_kept(tmp_path / ks.EDGES_JSONL)
    assert frozenset(fab) in disk["pairs"]           # 旧口径：0 违例（假 PASS）

    viol = rg.provenance_check(GLOBAL_ITEM, _global_res([fab]), graph, {}, base)
    kinds = {v[0] for v in viol}
    assert {"coupling_not_in_live_kept", "coupling_ungrounded"} <= kinds


# ------------------------------------------------------------------ ④⑤指纹门
def test_fingerprint_gate_dormant_by_default(base):
    assert base["dormant"]["expected_baseline_declared"] is False
    for name, rec in base["fingerprint_gate"].items():
        assert rec["verify"]["status"] == "observed_no_baseline", name   # 放行留痕，不判脏
        assert rec["verify"]["baseline_declared"] is False, name
        assert rec["freshness"] == "fresh", name                        # 恒开陈旧门：当前输入
        assert rec["declared_inputs"], name


def test_fingerprint_gate_escalates_when_baseline_declared(monkeypatch):
    from graphrag.community.source_fingerprint import compute_input_fingerprint
    digest = compute_input_fingerprint()["digest"]
    monkeypatch.setenv(fp.ENV_EXPECTED, digest)
    gate = ks.fingerprint_gate()
    assert all(r["verify"]["status"] == "ok" for r in gate.values())
    monkeypatch.setenv(fp.ENV_EXPECTED, "STALE-BASELINE")
    with pytest.raises(fp.InputFingerprintMismatch):
        ks.fingerprint_gate()


def test_run_golden_aborts_when_baseline_mismatch(tmp_path):
    """strict-on-enable 一旦激活，陈旧基准必须**中断评测**（不得默默给绿灯）。"""
    code = ("import os;os.environ['GRAPHRAG_EXPECTED_INPUT_FINGERPRINT']='STALE'"
            ";from graphrag.eval import run_golden as g;g.run(verbose=False,"
            "write_sidecar=False)")
    proc = subprocess.run([sys.executable, "-c", code], cwd=str(ROOT),
                          capture_output=True, text=True)
    assert proc.returncode != 0
    assert "InputFingerprintMismatch" in proc.stderr


def test_freshness_detects_drifted_declared_input(tmp_path):
    """恒开陈旧门：声明输入被改动 → 独立重算必须发现（不需任何基准）。"""
    f = tmp_path / "graphrag/data/l0_graph.json"
    f.parent.mkdir(parents=True)
    f.write_text('{"v":1}', encoding="utf-8")
    sha = hashlib.sha256(f.read_bytes()).hexdigest()
    files = [{"path": "graphrag/data/l0_graph.json", "sha256": sha}]
    assert ks._recompute_input_digest(files, root=tmp_path)            # 未漂移 → 摘要
    f.write_text('{"v":2}', encoding="utf-8")                          # 模拟上游重生成
    out = ks._recompute_input_digest(files, root=tmp_path)
    assert out.startswith("DRIFT:")


# ------------------------------------------------------------------ ⑥sidecar
def test_sidecar_deterministic_and_lives_in_eval_dir(tmp_path):
    from graphrag.nl.nl_router import NLRouter
    info = ks.resolve(NLRouter(), write_sidecar=False)
    p1, p2 = tmp_path / "s1.json", tmp_path / "s2.json"
    r1 = ks.write_sidecar_file(info, path=p1)
    r2 = ks.write_sidecar_file(info, path=p2)
    assert p1.read_bytes() == p2.read_bytes()          # 无时间戳 → 逐字节确定
    assert r1["sha256"] == r2["sha256"]
    payload = json.loads(p1.read_text(encoding="utf-8"))
    assert payload["disk_snapshot_for_drift_only"]["used_for_judging"] is False
    # JSONL 不内嵌顶层指纹键（M3 护栏口径）；指纹/摘要一律走 sidecar
    for ln in (DATA_META_DIR / ks.EDGES_JSONL).read_text(encoding="utf-8").splitlines():
        if ln.strip():
            assert ks.FIELD not in json.loads(ln)
    default_sidecar = Path(ks.__file__).parent / ks.SIDECAR_NAME
    assert default_sidecar.name == "kept_pairs_sidecar.json"
    # 默认落点＝本 agent 自有目录（不写 M3 的 data/meta；tmp 写入只是本例取证）
    assert default_sidecar.relative_to(Path(ROOT)).as_posix().startswith("graphrag/eval/")
    assert not (DATA_META_DIR / ks.SIDECAR_NAME).exists()


# ------------------------------------------------------------------ ⑦黄金集回归
@pytest.fixture(scope="module")
def golden():
    return rg.run(verbose=False, write_sidecar=False)


def test_golden_regression_after_p1_fixes(golden):
    s = golden["summary"]
    assert s["items"] == 21
    assert s["refusal_accuracy"]["hit"] == 8 and s["refusal_accuracy"]["n"] == 8   # u6-03 已翻转
    assert s["hallucination"]["ungrounded_or_gate_violations"] == 0
    assert (s["top3_strict"]["hit"], s["top3_strict"]["n"]) == (6, 7)              # 85.7%
    assert (s["overall_hit"]["hit"], s["overall_hit"]["n"]) == (12, 13)            # 92.3%
    assert s["intent_classification"]["match"] == 21
    assert s["intent_classification"]["mismatches"] == []


def test_golden_only_miss_is_u1_03(golden):
    assert [r["id"] for r in golden["rows"] if not r["hit"]] == ["u1-03"]


def test_golden_hit_vector_stable():
    a = rg.run(verbose=False, write_sidecar=False)
    b = rg.run(verbose=False, write_sidecar=False)
    va = "".join("1" if r["hit"] else "0" for r in a["rows"])
    vb = "".join("1" if r["hit"] else "0" for r in b["rows"])
    assert va == vb == "110111111111111111111"
