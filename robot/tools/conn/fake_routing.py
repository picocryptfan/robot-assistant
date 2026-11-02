#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""A fake routing server for Robot's travel time (docs/plans/skills-connections-plan.md §3.4, §7.4; C6).

Python's standard library only. It answers a route the way each server's docs and source give it (checked 26 Oct 2026),
with a made-up road: the straight-line distance times 1.3, driven at 50 km/h.

    --kind osrm         GET  /route/v1/<profile>/<lon>,<lat>;<lon>,<lat>   {"code": "Ok", "routes": [{"duration" s,
                        "distance" m, ...}], "waypoints": [...]}; 400 {"code": "InvalidQuery"} or {"code": "NoRoute"}.
                        Any profile name is taken, as osrm-routed takes it (it checks only the version).
    --kind valhalla     POST /route with the JSON request (or GET /route?json=...)   {"trip": {"summary": {"time" s,
                        "length" km}, "legs": [...], "status": 0, "units": "kilometers", ...}}; 400 {"error_code": 442}
    --kind graphhopper  POST /route with {"points": [[lon, lat], ...], "profile"}   {"paths": [{"distance" m, "time" ms,
                        ...}], "info": {...}}; 400 {"message"} for a profile it doesn't have (--profiles, "car" by default)

--no-route answers each server's "no route" instead. --strict refuses (400) a coordinate with more than two decimals, so a
test can show Robot sends where you are rounded to about 1 km. --token makes it require `Authorization: Bearer <token>`.
It listens on 127.0.0.1 unless told otherwise. Each request is printed as its method and the path's first words only
("GET /route/v1/driving"), never a coordinate, a header or a body.

    python3 -I robot/tools/conn/fake_routing.py --kind osrm --port 5000
    python3 -I robot/tools/conn/fake_routing.py --kind valhalla --port 8002 --cert server.pem --key server.key

