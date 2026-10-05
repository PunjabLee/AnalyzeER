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

导出消歧（终轮 CodeReview **P2-③** / RUNBOOK 风险表 **N-7**、§7.6-③）：
`global_analysis.py` / `nmi_vs_baseline.py` 的**模块名与其中的主函数同名**，旧写法
`from .global_analysis import global_analysis` 会把**包属性**由子模块**覆盖**成函数，
于是"按属性名打补丁 / 反射"的消费方静默拿到函数对象（例：eval ①档同源探针需 patch
模块属性 `build_subgraph`，函数没有该属性 → 0 捕获；`_m3_gate.py` 的
`from graphrag.community import nmi_vs_baseline as n` 会撞 `AttributeError`）。
现约定：**包属性 = 子模块对象**（可 patch、可取内部符号），函数以不重名别名导出：
- 调用入口 `graphrag.community.run_global_analysis(...)` ＝
  `graphrag.community.global_analysis.global_analysis(...)`（同一函数对象，别名不改语义；
  命名与既有 `run as run_communities` 一致）。对照层同理 `run_nmi_vs_baseline`。
- 模块入口 `graphrag.community.global_analysis` ＝ 子模块（**非**函数）。
- 既有 import 路径零影响：`from graphrag.community.global_analysis import global_analysis`
  （nl_router / semantic 走这条）与 `importlib.import_module("graphrag.community…")`
  （eval 探针 / M3 测试走这条）取到的仍是子模块与其中的同名函数。
- 唯一语义变化：`from graphrag.community import global_analysis`（或 `nmi_vs_baseline`）
  现得**模块**；若仍当函数调用 → `TypeError: 'module' object is not callable`（响亮失败，
  不再静默拿错对象）。仓内**无**该用法的消费方（grep 实测），仓外若有需改用 `run_*` 别名。
"""

from .communities import (  # noqa: F401
    ingest_edges, build_fused_graph, detect_communities, run as run_communities,
    REFERENCES_TYPE, RELATES_TO_TYPE,
)
from .community_summary import profile_communities  # noqa: F401
# P2-③：不再用同名函数覆盖子模块属性；包属性 `global_analysis`/`nmi_vs_baseline` 保持为子模块
from .global_analysis import global_analysis as run_global_analysis  # noqa: F401
from .nmi_vs_baseline import nmi_vs_baseline as run_nmi_vs_baseline  # noqa: F401

__all__ = [
    # 融合 / 检测 / 画像（函数）
    "ingest_edges", "build_fused_graph", "detect_communities", "run_communities",
    "profile_communities",
    # 全局与对照入口（函数；P2-③ 消歧后不再与同名子模块争用包属性）
    "run_global_analysis", "run_nmi_vs_baseline",
    # 子模块（按**模块对象**消费：打补丁 / 反射 / 取内部符号）
    "communities", "community_summary", "global_analysis", "nmi_vs_baseline",
    "source_fingerprint",
    # 常量
    "REFERENCES_TYPE", "RELATES_TO_TYPE",
]
