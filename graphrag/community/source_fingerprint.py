"""M3（终轮 CodeReview P1-2 · **生产侧**）· 派生产物 `input_fingerprint` 嵌入。

背景：`community_*` / 全局分析是**磁盘派生产物**，此前不含源指纹；下游消费（eval-gate
`run_golden` 拿磁盘 `community_edges.jsonl` 比实时遍历）存在陈旧/双源**静默失真**风险。
本模块为 M3 自有 JSON 派生产物在**顶层**嵌入 *输入集指纹*，使其**可校验**。

与消费侧 `graphrag/semantic/fingerprint.py` **同构**（避免生产者/消费者格式分叉）：
- 字段名 `input_fingerprint` 同名（本模块 `FIELD`）；
- 聚合摘要置于 `digest` 键——消费端 `extract_fingerprint` 对 dict 优先取
  `digest|sha256|value`，故能接住本产物。

**strict-on-enable 保持 dormant（关键红线）**：本生产层**默认不设置/不注入任何 expected
基准**（不写 `GRAPHRAG_EXPECTED_INPUT_FINGERPRINT`）。嵌入字段即赋予『可校验性』，但
是否强制比对由 eval/主线程决定；未激活前不破坏现状。

sha256 配方（确定性、可复现、**零新依赖**，仅 stdlib hashlib）：
1. 真实磁盘输入（单一事实源，M3 全部 JSON 产物运行期共读同两份）：
   - `graphrag/data/l0_graph.json`（M1 快照；00 §四 手工 18 域权威已落盘于 node.domain）
   - `graphrag/data/meta/lineage_edges.jsonl`（M2 REFERENCES）
   诚实口径：只指纹**运行期真实读到**的文件；M3 不直读 `er-model/00`（域权威经 M1 落入
   l0_graph.json），故不纳入 er-model——见交付回报『未决』。
2. 逐文件：`sha256(文件原始字节).hexdigest()`；files 按相对 ROOT 路径升序（稳定）。
3. 聚合：`sha256("\\n".join(f"{relpath}\\t{file_sha256}"))` 的 hexdigest，置于 `digest`。
"""

from __future__ import annotations

import hashlib
from pathlib import Path

from ..ingest.config import ROOT, L0_GRAPH_JSON, DATA_META_DIR

#: 与消费侧 semantic/fingerprint.py:FIELD 同名（格式契约，勿分叉）
FIELD = "input_fingerprint"
#: 消费端 extract_fingerprint 认得的聚合摘要键（本产物用 digest 承载）
DIGEST_KEYS = ("digest", "sha256", "value")
ALGO = "sha256"
SOURCE = "graphrag/community/source_fingerprint.py"

#: M3 JSON 派生产物的**真实磁盘输入**（均相对 ROOT 记录路径）
INPUT_FILES: tuple[Path, ...] = (
    L0_GRAPH_JSON,
    DATA_META_DIR / "lineage_edges.jsonl",
)


def rel_to_root(path) -> str:
    """相对 ROOT 的 POSIX 路径（跨机可移植、稳定排序键）。

    若路径不在 ROOT 之下（如临时/外部缺失文件），回退为绝对 POSIX 串——
    保证缺失依赖的报错信息仍能安全渲染，不被 `relative_to` 的 ValueError 掩盖。
    """
    pp = Path(path).resolve()
    try:
        return pp.relative_to(ROOT.resolve()).as_posix()
    except ValueError:
        return pp.as_posix()


def file_sha256(path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def compute_input_fingerprint(input_files=INPUT_FILES) -> dict:
    """对真实磁盘输入集算源指纹。

    缺依赖文件 → **FileNotFoundError**（fail-fast，行为明确）：绝不对**未读到**的源
    伪造指纹；宁可拒绝产出，也不落一个不可信/不可校验的字段。
    """
    files: list[dict] = []
    for p in input_files:
        pp = Path(p)
        if not pp.exists():
            raise FileNotFoundError(
                f"input_fingerprint 依赖的输入文件缺失（不可对未读到的源嵌入指纹）："
                f"{rel_to_root(pp)}")
        files.append({"path": rel_to_root(pp), "sha256": file_sha256(pp)})
    files.sort(key=lambda f: f["path"])
    canon = "\n".join(f"{f['path']}\t{f['sha256']}" for f in files)
    digest = hashlib.sha256(canon.encode("utf-8")).hexdigest()
    return {"algo": ALGO, "files": files, "digest": digest, "source": SOURCE}


def self_check(fingerprint: dict, input_files=INPUT_FILES) -> dict:
    """自校验（防『边写边算』输入漂移）：

    - 以**同一输入集即时重算**，必须与嵌入值逐字段相等（写入前磁盘输入 == 计算指纹时
      读到的磁盘输入；本层从不改写输入文件，只写产物，故二者必然一致）；
    - 聚合摘要必须落在消费端可接住的键（`DIGEST_KEYS` 之一非空）→ 保证『嵌入即可校验』。
    不一致 → `AssertionError`（fail-fast，拒绝落陈旧/错位指纹）。返回校验留痕。
    """
    recompute = compute_input_fingerprint(input_files)
    if recompute != fingerprint:
        raise AssertionError(
            "input_fingerprint 自校验失败：嵌入值与即时重算的输入集指纹不一致"
            "（疑似边写边算 / 输入漂移）——拒绝落盘。")
    if not any(fingerprint.get(k) for k in DIGEST_KEYS):
        raise AssertionError(
            f"input_fingerprint 缺少消费端可识别的聚合摘要键 {DIGEST_KEYS}。")
    return {
        "status": "ok",
        "files_checked": [f["path"] for f in fingerprint["files"]],
        "digest": fingerprint["digest"],
        "note": ("write=True 产出前已对 embedding 的即时输入集断言一致（防边写边算漂移）；"
                 "未注入 expected 基准（strict-on-enable 保持 dormant，激活由 eval/主线程决定）。"),
    }


def embed(result: dict, *, write: bool, input_files=INPUT_FILES) -> dict:
    """把 `input_fingerprint` 嵌入 result **顶层**（所有 JSON 产物一致）。

    - 无论 write 与否都嵌入（产物即具备可校验性；write=False 的内存结果同样带字段，
      便于测试直接断言）；
    - 仅当 `write=True` 时运行 `self_check`（落盘前的即时一致性断言）并把留痕
      `fingerprint_self_check` 一并写入顶层（不污染指纹计算本身，故不影响重算相等）。
    原地写回并返回 result。
    """
    fingerprint = compute_input_fingerprint(input_files)
    result[FIELD] = fingerprint
    if write:
        result["fingerprint_self_check"] = self_check(fingerprint, input_files)
    return result
