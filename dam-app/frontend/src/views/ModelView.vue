<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, type BomSummary, type BomTrace, type ModelOverview } from '../api'
import { hasRole, isLoggedIn } from '../auth'

const error = ref('')
const ok = ref('')
const canEdit = () => isLoggedIn() && hasRole('ADMIN', 'STEWARD')

const bom = ref<BomSummary[]>([])
const overview = ref<ModelOverview | null>(null)
const selected = ref<BomTrace | null>(null)

const CATS: Record<string, string> = {
  MASTER: '主数据 / 基础实体',
  TRANSACTIONAL: '交易 / 事务实体',
  CONFIG: '配置 / 字典实体'
}

const grouped = computed(() => {
  const m: Record<string, BomSummary[]> = { MASTER: [], TRANSACTIONAL: [], CONFIG: [] }
  for (const b of bom.value) (m[b.category] ||= []).push(b)
  return m
})

async function reload() {
  error.value = ''
  try {
    bom.value = await api.modelBom()
    overview.value = await api.modelOverview()
  } catch (e) { error.value = String(e) }
}

async function selectBom(id: number) {
  error.value = ''
  try { selected.value = await api.modelTrace(id) } catch (e) { error.value = String(e) }
}

async function rebuild() {
  if (!canEdit()) { error.value = '需要登录 ADMIN/STEWARD 才能重建模型'; return }
  error.value = ''; ok.value = ''
  try {
    const r = await api.modelRebuild()
    ok.value = `三级模型已重建：BOM ${r.bom} · 逻辑实体 ${r.ldm} · 物理表 ${r.pdm} · 映射 ${r.mappings}（${r.bomWithoutResolution} 个业务对象未能解析到具体物理表，按原文保留）`
    await reload()
    if (selected.value) await selectBom(selected.value.id)
  } catch (e) { error.value = String(e) }
}

onMounted(reload)
</script>

<template>
  <div class="page">
    <h2>三级模型 <small class="muted">业务对象(BOM) ↔ 逻辑实体(LDM) ↔ 物理表(PDM)，源自 <code>05</code> 三分类并经物理目录名校验（能力 M4）</small></h2>
    <p v-if="error" class="error">{{ error }}</p>
    <p v-if="ok" class="ok">{{ ok }}</p>

    <div v-if="overview" class="stats">
      <span>业务对象 <b>{{ overview.bomTotal }}</b></span>
      <span>逻辑实体 <b>{{ overview.ldmTotal }}</b></span>
      <span>物理表 <b>{{ overview.pdmTotal }}</b></span>
      <span>跨级映射 <b>{{ overview.mappingTotal }}</b></span>
      <span class="warn">未解析业务对象 <b>{{ overview.bomWithoutResolution }}</b></span>
      <button v-if="canEdit()" class="mini" @click="rebuild">重建模型</button>
      <span v-else class="muted">登录 ADMIN/STEWARD 后可重建。</span>
    </div>

    <div class="cols">
      <!-- BOM list grouped by category -->
      <section>
        <h3>业务对象（BOM）</h3>
        <div v-for="(list, cat) in grouped" :key="cat" class="catgroup">
          <h4>{{ CATS[cat] || cat }} <span class="muted">（{{ list.length }}）</span></h4>
          <ul class="boms">
            <li v-for="b in list" :key="b.id"
                class="bom" :class="{ active: selected?.id === b.id }"
                @click="selectBom(b.id)">
              <span class="fname">{{ b.name }}</span>
              <span v-if="b.label" class="lbl">{{ b.label }}</span>
              <span class="tag">{{ b.domainCode || '—' }} · {{ b.childCount }}</span>
            </li>
            <li v-if="!list.length" class="muted">—</li>
          </ul>
        </div>
      </section>

      <!-- selected business object -->
      <section>
        <h3>业务对象详情</h3>
        <template v-if="selected">
          <div class="detail">
            <p><b>{{ selected.name }}</b> <span v-if="selected.label" class="lbl">{{ selected.label }}</span></p>
            <p class="muted">分类：{{ CATS[selected.category] || selected.category }} · 域：{{ selected.domainCode || '—' }}</p>
            <p v-if="selected.description">说明：{{ selected.description }}</p>
            <p v-if="selected.sourceExpr" class="src">05 原文：{{ selected.sourceExpr }}</p>
          </div>
        </template>
        <p v-else class="muted">点击左侧任一业务对象查看其到逻辑/物理层的映射。</p>
      </section>

      <!-- mapping chain: LDM -> PDM -->
      <section>
        <h3>逻辑 → 物理映射</h3>
        <template v-if="selected">
          <ul class="chain">
            <li v-for="(n, i) in selected.nodes" :key="i" class="link">
              <div class="ldm">
                <span class="lvl">LDM</span>
                <router-link v-if="n.ldmName" :to="'/table/' + n.ldmName" class="fname">{{ n.ldmName }}</router-link>
                <span v-else class="muted">（无逻辑实体）</span>
                <span class="tag">{{ n.domainCode || '—' }}</span>
              </div>
              <div class="arrow">↓</div>
              <div class="pdm">
                <span class="lvl pdm-lvl">PDM</span>
                <template v-if="n.resolved">
                  <router-link :to="'/table/' + n.ldmName" class="fname">{{ n.assetUrn }}</router-link>
                  <span class="badge ok-b">已解析 · {{ n.columnCount }} 列</span>
                </template>
                <span v-else class="badge warn-b">物理表未解析（待确认）</span>
              </div>
              <p v-if="n.basis" class="basis">依据：{{ n.basis }}</p>
            </li>
            <li v-if="!selected.nodes.length" class="muted">
              该业务对象在 05 中为分组/别名表述，未直接给出可解析的表名——原文已在中间栏保留，不臆造映射。
            </li>
          </ul>
        </template>
        <p v-else class="muted">选择业务对象后，此处展示 BOM→LDM→PDM 全链路。</p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.page { padding: 16px 24px; font-family: system-ui, sans-serif; overflow-y: auto; height: 100%; }
