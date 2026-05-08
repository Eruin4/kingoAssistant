$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Script = Join-Path $Root "server\start_server.ps1"
$Action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$Script`""
$Trigger = New-ScheduledTaskTrigger -AtLogOn
$Settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName "HomeVoiceServer" -Action $Action -Trigger $Trigger -Settings $Settings -Description "Home Voice local command server" -Force | Out-Null
Start-ScheduledTask -TaskName "HomeVoiceServer"
Write-Host "Installed and started scheduled task: HomeVoiceServer"
