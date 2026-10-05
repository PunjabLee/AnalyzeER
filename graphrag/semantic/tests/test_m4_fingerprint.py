"""M4（P1-2）· `semantic/fingerprint.py` 消费侧校验契约单测。

与 `test_m4_semantic_nl.py` 的分工：本文件只测**指纹接口本身**的五态语义
（含 strict-on-enable：声明基准后上游未嵌入 = 无法校验 = fail-fast）；
消费点挂接（`m3_p2_handoff` / `NLRouter.l2`）由 `test_m4_semantic_nl.py` 覆盖。

关键诚实边界：
- 消费侧**不生产/不改写**指纹，也不得因校验而修改 M3 自有文件（只读）；
- 默认（无基准）不破坏渐进上线现状：放行留痕；
- 基准一旦声明（显式入参或 env）即转强制校验：缺失或不一致都拒绝消费。
"""

from __future__ import annotations

import json

import pytest

from graphrag.semantic import fingerprint as fp


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch):
    monkeypatch.delenv(fp.ENV_EXPECTED, raising=False)


def test_field_and_env_names_are_the_frozen_contract():
    assert fp.FIELD == "input_fingerprint"
    assert fp.ENV_EXPECTED == "GRAPHRAG_EXPECTED_INPUT_FINGERPRINT"


# ------------------------------------------------------------- 态①：缺失 + 无基准 → 放行留痕
def test_absent_without_baseline_passes_with_trace():
    rec = fp.verify_input_fingerprint({"q1_top_hubs": []}, name="x")
    assert rec["status"] == "absent_upstream_not_embedded"
    assert rec["baseline_declared"] is False            # 明示：未开校验，故放行
    assert "M3/eval" in rec["note"]                     # 责任在生产者侧，不静默


# ------------------------------------------------------------- 态②：缺失 + 已声明基准 → fail-fast
def test_absent_with_explicit_baseline_fails_fast():
    """strict-on-enable：开了强校验却拿到未嵌入字段的产物 = 无从校验 → 拒绝（假安全感不可接受）。"""
    with pytest.raises(fp.InputFingerprintMismatch) as ei:
        fp.verify_input_fingerprint({"no_field": 1}, name="community_result.json",
                                    expected="real-digest")
    msg = str(ei.value)
    assert "community_result.json" in msg and "无法校验" in msg
    assert fp.ENV_EXPECTED in msg                       # 指回可操作的撤除路径


def test_absent_with_env_baseline_fails_fast(monkeypatch):
    monkeypatch.setenv(fp.ENV_EXPECTED, "digest-from-gate")
    with pytest.raises(fp.InputFingerprintMismatch):
        fp.verify_input_fingerprint({}, name="global_analysis")


# ------------------------------------------------------------- 态③：存在 + 无基准 → 观测不判脏
def test_present_without_baseline_records_only():
    rec = fp.verify_input_fingerprint({"input_fingerprint": "abc123"}, name="x")
    assert rec["status"] == "observed_no_baseline" and rec["fingerprint"] == "abc123"
    assert rec["baseline_declared"] is False


# ------------------------------------------------------------- 态④⑤：存在 + 基准 → ok / fail-fast
def test_present_matching_baseline_ok():
    rec = fp.verify_input_fingerprint({"input_fingerprint": "abc123"}, name="x",
                                      expected="abc123")
    assert rec["status"] == "ok" and rec["baseline_declared"] is True


def test_present_mismatching_baseline_fails_fast():
    with pytest.raises(fp.InputFingerprintMismatch, match="不一致"):
        fp.verify_input_fingerprint({"input_fingerprint": "abc123"}, name="x",
                                    expected="zzz999")


def test_env_baseline_can_flip_observed_to_ok_or_red(monkeypatch):
    art = {"input_fingerprint": "abc123"}
    monkeypatch.setenv(fp.ENV_EXPECTED, "abc123")
    assert fp.verify_input_fingerprint(art, name="x")["status"] == "ok"
    monkeypatch.setenv(fp.ENV_EXPECTED, "stale-digest")
    with pytest.raises(fp.InputFingerprintMismatch):
        fp.verify_input_fingerprint(art, name="x")


# ------------------------------------------------------------- 字段形态兼容（生产者侧未定死）
@pytest.mark.parametrize("payload", [
    {"input_fingerprint": "d4"},
    {"input_fingerprint": {"digest": "d4"}},
    {"input_fingerprint": {"sha256": "d4"}},
    {"input_fingerprint": {"value": "d4"}},
])
def test_extract_accepts_str_or_wrapped_dict(payload):
    assert fp.extract_fingerprint(payload) == "d4"


@pytest.mark.parametrize("payload", [
    {}, {"input_fingerprint": None}, {"input_fingerprint": ""}, {"input_fingerprint": "   "},
    {"input_fingerprint": {}}, {"input_fingerprint": 123}, [], None, "raw string",
])
def test_extract_returns_none_for_missing_or_unusable(payload):
    assert fp.extract_fingerprint(payload) is None      # 不可用 → 视为未嵌入（不误判 ok）


def test_blank_env_is_not_a_baseline(monkeypatch):
    monkeypatch.setenv(fp.ENV_EXPECTED, "   ")
    assert fp.expected_from_env() is None               # 空串基准不触发强校验
    assert fp.verify_input_fingerprint({}, name="x")["status"] == "absent_upstream_not_embedded"


# ------------------------------------------------------------- 文件形态
def test_verify_json_file_absent_file_is_trace_not_error(tmp_path):
    rec = fp.verify_json_file(tmp_path / "nope.json", name="community_result.json")
    assert rec["status"] == "absent_file"               # 上游产物缺席：留痕非静默缺陷


def test_verify_json_file_reads_field(tmp_path):
    p = tmp_path / "artifact.json"
    p.write_text(json.dumps({"input_fingerprint": "file-digest"}), encoding="utf-8")
    assert fp.verify_json_file(p, name="a")["status"] == "observed_no_baseline"
    assert fp.verify_json_file(p, name="a", expected="file-digest")["status"] == "ok"
    with pytest.raises(fp.InputFingerprintMismatch):
        fp.verify_json_file(p, name="a", expected="other")


# ------------------------------------------------------------- 只读纪律（不得反写上游）
def test_verification_is_read_only(tmp_path):
    """校验不得改写产物文件/字段（生产者是 M3/eval，消费侧只读接住）。"""
    p = tmp_path / "upstream.json"
    raw = json.dumps({"input_fingerprint": "abc", "rows": [1, 2]})
    p.write_text(raw, encoding="utf-8")
    fp.verify_json_file(p, name="upstream", expected="abc")
    assert p.read_text(encoding="utf-8") == raw         # 字节级不变
    art = json.loads(raw)
    fp.verify_input_fingerprint(art, name="x", expected="abc")
    assert art == json.loads(raw)                       # 入参也不被就地篡改
