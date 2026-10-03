// Typed API client for the DAM catalog (proxied under /api by Vite).
// M1: adds grading/domain facets, relations, governance, dict, dq, audit and JWT auth.

import { authHeaders, onUnauthorized } from './auth'

export interface AssetSummary {
  id: number
  urn: string
  name: string
  grading: string | null
  domainCode: string | null
  prefixFamily: string | null
  hasPk: boolean | null
  columnCount: number | null
  tableComment: string | null
  certificationStatus: string | null
  sensitivityLevel: string | null
  deprecated: boolean | null
  ownerId: number | null
  stewardId: number | null
}

export interface ColumnView {
  id: number
  ordinal: number
  name: string
  type: string | null
  nullable: string | null
  defaultVal: string | null
  keyHint: string | null
  meaning: string | null
  sourceLayer: string | null
}

export interface RelationView {
  id: number
  direction: string // out（本表→）/ in（→本表）
  fromName: string | null
  fromColumn: string | null
  toName: string | null // null = 未解析目标（待 M5 确认台）
  toColumn: string | null
  targetRaw: string | null
  evidenceLevel: string | null
  origin: string | null
  confidence: number | null
  confirmStatus: string | null
  crossDomain: string | null
  sourceDoc: string | null
}

export interface AssetDetail {
  summary: AssetSummary
  columns: ColumnView[]
  relations: RelationView[]
}

export interface DomainInfo {
  id: number
  code: string
  name: string
  grading: string
  declaredCount: number
}

export interface GovernanceUpdate {
  certificationStatus?: string
  sensitivityLevel?: string
  deprecated?: boolean
  deprecationNote?: string
  ownerId?: number
  stewardId?: number
}

export interface StandardField {
  id?: number
  fieldName: string
  dataType?: string
  lengthVal?: number | null
  semantic?: string
  enabled?: boolean
}

export interface CodeValue {
  id?: number
  category: string
  codeValue: string
  meaning?: string
  ordinal?: number
}

export interface NamingRule {
  id?: number
  name: string
  target: string
  pattern: string
  description?: string
  enabled?: boolean
}

export interface DqRule {
  id: number
  code: string
  name: string
  category: string
  checker: string
  param: string | null
  scopeGrading: string | null
  severity: string
  enabled: boolean
}

export interface DqIssue {
  id: number
  ruleCode: string
  assetUrn: string
  columnName: string | null
  detail: string | null
  scannedAt: string
  status: string
}

export interface ScanReport {
  issues: number
  byRule: Record<string, number>
  scannedAt: string
}

export interface Page<T> {
  content: T[]
  totalElements: number
  number: number
  size: number
}

export interface AuditLog {
  id: number
  username: string
  action: string
  target: string | null
  detail: string | null
  atTs: string
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, {
    ...init,
    headers: {
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...authHeaders(),
      ...(init?.headers || {})
    }
  })
  if (res.status === 401 || res.status === 403) {
    onUnauthorized(res.status)
    throw new Error(`${init?.method || 'GET'} ${url} -> HTTP ${res.status}（需要登录/权限不足）`)
  }
  if (!res.ok) {
    throw new Error(`${init?.method || 'GET'} ${url} -> HTTP ${res.status}`)
  }
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

const post = <T>(url: string, body?: unknown) =>
  request<T>(url, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) })
const put = <T>(url: string, body: unknown) =>
  request<T>(url, { method: 'PUT', body: JSON.stringify(body) })
const patch = <T>(url: string, body: unknown) =>
  request<T>(url, { method: 'PATCH', body: JSON.stringify(body) })
const del = (url: string) => request<void>(url, { method: 'DELETE' })

export interface Facets {
  domains: Record<string, number>
  gradings: Record<string, number>
  total: number
}

// business glossary (capability M3)
export interface TermSummary {
  id: number
  name: string
  domainCode: string | null
  status: string
  refCount: number
}

export interface RefView {
  id: number
  assetId: number | null
  assetName: string | null
  columnId: number | null
  columnName: string | null
  refType: string
}

export interface TermView {
  id: number
  name: string
  definition: string | null
  aliases: string | null
  caliber: string | null
  domainCode: string | null
  ownerId: number | null
  ownerName: string | null
  status: string
  note: string | null
  refs: RefView[]
}

