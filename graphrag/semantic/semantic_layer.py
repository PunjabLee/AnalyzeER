"""M4 · 结构语义层（确定性、全部可回溯；**不含指标语义层**）。

职责边界（`spec/schema.md` §1.4 / `design-plan.md` §4.3 / R-9/R-10）：
- ✅ **结构语义**（本模块，全部可从 er-model/DDL 派生）：
  1. 业务域 = 语义分区（域成员/表数读 `00 §四` 权威分组 + M1 图，键=Dxx，见 `file-domain-map.md`）。
  2. 核心实体三分类（master/transactional/config）← `05 §二` 权威清单，逐条挂来源。
  3. 字段"含义" ← DDL **COMMENT 原文**（`test_erp.sql` 结构事实为最终仲裁；M1 Column.semantic）。
  4. D14 字典域 + 全局 KV 字典 = 枚举/码表语义（值→义，来源 DDL COMMENT + `03/D14`）。
  5. `Concept` 节点 + `REALIZED_BY` 边：把业务概念绑定到物理表（仅 `05 §二` 点名且可定位者）。
- ❌ **指标语义层**（KPI/计算口径/正式业务术语表）：`er-model`/DDL 中不存在，需外部 BI/需求源
  → **超本期范围、不实现、显式标注**（见 `OUT_OF_SCOPE`）。

诚实纪律：语义元素**100% 挂来源**；无法定位者标 `[待确认]`，**绝不臆造字段含义/关系**。
只读消费 M1 快照 `l0_graph.json`（+ M3 `community_*`），不回写上游。
"""

from __future__ import annotations

import hashlib
import json
import re
from collections import defaultdict
from pathlib import Path

from ..ingest.config import (
    DATA_META_DIR,
    DOMAIN_DECLARED,
    FILE_05,
    L0_GRAPH_JSON,
    SQL_FILE,
)
from ..store.graph import PropertyGraph
from ..store.loader import load_graph_json

# out/ 被 .gitignore 忽略；权威口径以 data/meta 可提交镜像为准（承 M1/M2/M3）。
OUT_DIR = DATA_META_DIR.parents[1] / "out" / "semantic"
SEMANTIC_ARTIFACT = "semantic_layer.json"

# ------------------------------------------------------------------ 语义层范围声明
SEMANTIC_LAYER_SCOPE = "structural"
SCOPE_STATEMENT = (
    "本层为**结构语义**（业务域分区 / 实体三分类 / 字段 DDL COMMENT 含义 / D14+全局 KV 枚举码表），"
    "全部可回溯到 er-model/* 或 test_erp.sql COMMENT；**不含指标语义层**。"
)
#: 显式超范围项（不得实现、不得虚构）—— §4.3/R-9/R-10
OUT_OF_SCOPE = [
    {"item": "指标/KPI 定义", "reason": "er-model+DDL 无指标口径，需外部 BI/需求源",
     "status": "超本期范围 [待确认·需放宽输入]"},
    {"item": "计算口径 / 派生指标血缘", "reason": "字段变换/ETL 级血缘已排除（§4.3/R-9）",
     "status": "超本期范围"},
    {"item": "正式业务术语表（glossary）", "reason": "er-model/DDL 不存在术语定义（§1.4/R-10）",
     "status": "超本期范围 [待确认]"},
    {"item": "LLM 语义合成 / NL 生成式作答", "reason": "确定性栈不依赖 LLM（stack-options §5）",
     "status": "接口 stub、默认禁用 [待确认·需 LLM]"},
]

# 00 §四 官方域名（file-domain-map.md §1 权威映射；键=Dxx，M0 冻结，非本层新设）
DOMAIN_NAMES = {
    "D01": "销售订单域", "D02": "备货与报价域", "D03": "合同与信用额度域",
    "D04": "应收核销与收款域", "D05": "发票与税务域", "D06": "费用与结算域",
    "D07": "退换货与回修域", "D08": "客户与贸易商域", "D09": "商品与产品主数据域",
    "D10": "库存与仓储域", "D11": "优惠券与权益域", "D12": "返利·对账·账单域",
    "D13": "销售目标与统计域", "D14": "商品属性与基础字典域", "D15": "质量管理域",
    "D16": "系统与协作配置域", "D17": "物流与发货域", "D18": "项目与打样域",
    "OT": "其他无前缀业务/遗留表", "B-FAMILY": "B 级平台/框架同构（非业务域）",
}
_CATEGORY_BY_SECTION = {"2.1": "master", "2.2": "transactional", "2.3": "config"}
_CATEGORY_CN = {"master": "主数据/基础实体", "transactional": "交易/事务实体",
                "config": "配置/字典实体"}

