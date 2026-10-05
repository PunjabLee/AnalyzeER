"""GraphRAG M4 · 规则式 NL 编排前端（确定性；LLM 合成默认禁用）。

唯一写入者：`rag-semantic-orchestrator`（M4）。只读消费 M1（`search/l1.py`）+
M2 底座 + M3（`community/*`）。子模块：
- `nl_router`：意图识别（查表/查字段/查关系/血缘影响/社区综述/枢纽耦合）→ 参数抽取
  → 结构化调用 L1/L2 → 用检索/遍历结果直接作答（默认 conf≥0.45 / show_uncertain=False / 多跳≤3）。
- `llm_synthesis`：后置 LLM 叙述合成接口 stub（`ENABLED=False`，[待确认·需 LLM]）。
- `demo`：六类 UC 自然语言入口端到端跑通（供 eval-gate 黄金集回归消费入口）。
"""

from .nl_router import NLRouter, run_demo_queries, INTENTS  # noqa: F401
from . import llm_synthesis  # noqa: F401

__all__ = ["NLRouter", "run_demo_queries", "INTENTS", "llm_synthesis"]
