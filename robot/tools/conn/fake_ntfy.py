#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""A fake ntfy server for Robot's skills connections (docs/plans/skills-connections-plan.md §3.2, §7.4; C6).

Python's standard library only. It answers what Robot sends, the way ntfy's docs and source describe it:

    GET  /v1/health     {"healthy": true}  (404 with --old: a server older than the health check)
    POST /              "Publish as JSON": {"topic", "message", "title", "priority" 1-5, "tags"}; answers the message
                        event ({"id", "time", "event": "message", "topic", ...}); 400 for a bad topic or field, 413 for a
                        message over 4,096 bytes, 429 past --limit publishes

With --token, it works like a server with users: every request is authenticated before anything else (ntfy's
maybeAuthenticate), so a wrong token gets 401 even on /v1/health, and a publish without one gets 403. Robot never puts the
topic in a URL; a publish to another topic than --topic gets 403. Nothing is delivered anywhere, nothing is saved, and it
listens on 127.0.0.1 unless told otherwise. Each request is printed as its method and path only, then "published" for a
message it took (never a header, the topic or the text), so a test can count what reached it.

    python3 -I robot/tools/conn/fake_ntfy.py --port 8080 --topic robot_demo_topic
    python3 -I robot/tools/conn/fake_ntfy.py --port 8443 --cert server.pem --key server.key --token tk_demo...

Over adb reverse the phone reaches it at http://127.0.0.1:<port>, which Robot refuses for ntfy (the topic is a secret, so
https only, D2). CN8 needs your own ntfy server, or ntfy.sh with your OK, not this.
"""
import argparse
import json
import re
import ssl
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

TOPIC = re.compile(r'^[-_A-Za-z0-9]{1,64}$')
TAG = re.compile(r'^[^,]{1,100}$')
MAX_MESSAGE = 4096


class Fake:
    def __init__(self, topic, token, old, limit):
        self.topic, self.token, self.old, self.limit = topic, token, old, limit
        self.lock = threading.Lock()
        self.published = 0

    def publish(self, body):
        """(status, answer) for a JSON publish."""
        if not isinstance(body, dict) or not isinstance(body.get('topic'), str) or not TOPIC.match(body['topic']):
            return 400, {'code': 40009, 'http': 400, 'error': 'invalid request: topic invalid'}
        if body['topic'] != self.topic:
            return 403, {'code': 40301, 'http': 403, 'error': 'forbidden'}
        message = body.get('message', '')
        if not isinstance(message, str):
            return 400, {'code': 40024, 'http': 400, 'error': 'invalid request: message invalid'}
        if len(message.encode('utf-8')) > MAX_MESSAGE:
            return 413, {'code': 41301, 'http': 413, 'error': 'message too large'}
        title = body.get('title')
        if title is not None and not isinstance(title, str):
            return 400, {'code': 40024, 'http': 400, 'error': 'invalid request: title invalid'}
        priority = body.get('priority', 3)
        if not isinstance(priority, int) or isinstance(priority, bool) or not 1 <= priority <= 5:
            return 400, {'code': 40007, 'http': 400, 'error': 'invalid priority'}
        tags = body.get('tags', [])
        if not isinstance(tags, list) or not all(isinstance(t, str) and TAG.match(t) for t in tags):
            return 400, {'code': 40024, 'http': 400, 'error': 'invalid request: tags invalid'}
        with self.lock:
            if self.limit is not None and self.published >= self.limit:
                return 429, {'code': 42901, 'http': 429, 'error': 'limit reached: too many requests'}
            self.published += 1
            n = self.published
        answer = {'id': f'demo{n:08d}', 'time': int(time.time()), 'expires': int(time.time()) + 43200, 'event': 'message',
                  'topic': self.topic, 'message': message, 'priority': priority}
        if title:
            answer['title'] = title
        if tags:
            answer['tags'] = tags
        return 200, answer


def handler(fake, quiet):
    class H(BaseHTTPRequestHandler):
        server_version = 'FakeNtfy/1'
        sys_version = ''

        def log_message(self, fmt, *args):
            pass

        def say(self, line):
            if not quiet:
                print(line, file=sys.stderr, flush=True)

        def send(self, status, body):
            raw = (json.dumps(body) + '\n').encode('utf-8')
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def authenticated(self):
            """None when it may go on; else it has answered. As ntfy: no header is anonymous, a wrong one is 401."""
            if fake.token is None:
                return True
            h = self.headers.get('Authorization')
            if h is None:
                return None
            if h != f'Bearer {fake.token}':
                self.send(401, {'code': 40101, 'http': 401, 'error': 'unauthorized'})
                return False
            return True

        def do_GET(self):
            # The method and the path only: never a header (the token) or a body (the topic).
            self.say(f'GET {urlsplit(self.path).path}')
            if self.authenticated() is False:
                return
            if urlsplit(self.path).path == '/v1/health' and not fake.old:
                return self.send(200, {'healthy': True})
            self.send(404, {'code': 40401, 'http': 404, 'error': 'page not found'})

        def do_POST(self):
            self.say(f'POST {urlsplit(self.path).path}')
            who = self.authenticated()
            if who is False:
                return
            if urlsplit(self.path).path != '/':
                return self.send(404, {'code': 40401, 'http': 404, 'error': 'page not found'})
            if who is None:
                return self.send(403, {'code': 40301, 'http': 403, 'error': 'forbidden'})
            n = int(self.headers.get('Content-Length') or 0)
            if n > 65536:
                return self.send(413, {'code': 41301, 'http': 413, 'error': 'request too large'})
            try:
                body = json.loads(self.rfile.read(n) or b'null')
            except ValueError:
                return self.send(400, {'code': 40024, 'http': 400, 'error': 'invalid request: not JSON'})
            status, answer = fake.publish(body)
            if status == 200:
                self.say('published')
            self.send(status, answer)

        def do_PUT(self):
            self.say(f'{self.command} {urlsplit(self.path).path}')
            self.send(405, {'code': 40501, 'http': 405, 'error': 'method not allowed'})

        do_DELETE = do_PATCH = do_PUT

    return H


def main(argv=None):
    p = argparse.ArgumentParser(description='A fake ntfy server for Robot (nothing is delivered).')
    p.add_argument('--host', default='127.0.0.1')
    p.add_argument('--port', type=int, default=8080, help='0 picks a free port, printed as "port <n>"')
    p.add_argument('--topic', default='robot_demo_topic')
    p.add_argument('--token', help='act as a server with users: this Bearer token is the only one accepted')
    p.add_argument('--old', action='store_true', help='answer 404 to /v1/health, as a server older than it')
    p.add_argument('--limit', type=int, help='answer 429 after this many publishes')
    p.add_argument('--cert', help='PEM certificate chain: serve https')
    p.add_argument('--key', help='PEM private key for --cert')
    p.add_argument('--quiet', action='store_true', help='print no request lines')
    a = p.parse_args(argv)
    if not TOPIC.match(a.topic):
        p.error('a topic has only letters, digits, _ and -, at most 64')
    httpd = ThreadingHTTPServer((a.host, a.port), handler(Fake(a.topic, a.token, a.old, a.limit), a.quiet))
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
