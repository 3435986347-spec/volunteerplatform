#!/usr/bin/env python3
"""本地联调用：把后台控制台（../volunteerplatform-back）以静态站点起在本机，
并在返回 index.html 时把接口基址改写成本机后端 http://localhost:8080/api。

为什么要这个脚本：控制台 index.html 在 localhost 下打开时【指向线上服务器】（有意为之，
见那段注释），直接 `python -m http.server` 起静态页，本地点的每一下都打在线上库上。
这个脚本只在【响应里】改写那一行，不动源文件，所以不会被误提交。

用法：python3 tools/serve_admin_local.py [端口，默认 5601] [后端基址，默认 http://localhost:8080/api]
"""
import http.server
import os
import sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 5601
API = sys.argv[2] if len(sys.argv) > 2 else 'http://localhost:8080/api'
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', 'volunteerplatform-back'))
REMOTE = "'http://118.145.69.25/api'"


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=ROOT, **kw)

    def end_headers(self):
        self.send_header('Cache-Control', 'no-store')
        super().end_headers()

    def do_GET(self):
        path = self.path.split('?', 1)[0]
        if path in ('/', '/index.html'):
            with open(os.path.join(ROOT, 'index.html'), encoding='utf-8') as f:
                html = f.read()
            if REMOTE not in html:
                self.send_error(500, 'index.html 里找不到线上基址那一行，拒绝在不确定打哪个库的情况下启动')
                return
            body = html.replace(REMOTE, "'" + API + "'").encode('utf-8')
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        super().do_GET()


if __name__ == '__main__':
    print('serving', ROOT, 'on', PORT, '→ API', API, flush=True)
    http.server.ThreadingHTTPServer(('127.0.0.1', PORT), Handler).serve_forever()
