"""M4 · NL 编排的**可选 LLM 合成**接口 —— **stub，默认禁用** [待确认·需 LLM]。

诚实边界（`spec/stack-options.md`、`design-plan.md` §1.2/R-0、§4.2/R6；与 M3
`community/summarizer.py` 同族纪律）：
- **M4 权威交付为规则式确定性 NL**（`graphrag/nl/nl_router.py`）：意图识别 → 结构化调用
  L1(检索/遍历)+L2(社区/全局) → **直接用检索/遍历结果作答**。全程无生成式文本。
- LLM 仅作**最终叙述合成**的**后置接口占位**：把已 grounding 的结构化答案改写为自然语言。
- **红线**：即便启用，LLM **不得**新建来源外的表/字段/关系/血缘；输出须逐条回填
  `SUPPORTED_BY`（可回溯 `er-model/*` 或 `test_erp.sql`），否则降级 `[待确认]`。
  故 LLM **绝不**参与“关系是否成立”的判定，只改写已判定为真的有出处的答案。
- 供应商/模型/是否本地 = **[待确认·需 LLM]**。因此 `ENABLED=False`，任何调用直接抛
  `NotImplementedError`，防止把自由文本误当作可回溯的确定性结论。
"""

from __future__ import annotations

#: 默认禁用（确定性栈不依赖 LLM）。启用需业务方在门决策后放宽输入。
ENABLED = False

DISCLAIMER = (
    "[待确认·需 LLM] 本接口为后置叙述占位，默认禁用。M4 权威交付为规则式确定性 NL "
    "（nl_router.answer → 结构化调用 L1/L2，用检索/遍历结果直接作答）。LLM 合成不得新建"
    "来源外实体/关系，须回填 SUPPORTED_BY，否则降级 [待确认]。"
)


def synthesize(structured_answer: dict, **_llm_kwargs) -> str:
    """把 NLRouter 的结构化答案改写为自然语言段落 —— **未实现**（需 LLM，默认禁用）。"""
    raise NotImplementedError(
        f"LLM 叙述合成未启用。{DISCLAIMER} 请直接使用 nl_router.NLRouter().answer(...) 的"
        "结构化字段（确定性、带出处）。"
    )


def compose_multi(answers: list[dict], **_llm_kwargs) -> str:
    """多答案聚合叙述 —— **未实现**（需 LLM，默认禁用）。"""
    raise NotImplementedError(
        f"LLM 多答案合成未启用。{DISCLAIMER}"
    )


def status() -> dict:
    return {
        "enabled": ENABLED,
        "stage": "post-LLM synthesis (optional)",
        "provider_model": "[待确认·需 LLM]",
        "hard_constraints": [
            "不得新建来源外的表/字段/关系/血缘（§4.2/R6）",
            "输出须逐条可回溯 SUPPORTED_BY，否则降级 [待确认]",
            "LLM 仅改写、不判定关系真伪；确定性栈为权威",
            "指标/KPI/术语表超范围，LLM 亦不得生成（R-9/R-10）",
        ],
        "disclaimer": DISCLAIMER,
    }
