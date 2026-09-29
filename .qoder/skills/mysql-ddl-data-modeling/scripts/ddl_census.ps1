# ddl_census.ps1 - Phase 1: DDL source census (ASCII-only comments: PS 5.1 decodes BOM-less .ps1 as ANSI, CJK comments can swallow line endings)
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/ddl_census.ps1 -SqlFile <ddl.sql> [-OutDir <dir>]
# Output: console summary + _line_map.txt (startLine|table) + _col_counts.txt (table|cols) + _nopk_tables.txt + _prefix_dist.txt
param(
    [Parameter(Mandatory = $true)][string]$SqlFile,
    [string]$OutDir = (Get-Location).Path
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }
$lines = Get-Content -LiteralPath $SqlFile -Encoding UTF8

# --- 1. CREATE TABLE matches (lenient: IF NOT EXISTS / optional backticks / space variants) ---
$creates = @()
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match '^\s*CREATE\s+TABLE\s+(IF\s+NOT\s+EXISTS\s+)?`?([^`\s(]+)`?') {
        $creates += [pscustomobject]@{ Line = $i + 1; Name = $Matches[2].ToLower() }
    }
}
$raw = $creates.Count
$uniq = ($creates.Name | Sort-Object -Unique)
Write-Output ('LINES=' + $lines.Count)
Write-Output ('CREATE_RAW=' + $raw)
Write-Output ('CREATE_UNIQ=' + $uniq.Count)
if ($raw -ne $uniq.Count) {
    Write-Output 'DUPLICATE_TABLE_NAMES:'
    $creates.Name | Group-Object | Where-Object Count -gt 1 | ForEach-Object { Write-Output ('  ' + $_.Name + ' x' + $_.Count) }
}

# --- 2. global FK / CONSTRAINT / PK counts ---
$fk = (Select-String -Path $SqlFile -Pattern 'FOREIGN\s+KEY').Count
$cons = (Select-String -Path $SqlFile -Pattern '\bCONSTRAINT\b').Count
$pk = (Select-String -Path $SqlFile -Pattern 'PRIMARY\s+KEY').Count
Write-Output ('FOREIGN_KEY=' + $fk)
Write-Output ('CONSTRAINT=' + $cons)
Write-Output ('PRIMARY_KEY=' + $pk)

# --- 3. per-table block parse: col count / has PK / table comment / index ---
$colCounts = @{}
$noPk = @()
$noComment = @()
$idxCount = 0
foreach ($c in $creates) {
    $end = $c.Line
    while ($end -lt $lines.Count -and $lines[$end] -notmatch '^\s*\)') { $end++ }
    if ($end -le $c.Line) { $colCounts[$c.Name] = 0; $noPk += $c.Name; $noComment += $c.Name; continue }
    $block = $lines[$c.Line..($end - 1)]
    $closeLine = ''
    if ($end -lt $lines.Count) { $closeLine = $lines[$end] }
    $cols = @($block | Where-Object { $_ -match '^\s*`' -and $_ -notmatch '^\s*`.*`\s+(INDEX|KEY|UNIQUE|PRIMARY|CONSTRAINT|FULLTEXT|SPATIAL)\b' })
    $colCounts[$c.Name] = $cols.Count
    $hasPk = @($block | Where-Object { $_ -match 'PRIMARY\s+KEY' }).Count -gt 0
    if (-not $hasPk) { $noPk += $c.Name }
    if ($closeLine -notmatch "COMMENT\s*=\s*'") { $noComment += $c.Name }
    $idxCount += @($block | Where-Object { $_ -match '^\s*(UNIQUE\s+)?(INDEX|KEY)\s' }).Count
}
Write-Output ('INDEX_TOTAL=' + $idxCount)
Write-Output ('TABLE_WITHOUT_PK=' + $noPk.Count)
Write-Output ('TABLE_WITHOUT_COMMENT=' + $noComment.Count)
if ($noPk.Count -gt 0 -and $noPk.Count -le 50) { $noPk | ForEach-Object { Write-Output ('  NOPK ' + $_) } }

# --- 4. prefix distribution (first segment + framework family buckets) ---
$prefix = @{}
foreach ($n in $uniq) {
    $seg = ($n -split '_')[0]
    if ($n -match '^[np][0-9a-f]{7}_') { $seg = ($n -split '_')[0] + '(hex)' }
    if ($n -match '_bak_\d{6,8}$') { $seg = '*_bak(date)' }
    if ($n -match '_copy\d*$') { $seg = '*_copy' }
    if ($n -notmatch '_') { $seg = '(no-prefix)' }
    $prefix[$seg] = 1 + $prefix[$seg]
}

# --- 5. persist ---
Set-Content -LiteralPath (Join-Path $OutDir '_line_map.txt') -Value ($creates | ForEach-Object { [string]$_.Line + '|' + $_.Name }) -Encoding UTF8
Set-Content -LiteralPath (Join-Path $OutDir '_col_counts.txt') -Value ($colCounts.GetEnumerator() | Sort-Object Name | ForEach-Object { $_.Key + '|' + $_.Value }) -Encoding UTF8
Set-Content -LiteralPath (Join-Path $OutDir '_nopk_tables.txt') -Value $noPk -Encoding UTF8
Set-Content -LiteralPath (Join-Path $OutDir '_prefix_dist.txt') -Value ($prefix.GetEnumerator() | Sort-Object Value -Descending | ForEach-Object { [string]$_.Value + ' ' + $_.Key }) -Encoding UTF8

# --- 6. checkpoint ---
if ((@(Get-Content -LiteralPath (Join-Path $OutDir '_line_map.txt'))).Count -ne $uniq.Count) {
    Write-Output 'CHECKPOINT_FAIL: line_map count != unique table count'
} else {
    Write-Output 'CHECKPOINT_OK: line_map == unique table count'
}