# DDL COMMENT 内联枚举（值→义）正则：单数字 + 可选分隔(:：=-) + ≥1 CJK 标签
_ENUM_PAIR_RE = re.compile(r"([0-9])\s*[:：=\-]?\s*([\u4e00-\u9fff]{1,10})")
_IDENT_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


def _sha16(text: str) -> str:
    return hashlib.sha1(text.encode("utf-8")).hexdigest()[:16]


def _src(file: str, section: str | None = None, quote: str | None = None) -> dict:
    """统一出处锚（回溯到文件/节/原文片段；抗行号漂移，见 schema §1.6）。"""
    return {"file": file, "section": section,
            "quote_hash": _sha16(quote) if quote is not None else None}


# ================================================================== 语义层
class SemanticLayer:
    def __init__(self, graph=None):
        self.g = graph if graph is not None else load_graph_json(L0_GRAPH_JSON)
        self._index()

    # ------------------------------------------------------ 内部索引
    def _index(self) -> None:
        self.tables: dict[str, dict] = {}
        self.columns: dict[str, dict[str, dict]] = defaultdict(dict)
        self.domains: dict[str, dict] = {}
        for n in self.g.nodes.values():
            if n["label"] == "Table":
                self.tables[n["name"]] = n
            elif n["label"] == "Column":
                self.columns[n["table_id"]][n["name"]] = n
            elif n["label"] == "Domain":
                self.domains[n["domain_id"]] = n
        self.a_tables = {t for t, n in self.tables.items()
                         if n.get("tier") == "A" and n.get("domain")}
        self.table_domain = {t: n["domain"] for t, n in self.tables.items()
                             if n.get("tier") == "A" and n.get("domain")}

    # ------------------------------------------------------ 1. 域 = 语义分区
    def domain_partitions(self) -> list[dict]:
        """18 业务域 + OT + B-FAMILY；成员表数读 M1 图（00 §四 权威分组）。"""
        members: dict[str, list[str]] = defaultdict(list)
        for t, dom in self.table_domain.items():
            members[dom].append(t)
        out = []
        for dom in list(DOMAIN_DECLARED) + ["B-FAMILY"]:
            dnode = self.domains.get(dom, {})
            enum_members = sorted(members.get(dom, []))
            declared = dnode.get("table_count") or DOMAIN_DECLARED.get(dom)
            out.append({
                "domain_id": dom,
                "name": DOMAIN_NAMES.get(dom),
                "tier": dnode.get("tier", "A" if dom in DOMAIN_DECLARED else "B"),
                "kind": dnode.get("kind", "leftover-ot" if dom == "OT"
                                  else ("family" if dom == "B-FAMILY" else "business-domain")),
                "declared_table_count": declared,
                "member_tables_in_graph": len(enum_members),
                "authoritative": True,          # 00 §四 为 ground truth，不被社区覆盖
                "source": _src("graphrag/spec/file-domain-map.md", "§1",
                               f"{dom}|{declared}|{len(enum_members)}"),
            })
        return out

    # ------------------------------------------------------ 2. 实体三分类 + REALIZED_BY
    def entity_classification(self) -> list[dict]:
        """解析 `05 §二` 三分类清单 → 概念条目（逐条挂来源 + 可定位者绑表）。"""
        text = Path(FILE_05).read_text(encoding="utf-8")
        lines = text.splitlines()
        items: list[dict] = []
        section = None                       # "2.1"/"2.2"/"2.3"
        in_sec2 = False
        for ln in lines:
            if ln.startswith("## 二、"):
                in_sec2 = True
                continue
            if in_sec2 and ln.startswith("## 三、"):
                break
            m = re.match(r"^###\s+(2\.\d)", ln)
            if m:
                section = m.group(1)
                continue
            if not (in_sec2 and section):
                continue
            if not ln.strip().startswith("|"):
                continue
            cells = [c.strip() for c in ln.strip().strip("|").split("|")]
            if len(cells) < 3 or cells[0] in ("实体", "") or set(cells[0]) <= {"-"}:
                continue                     # 表头 / 分隔线
            ent_cell, dom_cell, desc_cell = cells[0], cells[1], cells[2]
            cat = _CATEGORY_BY_SECTION[section]
            declared_domains = [d.strip() for d in dom_cell.split("/") if d.strip()]
            concept = self._parse_entity_row(cat, ent_cell, declared_domains,
                                             desc_cell, ln, section)
            items.append(concept)
        return items

    def _parse_entity_row(self, cat: str, ent_cell: str, declared_domains: list[str],
                          desc: str, raw_line: str, section: str) -> dict:
        ent_clean = ent_cell.replace("**", "").strip()
        tokens = [t.strip() for t in ent_clean.split("/") if t.strip()]
        realized: list[dict] = []
        last_full: str | None = None
        primary_term: str | None = None
        for tok in tokens:
            im = re.match(r"(_?[A-Za-z][A-Za-z0-9_]*)", tok)
            ident = im.group(1) if im else None
            gloss = None
            gm = re.search(r"[（(]([^）)]*)[）)]", tok)
            if gm:
                gloss = gm.group(1).strip()
            if not ident:                    # 纯中文描述（类别组）→ 不绑表
                if primary_term is None:
                    primary_term = tok
                continue
            cand = ident
            if ident.startswith("_") and last_full:
                cand = last_full + ident     # `_detail` → jf_sales_order_detail（文档前缀约定）
            tnode = self.tables.get(cand)
            if tnode is not None:            # 命中物理 Table 节点（A 可遍历 / B 影子，均登记 tier）
                tier = tnode.get("tier")
                realized.append({
                    "table": cand, "domain": tnode.get("domain"), "tier": tier,
                    "declared_domain": declared_domains,
                    "binding": "doc_named_exact_match", "traversable": tier == "A",
                    "note": ("前缀回补(05 `X / _suffix` 约定)" if ident.startswith("_")
                             else "05 点名，物理名精确命中实物表"),
                })
                if primary_term is None:
                    primary_term = ident
                if not ident.startswith("_"):
                    last_full = ident
            else:                            # 无同名物理表 → [待确认]，不臆造绑定
                realized.append({"table": cand, "domain": None, "tier": None,
                                 "declared_domain": declared_domains,
                                 "binding": "pending",
                                 "note": "[待确认]（05 点名但 DDL/图未定位）"})
                if primary_term is None:
                    primary_term = ident
        # 展示名：优先中文名（首个带括号的 gloss），否则首个标识符
        display = None
        for tok in tokens:
            gm = re.search(r"[（(]([^）)]*)[）)]", tok)
            if gm:
                display = gm.group(1).strip()
                break
        bound = [r for r in realized if r["binding"] != "pending"]
        status = "located" if bound else "[待确认]（未定位可回溯物理表 / 为类别组）"
        cid_slug = _sha16(raw_line)
        return {
            "concept_id": f"concept:{cat}:{cid_slug}",
            "category": cat,
            "category_cn": _CATEGORY_CN[cat],
            "term": display or primary_term or ent_clean,
            "identifiers": [r["table"] for r in realized],
            "declared_domains": declared_domains,
            "realized_by": [r["table"] for r in bound],
            "binding_detail": realized,
            "status": status,
            "desc": desc,
            "source": _src(f"er-model/{Path(FILE_05).name}", f"§二.{section}", raw_line),
        }

    # ------------------------------------------------------ 3. 字段含义（DDL COMMENT 原文）
    def field_meanings(self, tables: list[str] | None = None) -> dict[str, list[dict]]:
        """A 级列 → 语义标签（含义=DDL COMMENT 原文；无 COMMENT → [待确认]，不臆造）。"""
        target = tables if tables is not None else sorted(self.a_tables)
        out: dict[str, list[dict]] = {}
        for t in target:
            rows = []
            for cname in sorted(self.columns.get(t, {})):
                c = self.columns[t][cname]
                sem = (c.get("semantic") or "").strip()
                rows.append({
                    "column": cname,
                    "meaning": sem or None,
                    "data_type": c.get("data_type"),
                    "key_role": c.get("key_role"),
                    "source": (_src("test_erp.sql", None, f"{t}.{cname}|{sem}")
                               if sem else
                               {"file": None, "section": None, "quote_hash": None,
                                "status": "[待确认]（DDL 无 COMMENT）"}),
                    "has_meaning": bool(sem),
                })
            out[t] = rows
        return out

    def field_meaning_coverage(self) -> dict:
        total = with_c = 0
        for t in self.a_tables:
            for c in self.columns.get(t, {}).values():
                total += 1
                if (c.get("semantic") or "").strip():
                    with_c += 1
        return {"a_level_columns": total, "with_ddl_comment": with_c,
                "without_comment_pending": total - with_c,
                "comment_is_arbiter": "test_erp.sql（M1 Column.semantic=DDL COMMENT 原文）"}

    # ------------------------------------------------------ 4. 枚举 / 码表语义
    def enum_semantics(self) -> dict:
        return {
            "code_tables": self._code_tables(),
            "kv_dictionaries": self._kv_dictionaries(),
            "inline_enums_from_comment": self._inline_enums(),
        }

    def _code_tables(self) -> list[dict]:
        """D14 中 `id`+`name` 结构者 = 受控词表/码表（枚举值即数据行，非硬编码常量）。"""
        out = []
        d14 = sorted(t for t in self.a_tables if self.table_domain[t] == "D14")
        for t in d14:
            cm = self.columns.get(t, {})
            if "id" in cm and "name" in cm:
                out.append({
                    "table": t, "kind": "code_table",
                    "key_column": "id", "label_column": "name",
                    "status_column": "status" if "status" in cm else None,
                    "enum_values": "rows（受控词表；值域为表内数据行，DDL/文档不硬编码 → 不落具体常量）",
                    "source": _src("er-model/03-逻辑数据模型/D14-商品属性与基础字典域.md",
                                   "§标准字典结构", f"code:{t}"),
                })
        return out

    def _kv_dictionaries(self) -> list[dict]:
        """全局键值字典（sys_dict_type/sys_dict_data/jf_datat_dict/jf_basic_data_table）。"""
        specs = {
            "sys_dict_data": {"key": "type", "label": "name", "value": "value"},
            "sys_dict_type": {"key": "type", "label": "name", "value": None},
            "jf_datat_dict": {"key": "key", "label": "type", "value": "value_content"},
            "jf_basic_data_table": {"key": "name", "label": "name", "value": "table_name"},
        }
        out = []
        for t, s in specs.items():
            if t in self.a_tables and s["key"] in self.columns.get(t, {}):
                out.append({
                    "table": t, "kind": "kv_dict", "domain": self.table_domain[t],
                    "key_column": s["key"], "label_column": s["label"],
                    "value_column": s["value"],
                    "source": _src("er-model/05-跨域核心关系总览.md", "§二.3 配置/字典",
                                   f"kv:{t}"),
                })
        return out

    def _inline_enums(self) -> list[dict]:
        """DDL COMMENT 原文直接写明的值→义枚举（可从 test_erp.sql 逐字回溯）。"""
        out = []
        for t in sorted(self.a_tables):
            for cname in sorted(self.columns.get(t, {})):
                sem = (self.columns[t][cname].get("semantic") or "").strip()
                if not sem:
                    continue
                pairs = _ENUM_PAIR_RE.findall(sem)
                uniq_vals = {v for v, _ in pairs}
                if len(pairs) >= 2 and len(uniq_vals) >= 2:
                    out.append({
                        "table": t, "column": cname, "domain": self.table_domain[t],
                        "raw_comment": sem,
                        "values": [{"value": v, "label": lab} for v, lab in pairs],
                        "source": _src("test_erp.sql", None, f"{t}.{cname}|{sem}"),
                    })
        return out

    # ------------------------------------------------------ Concept 图（含 REALIZED_BY）
    def build_concept_graph(self) -> tuple[PropertyGraph, dict]:
        """在 M1 图副本上叠加 Concept 节点 + REALIZED_BY 边（仅绑可定位者；可回溯）。"""
        entities = self.entity_classification()
        nodes_added = 0
        edges_added = 0
        for e in entities:
            self.g.add_node(e["concept_id"], "Concept",
                            term=e["term"], category=e["category"],
                            layer=SEMANTIC_LAYER_SCOPE,
                            domain=e["declared_domains"], status=e["status"])
            nodes_added += 1
            ev_id = f"evsrc:{e['source']['quote_hash']}"
            if not self.g.has_node(ev_id):
                self.g.add_node(ev_id, "EvidenceSrc", file=e["source"]["file"],
                                section=e["source"]["section"], table=None, column=None,
                                quote_hash=e["source"]["quote_hash"])
            for r in e["binding_detail"]:
                if r["binding"] == "pending":
                    continue
                self.g.add_edge(e["concept_id"], f"table:{r['table']}", "REALIZED_BY",
                                is_inferred=True, evidence_level="comment_explicit",
                                source_file=e["source"]["file"],
                                quote_hash=e["source"]["quote_hash"],
                                binding=r["binding"])
                self.g.add_edge(e["concept_id"], ev_id, "SUPPORTED_BY",
                                rel_kind="REALIZED_BY", dst_table=r["table"])
                edges_added += 1
        return self.g, {"concepts": nodes_added, "realized_by_edges": edges_added}

    # ------------------------------------------------------ 汇总
    def build(self) -> dict:
        entities = self.entity_classification()
        cat_counts: dict[str, dict[str, int]] = {}
        for e in entities:
            c = cat_counts.setdefault(e["category"], {"concepts": 0, "located": 0,
                                                      "pending": 0})
            c["concepts"] += 1
            c["located" if e["status"] == "located" else "pending"] += 1
        fm = self.field_meanings()
        enums = self.enum_semantics()
        return {
            "milestone": "M4",
            "generator": "graphrag/semantic/semantic_layer.py",
            "semantic_layer_scope": SEMANTIC_LAYER_SCOPE,
            "scope_statement": SCOPE_STATEMENT,
            "input_sources": ["er-model/*", "test_erp.sql", "graphrag/data/l0_graph.json",
                              "graphrag/data/meta/community_*"],
            "out_of_scope": OUT_OF_SCOPE,
            "domains": self.domain_partitions(),
            "entity_classification": {
                "categories": entities,
                "counts_by_category": cat_counts,
                "total": len(entities),
                "source": "er-model/05-跨域核心关系总览.md §二（主数据/交易/配置）",
            },
            "field_meanings": fm,
            "field_meaning_coverage": self.field_meaning_coverage(),
            "enum_semantics": {
                "code_table_count": len(enums["code_tables"]),
                "kv_dict_count": len(enums["kv_dictionaries"]),
                "inline_enum_count": len(enums["inline_enums_from_comment"]),
                "detail": enums,
            },
            "concept_realized_by": {
                "note": "Concept 节点 + REALIZED_BY 边（结构语义，仅 05 点名且可定位者；"
                        "无法定位者 status=[待确认]，不臆造绑定）",
                "concepts": entities,
            },
            "honest_boundaries": [
                "字段含义仅取 DDL COMMENT 原文；无 COMMENT 者标 [待确认]，不臆造。",
                "实体三分类的 REALIZED_BY 仅绑 `05` 点名且命中物理 Table 节点者（A 级可遍历 / B 级影子登记 tier+traversable）；无同名表者 [待确认]。",
                "码表(D14)枚举值域=表内数据行，DDL/文档不硬编码 → 不落具体常量值（不臆造）。",
                "内联枚举逐字取自 COMMENT，附 raw_comment 供逐条回溯。",
                "指标/KPI/术语表超范围，未实现（见 out_of_scope）。",
            ],
        }


def run(write: bool = True) -> dict:
    layer = SemanticLayer()
    result = layer.build()
    if write:
        DATA_META_DIR.mkdir(parents=True, exist_ok=True)
        OUT_DIR.mkdir(parents=True, exist_ok=True)
        payload = json.dumps(result, ensure_ascii=False, indent=2)
        (DATA_META_DIR / SEMANTIC_ARTIFACT).write_text(payload, encoding="utf-8")
        (OUT_DIR / SEMANTIC_ARTIFACT).write_text(payload, encoding="utf-8")
    return result


if __name__ == "__main__":
    r = run(write=True)
    print(json.dumps({
        "domains": len(r["domains"]),
        "entity_total": r["entity_classification"]["total"],
        "entity_by_cat": r["entity_classification"]["counts_by_category"],
        "field_coverage": r["field_meaning_coverage"],
        "enum": {k: v for k, v in r["enum_semantics"].items() if k != "detail"},
        "out_of_scope": len(r["out_of_scope"]),
    }, ensure_ascii=False, indent=2))
