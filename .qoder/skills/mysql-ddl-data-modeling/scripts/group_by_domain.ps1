# group_by_domain.ps1 - Phase 4: auto domain grouping + zero-miss checkpoint (template; RULES must be customized per run; ASCII-only comments)
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/group_by_domain.ps1 -TableList <list.txt> [-OutFile <_grouped.txt>]
# Table list can be derived from census: Get-Content _line_map.txt | ForEach-Object { ($_ -split '\|')[1] } | Sort-Object -Unique | Set-Content _tbl_list.txt
param(
    [Parameter(Mandatory = $true)][string]$TableList,
    [string]$OutFile = (Join-Path (Get-Location).Path '_grouped.txt')
)
$ErrorActionPreference = 'Stop'
$tables = Get-Content -LiteralPath $TableList | Where-Object { $_ -and $_ -notmatch '^\s*#' } | ForEach-Object { $_.Trim().ToLower() }

# ---- ORDERED RULES: first match wins, order = priority ----
# Keys:
#   1) put special cases BEFORE greedy family rules;
#   2) exact table = '^prefix$', family = '^prefix_' ; NEVER use bare '^prefix'
#      (e.g. '^jf_product' wrongly swallows jf_production_scale);
#   3) keep the catch-all domain, then manually re-check every table it receives.
$rules = [ordered]@{
    'D01_ExampleOrder'   = @('^jf_order$', '^jf_order_')
    'D02_ExampleSpecial' = @('^jf_special_case$')
    'D98_ExampleRest'    = @('^jf_misc')
    'D99_CatchAll'       = @('.')
}

$assigned = @{}
$unmatched = @()
foreach ($t in $tables) {
    $hit = $null
    foreach ($dom in $rules.Keys) {
        foreach ($pat in $rules[$dom]) {
            if ($t -match $pat) { $hit = $dom; break }
        }
        if ($hit) { break }
    }
    if ($hit) { $assigned[$t] = $hit } else { $unmatched += $t }
}

# ---- output + validation ----
$out = foreach ($dom in $rules.Keys) {
    $members = @($assigned.GetEnumerator() | Where-Object Value -eq $dom | ForEach-Object Key | Sort-Object)
    foreach ($m in $members) { $dom + [char]9 + $m }
    $dom + [char]9 + '=COUNT=' + $members.Count
}
$OutDir = Split-Path -Parent $OutFile
if ($OutDir -and -not (Test-Path -LiteralPath $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }
Set-Content -LiteralPath $OutFile -Value $out -Encoding UTF8

Write-Output ('TOTAL_INPUT=' + $tables.Count)
Write-Output ('ASSIGNED=' + $assigned.Count)
Write-Output ('UNMATCHED=' + $unmatched.Count)
if ($unmatched.Count -gt 0) { $unmatched | ForEach-Object { Write-Output ('  UNMATCHED ' + $_) } }
$sum = 0
foreach ($dom in $rules.Keys) { $sum += @($assigned.GetEnumerator() | Where-Object Value -eq $dom).Count }
if ($sum -eq $tables.Count -and $unmatched.Count -eq 0) {
    Write-Output 'CHECKPOINT_OK: sum(domains) == input, zero unmatched'
} else {
    Write-Output 'CHECKPOINT_FAIL: fix rules and rerun (review catch-all membership manually too)'
}
