# compare_ddl_sources.ps1 - Phase 2: old vs new DDL source diff census (ASCII-only comments for PS 5.1 ANSI safety)
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/compare_ddl_sources.ps1 -OldSql <old.sql> -NewSql <new.sql> [-OutDir <dir>]
# Output: console summary + _diff_added.txt / _diff_dropped.txt / _diff_retained.txt / _diff_changed.txt (table|oldcols|newcols)
param(
    [Parameter(Mandatory = $true)][string]$OldSql,
    [Parameter(Mandatory = $true)][string]$NewSql,
    [string]$OutDir = (Get-Location).Path
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }

function Get-TableMap {
    param([string]$File)
    $lines = Get-Content -LiteralPath $File -Encoding UTF8
    $map = @{}
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^\s*CREATE\s+TABLE\s+(IF\s+NOT\s+EXISTS\s+)?`?([^`\s(]+)`?') {
            $name = $Matches[2].ToLower()
            $end = $i + 1
            while ($end -lt $lines.Count -and $lines[$end] -notmatch '^\s*\)') { $end++ }
            $cols = 0
            for ($j = $i + 1; $j -lt $end; $j++) {
                if ($lines[$j] -match '^\s*`' -and $lines[$j] -notmatch '^\s*`.*`\s+(INDEX|KEY|UNIQUE|PRIMARY|CONSTRAINT|FULLTEXT|SPATIAL)\b') { $cols++ }
            }
            if (-not $map.ContainsKey($name)) { $map[$name] = $cols }
        }
    }
    return $map
}

$old = Get-TableMap $OldSql
$new = Get-TableMap $NewSql
$oldNames = @($old.Keys)
$newNames = @($new.Keys)

$added = @($newNames | Where-Object { $oldNames -notcontains $_ })
$dropped = @($oldNames | Where-Object { $newNames -notcontains $_ })
$retained = @($oldNames | Where-Object { $newNames -contains $_ })

Write-Output ('OLD_TOTAL=' + $oldNames.Count)
Write-Output ('NEW_TOTAL=' + $newNames.Count)
Write-Output ('ADDED=' + $added.Count)
Write-Output ('DROPPED=' + $dropped.Count)
Write-Output ('RETAINED=' + $retained.Count)

# set identity checkpoint
if (($added.Count + $retained.Count) -ne $newNames.Count -or ($dropped.Count + $retained.Count) -ne $oldNames.Count) {
    Write-Output 'CHECKPOINT_FAIL: set identity broken'
} else {
    Write-Output 'CHECKPOINT_OK: added+retained=new, dropped+retained=old'
}
if ($dropped.Count -eq 0 -and $added.Count -gt 0 -and $retained.Count -eq $oldNames.Count) {
    Write-Output 'NOTE: new is STRICT SUPERSET of old (zero deletion)'
}

# column-count change evidence for retained tables
$changed = @()
foreach ($t in ($retained | Sort-Object)) {
    if ($old[$t] -ne $new[$t]) { $changed += ($t + '|' + $old[$t] + '|' + $new[$t]) }
}
Write-Output ('RETAINED_COL_CHANGED=' + $changed.Count)
if ($changed.Count -gt 0) {
    Write-Output 'CHANGED_TABLES(name|oldcols|newcols):'
    $changed | ForEach-Object { Write-Output ('  ' + $_) }
    Write-Output 'CONCLUSION_HINT: retained tables changed at column level => old artifacts must be regenerated'
}

Set-Content -LiteralPath (Join-Path $OutDir '_diff_added.txt') -Value ($added | Sort-Object) -Encoding UTF8
Set-Content -LiteralPath (Join-Path $OutDir '_diff_dropped.txt') -Value ($dropped | Sort-Object) -Encoding UTF8
Set-Content -LiteralPath (Join-Path $OutDir '_diff_retained.txt') -Value ($retained | Sort-Object) -Encoding UTF8
Set-Content -LiteralPath (Join-Path $OutDir '_diff_changed.txt') -Value $changed -Encoding UTF8
