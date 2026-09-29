$names = Get-Content er-model\_map.txt | ForEach-Object { ($_ -split '\|')[0] }
Write-Output "===Quartz set prefixes (N{hex}) count==="
$qs = $names | Where-Object { $_ -match '^N[0-9A-F]{7}_' } | ForEach-Object { ($_ -split '_')[0] } | Sort-Object -Unique
Write-Output ("quartz_sets=" + $qs.Count)
$qs | ForEach-Object { Write-Output $_ }
Write-Output "===P-series set prefixes (P{hex}) count==="
$ps = $names | Where-Object { $_ -match '^P[0-9A-F]{7}_' } | ForEach-Object { ($_ -split '_')[0] } | Sort-Object -Unique
Write-Output ("psets=" + $ps.Count)
$ps | ForEach-Object { Write-Output $_ }
Write-Output "===lcap app suffixes count==="
$ls = $names | Where-Object { $_ -like 'lcap_*' -and $_ -match '_[0-9a-f]{6}$' } | ForEach-Object { ($_ -split '_')[-1] } | Sort-Object -Unique
Write-Output ("lcap_apps=" + $ls.Count)
$ls | ForEach-Object { Write-Output $_ }
