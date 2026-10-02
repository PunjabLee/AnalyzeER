<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, type AssetDetail, type GovernanceUpdate, type RelationView } from '../api'
import { hasRole, isLoggedIn } from '../auth'

const route = useRoute()
const router = useRouter()
const detail = ref<AssetDetail | null>(null)
const loading = ref(false)
const error = ref('')
const saved = ref('')

// governance edit form (PATCH /api/assets/{id}/governance)
const form = ref<GovernanceUpdate>({ certificationStatus: undefined, sensitivityLevel: undefined, deprecated: undefined })

const canEdit = computed(() => isLoggedIn() && hasRole('ADMIN', 'STEWARD'))

// relations split for the two tables
const outRels = computed<RelationView[]>(() => detail.value?.relations.filter(r => r.direction === 'out') || [])
const inRels = computed<RelationView[]>(() => detail.value?.relations.filter(r => r.direction === 'in') || [])

async function load(name: string) {
  loading.value = true
  error.value = ''
  saved.value = ''
  try {
    detail.value = await api.detailByName(name)
    form.value = {
      certificationStatus: detail.value.summary.certificationStatus || undefined,
      sensitivityLevel: detail.value.summary.sensitivityLevel || undefined,
      deprecated: detail.value.summary.deprecated ?? undefined
    }
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}

async function saveGovernance() {
  const id = detail.value?.summary.id
  if (!id) {
    error.value = '未定位到资产 ID（无法编辑治理属性）'
    return
  }
  error.value = ''
  try {
    const s = await api.patchGovernance(id, form.value)
    if (detail.value) detail.value.summary = s
    saved.value = '治理属性已保存（见审计日志）'
  } catch (e) {
    error.value = String(e)
  }
}

function goto(name: string | null) {
  if (name) router.push('/table/' + name)
}

onMounted(() => load(String(route.params.name)))
watch(() => route.params.name, n => { if (n) load(String(n)) })
</script>

<template>
  <div class="page">
    <p v-if="error" class="error">{{ error }}</p>
    <p v-if="loading" class="muted">加载中…</p>

    <template v-if="detail">
      <h2>{{ detail.summary.name }} <small>{{ detail.summary.tableComment }}</small></h2>
      <div class="meta">
        <span>URN：{{ detail.summary.urn }}</span>
        <span>分级：<b>{{ detail.summary.grading }}</b></span>
        <span>域：<b>{{ detail.summary.domainCode }}</b></span>
        <span>列数：{{ detail.summary.columnCount }}</span>
        <span>主键：{{ detail.summary.hasPk ? '有' : '无' }}</span>
        <span>前缀族：{{ detail.summary.prefixFamily }}</span>
      </div>

      <section v-if="canEdit" class="gov">
        <h3>治理属性 <small>（ADMIN/STEWARD 可改，写审计）</small></h3>
        <div class="row">
          <label>认证状态
            <select v-model="form.certificationStatus">
              <option :value="undefined">—</option>
              <option>认证</option><option>待审</option><option>未认证</option>
            </select>
          </label>
          <label>敏感级
            <select v-model="form.sensitivityLevel">
              <option :value="undefined">—</option>
              <option>PII</option><option>机密</option><option>内部</option><option>公开</option>
            </select>
          </label>
          <label>废弃
            <input v-model="form.deprecated" type="checkbox" />
          </label>
          <button @click="saveGovernance">保存</button>
          <span v-if="saved" class="ok">{{ saved }}</span>
        </div>
      </section>

      <h3>推断关系（{{ detail.relations.length }} 条，全部来自逻辑模型 FK 列，待确认）</h3>
      <div class="rels">
        <table class="grid">
          <thead><tr><th>方向</th><th>列</th><th>目标表</th><th>依据</th><th>置信度</th><th>跨域</th><th>来源文档</th></tr></thead>
          <tbody>
            <tr v-for="r in [...outRels, ...inRels]" :key="r.id">
              <td>{{ r.direction === 'out' ? '出 →' : '← 入' }}</td>
              <td class="fname">{{ r.fromColumn }}</td>
              <td>
                <a v-if="r.direction === 'in' && r.fromName" @click="goto(r.fromName)">{{ r.fromName }}</a>
                <a v-else-if="r.toName" @click="goto(r.toName)">{{ r.toName }}</a>
                <span v-else class="muted">未解析：{{ r.targetRaw }}</span>
              </td>
              <td>{{ r.evidenceLevel }}｜{{ r.origin }}</td>
              <td>{{ r.confidence }}</td>
              <td>{{ r.crossDomain || '' }}</td>
              <td class="muted">{{ r.sourceDoc }}</td>
            </tr>
            <tr v-if="!detail.relations.length"><td colspan="7" class="muted">该表在逻辑模型中无 FK 标记</td></tr>
          </tbody>
        </table>
      </div>

      <h3>字段（{{ detail.columns.length }} 列）</h3>
      <table class="grid">
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
    </template>
  </div>
</template>

<style scoped>
.page { padding: 16px 24px; font-family: system-ui, sans-serif; overflow-y: auto; height: 100%; }
.meta { display: flex; flex-wrap: wrap; gap: 16px; color: #555; margin: 8px 0 16px; font-size: 13px; }
.muted { color: #888; }
.error { color: #c53030; }
.ok { color: #2f855a; font-size: 13px; }
h2 small { font-weight: 400; color: #666; margin-left: 8px; font-size: 14px; }
h3 { margin: 20px 0 8px; font-size: 15px; }
h3 small { font-weight: 400; color: #999; }
.gov { background: #f7fafc; border: 1px solid #e2e8f0; border-radius: 6px; padding: 12px; }
.row { display: flex; gap: 16px; align-items: center; flex-wrap: wrap; font-size: 13px; }
.row select, .row button { padding: 5px 10px; }
button { background: #2b6cb0; color: #fff; border: none; border-radius: 4px; cursor: pointer; }
table.grid { border-collapse: collapse; width: 100%; font-size: 13px; }
table.grid th, table.grid td { border: 1px solid #eaeaea; padding: 6px 8px; text-align: left; }
table.grid thead th { background: #f7f7f7; position: sticky; top: 0; }
.fname { font-family: ui-monospace, Menlo, monospace; color: #b7791f; }
.meaning { max-width: 480px; }
.rels a { color: #2b6cb0; cursor: pointer; text-decoration: underline; }
</style>
