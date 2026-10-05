"""M2 · 字段级引用/结构级血缘（REFERENCES）。

边界（schema.md §4.3 / 任务书）：**引用即血缘** —— 列→列 REFERENCES；
不含字段变换/ETL 数据流级血缘（`sum(a)→b` 类），不含指标语义层。

公开入口（名称即契约，M3/M4 按此集成；P2 修复：此前文档承诺 `build_lineage`
而实际导出 `build`，现两名同一函数、并以 `__all__` 固化）：
- `build.build_lineage(write=True)` ＝ `build.build(...)`：M1 JSON 快照回载 → 抽取
  REFERENCES → 增量合并 → 断言（不过门不落盘）→ 导出。
- `build.load_graph_with_lineage(min_conf=CONFIDENCE_DEFAULT_MIN, show_uncertain=False)`：
  只读消费口径（M1 快照 + M2 血缘边，内存合并，不双写 l0_*）；默认即检索门（P1-3）。
- `extractor.LineageExtractor`：推断器（comment_explicit > doc_relation > index_backed >
  name_inferred > semantic_inferred；自环记法/存疑文档线只入队不成边）。
- `impact.reference_traverse / table_impact / table_lineage_profile`：反向可达影响分析
  （BFS；逐边 conf≥min_conf 硬门，path_score 仅排序）。
"""

from .build import (  # noqa: F401
    build,
    build_lineage,
    load_graph_with_lineage,
    merge_edges_into_graph,
    table_impact_summary,
)
from .extractor import (  # noqa: F401
    LineageExtractor,
    REFERENCES_TYPE,
    KIND_DOC_UNCERTAIN,
    KIND_SELF_LOOP,
)
from .impact import (  # noqa: F401
    build_reference_adj,
    reference_traverse,
    table_impact,
    table_lineage_profile,
)

__all__ = [
    "build", "build_lineage", "load_graph_with_lineage", "merge_edges_into_graph",
    "table_impact_summary",
    "LineageExtractor", "REFERENCES_TYPE", "KIND_SELF_LOOP", "KIND_DOC_UNCERTAIN",
    "build_reference_adj", "reference_traverse", "table_impact", "table_lineage_profile",
]
