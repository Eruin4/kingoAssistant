$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $Root
$LogDir = Join-Path $Root "server\logs"
New-Item -ItemType Directory -Force $LogDir | Out-Null
$LogFile = Join-Path $LogDir "home_voice_server.log"
python .\server\home_voice_server.py --host 0.0.0.0 --port 8000 *>> $LogFile
