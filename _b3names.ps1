$map = Get-Content er-model\_map.txt | ForEach-Object { ($_ -split '\|')[0] }
Write-Output "===QUARTZ (first 14 full names)==="
$map | Where-Object { $_ -match '^n[0-9a-f]+_' } | Select-Object -First 14 | ForEach-Object { Write-Output $_ }
Write-Output "===QUARTZ distinct qrtz-suffix (strip hex prefix)==="
$map | Where-Object { $_ -match '^n[0-9a-f]+_' } | ForEach-Object { $_ -replace '^n[0-9a-f]+_','' } | Group-Object | ForEach-Object { Write-Output ($_.Count.ToString()+"  "+$_.Name) }
Write-Output "===P-SERIES (first 14 full names)==="
$map | Where-Object { $_ -match '^p[0-9a-f]+_' } | Select-Object -First 14 | ForEach-Object { Write-Output $_ }
Write-Output "===P-SERIES distinct suffix==="
$map | Where-Object { $_ -match '^p[0-9a-f]+_' } | ForEach-Object { $_ -replace '^p[0-9a-f]+_','' } | Group-Object | ForEach-Object { Write-Output ($_.Count.ToString()+"  "+$_.Name) }
