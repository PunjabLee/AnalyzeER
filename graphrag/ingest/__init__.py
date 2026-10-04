"""GraphRAG M1 · ingest 包（确定性解析器 + 数量等式）。

把 er-model/* + test_erp.sql 的结构事实解析为图构件。唯一依据：graphrag/spec/（M0 冻结）。
装载编排 `pipeline`（依赖 store 层）请按 `from graphrag.ingest.pipeline import run` 显式导入，
不在此急切导出以避免 ingest↔store 循环依赖。
"""

from .config import (  # noqa: F401
    CONFIDENCE_DEFAULT_MIN,
    MAX_HOPS,
    EVIDENCE_ORDER,
    EVIDENCE_CONFIDENCE,
    map_evidence,
    confidence_for,
    EQ,
    DOMAIN_DECLARED,
)
from .tiering import classify_tier, family_of, family_base, prefix_family, tier_counts  # noqa: F401
from .ddl_parser import parse_ddl, ddl_metrics  # noqa: F401
from .domain_parser import parse_domain_membership, domain_counts_report  # noqa: F401
from .relation_parser import parse_relations  # noqa: F401
from .derived_parser import parse_derived  # noqa: F401
from .issue_parser import parse_issues  # noqa: F401
from .header_parser import parse_logical_model, crosscheck_with_ddl  # noqa: F401
from .assertions import run_assertions, AssertReport  # noqa: F401
