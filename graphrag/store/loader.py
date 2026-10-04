"""M1 · 从稳定 JSON 快照回载 PropertyGraph（跨进程 L1 查询的闭环）。

`store.graph` 负责 解析器→图→JSON 导出；本模块负责 JSON→图，使落盘的
`graphrag/data/l0_graph.json` 可被检索侧只读回载，无需重跑确定性解析。
边经 `add_edge` 重建以复原 `_out/_in` 邻接索引（neighbors/遍历依赖）。
"""

from __future__ import annotations

import json
from pathlib import Path

from .graph import PropertyGraph


def load_graph_json(path) -> PropertyGraph:
    """从 export_graph 生成的 JSON 快照回载为可查询的 PropertyGraph。

    仅重建 nodes + edges + 邻接索引；不重算派生统计（counts 可即时调用）。
    """
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    g = PropertyGraph()
    for node in data.get("nodes", []):
        props = {k: v for k, v in node.items() if k not in ("node_id", "label")}
        g.add_node(node["node_id"], node["label"], **props)
    for edge in data.get("edges", []):
        props = {k: v for k, v in edge.items() if k not in ("src", "dst", "type")}
        g.add_edge(edge["src"], edge["dst"], edge["type"], **props)
    return g
