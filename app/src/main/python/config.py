# -*- coding: utf-8 -*-
"""复用 PC 版的配置 —— Android 上 PORT/HOST 通过环境变量可调，否则用默认值"""
import os

HOST = os.getenv("GH_PROXY_HOST", "0.0.0.0")
PORT = int(os.getenv("GH_PROXY_PORT", "8080"))

# 文件大小上限（字节）。Android 默认 5GB（防止下载耗尽存储）
SIZE_LIMIT = int(os.getenv("GH_PROXY_SIZE_LIMIT", str(5 * 1024 * 1024 * 1024)))

JSDELIVR = int(os.getenv("GH_PROXY_JSDELIVR", "0"))
CHUNK_SIZE = int(os.getenv("GH_PROXY_CHUNK_SIZE", str(64 * 1024)))
TIMEOUT = float(os.getenv("GH_PROXY_TIMEOUT", "60"))
MAX_REDIRECTS = int(os.getenv("GH_PROXY_MAX_REDIRECTS", "5"))
TLS_VERIFY = int(os.getenv("GH_PROXY_TLS_VERIFY", "1")) == 1

WHITE_LIST = os.getenv("GH_PROXY_WHITE_LIST", "")
BLACK_LIST = os.getenv("GH_PROXY_BLACK_LIST", "")
PASS_LIST = os.getenv("GH_PROXY_PASS_LIST", "")


def parse_rules(raw: str):
    rules = []
    for line in (raw or "").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = tuple(p.strip() for p in line.split("/") if p.strip() != "")
        if parts:
            rules.append(parts)
    return rules