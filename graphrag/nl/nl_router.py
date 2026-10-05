"""M4 · 规则式 NL 编排前端（确定性；**只调 L1 + L2、不生成未 grounding 的关系**）。

技术栈（`spec/stack-options.md`，主线程已定）：**LLM 不启用** —— 本层为纯规则式：
`自然语言/关键词 → 意图识别 → 参数抽取 → 结构化调用 L1(search/l1.py)+L2(community/*)
→ 用检索/遍历结果直接作答`。LLM 合成仅留接口 stub（`llm_synthesis.py`，`ENABLED=False`）。

六类意图（对齐 `spec/eval-baseline.md` §1 UC1–UC6）：
| intent | UC | 调用 |
|---|---|---|
| `find_table` | UC1 | `L1Search.find_tables`（FTS/BM25 + 域/tier 过滤） |
| `describe_column` | UC2 | `L1Search.columns_of` / `tables_with_column` |
| `find_relations` | UC3 | `L1Search.relations_of`（1 跳邻居，附证据级/出处） |
| `impact_lineage` | UC4/UC5 | `L1Search.traverse`（表级多跳；out=影响/下游，in=血缘/上游） |
| `community_rollup` | UC6 | `L1Search.domain_summary` + L2 `global_analysis.q3` |
| `hub_coupling` | 全局 | L2 `global_analysis.q1`（枢纽）/ `q2`（最紧耦合） |

诚实纪律（过门双门要点）：
- 默认门 `confidence≥0.45` / `show_uncertain=False` / `max_hops≤3`（承 `CONFIDENCE_DEFAULT_MIN`
  / `MAX_HOPS`）；`max_hops` 超 3 一律**钳制到 3**（预算护栏，可复现）。
- 关系类回答 100% 带 `source_file/quote_hash` 出处；无出处即**不输出**该关系。
- 未定位到实体/无路径 → `refused=True`，答“文档未记载 / [待确认]”，**拒绝臆造兜底**（§6）。
- 指标/KPI/术语表**超范围**：本层不识别、不作答（`OUT_OF_SCOPE`）。
- 方向语义**显式声明**（L1 表级 RELATES_TO：`out`=下游/影响、`in`=上游/血缘；全库 0 FK，均推断）。
"""

from __future__ import annotations

import re
from collections import defaultdict

from ..ingest.config import CONFIDENCE_DEFAULT_MIN, MAX_HOPS, DOMAIN_DECLARED
from ..search.l1 import L1Search
from ..store.loader import load_graph_json
from ..store.fts import FTSIndex, FTSQueryError
from ..ingest.config import FTS_DB, L0_GRAPH_JSON
from ..semantic.semantic_layer import DOMAIN_NAMES, OUT_OF_SCOPE

_NO_FK_DISCLAIMER = (
    "关系/血缘为逆向推断、非物理外键（全库 0 显式外键）；证据级最高至 comment_explicit。"
)

# ---------------------------------------------------------------- 意图模板
INTENTS = [
    {"id": "hub_coupling", "uc": "全局", "layer": "L2",
     "keywords": ["枢纽", "最重要", "核心表", "最关键", "耦合", "桥接", "紧密", "最紧",
                  "连接最紧", "之间耦合", "跨域", "跨社区", "betweenness", "hub",
                  "coupling", "top"]},
    {"id": "community_rollup", "uc": "UC6", "layer": "L1+L2",
     "keywords": ["社区", "概览", "综述", "总结", "质量问题", "分区", "cluster",
                  "域", "业务域", "有哪些问题", "issue"]},
    {"id": "impact_lineage", "uc": "UC4/UC5", "layer": "L1",
     "keywords": ["影响", "波及", "下游", "上游", "血缘", "来源", "依赖", "改了",
                  "变更", "删除后", "会怎样", "impact", "lineage", "trace"]},
    {"id": "find_relations", "uc": "UC3", "layer": "L1",
     "keywords": ["关系", "关联", "连接", "引用", "外键", "连到", "对应哪些表",
                  "相关表", "relation", "和什么相关", "和哪些"]},
    {"id": "describe_column", "uc": "UC2", "layer": "L1",
     "keywords": ["字段", "列", "什么类型", "包含哪些", "有哪些字段", "结构",
                  "属性", "schema", "column", "field"]},
    {"id": "find_table", "uc": "UC1", "layer": "L1",
     "keywords": ["哪些表", "哪张表", "找表", "查表", "有没有表", "什么表", "哪个表",
                  "存储", "保存", "记录", "table"]},
]
_INTENT_BY_ID = {i["id"]: i for i in INTENTS}
_PRIORITY = [i["id"] for i in INTENTS]      # 并列时靠前优先

