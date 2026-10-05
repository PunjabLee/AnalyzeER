"""M3（终轮 CodeReview **P2-③** / RUNBOOK N-7、§7.6-③）· 包导出**同名遮蔽**消歧契约锁。

锁住三件事（均为**静态属性判定**，不跑重计算、不落盘）：
1. **包属性 = 子模块对象**：`graphrag.community.<子模块名>` 永不再是函数/其他对象，
   故"按属性名打补丁/反射"的消费方（eval ①档同源探针 patch `build_subgraph`、
   `_m3_gate.py` 式 `from graphrag.community import nmi_vs_baseline as n` 再取
   `n.nmi_vs_baseline`）拿到的一定是模块 —— 遮蔽一旦复发即 FAIL。
2. **调用入口以不重名别名导出**：`run_global_analysis` / `run_nmi_vs_baseline` /
   `run_communities` 可调用，且与其所在模块内的函数是**同一 object**（别名不改语义）。
3. **既有 import 路径不破**：`from graphrag.community.global_analysis import global_analysis`
   （nl_router L2 / semantic 走这条）仍得函数；`__all__` 全部可解析（同 M2 §契约锁口径）。
"""

from __future__ import annotations

import importlib
import pkgutil
import sys
import types

import pytest

import graphrag.community as pkg

SUBMODULES = sorted(
    m.name for m in pkgutil.iter_modules(pkg.__path__) if m.name != "tests")


@pytest.mark.parametrize("name", SUBMODULES)
def test_package_attribute_is_the_submodule_never_shadowed(name):
    mod = importlib.import_module(f"graphrag.community.{name}")
    attr = getattr(pkg, name, None)
    assert attr is mod, (
        f"包属性 `graphrag.community.{name}` 被同名函数/其他对象遮蔽（P2-③ 复发）："
        f"实际得到 {type(attr).__name__}，应为 module。")
    assert isinstance(attr, types.ModuleType)
    assert sys.modules[f"graphrag.community.{name}"] is attr


def test_eval_probe_can_patch_module_attribute():
    """eval ①档探针的前置条件：模块命名空间里 `build_subgraph` 可见且可 patch。"""
    for name in ("communities", "global_analysis", "nmi_vs_baseline"):
        mod = getattr(pkg, name)
        assert isinstance(mod, types.ModuleType), f"{name} 不是模块，探针会静默 0 捕获"
        bs = getattr(mod, "build_subgraph", None)   # 仅探测存在性，不改动、不调用
        assert callable(bs) or bs is None           # 未导入者保持 None（不报错）
    assert callable(getattr(pkg.global_analysis, "build_subgraph")), (
        "`global_analysis` 模块应带 build_subgraph（同源探针的挂载点）")


def test_call_entries_are_aliases_of_module_functions():
    assert pkg.run_global_analysis is pkg.global_analysis.global_analysis
    assert pkg.run_nmi_vs_baseline is pkg.nmi_vs_baseline.nmi_vs_baseline
    assert pkg.run_communities is pkg.communities.run
    for entry in (pkg.run_global_analysis, pkg.run_nmi_vs_baseline, pkg.run_communities):
        assert callable(entry)
    # 模块名与可调用导出名不得再重合（防"名字即契约"重新二义）
    callables = {n for n in vars(pkg)
                 if not isinstance(getattr(pkg, n), types.ModuleType)
                 and callable(getattr(pkg, n))}
    assert not (callables & set(SUBMODULES)), f"重合名重新出现：{sorted(callables & set(SUBMODULES))}"


def test_existing_import_paths_still_hold():
    # nl_router.l2() / semantic.m3_p2_handoff 的写法：from <子模块> import <同名函数> → 函数
    from graphrag.community.global_analysis import global_analysis
    from graphrag.community.nmi_vs_baseline import nmi_vs_baseline
    assert global_analysis is pkg.run_global_analysis
    assert nmi_vs_baseline is pkg.run_nmi_vs_baseline
    # eval 探针的写法：importlib 取模块对象 → 仍得模块
    assert importlib.import_module("graphrag.community.global_analysis") is pkg.global_analysis
    # _m3_gate.py 的写法：from <包> import <子模块名> → 现得模块（此前是函数，会 AttributeError）
    from graphrag.community import nmi_vs_baseline as n_by_pkg
    assert isinstance(n_by_pkg, types.ModuleType)
    assert callable(n_by_pkg.nmi_vs_baseline)


def test_all_names_are_resolvable():
    assert pkg.__all__
    for name in pkg.__all__:
        assert hasattr(pkg, name), f"__all__ 声明了不可解析的名字：{name}"
