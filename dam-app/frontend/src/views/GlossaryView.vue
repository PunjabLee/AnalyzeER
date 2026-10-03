<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, type AssetSummary, type TermSummary, type TermUpsert, type TermView } from '../api'
import { hasRole, isLoggedIn } from '../auth'

const error = ref('')
const ok = ref('')
const canEdit = () => isLoggedIn() && hasRole('ADMIN', 'STEWARD')

const terms = ref<TermSummary[]>([])
const selected = ref<TermView | null>(null)
const assets = ref<AssetSummary[]>([])
const keyword = ref('')
const hoverTerm = ref<number | null>(null)

const nf = ref<TermUpsert>({ name: '', domainCode: '', note: '' })

async function reloadTerms() {
  try { terms.value = await api.glossaryList() } catch (e) { error.value = String(e) }
}

async function searchAssets() {
  error.value = ''
  try { assets.value = await api.list(keyword.value || undefined, undefined, undefined, 40) }
  catch (e) { error.value = String(e) }
}

async function selectTerm(id: number) {
  error.value = ''
  try { selected.value = await api.glossaryGet(id) } catch (e) { error.value = String(e) }
}

function onDragStartAsset(e: DragEvent, a: AssetSummary) {
  if (e.dataTransfer) {
    e.dataTransfer.setData('application/x-asset', String(a.id))
    e.dataTransfer.effectAllowed = 'copy'
  }
}

// M9.2 drag binding: drop an asset (table) onto a term card -> write glossary_term_ref
async function onDropOnTerm(e: DragEvent, termId: number) {
  e.preventDefault()
  hoverTerm.value = null
  if (!canEdit()) { error.value = '需要登录 ADMIN/STEWARD 才能绑定'; return }
  const raw = e.dataTransfer?.getData('application/x-asset')
  if (!raw) return
  try {
    await api.glossaryBind(termId, { assetId: Number(raw) })
    ok.value = '已将资产绑定到术语「' + (terms.value.find(t => t.id === termId)?.name || termId) + '」'
    await selectTerm(termId)
    await reloadTerms()
  } catch (err) { error.value = String(err) }
}

async function addTerm() {
  error.value = ''
  try {
    const t = await api.glossaryCreate(nf.value)
    ok.value = '术语已创建：' + t.name
    nf.value = { name: '', domainCode: '', note: '' }
    await reloadTerms(); await selectTerm(t.id)
  } catch (e) { error.value = String(e) }
}

async function removeTerm(id?: number) {
  if (!id) return
  try { await api.glossaryDelete(id); if (selected.value?.id === id) selected.value = null; await reloadTerms() }
  catch (e) { error.value = String(e) }
}

async function unbind(refId: number) {
  const termId = selected.value?.id
  try { await api.glossaryUnbind(refId); if (termId) await selectTerm(termId); await reloadTerms() }
  catch (e) { error.value = String(e) }
}

onMounted(async () => { await reloadTerms(); await searchAssets() })
</script>

