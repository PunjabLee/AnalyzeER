$names = Get-Content er-model\_map.txt | ForEach-Object { ($_ -split '\|')[0] }
# lcap base tables: lcap_ + name, with NO trailing _{hex} app suffix and no _bak
$l = $names | Where-Object { $_ -like 'lcap_*' -and $_ -notmatch '_[0-9a-f]{6}$' -and $_ -notmatch '_bak' } | Sort-Object
$l | Set-Content -Encoding ASCII er-model\_b3_lcap.txt
Write-Output ("lcap_base=" + $l.Count)
$l | ForEach-Object { Write-Output $_ }