# 方向关键词（L1 表级 RELATES_TO 遍历语义）
_KW_DOWNSTREAM = ["影响", "波及", "下游", "改了", "变更", "删除后", "会怎样", "谁依赖", "impact"]
_KW_UPSTREAM = ["血缘", "来源", "上游", "依赖谁", "来自", "引用了谁", "lineage"]

# 检索净化用：CJK 运行串（承 fts._CJK_RUN_RE 同族，用于抽取内容词）
_CJK_RUN_RE = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]+")
# 疑问/虚词/量词/动词停用（仅用于 UC1/模糊定位时的检索净化，不改意图判定）
_CJK_STOP = [
    "哪些", "哪张", "哪个", "哪些个", "什么", "有没有", "有无", "是否", "存在",
    "保存", "存储", "存", "的", "了", "是", "有", "在", "对", "和", "与", "及",
    "或", "关于", "请问", "想", "要", "怎么", "怎样", "如何", "会", "被", "给",
    "从", "到", "为", "吗", "呢", "吧", "啊", "这", "那", "该", "些", "张", "个",
    "列出", "查询", "检索", "显示", "告诉", "一下", "一些", "所有", "全部", "我",
]
# 各意图关键词（检索净化时一并剔除；均来自 INTENTS，无业务内容词）
_INTENT_ALL_KW = sorted({kw for it in INTENTS for kw in it["keywords"]}, key=len, reverse=True)

# 指标语义层/术语表触发词 → 命中的超范围项（显式拒答，绝不伪装成 UC1 检索）
_METRIC_TERMS = {
    "指标": "指标/KPI 定义", "kpi": "指标/KPI 定义", "gmv": "指标/KPI 定义",
    "口径": "计算口径 / 派生指标血缘", "计算口径": "计算口径 / 派生指标血缘",
    "术语表": "正式业务术语表（glossary）", "glossary": "正式业务术语表（glossary）",
    "环比": "指标/KPI 定义", "同比": "指标/KPI 定义",
}


