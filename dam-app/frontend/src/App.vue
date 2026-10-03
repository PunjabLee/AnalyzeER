<script setup lang="ts">
import { useRouter } from 'vue-router'
import { auth, hasRole, isLoggedIn, logout } from './auth'

const router = useRouter()

function goLogout() {
  logout()
  router.push('/login')
}
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <nav>
        <router-link to="/">资产目录</router-link>
        <router-link to="/dict">数据字典</router-link>
        <router-link to="/glossary">业务术语</router-link>
        <router-link to="/quality">数据质量</router-link>
        <router-link v-if="hasRole('ADMIN')" to="/audit">审计日志</router-link>
      </nav>
      <div class="user">
        <template v-if="isLoggedIn()">
          <span>{{ auth.username }}（{{ auth.roles.join('/') }}）</span>
          <button class="ghost" @click="goLogout">退出</button>
        </template>
        <router-link v-else to="/login" class="login">登录</router-link>
      </div>
    </header>
    <router-view class="view" />
  </div>
</template>

<style scoped>
.shell { display: flex; flex-direction: column; height: 100vh; }
.topbar { display: flex; justify-content: space-between; align-items: center; padding: 0 20px; height: 48px; border-bottom: 1px solid #e3e3e3; background: #fff; font-family: system-ui, sans-serif; flex-shrink: 0; }
nav { display: flex; gap: 18px; }
nav a { text-decoration: none; color: #4a5568; font-size: 14px; padding: 4px 2px; border-bottom: 2px solid transparent; }
nav a.router-link-active { color: #2b6cb0; border-bottom-color: #2b6cb0; font-weight: 600; }
.user { display: flex; align-items: center; gap: 10px; font-size: 13px; color: #666; }
.login { color: #2b6cb0; }
button.ghost { padding: 4px 10px; background: none; border: 1px solid #cbd5e0; border-radius: 4px; cursor: pointer; color: #4a5568; }
.view { flex: 1; min-height: 0; }
</style>
