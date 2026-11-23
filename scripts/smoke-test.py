#!/usr/bin/env python3
"""
Read-only smoke test for a server running the usr:console-1.0 feature.

Signs in through the console's login form, calls every read-only REST endpoint,
and (with --reader) checks that a reader-role user is refused the operations
that change the server. Nothing on the server is modified.

    python3 scripts/smoke-test.py --url https://localhost:9443/console \
        --admin admin:change-me --reader operator:change-me-too --insecure

--insecure skips certificate checks, for Liberty's generated self-signed certificate.
Uses only the Python 3 standard library.
"""
import argparse
import http.cookiejar
import json
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request

READ_ENDPOINTS = [
    ("/api/user", "json"),
    ("/api/collective/capabilities", "json"),
    ("/api/server/info", "json"),
    ("/api/server/memory", "json"),
    ("/api/server/health", "json"),
    ("/api/server/threadpools", "json"),
    ("/api/server/metrics/applications", "json"),
    ("/api/applications", "json"),
    ("/api/configuration/info", "json"),
    ("/api/configuration/files", "json"),
    ("/api/configuration/server.xml", "text"),
    ("/api/logs/info", "json"),
    ("/api/logs/list", "json"),
    ("/api/logs/messages?lines=5", "text"),
    ("/api/settings", "json"),
]

# Operations a reader must not be allowed to perform. They are refused before
# anything runs, so they don't change the server.
ADMIN_ONLY = [
    ("POST", "/api/server/gc"),
    ("GET", "/api/server/threads"),
    ("PUT", "/api/configuration/server.xml"),
    ("POST", "/api/applications/upload"),
    ("POST", "/api/collective/operations"),
]


class Client:
    def __init__(self, base, context):
        self.base = base.rstrip("/")
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
            urllib.request.HTTPSHandler(context=context))

    def request(self, method, path, body=None, content_type=None):
        req = urllib.request.Request(self.base + path, data=body, method=method)
        if content_type:
            req.add_header("Content-Type", content_type)
        try:
            with self.opener.open(req) as resp:
                return resp.status, resp.geturl(), resp.headers.get("Content-Type", ""), resp.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            return e.code, e.geturl(), e.headers.get("Content-Type", ""), e.read().decode("utf-8", "replace")

    def login(self, user, password):
        self.request("GET", "/")  # protected page: Liberty remembers it and shows the login form
        form = urllib.parse.urlencode({"j_username": user, "j_password": password}).encode()
        status, url, _, _ = self.request("POST", "/j_security_check", form, "application/x-www-form-urlencoded")
        return "login" not in url


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", default="https://localhost:9443/console", help="console URL")
    parser.add_argument("--admin", required=True, help="user:password of an administrator-role user")
    parser.add_argument("--reader", help="user:password of a reader-role user (optional)")
    parser.add_argument("--insecure", action="store_true", help="don't verify the server certificate")
    args = parser.parse_args()

    context = ssl._create_unverified_context() if args.insecure else ssl.create_default_context()
    failures = 0

    def check(name, ok, detail=""):
        nonlocal failures
        print(("PASS  " if ok else "FAIL  ") + name + ("" if ok else f"  ({detail})"))
        failures += 0 if ok else 1

    anonymous = Client(args.url, context)
    status, url, _, _ = anonymous.request("GET", "/api/server/info")
    check("anonymous request goes to the login page", "login.html" in url, url)

    user, _, password = args.admin.partition(":")
    admin = Client(args.url, context)
    if not admin.login(user, password):
        check(f"sign in as {user}", False, "wrong user name or password, or the user has no management role")
        return 1
    check(f"sign in as {user}", True)
    for path, kind in READ_ENDPOINTS:
        status, url, content_type, body = admin.request("GET", path)
        ok = status == 200 and "login" not in url and (kind != "json" or "application/json" in content_type)
        if ok and kind == "json":
            try:
                json.loads(body)
            except ValueError:
                ok = False
        check(f"GET {path}", ok, f"HTTP {status} {content_type}")
    status, _, content_type, body = admin.request("GET", "/api/user")
    if status == 200 and "application/json" in content_type:
        check("admin has the Administrator role", json.loads(body).get("administrator") is True, body)

    status, _, _, body = admin.request("GET", "/api/collective/capabilities")
    collective = status == 200 and json.loads(body).get("available") is True
    if collective:
        status, _, content_type, body = admin.request("GET", "/api/collective")
        check("GET /api/collective", status == 200 and "application/json" in content_type, f"HTTP {status}")
        servers = json.loads(body).get("servers", []) if status == 200 else []
        if servers:
            path = "/api/collective/servers/" + urllib.parse.quote(servers[0]["id"], safe="")
            status, _, content_type, metadata = admin.request("GET", path)
            check("GET monitoring target metadata", status == 200 and json.loads(metadata).get("server", {}).get("id") == servers[0]["id"], f"HTTP {status}")

    if args.reader:
        user, _, password = args.reader.partition(":")
        reader = Client(args.url, context)
        if not reader.login(user, password):
            check(f"sign in as {user}", False, "wrong user name or password, or the user has no management role")
            return 1
        check(f"sign in as {user}", True)
        status, _, _, body = reader.request("GET", "/api/server/info")
        check("reader can read server info", status == 200, f"HTTP {status}")
        if collective:
            status, _, _, _ = reader.request("GET", "/api/collective")
            check("reader can read collective inventory", status == 200, f"HTTP {status}")
        for method, path in ADMIN_ONLY:
            status, _, _, _ = reader.request(method, path, b"" if method != "GET" else None, "text/plain")
            check(f"reader refused {method} {path}", status == 403, f"HTTP {status}")

    print(f"\n{'All checks passed' if failures == 0 else f'{failures} check(s) failed'}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