class NLRouter:
    def __init__(self, graph=None, fts=None):
        self.g = graph if graph is not None else load_graph_json(L0_GRAPH_JSON)
        self.fts = fts if fts is not None else self._open_fts()
        self.l1 = L1Search(self.g, self.fts)
        # 参数字典（确定性、来自 M1 图）
        self.table_names = {n["name"] for n in self.g.nodes.values()
                            if n["label"] == "Table"}
        self.column_names = {n["name"] for n in self.g.nodes.values()
                             if n["label"] == "Column"}
        self.domains = {d for d in DOMAIN_DECLARED}
        self._name_to_code = {v: k for k, v in DOMAIN_NAMES.items()}
        self._l2 = None                       # 全局分析缓存（懒计算）

    @staticmethod
    def _open_fts():
        try:
            return FTSIndex(FTS_DB, rebuild=False)
        except Exception:                     # 索引缺失 → 仅图查询（find_table 返回 note）
            return None

    # ---------------------------------------------------------------- L2 懒加载
    def l2(self) -> dict:
        if self._l2 is None:
            from ..community.global_analysis import global_analysis
            self._l2 = global_analysis(write=False)
        return self._l2

    # ---------------------------------------------------------------- 超范围守卫
    @staticmethod
    def _out_of_scope_hit(query: str) -> list[str]:
        low = query.lower()
        return sorted({_METRIC_TERMS[k] for k in _METRIC_TERMS if k in low})

    def _out_of_scope_response(self, query, items, gates) -> dict:
        return {
            "query": query, "intent": "out_of_scope", "uc": "N/A（超范围）",
            "layer": "none", "matched_params": {"out_of_scope_terms": items},
            "gates": gates, "answer": None, "refused": True, "sources": [],
            "note": ("指标语义层 / KPI / 计算口径 / 正式业务术语表：er-model+DDL 无来源，"
                     "**超本期范围** → 不实现、不作答、不臆造（§4.3/R-9/R-10）。"),
            "out_of_scope": [o["item"] for o in OUT_OF_SCOPE],
            "llm_synthesis": {"enabled": False,
                              "note": "确定性；LLM 合成 stub，即便启用亦不得生成指标口径"},
            "disclaimer": "本层为结构语义（域/实体三分类/字段 COMMENT/D14 枚举），不含指标语义层。",
        }

    # ---------------------------------------------------------------- 意图识别
    def classify(self, query: str) -> tuple[str, dict]:
        low = query.lower()
        scores: dict[str, int] = defaultdict(int)
        for it in INTENTS:
            for kw in it["keywords"]:
                if kw in low or kw in query:
                    scores[it["id"]] += 1
        if not scores:
            return "find_table", dict(scores)
        best = max(scores.items(), key=lambda kv: (kv[1], -_PRIORITY.index(kv[0])))
        return best[0], dict(scores)

    # ---------------------------------------------------------------- 参数抽取
    def extract(self, query: str) -> dict:
        idents = set(re.findall(r"[A-Za-z_][A-Za-z0-9_]*", query))
        tables = sorted((t for t in idents if t in self.table_names), key=len, reverse=True)
        # 点号 table.column
        dotted = [(t, c) for t, c in re.findall(r"([A-Za-z_]\w*)\.([A-Za-z_]\w*)", query)
                  if t in self.table_names]
        columns = sorted((c for c in idents if c in self.column_names), key=len, reverse=True)
        # 域：D\d{2} 或中文域名回指
        domains = set(re.findall(r"\bD\d{2}\b", query.upper()))
        for name, code in self._name_to_code.items():
            if name and name in query:
                domains.add(code)
        # 方向
        direction = None
        if any(k in query for k in _KW_UPSTREAM):
            direction = "in"
        if any(k in query for k in _KW_DOWNSTREAM):
            direction = "out"
        return {"tables": tables, "columns": columns, "dotted": dotted,
                "domains": sorted(domains), "direction": direction}

    # ---------------------------------------------------------------- 检索净化
    def _clean_search(self, query: str) -> str:
        """去意图词/停用词/域码，保留 CJK 内容串（≥2）+ 已登记标识符 → 供 FTS。

        规则式：FTS trigram 需**连续**短语，整句 NL 会因疑问词/动词打断而 0 命中；
        故先净化为内容词（如「哪些表存贸易商额度」→「贸易商额度」）。
        """
        low = " " + query + " "
        for kw in _INTENT_ALL_KW:
            low = low.replace(kw, " ")
        for ph in _CJK_STOP:
            low = low.replace(ph, " ")
        low = re.sub(r"\bD\d{1,2}\b", " ", low)
        runs = [r for r in _CJK_RUN_RE.findall(low) if len(r) >= 2]
        idents = [i for i in re.findall(r"[A-Za-z_][A-Za-z0-9_.]*", query)
                  if i.lower() in self.table_names or i.lower() in self.column_names]
        return " ".join(runs + idents)

    # ---------------------------------------------------------------- 表定位（含 FTS 模糊）
    def _resolve_table(self, query: str, params: dict):
        if params["tables"]:
            return params["tables"][0], "explicit"
        if params["dotted"]:
            return params["dotted"][0][0], "explicit(dotted)"
        if self.fts is not None:
            try:
                hits = self.fts.search(self._clean_search(query) or query,
                                       label="Table", tiers=("A", "B"), limit=1)
            except FTSQueryError:
                hits = []
            if hits:
                return hits[0]["name"], "fts_fuzzy"
        return None, None

    # ---------------------------------------------------------------- 主入口
    def answer(self, query: str, min_confidence: float = CONFIDENCE_DEFAULT_MIN,
               show_uncertain: bool = False, max_hops: int = MAX_HOPS,
               intent: str | None = None) -> dict:
        assert query and query.strip(), "空查询"
        hops = min(max(1, int(max_hops)), MAX_HOPS)   # 预算护栏：钳制 ≤3
        it, scores = (intent, {"override": 1}) if intent else self.classify(query)
        params = self.extract(query)
        gates = {"min_confidence": min_confidence, "show_uncertain": show_uncertain,
                 "max_hops": hops, "hop_budget_note": "默认 ≤3；超限钳制（可复现）"}
        oos = self._out_of_scope_hit(query)
        if oos:                               # 指标/KPI/术语表：超范围显式拒答，不伪装检索
            return self._out_of_scope_response(query, oos, gates)
        handler = getattr(self, f"_do_{it}")
        res = handler(query, params, gates, scores)
        res.update({
            "query": query, "intent": it, "uc": _INTENT_BY_ID[it]["uc"],
            "layer": _INTENT_BY_ID[it]["layer"], "intent_scores": scores,
            "matched_params": params, "gates": gates,
            "sources": res.pop("sources", []),
            "out_of_scope": [o["item"] for o in OUT_OF_SCOPE],
            "llm_synthesis": {"enabled": False,
                              "note": "确定性作答；LLM 合成为 stub（llm_synthesis.py）"},
            "disclaimer": _NO_FK_DISCLAIMER,
        })
        return res

    # ------------------------------------------------------------ UC1 查表
    def _do_find_table(self, query, params, gates, scores) -> dict:
        domain = params["domains"][0] if params["domains"] else None
        text = self._clean_search(query) or query
        r = self.l1.find_tables(text, domain=domain, limit=10)
        out = {"answer": r, "search_text": text, "refused": r.get("count", 0) == 0}
        if not out["refused"]:
            out["sources"] = [{"kind": "Table", "name": h["name"],
                               "tier": h.get("tier"), "domain": h.get("domain")}
                              for h in r["results"]]
        else:
            out["sources"] = []
            out["note"] = "FTS 无命中或索引未就绪（不臆造，标 [待确认]）"
        return out

    # ------------------------------------------------------------ UC2 查字段
    def _do_describe_column(self, query, params, gates, scores) -> dict:
        if params["dotted"]:
            t, c = params["dotted"][0]
            cols = self.l1.columns_of(t)
            col = next((x for x in cols.get("columns", []) if x["name"] == c), None)
            return {"answer": {"table": t, "column": c, "detail": col},
                    "refused": col is None,
                    "sources": ([{"kind": "Column", "table": t, "name": c,
                                  "source": "test_erp.sql COMMENT"}] if col else [])}
        if params["tables"]:
            t = params["tables"][0]
            cols = self.l1.columns_of(t)
            return {"answer": cols,
                    "refused": not cols.get("exists"),
                    "sources": [{"kind": "Column", "table": t, "name": x["name"],
                                 "has_meaning": bool(x.get("semantic")),
                                 "source": "test_erp.sql COMMENT（无→[待确认]）"}
                                for x in cols.get("columns", [])]}
        if params["columns"]:
            c = params["columns"][0]
            r = self.l1.tables_with_column(c, limit=20)
            return {"answer": r,
                    "refused": not r["exact_tables"] and not r["semantic_tables"],
                    "sources": [{"kind": "Column", "name": c, "table": t,
                                 "match": "exact"} for t in r["exact_tables"]]}
        # 中文短语模糊定位表后给列
        t, via = self._resolve_table(query, params)
        if t is None:
            return {"answer": None, "refused": True, "sources": [],
                    "note": "未定位到表/列（文档未记载，[待确认]，不臆造）"}
        cols = self.l1.columns_of(t)
        return {"answer": cols, "refused": not cols.get("exists"),
                "resolved_via": via,
                "sources": [{"kind": "Column", "table": t, "name": x["name"],
                             "source": "test_erp.sql COMMENT"}
                            for x in cols.get("columns", [])]}

    # ------------------------------------------------------------ UC3 查关系
    def _do_find_relations(self, query, params, gates, scores) -> dict:
        t, via = self._resolve_table(query, params)
        if t is None:
            return {"answer": None, "refused": True, "sources": [],
                    "note": "未定位到表，无法给出关系（[待确认]，不臆造）"}
        r = self.l1.relations_of(t, min_conf=gates["min_confidence"],
                                 show_uncertain=gates["show_uncertain"])
        srcs = [{"kind": "RELATES_TO", "src": x["src"], "dst": x["dst"],
                 "confidence": x["confidence"], "evidence_level": x["evidence_level"],
                 "file": x["source_file"], "quote_hash": x["quote_hash"]}
                for x in r.get("relations", [])]
        return {"answer": r, "resolved_via": via,
                "refused": r.get("exists") and r.get("count", 0) == 0,
                "sources": srcs}

    # ------------------------------------------------------------ UC4/UC5 血缘/影响
    def _do_impact_lineage(self, query, params, gates, scores) -> dict:
        t, via = self._resolve_table(query, params)
        if t is None:
            return {"answer": None, "refused": True, "sources": [],
                    "note": "未定位到表，无法遍历血缘/影响（[待确认]，不臆造）"}
        direction = params["direction"] or "out"
        semantics = ("影响/下游（谁依赖本表）" if direction == "out"
                     else "血缘/上游（本表依赖谁）")
        r = self.l1.traverse(t, direction=direction, max_hops=gates["max_hops"],
                             min_conf=gates["min_confidence"],
                             show_uncertain=gates["show_uncertain"])
        srcs = [{"kind": "traverse_edge", "from": p["from"], "to": p["to"],
                 "hop": p["hop"], "confidence": p["confidence"],
                 "evidence_level": p["evidence_level"],
                 "file": p["source_file"], "quote_hash": p["quote_hash"]}
                for p in r.get("paths", [])]
        return {"answer": r, "resolved_via": via,
                "direction_semantics": direction + " = " + semantics,
                "refused": r.get("exists") and r.get("edge_traversed", 0) == 0,
                "sources": srcs}

    # ------------------------------------------------------------ UC6 社区/域综述
    def _do_community_rollup(self, query, params, gates, scores) -> dict:
        domain = params["domains"][0] if params["domains"] else None
        if domain is None:
            return {"answer": None, "refused": True, "sources": [],
                    "note": "未识别到业务域（Dxx/域名）；全域综述请用 hub_coupling 或指定域"}
        # L1：域内实体/问题（权威 00 分组）
        l1_sum = self.l1.domain_summary(domain)
        # L2：该域在全局 rollup 中的社区分布/内外边/域内枢纽
        q3 = next((row for row in self.l2()["q3_domain_rollup_uc6"]
                   if row["domain"] == domain), None)
        ans = {"domain": domain, "domain_name": DOMAIN_NAMES.get(domain),
               "l1_domain_summary": l1_sum, "l2_community_rollup": q3,
               "authoritative": "00 §四 手工分组（社区仅分析视图）"}
        srcs = [{"kind": "Domain", "domain": domain, "file": "er-model/00-总览与分组清单.md"}]
        if q3:
            for h in q3["top_hub_tables"]:
                srcs.append({"kind": "hub_table", "table": h["table"],
                             "weighted_degree": h["weighted_degree"]})
        refused = not l1_sum.get("exists")
        return {"answer": ans, "refused": refused, "sources": srcs}

    # ------------------------------------------------------------ 全局 枢纽/耦合
    def _do_hub_coupling(self, query, params, gates, scores) -> dict:
        g = self.l2()
        want_coupling = any(k in query for k in
                            ["耦合", "桥接", "紧密", "最紧", "跨域", "跨社区", "coupling"])
        want_hubs = any(k in query for k in ["枢纽", "最重要", "核心表", "关键", "hub"])
        if want_coupling and not want_hubs:
            ans = {"tightest_couplings": g["q2_tightest_couplings"]}
        elif want_hubs and not want_coupling:
            ans = {"top_hubs": g["q1_top_hubs"]}
        else:
            ans = {"top_hubs": g["q1_top_hubs"],
                   "tightest_couplings": g["q2_tightest_couplings"]}
        srcs = []
        for h in ans.get("top_hubs", [])[:10]:
            for pv in h.get("sample_edge_provenance", [])[:2]:
                srcs.append({"kind": "hub", "table": h["table"],
                             "file": pv.get("file"), "quote_hash": pv.get("quote_hash")})
        tc = ans.get("tightest_couplings", {})
        for cp in tc.get("cross_community_coupling", [])[:5]:
            for pair in cp["sample_table_pairs"][:2]:
                srcs.append({"kind": "coupling_pair", "src": pair["src"], "dst": pair["dst"],
                             "weight": pair.get("weight")})
        for cd in tc.get("cross_domain_coupling", [])[:5]:
            srcs.append({"kind": "cross_domain", "domains": cd["domains"],
                         "cross_domain_weight": cd["cross_domain_weight"]})
        return {"answer": ans, "refused": False, "sources": srcs,
                "note": "枢纽/耦合结论逐条来自 L2 结构遍历（可回溯表/边），不覆盖 00 权威域"}


def run_demo_queries() -> list[dict]:
    """六类 UC 各一条自然语言入口冒烟（供 demo/tests 消费）。"""
    router = NLRouter()
    qs = [
        "哪些表存贸易商额度？",                                   # UC1
        "jf_sales_order 有哪些字段？",                            # UC2
        "jf_customer 关联哪些表？",                               # UC3
        "改了 jf_product 会影响哪些表？",                          # UC4 影响
        "jf_receivable_write_off 的血缘来自哪些表？",              # UC5 血缘
        "D14 域的概览与质量问题？",                                # UC6
        "全库最枢纽的表是哪些？",                                  # 全局 枢纽
    ]
    return [router.answer(q) for q in qs]


if __name__ == "__main__":
    import json as _json
    for a in run_demo_queries():
        print(_json.dumps({
            "query": a["query"], "intent": a["intent"], "uc": a["uc"],
            "resolved_params": {k: v for k, v in a["matched_params"].items() if v},
            "refused": a["refused"], "n_sources": len(a["sources"]),
        }, ensure_ascii=False))