export interface TermUpsert {
  name: string
  definition?: string
  aliases?: string
  caliber?: string
  domainCode?: string
  ownerId?: number
  status?: string
  note?: string
}

export interface TermBinding {
  assetId?: number
  columnId?: number
  refType?: string
}

export const api = {
  // catalog
  count: () => request<number>('/api/assets/count'),
  list: (keyword?: string, domain?: string, grading?: string, limit = 200) =>
    request<AssetSummary[]>(
      '/api/assets?limit=' + limit +
      (keyword ? '&keyword=' + encodeURIComponent(keyword) : '') +
      (domain ? '&domain=' + encodeURIComponent(domain) : '') +
      (grading ? '&grading=' + encodeURIComponent(grading) : '')
    ),
  facets: () => request<Facets>('/api/assets/facets'),
  domains: () => request<DomainInfo[]>('/api/domains'),
  detailByName: (name: string) => request<AssetDetail>('/api/assets/by-name/' + encodeURIComponent(name)),
  patchGovernance: (assetId: number, g: GovernanceUpdate) =>
    patch<AssetSummary>('/api/assets/' + assetId + '/governance', g),

  // M9.1 field drag-sort: persist a new full column order for an asset
  reorderColumns: (assetId: number, columnIds: number[]) =>
    patch<ColumnView[]>('/api/assets/' + assetId + '/columns/order', { columnIds }),

  // business glossary (M3)
  glossaryList: (domain?: string, keyword?: string) =>
    request<TermSummary[]>('/api/glossary/terms' +
      (domain ? '?domain=' + encodeURIComponent(domain) : '') +
      (keyword ? (domain ? '&' : '?') + 'keyword=' + encodeURIComponent(keyword) : '')),
  glossaryGet: (id: number) => request<TermView>('/api/glossary/terms/' + id),
  glossaryCreate: (t: TermUpsert) => post<TermView>('/api/glossary/terms', t),
  glossaryUpdate: (id: number, t: TermUpsert) => put<TermView>('/api/glossary/terms/' + id, t),
  glossaryDelete: (id: number) => del('/api/glossary/terms/' + id),
  glossaryRefs: (id: number) => request<RefView[]>('/api/glossary/terms/' + id + '/refs'),
  glossaryBind: (id: number, b: TermBinding) => post<RefView>('/api/glossary/terms/' + id + '/refs', b),
  glossaryUnbind: (refId: number) => del('/api/glossary/refs/' + refId),
  glossaryByAsset: (assetId: number) => request<TermSummary[]>('/api/glossary/by-asset/' + assetId),

  // dictionary (M2)
  listFields: () => request<StandardField[]>('/api/dict/standard-fields'),
  createField: (f: StandardField) => post<StandardField>('/api/dict/standard-fields', f),
  updateField: (id: number, f: StandardField) => put<StandardField>('/api/dict/standard-fields/' + id, f),
  deleteField: (id: number) => del('/api/dict/standard-fields/' + id),
  listCodes: (category?: string) =>
    request<CodeValue[]>('/api/dict/code-values' + (category ? '?category=' + encodeURIComponent(category) : '')),
  createCode: (c: CodeValue) => post<CodeValue>('/api/dict/code-values', c),
  deleteCode: (id: number) => del('/api/dict/code-values/' + id),
  listNaming: () => request<NamingRule[]>('/api/dict/naming-rules'),
  createNaming: (r: NamingRule) => post<NamingRule>('/api/dict/naming-rules', r),
  deleteNaming: (id: number) => del('/api/dict/naming-rules/' + id),

  // data quality
  dqRules: () => request<DqRule[]>('/api/dq/rules'),
  dqScan: () => post<ScanReport>('/api/dq/scan'),
  dqSummary: () => request<Record<string, number>>('/api/dq/issues/summary'),
  dqIssues: (ruleCode?: string, size = 100) =>
    request<Page<DqIssue>>('/api/dq/issues?size=' + size + (ruleCode ? '&ruleCode=' + encodeURIComponent(ruleCode) : '')),

  // audit (ADMIN)
  audit: (size = 100) => request<Page<AuditLog>>('/api/audit?size=' + size),

  // export links (open in browser)
  exportJsonUrl: (domain?: string) => '/api/export/json' + (domain ? '?domain=' + domain : ''),
  exportYamlUrl: (domain?: string) => '/api/export/yaml' + (domain ? '?domain=' + domain : '')
}
