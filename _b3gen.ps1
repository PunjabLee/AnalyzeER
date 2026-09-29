$names = Get-Content er-model\_map.txt | ForEach-Object { ($_ -split '\|')[0] }
# Quartz representative: first set N0DD15FF_
$q = $names | Where-Object { $_ -match '^N0DD15FF_' } | Sort-Object
$q | Set-Content -Encoding ASCII er-model\_b3_quartz.txt
Write-Output ("quartz_rep=" + $q.Count)
# P-series representative: one set P12BC091_
$p = $names | Where-Object { $_ -match '^P12BC091_' } | Sort-Object
$p | Set-Content -Encoding ASCII er-model\_b3_pseries.txt
Write-Output ("pseries_rep=" + $p.Count)
# lcap: base (non-bak) tables, list distinct full names sample
$l = $names | Where-Object { $_ -like 'lcap_*' -and $_ -notmatch '_bak$' }
Write-Output ("lcap_non_bak=" + $l.Count)
Write-Output "===lcap sample first 40==="
$l | Select-Object -First 40 | ForEach-Object { Write-Output $_ }
