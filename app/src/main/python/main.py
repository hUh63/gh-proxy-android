# -*- coding: utf-8 -*-
"""
FastAPI 入口（Android 版）

与 PC 版的差异：
  - 首页 HTML 由 app_main.py 注入（Android 上无文件系统访问）
  - favicon 改为内联 SVG
"""
from fastapi import FastAPI, Request
from fastapi.responses import Response, RedirectResponse

from proxy import handle_proxy

app = FastAPI(title="gh-proxy-android", docs_url=None, redoc_url=None, openapi_url=None)

# 由 app_main.py 注入的首页 HTML 字符串
INDEX_HTML = ""

# 内联 SVG favicon（避免静态文件依赖）
FAVICON_SVG = (
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">'
    '<rect width="64" height="64" rx="14" fill="url(#g)"/>'
    '<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">'
    '<stop offset="0" stop-color="#2f81f7"/><stop offset="1" stop-color="#a371f7"/>'
    '</linearGradient></defs>'
    '<text x="32" y="42" text-anchor="middle" font-family="sans-serif" '
    'font-weight="800" font-size="28" fill="#fff">fff</text></svg>'
)


@app.get("/", response_class=Response)
async def index(request: Request):
    q = request.query_params.get("q")
    if q:
        base = str(request.base_url).rstrip("/")
        return RedirectResponse(f"{base}/{q}", status_code=301)
    return Response(content=INDEX_HTML, media_type="text/html; charset=utf-8")


@app.get("/favicon.ico")
async def favicon():
    return Response(content=FAVICON_SVG, media_type="image/svg+xml")


@app.api_route("/{path:path}", methods=["GET", "POST", "HEAD", "OPTIONS", "PUT", "PATCH", "DELETE"])
async def catch_all(path: str, request: Request):
    if request.method == "OPTIONS":
        return Response(
            status_code=204,
            headers={
                "access-control-allow-origin": "*",
                "access-control-allow-methods": "GET,POST,PUT,PATCH,TRACE,DELETE,HEAD,OPTIONS",
                "access-control-max-age": "1728000",
                "access-control-allow-headers": "*",
            },
        )
    if not path:
        return RedirectResponse("/", status_code=302)
    return await handle_proxy(request, path)