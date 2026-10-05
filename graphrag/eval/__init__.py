"""GraphRAG 评测门禁（rag-eval-gate 唯一持有）。

- `golden/`：六类 UC + 全局 + 超范围守卫黄金问答集（题目实例 + 期望命中，逐题可回溯）。
  唯一写入者＝rag-eval-gate；执行专家**只读**（禁改题目/阈值，防“考生自己出题”）。
- `run_golden.py`：确定性基线测法 harness（`spec/eval-baseline.md` §2）；阈值一律 `[待确认]`，只报实测。
"""
