<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, type CodeValue, type NamingRule, type StandardField } from '../api'
import { hasRole, isLoggedIn } from '../auth'

const fields = ref<StandardField[]>([])
const codes = ref<CodeValue[]>([])
const naming = ref<NamingRule[]>([])
const category = ref('')
const error = ref('')
const ok = ref('')

const canEdit = () => isLoggedIn() && hasRole('ADMIN', 'STEWARD')

// 新增表单（最小可用，拖拽整理在 M5/PLAN §9 落地）
const nf = ref<StandardField>({ fieldName: '', dataType: '', semantic: '' })
const nc = ref<CodeValue>({ category: '', codeValue: '', meaning: '', ordinal: 0 })
const nr = ref<NamingRule>({ name: '', target: 'column', pattern: '', description: '' })

async function reload() {
  error.value = ''
  try {
    ;[fields.value, codes.value, naming.value] = await Promise.all([
      api.listFields(),
      api.listCodes(category.value || undefined),
      api.listNaming()
    ])
  } catch (e) {
    error.value = String(e)
  }
}

async function addField() {
  try { await api.createField(nf.value); ok.value = '标准字段已添加'; nf.value = { fieldName: '', dataType: '', semantic: '' }; await reload() }
  catch (e) { error.value = String(e) }
}
async function removeField(id?: number) {
  if (!id) return
  try { await api.deleteField(id); await reload() } catch (e) { error.value = String(e) }
}
async function addCode() {
  try { await api.createCode(nc.value); ok.value = '码值已添加'; nc.value = { category: '', codeValue: '', meaning: '', ordinal: 0 }; await reload() }
  catch (e) { error.value = String(e) }
}
async function removeCode(id?: number) {
  if (!id) return
  try { await api.deleteCode(id); await reload() } catch (e) { error.value = String(e) }
}
async function addNaming() {
  try { await api.createNaming(nr.value); ok.value = '命名规则已添加'; nr.value = { name: '', target: 'column', pattern: '', description: '' }; await reload() }
  catch (e) { error.value = String(e) }
}
async function removeNaming(id?: number) {
  if (!id) return
  try { await api.deleteNaming(id); await reload() } catch (e) { error.value = String(e) }
}

onMounted(reload)
</script>

<template>
  <div class="page">
    <h2>数据字典 <small class="muted">治理工具字典（dict_*），区别于被治理的业务字典表（D14/OT）</small></h2>
    <p v-if="error" class="error">{{ error }}</p>
    <p v-if="ok" class="ok">{{ ok }}</p>
    <p v-if="!canEdit()" class="muted">只读视图：登录 ADMIN/STEWARD 后可编辑。</p>

    <div class="cols">
      <section>
        <h3>标准字段库（M2.1）</h3>
        <table class="grid">
          <thead><tr><th>字段名</th><th>类型</th><th>长度</th><th>语义</th><th></th></tr></thead>
          <tbody>
            <tr v-for="f in fields" :key="f.id">
              <td class="fname">{{ f.fieldName }}</td><td>{{ f.dataType }}</td>
              <td>{{ f.lengthVal ?? '' }}</td><td>{{ f.semantic }}</td>
              <td v-if="canEdit()"><a class="del" @click="removeField(f.id)">删除</a></td>
            </tr>
          </tbody>
        </table>
        <form v-if="canEdit()" class="add" @submit.prevent="addField">
          <input v-model="nf.fieldName" placeholder="字段名" required />
          <input v-model="nf.dataType" placeholder="类型" />
          <input v-model="nf.semantic" placeholder="语义" />
          <button>添加</button>
        </form>
      </section>

      <section>
        <h3>码值 / 枚举（M2.2）
          <input v-model="category" placeholder="按分类过滤，如 confirm_status" class="filter" @keyup.enter="reload" />
          <button class="mini" @click="reload">过滤</button>
        </h3>
        <table class="grid">
          <thead><tr><th>分类</th><th>码值</th><th>含义</th><th>序</th><th></th></tr></thead>
          <tbody>
            <tr v-for="c in codes" :key="c.id">
              <td>{{ c.category }}</td><td class="fname">{{ c.codeValue }}</td>
              <td>{{ c.meaning }}</td><td>{{ c.ordinal }}</td>
              <td v-if="canEdit()"><a class="del" @click="removeCode(c.id)">删除</a></td>
            </tr>
          </tbody>
        </table>
        <form v-if="canEdit()" class="add" @submit.prevent="addCode">
          <input v-model="nc.category" placeholder="分类" required />
          <input v-model="nc.codeValue" placeholder="码值" required />
          <input v-model="nc.meaning" placeholder="含义" />
          <button>添加</button>
        </form>
      </section>

      <section>
        <h3>命名规范规则（M2.4，供 dq 扫描）</h3>
        <table class="grid">
          <thead><tr><th>名称</th><th>对象</th><th>正则</th><th>说明</th><th></th></tr></thead>
          <tbody>
            <tr v-for="r in naming" :key="r.id">
              <td>{{ r.name }}</td><td>{{ r.target }}</td>
              <td class="fname">{{ r.pattern }}</td><td>{{ r.description }}</td>
              <td v-if="canEdit()"><a class="del" @click="removeNaming(r.id)">删除</a></td>
            </tr>
          </tbody>
        </table>
        <form v-if="canEdit()" class="add" @submit.prevent="addNaming">
          <input v-model="nr.name" placeholder="规则名" required />
          <select v-model="nr.target"><option value="table">table</option><option value="column">column</option></select>
          <input v-model="nr.pattern" placeholder="正则，如 ^[a-z][a-z0-9_]*$" required />
          <button>添加</button>
        </form>
      </section>
    </div>
  </div>
</template>

<style scoped>
.page { padding: 16px 24px; font-family: system-ui, sans-serif; overflow-y: auto; height: 100%; }
h2 small { font-weight: 400; font-size: 12px; }
h3 { font-size: 14px; margin: 8px 0; }
.muted { color: #888; } .error { color: #c53030; } .ok { color: #2f855a; }
.cols { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 20px; align-items: start; }
@media (max-width: 1200px) { .cols { grid-template-columns: 1fr; } }
table.grid { border-collapse: collapse; width: 100%; font-size: 12px; }
table.grid th, table.grid td { border: 1px solid #eaeaea; padding: 5px 6px; text-align: left; }
table.grid thead th { background: #f7f7f7; }
.fname { font-family: ui-monospace, Menlo, monospace; color: #b7791f; }
.add { display: flex; gap: 6px; margin-top: 8px; }
.add input, .add select { padding: 5px; border: 1px solid #ccc; border-radius: 4px; min-width: 0; flex: 1; }
.add button, .mini { padding: 5px 10px; background: #2b6cb0; color: #fff; border: none; border-radius: 4px; cursor: pointer; }
.mini { font-size: 12px; }
.filter { padding: 4px 8px; border: 1px solid #ccc; border-radius: 4px; font-size: 12px; width: 200px; margin-left: 8px; }
.del { color: #c53030; cursor: pointer; font-size: 12px; }
</style>
