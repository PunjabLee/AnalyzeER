$map = Get-Content er-model\_map.txt | ForEach-Object { ($_ -split '\|')[0] }
Write-Output ("total=" + $map.Count)
Write-Output ("lcap=" + ($map | Where-Object { $_ -like 'lcap_*' }).Count)
Write-Output ("quartz_n=" + ($map | Where-Object { $_ -match '^n[0-9a-f]+_' }).Count)
Write-Output ("p_series=" + ($map | Where-Object { $_ -match '^p[0-9a-f]+_' }).Count)
Write-Output "---lcap distinct suffixes---"
$map | Where-Object { $_ -like 'lcap_*' } | ForEach-Object { $_ -replace '^lcap_','' -replace '_[0-9a-f]{6,}$','' } | Group-Object | Sort-Object Count -Descending | Select-Object -First 40 | ForEach-Object { Write-Output ($_.Count.ToString() + "  " + $_.Name) }
