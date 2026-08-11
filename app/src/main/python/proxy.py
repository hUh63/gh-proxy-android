# -*- coding: utf-8 -*-
"""复用 PC 版的代理逻辑 —— 与 gh-proxy-python/app/proxy.py 完全一致"""
import re

import httpx
from fastapi import Request
from fastapi.responses import RedirectResponse, Response, StreamingResponse

# Chaquopy 默认把 src/main/python 当顶级模块根目录（不视为包），
# 因此所有模块间 import 用绝对 import，不可用相对 import。
import config

# ------------------------------------------------------------------
# URL 匹配规则（与 gh-proxy 完全一致）
# ------------------------------------------------------------------
EXP_RELEASE_ARCHIVE = re.compile(
    r"^(?:https?://)?github\.com/(?P<author>[^/]+)/(?P<repo>[^/]+)/(?:releases|archive)/.*$",
    re.IGNORECASE,
)
EXP_BLOB_RAW = re.compile(
    r"^(?:https?://)?github\.com/(?P<author>[^/]+)/(?P<repo>[^/]+)/(?:blob|raw)/.*$",
    re.IGNORECASE,
)
EXP_GIT = re.compile(
    r"^(?:https?://)?github\.com/(?P<author>[^/]+)/(?P<repo>[^/]+)/(?:info|git-).*$",
    re.IGNORECASE,
)
EXP_RAW = re.compile(
    r"^(?:https?://)?raw\.(?:githubusercontent|github)\.com/(?P<author>[^/]+)/(?P<repo>[^/]+)/.+?/.+$",
    re.IGNORECASE,
)
EXP_GIST = re.compile(
    r"^(?:https?://)?gist\.(?:githubusercontent|github)\.com/(?P<author>[^/]+)/.+?/.+$",
    re.IGNORECASE,
)
EXP_TAGS = re.compile(
    r"^(?:https?://)?github\.com/(?P<author>[^/]+)/(?P<repo>[^/]+)/tags.*$",
    re.IGNORECASE,
)
ALL_EXPS = (EXP_RELEASE_ARCHIVE, EXP_BLOB_RAW, EXP_GIT, EXP_RAW, EXP_GIST, EXP_TAGS)

KEEP_RESPONSE_HEADERS = {
    "content-type", "content-length", "content-disposition", "content-range",
    "accept-ranges", "etag", "last-modified", "cache-control",
    "expires", "date", "location", "x-ratelimit-remaining", "x-ratelimit-reset",
}
DROP_REQUEST_HEADERS = {
    "host", "connection", "accept-encoding", "transfer-encoding",
    "content-length", "content-encoding",
}

WHITE_LIST = config.parse_rules(config.WHITE_LIST)
BLACK_LIST = config.parse_rules(config.BLACK_LIST)
PASS_LIST = config.parse_rules(config.PASS_LIST)


def check_url(url: str):
    for exp in ALL_EXPS:
        m = exp.match(url)
        if m:
            return m
    return None


def match_rules(match, rules) -> bool:
    if not rules:
        return False
    author = match.group("author")
    repo = match.group("repo") if "repo" in match.groupdict() and match.group("repo") else None
    for rule in rules:
        if len(rule) == 1:
            if rule[0] == author:
                return True
        elif len(rule) == 2:
            if rule[0] == "*" and repo and rule[1] == repo:
                return True
            if rule[0] == author and repo and rule[1] == repo:
                return True
    return False


def enforce_access_control(match) -> str | None:
    if WHITE_LIST and not match_rules(match, WHITE_LIST):
        return "Forbidden by white list."
    if BLACK_LIST and match_rules(match, BLACK_LIST):
        return "Forbidden by black list."
    if PASS_LIST and match_rules(match, PASS_LIST):
        return "pass"
    return None


def build_upstream_headers(request: Request) -> dict:
    headers = {}
    for key, value in request.headers.items():
        if key.lower() in DROP_REQUEST_HEADERS:
            continue
        if key.lower().startswith("x-forwarded"):
            continue
        headers[key] = value
    headers.setdefault("user-agent", "gh-proxy-android/1.0 (+https://github.com/hunshcn/gh-proxy)")
    return headers