<template>
  <div class="page">
    <h2>业务术语 <small class="muted">术语 ↔ 模型对象引用（能力 M3）；把左侧资产拖到右侧术语卡片完成绑定（M9.2）</small></h2>
    <p v-if="error" class="error">{{ error }}</p>
    <p v-if="ok" class="ok">{{ ok }}</p>
    <p v-if="!canEdit()" class="muted">只读视图：登录 ADMIN/STEWARD 后可创建术语与拖拽绑定。</p>

    <div class="cols">
      <!-- asset source (draggable) -->
      <section>
        <h3>资产（可拖拽源）</h3>
        <form class="add" @submit.prevent="searchAssets">
          <input v-model="keyword" placeholder="搜索表名，如 trader" />
          <button class="mini">搜索</button>
        </form>
        <ul class="assets">
          <li v-for="a in assets" :key="a.id" class="asset"
              :draggable="canEdit()"
              @dragstart="onDragStartAsset($event, a)">
            <span class="grip">⋮</span>
            <span class="fname">{{ a.name }}</span>
            <span class="tag">{{ a.grading }}｜{{ a.domainCode }}</span>
          </li>
        </ul>
      </section>

      <!-- term cards (drop targets) -->
      <section>
        <h3>术语列表（放置目标）</h3>
        <form v-if="canEdit()" class="add" @submit.prevent="addTerm">
          <input v-model="nf.name" placeholder="术语名，如 贸易商" required />
          <input v-model="nf.domainCode" placeholder="域，如 D08" />
          <button>新建</button>
        </form>
        <ul class="terms">
          <li v-for="t in terms" :key="t.id"
              class="term" :class="{ hover: hoverTerm === t.id, active: selected?.id === t.id }"
              @dragover.prevent="hoverTerm = t.id"
              @dragleave="hoverTerm = null"
              @drop="onDropOnTerm($event, t.id)"
              @click="selectTerm(t.id)">
            <div class="termhead">
              <b>{{ t.name }}</b>
              <span class="muted">{{ t.domainCode || '—' }} · {{ t.status }} · 引用 {{ t.refCount }}</span>
              <a v-if="canEdit()" class="del" @click.stop="removeTerm(t.id)">删除</a>
            </div>
          </li>
          <li v-if="!terms.length" class="muted">暂无术语</li>
        </ul>
      </section>

      <!-- selected term detail + references -->
      <section>
        <h3>选中术语详情</h3>
        <template v-if="selected">
          <div class="detail">
            <p><b>{{ selected.name }}</b> <span class="muted">（{{ selected.status }}）</span></p>
            <p v-if="selected.definition">定义：{{ selected.definition }}</p>
            <p v-if="selected.caliber">口径：{{ selected.caliber }}</p>
            <p v-if="selected.note" class="muted">备注：{{ selected.note }}</p>
            <h4>绑定引用（{{ selected.refs.length }}）</h4>
            <ul class="refs">
              <li v-for="r in selected.refs" :key="r.id">
                <span class="fname">{{ r.assetName }}</span>
                <span v-if="r.columnName">.{{ r.columnName }}</span>
                <span class="tag">{{ r.refType }}</span>
                <a v-if="canEdit()" class="del" @click="unbind(r.id)">解绑</a>
              </li>
              <li v-if="!selected.refs.length" class="muted">尚无绑定，从左侧拖入资产</li>
            </ul>
          </div>
        </template>
        <p v-else class="muted">点击左侧任一术语查看详情与引用。</p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.page { padding: 16px 24px; font-family: system-ui, sans-serif; overflow-y: auto; height: 100%; }
h2 small { font-weight: 400; font-size: 12px; }
h3 { font-size: 14px; margin: 8px 0; } h4 { font-size: 13px; margin: 10px 0 4px; }
.muted { color: #888; } .error { color: #c53030; } .ok { color: #2f855a; }
.cols { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 20px; align-items: start; }
@media (max-width: 1200px) { .cols { grid-template-columns: 1fr; } }
.add { display: flex; gap: 6px; margin: 6px 0; }
.add input { padding: 5px; border: 1px solid #ccc; border-radius: 4px; flex: 1; min-width: 0; }
.add button, .mini { padding: 5px 10px; background: #2b6cb0; color: #fff; border: none; border-radius: 4px; cursor: pointer; }
.assets, .terms, .refs { list-style: none; margin: 0; padding: 0; }
.asset { border: 1px solid #eaeaea; border-radius: 4px; padding: 6px 8px; margin-bottom: 6px; font-size: 13px; display: flex; align-items: center; gap: 8px; background: #fff; }
.asset[draggable="true"] { cursor: grab; } .asset[draggable="true"]:hover { border-color: #2b6cb0; }
.grip { color: #cbd5e0; } .tag { margin-left: auto; font-size: 11px; color: #718096; }
.term { border: 1px dashed #cbd5e0; border-radius: 6px; padding: 8px 10px; margin-bottom: 6px; cursor: pointer; }
.term.active { border-style: solid; border-color: #2b6cb0; }
.term.hover { background: #ebf8ff; border-color: #2b6cb0; }
.termhead { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.termhead .del, .refs .del { color: #c53030; cursor: pointer; font-size: 12px; margin-left: auto; }
.detail { font-size: 13px; } .refs li { padding: 4px 0; border-bottom: 1px solid #f0f0f0; display: flex; align-items: center; gap: 8px; }
.fname { font-family: ui-monospace, Menlo, monospace; color: #b7791f; }
</style>
