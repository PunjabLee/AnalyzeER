"""M3 · LLM 社区/全局叙述摘要接口 —— **stub，默认禁用** [待确认·需 LLM]。

诚实边界（`spec/stack-options.md` §5、`design-plan.md` §1.2/R-0、§4.2/R6）：
- **M3 交付为确定性结构摘要**（`community_summary.py` / `global_analysis.py` 已产出），
  本模块**不**在确定性栈内运行，仅提供后置 LLM 叙述的**接口占位**。
- LLM 抽取层冗余可省（关系已结构化落盘，再抽只失真无增量）。
- 若未来启用 LLM 叙述：供应商/模型/是否本地 = **[待确认]**；且**不得新建来源外**
  表/字段/关系，须回填 `SUPPORTED_BY`，否则降级为 `[待确认]`。
- 因此 `ENABLED=False`，任何调用直接抛 `NotImplementedError`，防止把自由文本摘要
  误当作可回溯的确定性结论。
"""

from __future__ import annotations

#: 默认禁用（确定性栈不依赖 LLM）。启用需业务方在 M3 后续门决策。
ENABLED = False

DISCLAIMER = (
    "[待确认·需 LLM] 本接口为后置叙述占位，默认禁用。M3 权威交付为确定性结构化摘要"
    "（community_profiles / global_analysis / nmi_vs_baseline）；LLM 叙述不得新建来源外"
    "实体/关系，须回填 SUPPORTED_BY，否则降级 [待确认]。"
)


def generate_community_narrative(profile: dict, **_llm_kwargs) -> str:
    """对单个社区画像生成自然语言叙述 —— **未实现**（需 LLM，默认禁用）。"""
    raise NotImplementedError(
        f"LLM 社区叙述未启用。{DISCLAIMER} 请使用 community_summary.profile_communities "
        "的结构化字段（确定性）。"
    )


def generate_global_narrative(global_result: dict, **_llm_kwargs) -> str:
    """全局 map-reduce 的自由文本综述 —— **未实现**（需 LLM，默认禁用）。"""
    raise NotImplementedError(
        f"LLM 全局叙述未启用。{DISCLAIMER} 确定性 map-reduce 结论见 global_analysis.py。"
    )


def status() -> dict:
    return {
        "enabled": ENABLED,
        "stage": "post-LLM (optional, M3 后)",
        "provider_model": "[待确认]",
        "hard_constraints": [
            "不得新建来源外的表/字段/关系（§4.2/R6）",
            "输出须可回溯 SUPPORTED_BY，否则降级 [待确认]",
            "LLM 抽取层冗余可省（R-0），非本期交付",
        ],
        "disclaimer": DISCLAIMER,
    }