CN7 uses it over adb reverse: the phone reaches it at http://127.0.0.1:<port>, which Robot allows for a routing server
(loopback; any other http address is refused, so where you are never goes to someone else's machine on their Wi-Fi).
"""
import argparse
import json
import math
import re
import ssl
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlsplit

OSRM_PATH = re.compile(r'^/route/v1/([A-Za-z0-9_-]+)/(.+)$')
SPEED = 50 / 3.6  # m/s
WINDING = 1.3


def metres(a, b):
    """Great-circle distance between (lat, lon) pairs, in metres."""
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(h))


def road(points):
    """(seconds, metres) along [points] (lat, lon)."""
    d = sum(metres(points[i], points[i + 1]) for i in range(len(points) - 1)) * WINDING
    return d / SPEED, d


def decimals(v):
    s = repr(float(v))
    return len(s.split('.')[1].rstrip('0')) if '.' in s and 'e' not in s else 0


class Fake:
    def __init__(self, kind, no_route, strict, profiles):
        self.kind, self.no_route, self.strict, self.profiles = kind, no_route, strict, profiles

    def points_ok(self, points):
        if len(points) < 2:
            return False
        for lat, lon in points:
            if not (isinstance(lat, (int, float)) and isinstance(lon, (int, float))) or isinstance(lat, bool) or isinstance(lon, bool):
                return False
            if not (-90 <= lat <= 90 and -180 <= lon <= 180):
                return False
            if self.strict and (decimals(lat) > 2 or decimals(lon) > 2):
                return False
        return True

    def osrm(self, coords):
        try:
            points = [(float(c.split(',')[1]), float(c.split(',')[0])) for c in coords.split(';')]
        except (IndexError, ValueError):
            return 400, {'code': 'InvalidUrl', 'message': 'URL string malformed'}
        if not self.points_ok(points):
            return 400, {'code': 'InvalidQuery', 'message': 'Query string malformed'}
        if self.no_route:
            return 400, {'code': 'NoRoute', 'message': 'Impossible route between points'}
        s, d = road(points)
        legs = [{'steps': [], 'summary': '', 'weight': round(s, 1), 'duration': round(s, 1), 'distance': round(d, 1)}]
        return 200, {'code': 'Ok', 'routes': [{'geometry': '', 'legs': legs, 'weight_name': 'routability', 'weight': round(s, 1),
                                                 'duration': round(s, 1), 'distance': round(d, 1)}],
                     'waypoints': [{'hint': 'demo', 'distance': 3.2, 'name': 'Demo Street', 'location': [lo, la]} for la, lo in points]}

    def valhalla(self, req):
        try:
            points = [(float(l['lat']), float(l['lon'])) for l in req['locations']]
        except (KeyError, TypeError, ValueError):
            return 400, {'error_code': 110, 'error': 'Insufficiently specified required parameter \'locations\'', 'status_code': 400,
                         'status': 'Bad Request'}
        if not self.points_ok(points):
            return 400, {'error_code': 171, 'error': 'No suitable edges near location', 'status_code': 400, 'status': 'Bad Request'}
        if self.no_route:
            return 400, {'error_code': 442, 'error': 'No path could be found for input', 'status_code': 400, 'status': 'Bad Request'}
        s, d = road(points)
        km = round(d / 1000, 3) if req.get('units', 'kilometers') in ('kilometers', 'km') else round(d / 1609.344, 3)
        summary = {'has_time_restrictions': False, 'has_toll': False, 'has_highway': False, 'has_ferry': False,
                   'min_lat': min(p[0] for p in points), 'min_lon': min(p[1] for p in points), 'max_lat': max(p[0] for p in points),
                   'max_lon': max(p[1] for p in points), 'time': round(s, 3), 'length': km, 'cost': round(s * 1.1, 3)}
        return 200, {'trip': {'locations': [{'type': 'break', 'lat': la, 'lon': lo, 'original_index': i} for i, (la, lo) in enumerate(points)],
                              'legs': [{'summary': summary, 'shape': ''}], 'summary': summary, 'status_message': 'Found route between points',
                              'status': 0, 'units': 'kilometers', 'language': 'en-US'}}

    def graphhopper(self, req):
        if req.get('profile') not in self.profiles:
            return 400, {'message': f"The requested profile '{req.get('profile')}' does not exist", 'hints': []}
        try:
            points = [(float(p[1]), float(p[0])) for p in req['points']]
        except (KeyError, TypeError, ValueError, IndexError):
            return 400, {'message': 'Points must not be empty', 'hints': []}
        if not self.points_ok(points):
            return 400, {'message': 'Point 0 is out of bounds', 'hints': []}
        if self.no_route:
            return 400, {'message': 'Connection between locations not found', 'hints': [{'message': 'Connection between locations not found',
                                                                                          'details': 'ConnectionNotFoundException'}]}
        s, d = road(points)
        return 200, {'hints': {'visited_nodes.sum': 42, 'visited_nodes.average': 42.0},
                     'info': {'copyrights': ['GraphHopper', 'OpenStreetMap contributors'], 'took': 1},
                     'paths': [{'distance': round(d, 3), 'weight': round(s * 1.2, 6), 'time': int(s * 1000), 'transfers': 0,
                                'snapped_waypoints': 'demo'}]}


def handler(fake, token, quiet):
    class H(BaseHTTPRequestHandler):
        server_version = 'FakeRouting/1'
        sys_version = ''

        def log_message(self, fmt, *args):
            pass

        def seen(self):
            if not quiet:
                # The method and the path's first words only: never a coordinate, a header or a body.
                words = urlsplit(self.path).path.split('/')[:4]
                print(f'{self.command} {"/".join(words)}', file=sys.stderr, flush=True)

        def send(self, status, body):
            raw = json.dumps(body).encode('utf-8')
            self.send_response(status)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def authorized(self):
            if token is not None and self.headers.get('Authorization') != f'Bearer {token}':
                self.send(401, {'message': 'Unauthorized'})
                return False
            return True

        def body(self):
            n = int(self.headers.get('Content-Length') or 0)
            if n > 65536:
                return None
            try:
                return json.loads(self.rfile.read(n) or b'null')
            except ValueError:
                return None

        def do_GET(self):
            self.seen()
            if not self.authorized():
                return
            u = urlsplit(self.path)
            if fake.kind == 'osrm':
                m = OSRM_PATH.match(u.path)
                return self.send(*fake.osrm(unquote(m.group(2)))) if m else self.send(400, {'code': 'InvalidUrl', 'message': 'URL string malformed'})
            if fake.kind == 'valhalla' and u.path == '/route':
                try:
                    req = json.loads(parse_qs(u.query).get('json', ['null'])[0])
                except ValueError:
                    req = None
                return self.send(*fake.valhalla(req)) if isinstance(req, dict) else self.send(400, {'error_code': 100, 'status_code': 400})
            self.send(404, {'message': 'Not found'})

        def do_POST(self):
            self.seen()
            if not self.authorized():
                return
            u = urlsplit(self.path)
            req = self.body()
            if u.path != '/route' or fake.kind == 'osrm':
                return self.send(404, {'message': 'Not found'})
            if not isinstance(req, dict):
                return self.send(400, {'message': 'Unsupported request'})
            self.send(*(fake.valhalla(req) if fake.kind == 'valhalla' else fake.graphhopper(req)))

    return H


def main(argv=None):
    p = argparse.ArgumentParser(description='A fake routing server for Robot (a made-up road).')
    p.add_argument('--kind', choices=['osrm', 'valhalla', 'graphhopper'], required=True)
    p.add_argument('--host', default='127.0.0.1')
    p.add_argument('--port', type=int, default=5000, help='0 picks a free port, printed as "port <n>"')
    p.add_argument('--no-route', action='store_true', help="answer each server's own \"no route\"")
    p.add_argument('--strict', action='store_true', help='refuse a coordinate with more than two decimals')
    p.add_argument('--profiles', default='car', help='GraphHopper: the profiles it has, comma-separated')
    p.add_argument('--token', help='require Authorization: Bearer <token>')
    p.add_argument('--cert', help='PEM certificate chain: serve https')
    p.add_argument('--key', help='PEM private key for --cert')
    p.add_argument('--quiet', action='store_true', help='print no request lines')
    a = p.parse_args(argv)
    fake = Fake(a.kind, a.no_route, a.strict, set(a.profiles.split(',')))
    httpd = ThreadingHTTPServer((a.host, a.port), handler(fake, a.token, a.quiet))
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
