"""GraphRAG M3 · 社区/全局分析层（确定性，无 LLM）。

唯一写入者：`rag-graph-analyst`（M3）。上游只读消费 M1/M2 交付：
- 底座 = `graphrag/lineage/load_graph_with_lineage()`（M1 快照 l0_graph.json +
  M2 REFERENCES 合并，单一事实源，不回写 l0_*）。
- 边口径 = 默认可见 **440 REFERENCES**（P1 修复后基线，非旧 446）+ M1 RELATES_TO，
  Table 级融合、`confidence` 加权。

硬约束（`spec/stack-options.md` §3 / `evidence-confidence-map.md` §1.1 / 任务书）：
- 社区检测**仅喂 `confidence ≥ 0.45`** 的边；低置信(59)/存疑/队列(110)**入算法 = 0**，
  并记录被排除计数（`communities.ingest_edges` 的 `exclusion` 报告）。
- 实现 = **networkx 3.7 `greedy_modularity_communities`**（零新依赖；
  **未** `pip install python-louvain/igraph/leidenalg`）→ 依赖策略 = 已定候选的退化实现。
- 涌现簇 vs `00` 手工 **18 域(+OT)** 做 NMI 对照（`nmi_vs_baseline.py`）：00 仍为
  ground truth，社区=分析视图，**不覆盖**权威分组。
- LLM 摘要后置、默认不启用（`summarizer.py` stub，标 [待确认·需 LLM]）。

子模块：
- `communities`：融合子图 + greedy_modularity + 规模/内外边比 + 落盘（data/meta 镜像）。
- `community_summary`：结构化社区画像（成员/主导域/枢纽/桥接），确定性。
- `global_analysis`：全局 map-reduce 确定性版（枢纽 TopN / 域间最紧耦合 / 按域综述）。
- `nmi_vs_baseline`：涌现社区 vs 18 域 NMI + 混淆映射 + 差异簇可解释清单。
- `summarizer`：LLM 摘要接口 stub（默认禁用）。
"""

from .communities import (  # noqa: F401
    ingest_edges, build_fused_graph, detect_communities, run as run_communities,
    REFERENCES_TYPE, RELATES_TO_TYPE,
)
from .community_summary import profile_communities  # noqa: F401
from .global_analysis import global_analysis  # noqa: F401
from .nmi_vs_baseline import nmi_vs_baseline  # noqa: F401

__all__ = [
    "ingest_edges", "build_fused_graph", "detect_communities", "run_communities",
    "profile_communities", "global_analysis", "nmi_vs_baseline",
    "REFERENCES_TYPE", "RELATES_TO_TYPE",
]
