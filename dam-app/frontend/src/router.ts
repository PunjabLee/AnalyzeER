// M1 routing: catalog drill-down, table detail, dict, quality, audit, login.
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { isLoggedIn } from './auth'
import CatalogView from './views/CatalogView.vue'
import TableDetailView from './views/TableDetailView.vue'
import DictView from './views/DictView.vue'
import QualityView from './views/QualityView.vue'
import AuditView from './views/AuditView.vue'
import LoginView from './views/LoginView.vue'

const routes: RouteRecordRaw[] = [
  { path: '/', component: CatalogView },
  { path: '/table/:name', component: TableDetailView },
  { path: '/dict', component: DictView },
  { path: '/quality', component: QualityView },
  { path: '/audit', component: AuditView, meta: { requiresLogin: true } },
  { path: '/login', component: LoginView }
]

export const router = createRouter({
  history: createWebHistory(),
  routes
})

// reads stay open; the audit workbench requires a session
router.beforeEach(to => {
  if (to.meta.requiresLogin && !isLoggedIn()) {
    return '/login'
  }
})
