#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""A fake webhook receiver for Robot's skills connections (docs/plans/skills-connections-plan.md §3.3, §7.4; C6).

Python's standard library only. It stands for the server a webhook of yours reaches (an automation tool, a chat's
incoming webhook):

    GET  /                  an HTML page, as a server's front page is (Robot's Test asks only this, with no key)
    any  --path PATH        the webhook: answers --reply (JSON, by default {"ok": true, "saved": true, "count": 3}) with
                            --status (200 by default); a JSON body must be JSON (400 otherwise)
    anything else           404

--path may hold a secret (as Discord's and Slack's do): only that exact path is the webhook. --token makes the webhook
require `Authorization: Bearer <token>` (401 otherwise); the front page never does. It listens on 127.0.0.1 unless told
otherwise. Each request is printed as its method and "/" or "webhook" or "other", never the path itself (it may be the
secret), a header or a body, so a test can count what reached it (CN12: 31 runs in a minute; the 31st never arrives).

    python3 -I robot/tools/conn/fake_webhook.py --port 5678 --path /webhook/trips
    python3 -I robot/tools/conn/fake_webhook.py --port 8443 --cert server.pem --key server.key --path /api/webhooks/1/AbC...
"""
import argparse
import json
import ssl
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

PAGE = b'<!doctype html><title>Demo server</title><p>Nothing to see here.</p>\n'


def handler(path, token, status, reply, quiet, counts):
    lock = threading.Lock()

    class H(BaseHTTPRequestHandler):
        server_version = 'FakeWebhook/1'
        sys_version = ''

        def log_message(self, fmt, *args):
            pass

        def send(self, code, body, kind='application/json'):
            raw = body if isinstance(body, bytes) else json.dumps(body).encode('utf-8')
            self.send_response(code)
            self.send_header('Content-Type', kind)
            self.send_header('Content-Length', str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def any(self):
            p = urlsplit(self.path).path
            what = '/' if p == '/' else 'webhook' if p == path else 'other'
            with lock:
                counts[what] = counts.get(what, 0) + 1
            if not quiet:
                # Never the path itself (it may be the secret), a header or a body.
                print(f'{self.command} {what}', file=sys.stderr, flush=True)
            n = int(self.headers.get('Content-Length') or 0)
            data = self.rfile.read(n) if 0 < n <= 65536 else b''
            if what == '/' and self.command in ('GET', 'HEAD'):
                return self.send(200, PAGE, 'text/html; charset=utf-8')
            if what != 'webhook':
                return self.send(404, {'message': 'Not found'})
            if token is not None and self.headers.get('Authorization') != f'Bearer {token}':
                return self.send(401, {'message': 'Unauthorized'})
            if n > 65536:
                return self.send(413, {'message': 'Too large'})
            if data and (self.headers.get('Content-Type') or '').startswith('application/json'):
                try:
                    json.loads(data)
                except ValueError:
                    return self.send(400, {'message': 'Not JSON'})
            self.send(status, reply)

        do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = do_HEAD = any

    return H


def main(argv=None):
    p = argparse.ArgumentParser(description='A fake webhook receiver for Robot.')
    p.add_argument('--host', default='127.0.0.1')
    p.add_argument('--port', type=int, default=5678, help='0 picks a free port, printed as "port <n>"')
    p.add_argument('--path', default='/webhook/robot', help="the webhook's path (it may hold a secret)")
    p.add_argument('--token', help='require Authorization: Bearer <token> on the webhook')
    p.add_argument('--status', type=int, default=200)
    p.add_argument('--reply', default='{"ok": true, "saved": true, "count": 3}', help='the JSON the webhook answers')
    p.add_argument('--cert', help='PEM certificate chain: serve https')
    p.add_argument('--key', help='PEM private key for --cert')
    p.add_argument('--quiet', action='store_true', help='print no request lines')
    a = p.parse_args(argv)
    reply = json.loads(a.reply)
    httpd = ThreadingHTTPServer((a.host, a.port), handler(a.path, a.token, a.status, reply, a.quiet, {}))
    httpd.daemon_threads = True
    if a.cert:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.load_cert_chain(a.cert, a.key)
        httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
    print(f'port {httpd.server_address[1]}', flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


if __name__ == '__main__':
    main()
