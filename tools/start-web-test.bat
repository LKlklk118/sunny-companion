@echo off
rem ============================================================
rem  阳光专属陪伴 · Web 测试版 一键启动
rem  双击本文件即可：启动本地服务并自动打开浏览器
rem  - 本机访问   : http://127.0.0.1:8765
rem  - 手机访问   : http://<本机局域网IP>:8765  （需同一 WiFi）
rem  关闭窗口 = 停止服务
rem ============================================================
chcp 65001 >nul
cd /d "%~dp0.."
where node >nul 2>nul
if errorlevel 1 (
  echo [错误] 未检测到 Node.js，请先安装：https://nodejs.org
  pause
  exit /b 1
)
start "" http://127.0.0.1:8765
node tools\preview-server.js
pause
