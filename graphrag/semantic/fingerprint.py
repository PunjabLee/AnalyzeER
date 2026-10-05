"""M4（P1-2）· 上游产物 `input_fingerprint` 的**消费侧校验接口**（只读、fail-fast）。

背景：M3/eval 侧（**生产者**）将陆续在 `community_*` / 全局分析结果中嵌入
`input_fingerprint`（输入集指纹），用于阻断"拿脏上游作答"。本模块**只做消费校验**，
不生成/不改写指纹、**不改 M3 自有文件**（生产者嵌入由 `rag-community-analyst` /
`rag-eval-gate` 负责；此处仅接口预留 + 挂接消费点）。

策略（四态留痕 + 两态 fail-fast；**strict-on-enable**）：

| 上游字段 | 期望基准 | 结果 |
|---|---|---|
| 缺失 | 未声明（默认） | `absent_upstream_not_embedded`：放行留痕（M3 尚未嵌入的现实，渐进上线期） |
| 缺失 | **已声明** | **raise**：已开启强校验却无从校验 = 假安全感，拒绝消费（要求生产者先嵌入或撤基准） |
| 存在 | 未声明 | `observed_no_baseline`：记录指纹不判脏（基准未由编排/门注入） |
| 存在 | 一致 | `ok` |
| 存在 | 不一致 | **raise `InputFingerprintMismatch`**（fail-fast，拒绝拿脏产物作答） |

"声明期望基准"= 显式入参 `expected` 或环境变量 `GRAPHRAG_EXPECTED_INPUT_FINGERPRINT`
（编排/门禁注入，保证可复现）。故**默认不破坏现状**：只有生产者嵌入字段后由门打开基准，
校验才转为强制。字段形态兼容裸字符串或 `{"digest"|"sha256"|"value": ...}`。
"""

from __future__ import annotations

import json
import os
from pathlib import Path

#: 上游产物中约定的指纹字段名（生产者侧新增，消费侧只读接住）
FIELD = "input_fingerprint"
#: 期望基准的环境变量（由编排者/门禁注入，非本模块产出）
ENV_EXPECTED = "GRAPHRAG_EXPECTED_INPUT_FINGERPRINT"


class InputFingerprintMismatch(RuntimeError):
    """上游产物 `input_fingerprint` 与期望基准不一致（或已开校验却缺字段）→ fail-fast。"""


def expected_from_env() -> str | None:
    v = os.environ.get(ENV_EXPECTED)
    return v.strip() if v and v.strip() else None


def extract_fingerprint(artifact) -> str | None:
    """从产物 dict 中接住指纹字段（str 或 {"digest"/"sha256"/"value"} 形态）；缺失→None。"""
    if not isinstance(artifact, dict):
        return None
    fp = artifact.get(FIELD)
    if isinstance(fp, dict):
        fp = fp.get("digest") or fp.get("sha256") or fp.get("value")
    if isinstance(fp, str) and fp.strip():
        return fp.strip()
    return None


def verify_input_fingerprint(artifact, *, name: str,
                             expected: str | None = None) -> dict:
    """消费校验（接口预留）。返回留痕 dict；不可校验/不一致时抛
    `InputFingerprintMismatch`（fail-fast，不返回）。"""
    if expected is None:
        expected = expected_from_env()
    found = extract_fingerprint(artifact)
    if found is None:
        if expected is not None:                # strict-on-enable：开了校验却无从校验
            raise InputFingerprintMismatch(
                f"{name} 未携带 {FIELD}，而消费侧已声明期望基准 {expected!r}："
                "→ 无法校验（等同假安全感），fail-fast。请由生产者（M3/eval）嵌入该字段，"
                f"或撤除 {ENV_EXPECTED}/显式 expected 以回到『放行留痕』的渐进上线态。")
        return {"artifact": name, "status": "absent_upstream_not_embedded",
                "baseline_declared": False,
                "note": ("生产者尚未嵌入 input_fingerprint（M3/eval 侧职责）；"
                         "消费侧无期望基准 → 放行留痕，不视为脏")}
    if expected is None:
        return {"artifact": name, "status": "observed_no_baseline",
                "fingerprint": found, "baseline_declared": False,
                "note": (f"已观测指纹但无期望基准（显式 expected 或 {ENV_EXPECTED}）"
                         "→ 记录不判脏；基准到位后自动升级为比对")}
    if found != expected:
        raise InputFingerprintMismatch(
            f"{name} 的 {FIELD}={found!r} ≠ 期望基准 {expected!r}："
            "上游产物与本基准不一致 → fail-fast，拒绝消费（勿拿脏图作答）")
    return {"artifact": name, "status": "ok", "fingerprint": found,
            "baseline_declared": True}


def verify_json_file(path, *, name: str, expected: str | None = None) -> dict:
    """对 JSON 文件产物做同一校验（文件不存在 → `absent_file` 留痕，不阻断）。"""
    p = Path(path)
    if not p.exists():
        return {"artifact": name, "status": "absent_file",
                "note": f"{p} 不存在，无从校验（上游产物缺席留痕，非缺陷静默）"}
    data = json.loads(p.read_text(encoding="utf-8"))
    return verify_input_fingerprint(data, name=name, expected=expected)
