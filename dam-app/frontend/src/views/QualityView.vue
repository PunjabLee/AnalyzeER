<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, type DqIssue, type DqRule, type ScanReport } from '../api'
import { hasRole, isLoggedIn } from '../auth'

const router = useRouter()
const rules = ref<DqRule[]>([])
const summary = ref<Record<string, number>>({})
const issues = ref<DqIssue[]>([])
const activeRule = ref('')
const report = ref<ScanReport | null>(null)
const error = ref('')
const busy = ref(false)

const canScan = () => isLoggedIn() && hasRole('ADMIN', 'STEWARD')

async function reload() {
  try {
    rules.value = await api.dqRules()
    summary.value = await api.dqSummary()
    issues.value = (await api.dqIssues(activeRule.value || undefined)).content
  } catch (e) {
    error.value = String(e)
  }
}

async function scan() {
  busy.value = true
  error.value = ''
  try {
    report.value = await api.dqScan()
    await reload()
  } catch (e) {
    error.value = String(e)
  } finally {
    busy.value = false
  }
}

function pickRule(code: string) {
  activeRule.value = activeRule.value === code ? '' : code
  reload()
}

function tableName(urn: string) {
  return urn.split(':').pop() || urn
}

onMounted(reload)
</script>

<template>
  <div class="page">
    <h2>数据质量 <small class="muted">结构类规则随 M1 落地；关系类依赖血缘，先置灰（评审#6）</small></h2>
    <p v-if="error" class="error">{{ error }}</p>
    <p>
      <button v-if="canScan()" :disabled="busy" @click="scan">{{ busy ? '扫描中…' : '执行扫描' }}</button>
      <span v-if="report" class="ok">上次扫描：{{ report.issues }} 条命中</span>
      <span v-else-if="!canScan()" class="muted">登录 ADMIN/STEWARD 后可执行扫描</span>
    </p>

    <div class="cols">
      <section>
        <h3>规则清单</h3>
        <table class="grid">
          <thead><tr><th>编码</th><th>名称</th><th>类别</th><th>严重级</th><th>命中</th><th></th></tr></thead>
          <tbody>
            <tr v-for="r in rules" :key="r.id" :class="{ off: !r.enabled, pick: activeRule === r.code }"
                @click="r.enabled && pickRule(r.code)">
              <td class="fname">{{ r.code }}</td>
              <td>{{ r.name }}</td>
              <td>{{ r.category }}</td>
              <td>{{ r.severity }}</td>
              <td>{{ summary[r.code] ?? (r.enabled ? '未扫' : '—') }}</td>
              <td>{{ r.scopeGrading ? '范围' + r.scopeGrading : '' }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <section>
        <h3>问题清单（{{ issues.length }} 条{{ activeRule ? '，规则 ' + activeRule : '' }}）</h3>
        <table class="grid">
          <thead><tr><th>规则</th><th>表</th><th>列</th><th>明细</th></tr></thead>
          <tbody>
            <tr v-for="i in issues" :key="i.id">
              <td class="fname">{{ i.ruleCode }}</td>
              <td><a class="link" @click="router.push('/table/' + tableName(i.assetUrn))">{{ tableName(i.assetUrn) }}</a></td>
              <td class="fname">{{ i.columnName || '' }}</td>
              <td>{{ i.detail }}</td>
            </tr>
            <tr v-if="!issues.length"><td colspan="4" class="muted">暂无命中——先“执行扫描”，或该规则 0 违规。</td></tr>
          </tbody>
        </table>
      </section>
    </div>
  </div>
</template>

<style scoped>
.page { padding: 16px 24px; font-family: system-ui, sans-serif; overflow-y: auto; height: 100%; }
h2 small { font-weight: 400; font-size: 12px; }
h3 { font-size: 14px; }
.muted { color: #888; } .error { color: #c53030; } .ok { color: #2f855a; margin-left: 12px; }
button { padding: 6px 14px; background: #2b6cb0; color: #fff; border: none; border-radius: 4px; cursor: pointer; }
.cols { display: grid; grid-template-columns: 1fr 1.2fr; gap: 20px; align-items: start; }
@media (max-width: 1100px) { .cols { grid-template-columns: 1fr; } }
table.grid { border-collapse: collapse; width: 100%; font-size: 12px; }
table.grid th, table.grid td { border: 1px solid #eaeaea; padding: 5px 6px; text-align: left; }
table.grid thead th { background: #f7f7f7; }
table.grid tbody tr { cursor: pointer; }
table.grid tbody tr.off { color: #aaa; background: #fafafa; cursor: default; }
table.grid tbody tr.pick { background: #ebf4ff; }
.fname { font-family: ui-monospace, Menlo, monospace; color: #b7791f; }
.link { color: #2b6cb0; text-decoration: underline; }
</style>
