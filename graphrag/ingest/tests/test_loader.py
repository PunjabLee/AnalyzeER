"""M1 · store 侧 JSON 快照回载往返单测（跨进程 L1 闭环）。"""

from __future__ import annotations

import pytest

from graphrag.ingest.pipeline import run
from graphrag.store.graph import export_graph
from graphrag.store.loader import load_graph_json
from graphrag.search.l1 import L1Search


@pytest.fixture(scope="module")
def graph_once():
    return run(write=False)["graph"]


def test_roundtrip_load_graph_json(tmp_path, graph_once):
    g = graph_once
    path = tmp_path / "g.json"
    export_graph(g, path)
    g2 = load_graph_json(path)
    # 往返守恒：节点/边计数、标签分布、tier 分布一致
    assert len(g2.nodes) == len(g.nodes)
    assert len(g2.edges) == len(g.edges)
    assert g2.counts() == g.counts()
    # 邻接索引复原：A 级表的关系边在回载图可继续查询
    a_tbl = next(n["name"] for n in g.nodes.values()
                 if n["label"] == "Table" and n.get("tier") == "A")
    r1 = L1Search(g, None).relations_of(a_tbl)
    r2 = L1Search(g2, None).relations_of(a_tbl)
    assert r1["count"] == r2["count"]
