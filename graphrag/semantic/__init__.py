"""GraphRAG M4 · 结构语义层（确定性、可回溯；不含指标语义层）。

唯一写入者：`rag-semantic-orchestrator`（M4）。只读消费 M1 快照 + M3 `community_*`。
子模块：
- `semantic_layer`：域=语义分区 / 实体三分类 / 字段 DDL COMMENT 含义 / D14+全局 KV 枚举码表 /
  Concept+REALIZED_BY；指标/KPI/术语表显式超范围（`OUT_OF_SCOPE`）。
- `m3_p2_handoff`：承接 M3 三项 P2（75 孤立 A 表名单落盘 / 混合簇降级声明 / 交黄金集入口）。
落盘：`graphrag/data/meta/semantic_*`（可提交镜像；`graphrag/out/semantic/` 被 .gitignore `out/` 忽略）。
"""

from .semantic_layer import (  # noqa: F401
    SemanticLayer, run as build_semantic_layer,
    SEMANTIC_LAYER_SCOPE, SCOPE_STATEMENT, OUT_OF_SCOPE, DOMAIN_NAMES,
)

__all__ = [
    "SemanticLayer", "build_semantic_layer",
    "SEMANTIC_LAYER_SCOPE", "SCOPE_STATEMENT", "OUT_OF_SCOPE", "DOMAIN_NAMES",
]
