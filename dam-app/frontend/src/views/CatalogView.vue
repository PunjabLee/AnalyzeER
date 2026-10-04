<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, type AssetSummary, type DomainInfo, type Facets } from '../api'
import { hasRole } from '../auth'

const router = useRouter()
const count = ref<number>(0)
const keyword = ref('')
const assets = ref<AssetSummary[]>([])
const domains = ref<DomainInfo[]>([])
const facets = ref<Facets>({ domains: {}, gradings: {}, total: 0 })
const activeDomain = ref<string>('')   // '' = 全部
const activeGrading = ref<string>('')  // '' = 全部
const loading = ref(false)
const error = ref('')

async function loadList() {
  loading.value = true
  error.value = ''
  try {
    assets.value = await api.list(
      keyword.value || undefined,
      activeDomain.value || undefined,
      activeGrading.value || undefined,
      500
    )
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}

function pickDomain(code: string) {
  activeDomain.value = activeDomain.value === code ? '' : code
  loadList()
}

function pickGrading(g: string) {
  activeGrading.value = activeGrading.value === g ? '' : g
  loadList()
}

// P2: bulk export is role-gated server-side (STEWARD/ADMIN); hide the entry for others
const canExport = computed(() => hasRole('STEWARD', 'ADMIN'))
async function doExport(fmt: 'json' | 'yaml') {
  try {
    await api.downloadExport(fmt, activeDomain.value || undefined)
  } catch (e) {
    error.value = String(e)
  }
}

onMounted(async () => {
  try {
    ;[count.value, domains.value, facets.value] = await Promise.all([
      api.count(), api.domains(), api.facets()
    ])
  } catch (e) {
    error.value = String(e)
  }
  await loadList()
})
</script>

<template>
  <div class="layout">
    <aside class="side">
      <h1>资产目录 <span class="badge">M1</span></h1>
      <p class="muted">已入库表数：{{ count }}</p>

      <div class="search">
        <input v-model="keyword" placeholder="搜索表名，如 jf_sales" @keyup.enter="loadList" />
        <button @click="loadList">搜索</button>
      </div>

      <div class="facets">
        <h3>分级</h3>
        <span v-for="g in ['A', 'B', 'C']" :key="g" class="chip"
              :class="{ on: activeGrading === g }" @click="pickGrading(g)">
          {{ g }}（{{ facets.gradings[g] ?? '—' }}）
        </span>
        <h3>业务域</h3>
        <ul class="domlist">
          <li :class="{ on: activeDomain === '' }" @click="pickDomain('')">
            全部 <em>{{ count }}</em>
          </li>
          <li v-for="d in domains" :key="d.code"
              :class="{ on: activeDomain === d.code }" @click="pickDomain(d.code)">
            <span class="dcode">{{ d.code }}</span> {{ d.name }}
            <em>{{ facets.domains[d.code] ?? d.declaredCount }}</em>
          </li>
        </ul>
      </div>

      <a v-if="canExport" class="export" href="#" @click.prevent="doExport('json')">导出 JSON</a>
      <a v-if="canExport" class="export" href="#" @click.prevent="doExport('yaml')">导出 YAML</a>
    </aside>

    <main class="main">
      <p v-if="error" class="error">{{ error }}</p>
      <p v-if="loading" class="muted">加载中…</p>
      <p v-else-if="!assets.length" class="muted">无匹配资产。</p>

      <table v-else class="grid">
        <thead>
          <tr>
            <th>表名</th><th>注释</th><th>分级</th><th>域</th>
            <th>列数</th><th>主键</th><th>认证</th><th>敏感级</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="a in assets" :key="a.urn" @click="router.push('/table/' + a.name)">
            <td class="tname">{{ a.name }}</td>
            <td class="tcomment">{{ a.tableComment || '—' }}</td>
            <td><span class="grade" :class="'g' + a.grading">{{ a.grading }}</span></td>
            <td>{{ a.domainCode }}</td>
            <td>{{ a.columnCount }}</td>
            <td>{{ a.hasPk ? '✓' : '✗ 无PK' }}</td>
            <td>{{ a.certificationStatus || '—' }}</td>
            <td>{{ a.sensitivityLevel || '—' }}</td>
          </tr>
        </tbody>
      </table>
    </main>
  </div>
</template>

<style scoped>
* { box-sizing: border-box; }
.layout { display: flex; height: 100%; font-family: system-ui, sans-serif; }
.side { width: 300px; border-right: 1px solid #e3e3e3; padding: 16px; overflow-y: auto; flex-shrink: 0; }
.main { flex: 1; padding: 16px 24px; overflow-y: auto; }
.badge { font-size: 12px; background: #2b6cb0; color: #fff; border-radius: 4px; padding: 2px 6px; }
.muted { color: #888; }
.error { color: #c53030; }
.search { display: flex; gap: 8px; margin: 12px 0; }
.search input { flex: 1; padding: 6px 8px; border: 1px solid #ccc; border-radius: 4px; }
.search button { padding: 6px 12px; }
h3 { font-size: 13px; margin: 12px 0 6px; color: #555; }
.chip { display: inline-block; margin-right: 6px; padding: 3px 10px; border: 1px solid #cbd5e0; border-radius: 12px; font-size: 12px; cursor: pointer; }
.chip.on { background: #2b6cb0; color: #fff; border-color: #2b6cb0; }
.domlist { list-style: none; padding: 0; margin: 0; font-size: 13px; }
.domlist li { padding: 5px 8px; border-radius: 4px; cursor: pointer; display: flex; gap: 6px; align-items: baseline; }
.domlist li:hover { background: #f5f8ff; }
.domlist li.on { background: #ebf4ff; color: #2c5282; font-weight: 600; }
.domlist em { margin-left: auto; color: #999; font-style: normal; }
.dcode { font-family: monospace; color: #b7791f; }
.export { display: block; margin-top: 10px; font-size: 13px; color: #2b6cb0; }
table.grid { border-collapse: collapse; width: 100%; font-size: 13px; }
table.grid th, table.grid td { border: 1px solid #eaeaea; padding: 6px 8px; text-align: left; }
table.grid thead th { background: #f7f7f7; position: sticky; top: 0; }
table.grid tbody tr { cursor: pointer; }
table.grid tbody tr:hover { background: #f5f8ff; }
.tname { font-weight: 600; color: #2c5282; }
.tcomment { color: #777; max-width: 320px; }
.grade { padding: 1px 7px; border-radius: 3px; font-size: 12px; }
.gA { background: #e6fffa; color: #234e52; }
.gB { background: #fefcbf; color: #744210; }
.gC { background: #fed7d7; color: #822727; }
</style>