h2 small { font-weight: 400; font-size: 12px; }
h3 { font-size: 14px; margin: 8px 0; } h4 { font-size: 13px; margin: 10px 0 4px; }
code { background: #f7f7f7; padding: 1px 4px; border-radius: 3px; }
.muted { color: #888; } .error { color: #c53030; } .ok { color: #2f855a; }
.stats { display: flex; gap: 16px; align-items: center; flex-wrap: wrap; font-size: 13px; margin: 8px 0 4px; }
.stats .warn { color: #b7791f; }
.mini { padding: 5px 10px; background: #2b6cb0; color: #fff; border: none; border-radius: 4px; cursor: pointer; margin-left: auto; }
.cols { display: grid; grid-template-columns: 1fr 1fr 1.2fr; gap: 20px; align-items: start; }
@media (max-width: 1200px) { .cols { grid-template-columns: 1fr; } }
.catgroup { margin-bottom: 10px; }
.boms, .chain { list-style: none; margin: 0; padding: 0; }
.bom { border: 1px solid #eaeaea; border-radius: 4px; padding: 6px 8px; margin-bottom: 6px; font-size: 13px; display: flex; align-items: center; gap: 8px; cursor: pointer; background: #fff; }
.bom.active { border-color: #2b6cb0; box-shadow: 0 0 0 1px #2b6cb0 inset; }
.bom:hover { border-color: #2b6cb0; }
.lbl { color: #4a5568; }
.tag { margin-left: auto; font-size: 11px; color: #718096; }
.detail { font-size: 13px; }
.src { color: #718096; font-size: 12px; background: #fbfbfb; border: 1px solid #eee; border-radius: 4px; padding: 6px; }
.link { border: 1px solid #eaeaea; border-radius: 6px; padding: 8px 10px; margin-bottom: 8px; background: #fff; }
.ldm, .pdm { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.arrow { color: #cbd5e0; text-align: center; line-height: 1.1; }
.lvl { font-size: 10px; font-weight: 700; color: #fff; background: #2b6cb0; border-radius: 3px; padding: 1px 5px; }
.pdm-lvl { background: #2f855a; }
.badge { margin-left: auto; font-size: 11px; border-radius: 3px; padding: 1px 6px; }
.ok-b { color: #22543d; background: #c6f6d5; } .warn-b { color: #7b341e; background: #feebc8; }
.basis { font-size: 11px; color: #a0aec0; margin: 4px 0 0; }
.fname { font-family: ui-monospace, Menlo, monospace; color: #b7791f; text-decoration: none; }
a.fname:hover { text-decoration: underline; }
</style>
