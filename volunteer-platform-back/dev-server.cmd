@echo off
REM ============================================================
REM 后台前端开发服务器（仅本地开发用，不影响生产 nginx 部署）
REM   - live-reload：assets/*.js、index.html 一保存，浏览器自动刷新
REM   - 不缓存：免去每次 Ctrl+Shift+R 强刷
REM 首次运行会自动下载 live-server（需联网，之后走缓存）。
REM 停止：在本窗口按 Ctrl+C。端口沿用 5500（api.js 据此走 http://localhost:8080/api）。
REM ============================================================
cd /d "%~dp0"
echo 启动后台前端开发服务器 http://localhost:5500 （文件改动自动刷新，按 Ctrl+C 停止）...
npx --yes live-server --port=5500 --entry-file=index.html --wait=200
