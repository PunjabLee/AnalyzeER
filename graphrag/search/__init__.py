"""M1 · L1 检索：确定性（FTS/BM25 + 图遍历），无 LLM/向量。"""

from __future__ import annotations

from .l1 import L1Search

__all__ = ["L1Search"]
