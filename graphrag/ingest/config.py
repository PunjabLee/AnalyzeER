"""M1 · 全局常量与契约固化值（唯一依据：graphrag/spec/ M0 冻结）。

所有数值均来自 M0 实测（非约定）。禁止在本文件引入未实测的关系符/表头/计数。
"""

from __future__ import annotations

import re
from pathlib import Path

# --------------------------------------------------------------------------
# 路径（macOS；输入边界仅 er-model/* + test_erp.sql，Agents.md §0）
# --------------------------------------------------------------------------
ROOT = Path(__file__).resolve().parents[2]          # .../AnalyzeER
ER_MODEL = ROOT / "er-model"
SQL_FILE = ROOT / "test_erp.sql"
SPEC_DIR = ROOT / "graphrag" / "spec"

DOMAIN_01 = ER_MODEL / "01-ER图"
DOMAIN_03 = ER_MODEL / "03-逻辑数据模型"
FILE_00 = ER_MODEL / "00-总览与分组清单.md"
FILE_04 = ER_MODEL / "04-C级备份与测试表清单.md"
FILE_05 = ER_MODEL / "05-跨域核心关系总览.md"

GRAPH_DIR = ROOT / "graphrag" / "data"
OUT_META_DIR = ROOT / "graphrag" / "out" / "meta"     # 核心交付（注意 .gitignore:18 out/）
DATA_META_DIR = GRAPH_DIR / "meta"                    # 可提交镜像（graphrag/data 未被忽略）
STORE_DIR = GRAPH_DIR                                  # sqlite + json 快照落此

L0_GRAPH_JSON = GRAPH_DIR / "l0_graph.json"
L0_EDGES_JSON = GRAPH_DIR / "l0_edges.jsonl"
FTS_DB = GRAPH_DIR / "l0_index.db"

# --------------------------------------------------------------------------
# 检索阈值（evidence-confidence-map.md §1.1；design-plan §5.2-1 / §6）
# --------------------------------------------------------------------------
CONFIDENCE_DEFAULT_MIN = 0.45      # 默认召回下限（name_inferred 及以上放行）
MAX_HOPS = 3                        # 多跳预算默认上限 [待确认]（本身标 [待确认]）
EVIDENCE_CAP = 0.95                 # 无物理 FK，confidence 上限锁 0.95（永不宣称约束）

# --------------------------------------------------------------------------
# 五级证据映射（evidence-confidence-map.md §1；区间为 design-plan 初设 [待确认]）
# 取区间中点作排序权重；上限 0.95。
# --------------------------------------------------------------------------
EVIDENCE_ORDER = [
    "comment_explicit",   # 0.85–0.95
    "index_backed",       # 0.70–0.85
    "name_inferred",      # 0.45–0.70
    "semantic_inferred",  # 0.20–0.45
    "unconfirmed",        # 0.00–0.20
]

EVIDENCE_CONFIDENCE = {
    "comment_explicit": 0.90,
    "index_backed": 0.775,
    "name_inferred": 0.575,
    "semantic_inferred": 0.325,
    "unconfirmed": 0.10,
}

# 文本 `[证据]` 标签片段 → evidence_level（evidence-confidence-map.md §1/§3）
_TAG_TO_LEVEL = [
    ("注释明示", "comment_explicit"),
    ("索引佐证", "index_backed"),
    ("索引", "index_backed"),
    ("命名推断", "name_inferred"),
    ("字段命名", "name_inferred"),
    ("命名", "name_inferred"),            # 裸标签 [命名]（01/D11 实测 ×6，原被静默降 unconfirmed）
    ("业务语义推断", "semantic_inferred"),
    ("语义推断", "semantic_inferred"),
    ("语义", "semantic_inferred"),        # 裸标签 [语义]（01/D10 实测 ×2，与复合标签并存取强）
    ("待确认", "unconfirmed"),
]

# 归入 name_inferred 家族的标签串（用于 evidence_tags=["name"]）
_NAME_TOKENS = {"命名推断", "字段命名", "命名"}
_INDEX_TOKENS = {"索引佐证", "索引"}


def _rank(level: str) -> int:
    """证据级强度序号（越小越强）。"""
    return EVIDENCE_ORDER.index(level)


def map_evidence(desc: str) -> dict:
    """从关系线描述文本解析 evidence_level / evidence_tags / 特殊标记。

    返回 {evidence_level, evidence_tags, external_reference, cross_domain, polymorphic,
          discriminant, has_uncertain}。多标签并存取较高档（evidence-confidence-map §2）。
    """
    # 抽取所有方括号内容（含复合 `命名推断+索引`、`字段命名/索引佐证`、`跨域D09`、`外部系统`）
    brackets = re.findall(r"\[([^\]]+)\]", desc)
    blob = " ".join(brackets) + " " + desc

    levels = set()
    tags = set()
    for frag, lvl in _TAG_TO_LEVEL:
        if frag in blob:
            levels.add(lvl)
            if frag in _NAME_TOKENS:
                tags.add("name")
            if frag in _INDEX_TOKENS:
                tags.add("index")
            if frag == "注释明示":
                tags.add("comment")

    # 取最高档
    if levels:
        level = min(levels, key=_rank)
    else:
        level = "unconfirmed"  # 无标签 → 存疑，进待确认队列

    evidence_tags = sorted(tags)
    # 复合：name + index → 已取 index_backed（min rank），evidence_tags 保留两者
    external = "外部系统" in blob
    cross_domain = ("跨域" in blob) or bool(re.search(r"跨域", desc))
    polymorphic = "多态" in blob or "related_order_id" in desc
    discriminant = "order_type" if "order_type" in desc else None
    has_uncertain = "待确认" in blob

    return {
        "evidence_level": level,
        "evidence_tags": evidence_tags,
        "external_reference": external,
        "cross_domain": cross_domain,
        "polymorphic": polymorphic,
        "discriminant": discriminant,
        "has_uncertain": has_uncertain,
    }


