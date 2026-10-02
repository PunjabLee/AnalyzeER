// Typed API client for the M0 asset catalog (proxied under /api by Vite).

export interface AssetSummary {
  urn: string
  name: string
  grading: string | null
  domainCode: string | null
  prefixFamily: string | null
  hasPk: boolean | null
  columnCount: number | null
  tableComment: string | null
}

export interface ColumnView {
  ordinal: number
  name: string
  type: string | null
  nullable: string | null
  defaultVal: string | null
  keyHint: string | null
  meaning: string | null
  sourceLayer: string | null
}

export interface AssetDetail {
  summary: AssetSummary
  columns: ColumnView[]
}

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url)
  if (!res.ok) {
    throw new Error(`GET ${url} -> HTTP ${res.status}`)
  }
  return res.json() as Promise<T>
}

export const api = {
  count: () => getJson<number>('/api/assets/count'),
  list: (keyword?: string, limit = 200) =>
    getJson<AssetSummary[]>(
      '/api/assets?limit=' + limit + (keyword ? '&keyword=' + encodeURIComponent(keyword) : '')
    ),
  detailByName: (name: string) =>
    getJson<AssetDetail>('/api/assets/by-name/' + encodeURIComponent(name))
}
