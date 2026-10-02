// Minimal JWT auth store for the M1 POC (PLAN M7.1).
// Token + profile live in localStorage; views read the reactive state.

import { reactive, readonly } from 'vue'

export const auth = reactive({
  token: localStorage.getItem('dam_token') || '',
  username: localStorage.getItem('dam_user') || '',
  roles: JSON.parse(localStorage.getItem('dam_roles') || '[]') as string[]
})

export function isLoggedIn(): boolean {
  return !!auth.token
}

export function hasRole(...roles: string[]): boolean {
  return roles.some(r => auth.roles.includes(r))
}

export function authHeaders(): Record<string, string> {
  return auth.token ? { Authorization: 'Bearer ' + auth.token } : {}
}

export async function login(username: string, password: string): Promise<void> {
  const res = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  })
  if (!res.ok) {
    throw new Error('登录失败：用户名或密码错误')
  }
  const data = (await res.json()) as { token: string; roles: string[] }
  auth.token = data.token
  auth.username = username
  auth.roles = data.roles.map(r => r.replace('ROLE_', ''))
  localStorage.setItem('dam_token', auth.token)
  localStorage.setItem('dam_user', auth.username)
  localStorage.setItem('dam_roles', JSON.stringify(auth.roles))
}

export function logout(): void {
  auth.token = ''
  auth.username = ''
  auth.roles = []
  localStorage.removeItem('dam_token')
  localStorage.removeItem('dam_user')
  localStorage.removeItem('dam_roles')
}

/** 401/403 hook used by the api client: drop the stale token and bounce to login */
export function onUnauthorized(status: number): void {
  if (status === 401) logout()
}

export const authReadonly = readonly(auth)
