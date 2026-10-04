"""M2 · 字段级引用/结构级血缘（REFERENCES）。

边界（schema.md §4.3 / 任务书）：**引用即血缘** —— 列→列 REFERENCES；
不含字段变换/ETL 数据流级血缘（`sum(a)→b` 类），不含指标语义层。

公开入口：
- `build.build_lineage(...)`：从 M1 JSON 快照回载图 → 抽取 REFERENCES → 增量合并 → 导出。
- `extractor.LineageExtractor`：推断器（comment_explicit > index_backed > name_inferred）。
- `impact.reference_traverse / table_impact / table_lineage_profile`：反向可达影响分析（BFS）。
- `build.load_graph_with_lineage()`：只读消费口径（M1 快照 + M2 血缘边，内存合并，不双写 l0_*）。
"""

from .extractor import LineageExtractor, REFERENCES_TYPE  # noqa: F401
from .impact import (  # noqa: F401
    reference_traverse,
    table_impact,
    table_lineage_profile,
)
