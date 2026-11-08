#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""A fake MCP server for Robot's tests (1.3, S3.6): both eras Robot speaks, standard library only.

  modern  the 2026-07-28 revision: server/discover, _meta on every request, MCP-Protocol-Version and Mcp-Method
          headers checked, Mcp-Name for tools/call, results with resultType "complete". No session.
  legacy  the initialize era (2025-06-18): server/discover answered as the reference SDK answers an unknown request
          without a session (400), initialize gives an Mcp-Session-Id, every later request needs it (404 for one it
          doesn't know), DELETE ends it.

Its tools are shaped like Home Assistant's (HassTurnOn, HassTurnOff, GetLiveContext). --change adds a property to
HassTurnOff's inputSchema and rewords its description (design M2: the tool turns off in Robot as "changed on your
server"). --token makes it want `Authorization: Bearer <token>` (401 otherwise; M3 runs it on 127.0.0.1:8808 with and
without one). --sse answers as Server-Sent Events, with a log notification before the answer; --hold keeps the stream
open that many seconds after it (Robot closes it once the answer came).

  python3 fake_mcp.py --era legacy --sse --token TEST-TOKEN          # serve on 127.0.0.1:8808
  python3 fake_mcp.py --record DIR                                   # record the replies Robot's tests replay

--record runs the server on a free port, sends it the requests Robot sends (McpWire's, by hand) for each scenario, and
writes DIR/<scenario>.json: each exchange's method, status, Content-Type, session and body. Robot's JVM tests
(helpers McpClientTest) replay those files. Nothing here reaches the network: it listens on loopback unless --host says.
"""
import argparse
import json
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODERN = "2026-07-28"
LEGACY = "2025-06-18"
NAME = {"type": "string", "description": "Name of a device or entity"}
AREA = {"type": "string", "description": "Name of an area"}


def tools(change):
    off = {"name": NAME, "area": AREA}
    if change:
        off["floor"] = {"type": "string", "description": "Name of a floor"}
    return [
        {"name": "HassTurnOn", "description": "Turns on/opens a device or entity",
         "inputSchema": {"type": "object", "properties": {"name": NAME, "area": AREA}}},
        {"name": "HassTurnOff", "description": "Turns off/closes a device or entity, by name, area or floor" if change
         else "Turns off/closes a device or entity",
         "inputSchema": {"type": "object", "properties": off}},
        {"name": "GetLiveContext", "description": "Provides real-time information about the state of the home",
         "inputSchema": {"type": "object", "properties": {}}, "annotations": {"readOnlyHint": True}},
    ]


class Fake:
    def __init__(self, era, token=None, sse=False, hold=0.0, change=False):
        self.era, self.token, self.sse, self.hold, self.change = era, token, sse, hold, change
        self.sessions = set()
        self.lock = threading.Lock()

    def result(self, method, params):
        """(result, error) for a request this server knows."""
        if method == "server/discover" and self.era == "modern":
            return {"supportedVersions": [MODERN], "capabilities": {"tools": {}}, "resultType": "complete",
                    "_meta": {"io.modelcontextprotocol/serverInfo": {"name": "fake-mcp", "version": "1"}}}, None
        if method == "initialize" and self.era == "legacy":
            return {"protocolVersion": LEGACY, "capabilities": {"tools": {"listChanged": False}},
                    "serverInfo": {"name": "fake-mcp", "version": "1"}}, None
        if method == "tools/list":
            r = {"tools": tools(self.change)}
        elif method == "tools/call":
            name, args = params.get("name"), params.get("arguments") or {}
            if name not in [t["name"] for t in tools(self.change)]:
                return None, {"code": -32602, "message": "Unknown tool"}
            if name == "GetLiveContext":
                r = {"content": [{"type": "text", "text": "Kitchen light: on. Front door: locked."}], "isError": False}
            elif not (args.get("name") or args.get("area")):
                r = {"content": [{"type": "text", "text": "Say a name or an area."}], "isError": True}
            else:
                what = args.get("name") or args.get("area")
                r = {"content": [{"type": "text", "text": "%s %s." % ("Turned off" if name == "HassTurnOff" else "Turned on", what)}],
                     "isError": False}
        else:
            return None, {"code": -32601, "message": "Method not found"}
        if self.era == "modern":
            r["resultType"] = "complete"
        return r, None


class Handler(BaseHTTPRequestHandler):
    fake = None  # set by serve()

    def log_message(self, *a):
        pass

    def reply(self, status, obj=None, session=None, sse=False):
        body = b""
        if obj is not None:
            text = json.dumps(obj)
            if sse:
                note = json.dumps({"jsonrpc": "2.0", "method": "notifications/message", "params": {"level": "info", "data": "working"}})
                body = ("event: message\ndata: %s\n\nevent: message\ndata: %s\n\n" % (note, text)).encode()
            else:
                body = text.encode()
        self.send_response(status)
        if obj is not None:
            self.send_header("Content-Type", "text/event-stream" if sse else "application/json")
        if session:
            self.send_header("Mcp-Session-Id", session)
        if sse and self.fake.hold:
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)
            self.wfile.flush()
            time.sleep(self.fake.hold)
            return
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def allowed(self):
        f = self.fake
        if f.token and self.headers.get("Authorization") != "Bearer " + f.token:
            self.reply(401)
            return False
        return True

    def do_DELETE(self):
        if not self.allowed():
            return
        s = self.headers.get("Mcp-Session-Id")
        with self.fake.lock:
            known = s in self.fake.sessions
            self.fake.sessions.discard(s)
        self.reply(200 if known else 404)

    def do_GET(self):
        self.reply(405)

    def do_POST(self):
        f = self.fake
        if not self.allowed():
            return
        try:
            m = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        except ValueError:
            return self.reply(400, {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Parse error"}})
        method, rid, params = m.get("method"), m.get("id"), m.get("params") or {}
        if f.era == "modern":
            if self.headers.get("MCP-Protocol-Version") != MODERN or self.headers.get("Mcp-Method") != method:
                return self.reply(400, {"jsonrpc": "2.0", "id": rid, "error": {"code": -32600, "message": "Bad headers"}})
            meta = params.get("_meta") or {}
            if meta.get("io.modelcontextprotocol/protocolVersion") != MODERN:
                return self.reply(400, {"jsonrpc": "2.0", "id": rid, "error": {"code": -32600, "message": "No _meta"}})
            if method == "tools/call" and self.headers.get("Mcp-Name") != params.get("name"):
                return self.reply(400, {"jsonrpc": "2.0", "id": rid, "error": {"code": -32600, "message": "Bad Mcp-Name"}})
        session = None
        if f.era == "legacy":
            if method == "initialize":
                session = uuid.uuid4().hex
                with f.lock:
                    f.sessions.add(session)
            else:
                s = self.headers.get("Mcp-Session-Id")
                if s is None:
                    # The reference SDK's answer to a request without a session (server/discover's probe gets this).
                    return self.reply(400, {"jsonrpc": "2.0", "id": "server-error",
                                            "error": {"code": -32600, "message": "Bad Request: Missing session ID"}})
                with f.lock:
                    known = s in f.sessions
                if not known:
                    return self.reply(404, {"jsonrpc": "2.0", "id": "server-error", "error": {"code": -32001, "message": "Session not found"}})
                if self.headers.get("MCP-Protocol-Version") != LEGACY:
                    return self.reply(400, {"jsonrpc": "2.0", "id": rid, "error": {"code": -32600, "message": "Bad version"}})
        if rid is None:
            return self.reply(202)
        r, e = f.result(method, params)
        msg = {"jsonrpc": "2.0", "id": rid}
        msg.update({"error": e} if e else {"result": r})
        self.reply(200, msg, session, sse=f.sse)


def serve(fake, host, port):
    handler = type("H", (Handler,), {"fake": fake})
    return ThreadingHTTPServer((host, port), handler)


# Recording: the requests Robot sends (McpWire), by hand.

def meta():
    return {"io.modelcontextprotocol/protocolVersion": MODERN, "io.modelcontextprotocol/clientCapabilities": {},
            "io.modelcontextprotocol/clientInfo": {"name": "Robot", "version": "test"}}


def send(base, verb, method, rid, params, headers):
    body = None
    if method is not None:
        m = {"jsonrpc": "2.0", "method": method, "params": params}
        if rid is not None:
            m["id"] = rid
        body = json.dumps(m).encode()
    h = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
    h.update(headers)
    req = urllib.request.Request(base, data=body, method=verb, headers=h)
    try:
        r = urllib.request.urlopen(req, timeout=5)
    except urllib.error.HTTPError as e:
        r = e
    return {"method": method or verb, "status": r.status if hasattr(r, "status") else r.code,
            "type": r.headers.get("Content-Type"), "session": r.headers.get("Mcp-Session-Id"),
            "body": r.read().decode()}


def record(out):
    import os
    os.makedirs(out, exist_ok=True)
    scenarios = {
        "modern": dict(era="modern"),
        "modern-changed": dict(era="modern", change=True),
        "legacy-sse": dict(era="legacy", sse=True, token="TEST-TOKEN"),
    }
    for name, kw in scenarios.items():
        fake = Fake(**kw)
        httpd = serve(fake, "127.0.0.1", 0)
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        base = "http://127.0.0.1:%d/api/mcp" % httpd.server_address[1]
        auth = {"Authorization": "Bearer TEST-TOKEN"} if kw.get("token") else {}
        got = []
        if kw["era"] == "modern":
            def post(method, rid, params, extra=None):
                h = dict(auth, **{"MCP-Protocol-Version": MODERN, "Mcp-Method": method}, **(extra or {}))
                got.append(send(base, "POST", method, rid, dict(params, _meta=meta()), h))
            post("server/discover", 1, {})
            post("tools/list", 2, {})
            post("tools/call", 3, {"name": "HassTurnOff", "arguments": {"name": "kitchen"}}, {"Mcp-Name": "HassTurnOff"})
        else:
            got.append(send(base, "POST", "server/discover", 1, {"_meta": meta()}, dict(auth, **{"MCP-Protocol-Version": MODERN, "Mcp-Method": "server/discover"})))
            got.append(send(base, "POST", "initialize", 2, {"protocolVersion": "2025-11-25", "capabilities": {},
                                                             "clientInfo": {"name": "Robot", "version": "test"}}, auth))
            s = got[-1]["session"]
            h = dict(auth, **{"Mcp-Session-Id": s, "MCP-Protocol-Version": LEGACY})
            got.append(send(base, "POST", "notifications/initialized", None, {}, h))
            got.append(send(base, "POST", "tools/list", 3, {}, h))
            got.append(send(base, "POST", "tools/call", 4, {"name": "HassTurnOff", "arguments": {"name": "kitchen"}}, h))
            got.append(send(base, "DELETE", None, None, None, h))
            got.append(send(base, "POST", "tools/list", 5, {}, h))  # the session is gone: 404
            got[-1]["method"] = "tools/list after DELETE"
            got.append(send(base, "POST", "tools/list", 6, {}, {"Mcp-Session-Id": s, "MCP-Protocol-Version": LEGACY}))
            got[-1]["method"] = "tools/list without the token"
        httpd.shutdown()
        with open(os.path.join(out, name + ".json"), "w") as f:
            json.dump(got, f, indent=1, sort_keys=True)
            f.write("\n")
        print("%s: %d exchanges, statuses %s" % (name, len(got), [g["status"] for g in got]))


def main():
    p = argparse.ArgumentParser(description="A fake MCP server for Robot's tests.")
    p.add_argument("--era", choices=["modern", "legacy"], default="modern")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8808)
    p.add_argument("--token", help="want Authorization: Bearer TOKEN")
    p.add_argument("--sse", action="store_true", help="answer as Server-Sent Events")
    p.add_argument("--hold", type=float, default=0.0, help="keep a stream open this many seconds after its answer")
    p.add_argument("--change", action="store_true", help="HassTurnOff's schema and description changed (M2)")
    p.add_argument("--record", metavar="DIR", help="record the replies Robot's tests replay, then exit")
    a = p.parse_args()
    if a.record:
        record(a.record)
        return
    httpd = serve(Fake(a.era, a.token, a.sse, a.hold, a.change), a.host, a.port)
    print("fake MCP (%s%s) on http://%s:%d/ (any path)" % (a.era, ", SSE" if a.sse else "", a.host, httpd.server_address[1]), file=sys.stderr)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
