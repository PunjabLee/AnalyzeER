<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, type AuditLog } from '../api'
import { hasRole, isLoggedIn } from '../auth'

const logs = ref<AuditLog[]>([])
const error = ref('')
const admin = () => isLoggedIn() && hasRole('ADMIN')

async function reload() {
  error.value = ''
  try {
    logs.value = (await api.audit(200)).content
  } catch (e) {
    error.value = String(e) // 403 → 需要 ADMIN
  }
}

onMounted(() => { if (admin()) reload() })
</script>

<template>
  <div class="page">
    <h2>审计日志 <small class="muted">仅 ADMIN 可见（PLAN M7.2）</small></h2>
    <p v-if="!admin()" class="muted">请以 admin 身份登录后查看。</p>
    <template v-else>
      <p><button @click="reload">刷新</button></p>
      <p v-if="error" class="error">{{ error }}</p>
      <table class="grid">
        <thead><tr><th>时间</th><th>用户</th><th>动作</th><th>对象</th><th>明细</th></tr></thead>
        <tbody>
          <tr v-for="l in logs" :key="l.id">
            <td>{{ l.atTs.replace('T', ' ').slice(0, 19) }}</td>
            <td>{{ l.username }}</td>
            <td class="fname">{{ l.action }}</td>
            <td>{{ l.target }}</td>
            <td class="detail">{{ l.detail }}</td>
          </tr>
        </tbody>
      </table>
    </template>
  </div>
</template>

<style scoped>
.page { padding: 16px 24px; font-family: system-ui, sans-serif; overflow-y: auto; height: 100%; }
h2 small { font-weight: 400; font-size: 12px; }
.muted { color: #888; } .error { color: #c53030; }
button { padding: 6px 14px; background: #2b6cb0; color: #fff; border: none; border-radius: 4px; cursor: pointer; }
table.grid { border-collapse: collapse; width: 100%; font-size: 12px; }
table.grid th, table.grid td { border: 1px solid #eaeaea; padding: 5px 6px; text-align: left; }
table.grid thead th { background: #f7f7f7; position: sticky; top: 0; }
.fname { font-family: ui-monospace, Menlo, monospace; color: #b7791f; }
.detail { max-width: 520px; color: #666; }
</style>