def confidence_for(level: str) -> float:
    return min(EVIDENCE_CONFIDENCE.get(level, 0.10), EVIDENCE_CAP)


# --------------------------------------------------------------------------
# ER 关系连接符：census §3 冻结正则（左基数标记可选）
# --------------------------------------------------------------------------
CONNECTOR_CORE = r"(?:\|\||\|o|\}o)?(?:--|\.\.)(?:\|\||o\||o\{|\|\{)"
CONNECTOR_RE = re.compile(CONNECTOR_CORE)

# 行结构锚定（census §2.1「实体 连接符 实体 :」法，避免截尾假阳性）
RELATION_LINE_RE = re.compile(
    r"""^\s*(?P<left>[A-Za-z_][A-Za-z0-9_]*)\s+
        (?P<conn>""" + CONNECTOR_CORE + r""")\s+
        (?P<right>[A-Za-z_][A-Za-z0-9_]*)\s*:\s*
        "(?P<desc>[^"]*)"\s*$""",
    re.VERBOSE,
)

# 缺左基数标记（malformed）：连接符以 -- 或 .. 起始
LEFT_MISSING_RE = re.compile(r"^(--|\.\.)")

# 基数 token（census §5：cardinality 只从描述文本取，不从连接符反推）
CARDINALITY_RE = re.compile(r"(?<![\dA-Za-z])([1N]:[1NM])(?![\dA-Za-z])")

# via_column：优先 `tbl.col` 的 col，回退 `xxx_id/no/code`
_VIA_DOT_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*\.([A-Za-z_][A-Za-z0-9_]*)")
_VIA_SUFFIX_RE = re.compile(r"\b([A-Za-z0-9_]+(?:_id|_no|_code))\b")


# --------------------------------------------------------------------------
# 前缀归级（schema §3 / 铁律 N-2：先剔 C 级，再归 A/B）
# --------------------------------------------------------------------------
BAK14_RE = re.compile(r"_bak_\d{14}$")             # *_bak_<14位时戳> → C
TEST_PREFIX_RE = re.compile(r"^test_")              # test_ 前缀 → C
JF_PREFIX_RE = re.compile(r"^jf_")
LCAP_PREFIX_RE = re.compile(r"^lcap_")
QUARTZ_PREFIX_RE = re.compile(r"^N[0-9A-Fa-f]{6,}_")   # Quartz（N{hex}_）→ B
ACTIVITI_PREFIX_RE = re.compile(r"^P[0-9A-Fa-f]{6,}_")  # Activiti/Flowable（P{hex}_）→ B
APPCODE_SUFFIX_RE = re.compile(r"_[0-9a-f]{6}$")        # lcap 应用副本段 _{6hex}

# OT 疑似遗留（schema §1.2/§2.2；OT 内 entity*/seq_10/sheet1/temp）
OT_SUSPECTED_LEGACY = {"entity1", "entity12345", "seq_10", "sheet1", "temp"}

# --------------------------------------------------------------------------
# M0 实测数量等式目标（eval-baseline.md §3；全部为实测锚定值）
# --------------------------------------------------------------------------
EQ = {
    "ddl_table_total": 1322,
    "explicit_fk": 0,
    "pk_declared": 1311,
    "no_pk": 11,
    "a_level": 349,       # jf 332 + OT 17
    "jf_a": 332,
    "ot_a": 17,
    "b_level": 853,       # lcap 450 + Quartz 275 + Activiti 128
    "b_lcap": 450,
    "b_quartz": 275,
    "b_activiti": 128,
    "c_level": 120,       # _bak_ 117 + test_ 3
    "c_bak": 117,
    "c_test": 3,
    "registered_total": 1322,   # A+B+C
    "issue_total": 27,          # 05 实测（口径差 C-δ：非 30）
    "rel_01_total": 456,        # 01/ 关系线全量（口径 ξ）
    "rel_01_sub_443": 443,      # 6 字符合规子口径 ξ′
    "rel_01_malformed": 13,     # 缺左基数标记
    "rel_05_total": 33,         # 05 核心关系线（全 ||..o{）
    # 表头归一（header-normalization.md §1/§3/§4 实测）：
    "field_header_variants": 11,     # §3 字段表头签名种数
    "field_header_rows": 292,        # §3 字段表行数（逐条 103+60+59+27+14+12+6+5+4+1+1）
    "nonfield_header_variants": 8,   # §4 非字段辅助表头种数
    "nonfield_header_rows": 17,      # §4 非字段行数（7+3+2+1+1+1+1+1）
    "total_header_rows": 309,        # §1 全 |…| 表头签名行 = 19 种 / 309 行
}

# 每域声明表数（file-domain-map.md §1 / 00 §四）
DOMAIN_DECLARED = {
    "D01": 18, "D02": 21, "D03": 19, "D04": 33, "D05": 13, "D06": 15,
    "D07": 23, "D08": 27, "D09": 31, "D10": 24, "D11": 21, "D12": 11,
    "D13": 16, "D14": 34, "D15": 6, "D16": 10, "D17": 6, "D18": 4,
    "OT": 17,
}