def build_response_headers(resp: httpx.Response) -> dict:
    headers = {}
    for key, value in resp.headers.items():
        if key.lower() in KEEP_RESPONSE_HEADERS:
            headers[key] = value
    headers["access-control-allow-origin"] = "*"
    headers["access-control-expose-headers"] = "*"
    headers["access-control-allow-methods"] = "GET,POST,PUT,PATCH,TRACE,DELETE,HEAD,OPTIONS"
    return headers


async def proxy_stream(request: Request, target_url: str, redirects_left: int) -> Response:
    upstream_headers = build_upstream_headers(request)
    body = await request.body()
    transport = httpx.AsyncHTTPTransport(retries=1, verify=config.TLS_VERIFY)
    timeout = httpx.Timeout(config.TIMEOUT, connect=15)
    client = httpx.AsyncClient(transport=transport, timeout=timeout,
                               follow_redirects=False, trust_env=False)
    try:
        req = client.build_request(request.method, target_url, headers=upstream_headers, content=body)
        resp = await client.send(req, stream=True)

        if resp.status_code in (301, 302, 303, 307, 308) and resp.headers.get("location"):
            location = resp.headers["location"]
            await resp.aclose()
            await client.aclose()
            m = check_url(location)
            if m:
                return RedirectResponse("/" + location, status_code=resp.status_code,
                                        headers={"access-control-allow-origin": "*",
                                                 "access-control-expose-headers": "*"})
            if redirects_left <= 0:
                return Response("Too many redirects.", status_code=502)
            return await proxy_stream(request, location, redirects_left - 1)

        content_length = resp.headers.get("content-length")
        if content_length and content_length.isdigit() and int(content_length) > config.SIZE_LIMIT:
            await resp.aclose()
            await client.aclose()
            return RedirectResponse(target_url, status_code=302)

        headers = build_response_headers(resp)
        status = resp.status_code
        chunk = config.CHUNK_SIZE

        async def generate():
            try:
                async for data in resp.aiter_bytes(chunk):
                    yield data
            finally:
                await resp.aclose()
                await client.aclose()

        return StreamingResponse(generate(), status_code=status, headers=headers)

    except httpx.TimeoutException:
        await client.aclose()
        return Response("Upstream timeout.", status_code=504)
    except httpx.HTTPError as e:
        await client.aclose()
        return Response(f"Upstream error: {type(e).__name__}", status_code=502)
    except Exception as e:
        await client.aclose()
        return Response(f"server error: {e}", status_code=500)


async def handle_proxy(request: Request, path_url: str) -> Response:
    if not path_url.startswith("http"):
        path_url = "https://" + path_url
    if "://" not in path_url[4:9]:
        path_url = path_url.replace("s:/", "s://", 1)

    m = check_url(path_url)
    if not m:
        return Response("Invalid input.", status_code=403)

    ctrl = enforce_access_control(m)
    if ctrl and ctrl != "pass":
        return Response(ctrl, status_code=403)
    pass_by = ctrl == "pass"

    if (config.JSDELIVR or pass_by) and EXP_BLOB_RAW.match(path_url):
        new_url = re.sub(
            r"^https?://github\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.*)$",
            r"https://cdn.jsdelivr.net/gh/\1/\2@\3/\4", path_url)
        return RedirectResponse(new_url, status_code=302)
    if (config.JSDELIVR or pass_by) and EXP_RAW.match(path_url):
        new_url = re.sub(
            r"^https?://raw\.(?:githubusercontent|github)\.com/([^/]+)/([^/]+)/([^/]+)/(.*)$",
            r"https://cdn.jsdelivr.net/gh/\1/\2@\3/\4", path_url)
        return RedirectResponse(new_url, status_code=302)

    if EXP_BLOB_RAW.match(path_url):
        path_url = path_url.replace("/blob/", "/raw/", 1)
    if pass_by:
        return RedirectResponse(path_url, status_code=302)

    try:
        return await proxy_stream(request, path_url, config.MAX_REDIRECTS)
    except httpx.TimeoutException:
        return Response("Upstream timeout.", status_code=504)
    except httpx.HTTPError as e:
        return Response(f"Upstream error: {type(e).__name__}", status_code=502)
    except Exception as e:
        return Response(f"server error: {e}", status_code=500)