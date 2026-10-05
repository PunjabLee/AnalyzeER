"""M3（终轮 CodeReview P1-2 · 生产侧）· `input_fingerprint` 嵌入契约单测。

覆盖任务 §3 交付要点：
- 字段名/聚合摘要键与**消费侧** `semantic/fingerprint.py` **同构**（格式不分叉）；
- sha256 配方**独立重算**一致（逐文件 sha256 + 稳定聚合），确定性可复现；
- `write=True` 落盘产物顶层含 `input_fingerprint`，且 == 对当前输入即时重算值；
- 自校验：fresh 输入通过；篡改/漂移 → fail-fast；
- 缺依赖文件 → 行为明确（`FileNotFoundError`，绝不对未读到的源伪造指纹）；
- 消费端 dormant：`extract` 接住 digest；无基准 → `observed_no_baseline`（放行留痕），
  注入基准且一致 → `ok`；陈旧基准 → `InputFingerprintMismatch`；
- **关键护栏**：`community_edges.jsonl`（逐行 JSONL）不得混入顶层指纹对象
  （否则 eval-gate `run_golden.load_kept_pairs()` 取 `r["src"]` 会 KeyError）。
"""

from __future__ import annotations

import hashlib
import importlib
import json

import pytest

from graphrag.ingest.config import DATA_META_DIR, L0_GRAPH_JSON, ROOT
from graphrag.community import source_fingerprint as sf
# 消费侧接口仅在测试层引用，用于证明生产/消费格式同构（非运行期耦合）
from graphrag.semantic import fingerprint as consumer_fp

comm = importlib.import_module("graphrag.community.communities")
cs = importlib.import_module("graphrag.community.community_summary")
ga = importlib.import_module("graphrag.community.global_analysis")
nmi = importlib.import_module("graphrag.community.nmi_vs_baseline")

JSON_ARTIFACTS = [
    "community_result.json", "community_profiles.json",
    "community_global_analysis.json", "community_nmi_vs_baseline.json",
]


@pytest.fixture(autouse=True)
def _dormant(monkeypatch):
    """确保 strict-on-enable 保持 dormant：测试全程不注入 expected 基准。"""
    monkeypatch.delenv(consumer_fp.ENV_EXPECTED, raising=False)


def _manual_digest():
    """复刻配方独立重算聚合摘要（不依赖被测实现）。"""
    files = sorted(
        [L0_GRAPH_JSON, DATA_META_DIR / "lineage_edges.jsonl"],
        key=lambda p: p.resolve().relative_to(ROOT.resolve()).as_posix())
    rows = [f"{p.resolve().relative_to(ROOT.resolve()).as_posix()}\t"
            f"{hashlib.sha256(p.read_bytes()).hexdigest()}" for p in files]
    return hashlib.sha256("\n".join(rows).encode("utf-8")).hexdigest()


# ------------------------------------------------------------------ 格式同构
def test_field_name_isomorphic_with_consumer():
    assert sf.FIELD == consumer_fp.FIELD == "input_fingerprint"


def test_consumer_extract_uses_digest_key():
    fp = sf.compute_input_fingerprint()
    # 消费端对 dict 形态优先取 digest|sha256|value → 能接住本产物的聚合摘要
    assert consumer_fp.extract_fingerprint({sf.FIELD: fp}) == fp["digest"]


# ------------------------------------------------------------------ 配方确定性
def test_compute_deterministic_and_matches_manual_recipe():
    a, b = sf.compute_input_fingerprint(), sf.compute_input_fingerprint()
    assert a == b
    assert a["algo"] == "sha256"
    assert a["digest"] == _manual_digest()
    assert [f["path"] for f in a["files"]] == [
        "graphrag/data/l0_graph.json", "graphrag/data/meta/lineage_edges.jsonl"]


# ------------------------------------------------------------------ 缺依赖 → 明确
def test_missing_dependency_raises_clearly(tmp_path):
    missing = tmp_path / "absent.json"
    with pytest.raises(FileNotFoundError):
        sf.compute_input_fingerprint((missing,))
    with pytest.raises(FileNotFoundError):
        sf.embed({}, write=False, input_files=(missing,))


# ------------------------------------------------------------------ 自校验
def test_self_check_passes_on_fresh_and_detects_drift():
    fp = sf.compute_input_fingerprint()
    assert sf.self_check(fp)["status"] == "ok"
    tampered = dict(fp)
    tampered["digest"] = "deadbeef"                      # 模拟边写边算/漂移
    with pytest.raises(AssertionError):
        sf.self_check(tampered)


# ------------------------------------------------------------------ 内存结果嵌字段
@pytest.mark.parametrize("runner", [
    lambda: comm.run(write=False)["result"],
    lambda: cs.run(write=False),
    lambda: ga.global_analysis(write=False),
    lambda: nmi.nmi_vs_baseline(write=False),
])
def test_inmemory_result_carries_fingerprint_equal_recompute(runner):
    art = runner()
    fp = art.get(sf.FIELD)
    assert fp is not None
    assert fp["digest"] == sf.compute_input_fingerprint()["digest"]


# ------------------------------------------------------------------ 落盘产物 + dormant
def test_regenerated_disk_artifacts_embed_fingerprint():
    comm.run(write=True)
    cs.run(write=True)
    ga.global_analysis(write=True)
    nmi.nmi_vs_baseline(write=True)
    expected = sf.compute_input_fingerprint()
    for name in JSON_ARTIFACTS:
        d = json.loads((DATA_META_DIR / name).read_text(encoding="utf-8"))
        assert d[sf.FIELD] == expected                   # == 对当前输入重算值
        assert d["fingerprint_self_check"]["status"] == "ok"
        # dormant：未注入基准时消费端仅记录不判脏
        assert consumer_fp.verify_json_file(
            DATA_META_DIR / name, name=name)["status"] == "observed_no_baseline"
        # 门注入一致基准 → ok
        assert consumer_fp.verify_json_file(
            DATA_META_DIR / name, name=name, expected=expected["digest"])["status"] == "ok"


def test_stale_baseline_fails_fast_on_disk_artifact():
    comm.run(write=True)
    with pytest.raises(consumer_fp.InputFingerprintMismatch):
        consumer_fp.verify_json_file(
            DATA_META_DIR / "community_result.json", name="r", expected="STALE-DIGEST")


# ------------------------------------------------------------------ 关键护栏：边文件不被污染
def test_community_edges_jsonl_not_polluted():
    comm.run(write=True)
    p = DATA_META_DIR / "community_edges.jsonl"
    n = 0
    for line in p.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        r = json.loads(line)
        assert "src" in r and "dst" in r               # 每行仍是可被 load_kept_pairs 读的边记录
        assert sf.FIELD not in r                        # 未混入顶层指纹对象
        n += 1
    assert n == comm.run(write=False)["result"]["ingestion"]["kept_edges"]
