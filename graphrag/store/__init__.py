"""M1 · store 包：内存属性图 + SQLite FTS5 持久化（不引入重型图库/向量库）。"""

from __future__ import annotations

from .graph import PropertyGraph, build_graph
from .loader import load_graph_json
from .fts import FTSIndex

__all__ = ["PropertyGraph", "build_graph", "load_graph_json", "FTSIndex"]
