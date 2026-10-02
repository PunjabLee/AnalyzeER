<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, type AssetDetail, type AssetSummary } from './api'

const count = ref<number>(0)
const keyword = ref<string>('')
const assets = ref<AssetSummary[]>([])
const detail = ref<AssetDetail | null>(null)
const loading = ref(false)
const error = ref<string>('')

async function loadList() {
  loading.value = true
  error.value = ''
  try {
    assets.value = await api.list(keyword.value || undefined, 300)
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}

async function openTable(name: string) {
  loading.value = true
  error.value = ''
  try {
    detail.value = await api.detailByName(name)
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try {
    count.value = await api.count()
  } catch (e) {
    error.value = String(e)
  }
  await loadList()
})
</script>

<template>
  <div class="layout">
    <aside class="side">
      <h1>资产目录 <span class="badge">M0</span></h1>
      <p class="muted">已入库表数：{{ count }}</p>
      <div class="search">
        <input v-model="keyword" placeholder="搜索表名，如 jf_sales" @keyup.enter="loadList" />
        <button @click="loadList">搜索</button>
      </div>
      <ul class="list">
        <li v-for="a in assets" :key="a.urn" @click="openTable(a.name)">
          <span class="tname">{{ a.name }}</span>
          <span class="tcomment">{{ a.tableComment || '—' }}</span>
        </li>
      </ul>
    </aside>

    <main class="main">
      <p v-if="error" class="error">{{ error }}</p>
      <p v-if="loading" class="muted">加载中…</p>

      <div v-if="detail" class="detail">
        <h2>{{ detail.summary.name }} <small>{{ detail.summary.tableComment }}</small></h2>
        <div class="meta">
          <span>URN：{{ detail.summary.urn }}</span>
          <span>列数：{{ detail.summary.columnCount }}</span>
          <span>主键：{{ detail.summary.hasPk ? '有' : '无' }}</span>
          <span>前缀族：{{ detail.summary.prefixFamily }}</span>
        </div>
        <table class="cols">
          <thead>
            <tr>
              <th>#</th><th>字段</th><th>类型</th><th>可空</th>
              <th>默认</th><th>键</th><th>含义</th><th>来源</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in detail.columns" :key="c.ordinal">
              <td>{{ c.ordinal }}</td>
              <td class="fname">{{ c.name }}</td>
              <td>{{ c.type }}</td>
              <td>{{ c.nullable }}</td>
              <td>{{ c.defaultVal ?? '' }}</td>
              <td>{{ c.keyHint ?? '' }}</td>
              <td class="meaning">{{ c.meaning ?? '' }}</td>
              <td>{{ c.sourceLayer }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else-if="!loading" class="muted">从左侧选择一个表查看字段明细。</p>
    </main>
  </div>
</template>

<style scoped>
* { box-sizing: border-box; }
.layout { display: flex; height: 100vh; font-family: system-ui, sans-serif; }
.side { width: 340px; border-right: 1px solid #e3e3e3; padding: 16px; overflow-y: auto; }
.main { flex: 1; padding: 16px 24px; overflow-y: auto; }
.badge { font-size: 12px; background: #2b6cb0; color: #fff; border-radius: 4px; padding: 2px 6px; vertical-align: middle; }
.muted { color: #888; }
.error { color: #c53030; }
.search { display: flex; gap: 8px; margin: 12px 0; }
.search input { flex: 1; padding: 6px 8px; border: 1px solid #ccc; border-radius: 4px; }
.search button { padding: 6px 12px; }
.list { list-style: none; padding: 0; margin: 0; }
.list li { padding: 8px; border-bottom: 1px solid #f0f0f0; cursor: pointer; display: flex; flex-direction: column; }
.list li:hover { background: #f5f8ff; }
.tname { font-weight: 600; color: #2c5282; }
.tcomment { font-size: 12px; color: #777; }
.detail h2 small { font-weight: 400; color: #666; margin-left: 8px; }
.meta { display: flex; flex-wrap: wrap; gap: 16px; color: #555; margin: 8px 0 16px; font-size: 13px; }
table.cols { border-collapse: collapse; width: 100%; font-size: 13px; }
table.cols th, table.cols td { border: 1px solid #eaeaea; padding: 6px 8px; text-align: left; }
table.cols thead th { background: #f7f7f7; position: sticky; top: 0; }
.fname { font-family: ui-monospace, Menlo, monospace; color: #b7791f; }
.meaning { max-width: 480px; }
</style>
