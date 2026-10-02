<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { login } from '../auth'

const router = useRouter()
const username = ref('')
const password = ref('')
const error = ref('')
const busy = ref(false)

// POC 固定口令见 application.yml dam.security.poc-password
async function submit() {
  busy.value = true
  error.value = ''
  try {
    await login(username.value.trim(), password.value)
    await router.push('/')
  } catch (e) {
    error.value = String(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="wrap">
    <form class="card" @submit.prevent="submit">
      <h1>数据资产管理 · 登录</h1>
      <p class="muted">POC 账号：admin / steward / viewer</p>
      <input v-model="username" placeholder="用户名" autocomplete="username" />
      <input v-model="password" type="password" placeholder="密码" autocomplete="current-password" />
      <button :disabled="busy">{{ busy ? '登录中…' : '登录' }}</button>
      <p v-if="error" class="error">{{ error }}</p>
    </form>
  </div>
</template>

<style scoped>
.wrap { display: flex; height: 100vh; align-items: center; justify-content: center; background: #f3f6fb; font-family: system-ui, sans-serif; }
.card { width: 320px; background: #fff; border: 1px solid #e3e8f0; border-radius: 8px; padding: 24px; display: flex; flex-direction: column; gap: 12px; }
input { padding: 8px; border: 1px solid #ccc; border-radius: 4px; }
button { padding: 8px; background: #2b6cb0; color: #fff; border: none; border-radius: 4px; cursor: pointer; }
.muted { color: #888; font-size: 13px; }
.error { color: #c53030; font-size: 13px; }
h1 { font-size: 18px; margin: 0; }
</style>
