#!/usr/bin/env python3
"""Kaos testi verisi: data/access-dirty.log ve data/access-empty.log dosyalarını üretir.

Her satırın türü bilinçli olarak seçilmiştir; ARCHITECTURE.md'deki "hata türü -> politika"
tablosu ve DirtyDataChaosTest bu dosyaya dayanır. Çalıştırma: python3 tools/generate_access_dirty.py
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def clf(ip, minute, method, path, status, size, day="30/Sep/2026"):
    return f'{ip} - - [{day}:10:{minute:02d}:00 +0300] "{method} {path} HTTP/1.1" {status} {size}'.encode()


VALID = [
    clf("10.2.0.1", 0, "GET", "/", 200, 512),
    clf("10.2.0.2", 1, "GET", "/api/items", 200, 734),
    clf("10.2.0.3", 2, "POST", "/api/orders", 201, 128),
    clf("10.2.0.4", 3, "GET", "/missing", 404, 0),
    clf("10.2.0.5", 4, "GET", "/static/app.js", 200, 48213),
    clf("2001:db8::7", 5, "GET", "/ipv6", 200, 300),
    clf("10.2.0.6", 6, "DELETE", "/api/items/7", 500, 64),
    clf("10.2.0.7", 7, "PUT", "/api/cart", 200, 77),
    clf("10.2.0.8", 8, "GET", "/login", 302, 0),
    clf("10.2.0.9", 9, "HEAD", "/", 200, 0),
]

# (açıklama, satır baytları)
LINES = [
    ("valid", VALID[0]),
    ("truncated", VALID[1][:40]),
    ("valid", VALID[1]),
    ("empty", b""),
    ("non-utf8", b'10.2.0.20 - - [30/Sep/2026:10:10:00 +0300] "GET /caf\xe9\xff HTTP/1.1" 200 10'),
    ("valid", VALID[2]),
    ("negative-bytes", clf("10.2.0.21", 11, "GET", "/neg", 200, -512)),
    ("year-1970", clf("10.2.0.22", 12, "GET", "/old", 200, 10, day="01/Jan/1970")),
    ("valid", VALID[3]),
    ("year-2099", clf("10.2.0.23", 13, "GET", "/future", 200, 10, day="01/Jan/2099")),
    ("huge-line", clf("10.2.0.24", 14, "GET", "/" + "a" * (100 * 1024), 200, 10)),
    ("valid", VALID[4]),
    ("blank", b"   "),
    ("truncated", VALID[5][:55]),
    ("valid", VALID[5]),
    ("invalid-ip", clf("999.10.10.1", 15, "GET", "/bad-ip", 200, 10)),
    ("invalid-status", clf("10.2.0.25", 16, "GET", "/bad-status", 999, 10)),
    ("valid", VALID[6]),
    ("garbage", b"\x00\x01 this is not a log line at all"),
    ("invalid-date", clf("10.2.0.26", 17, "GET", "/feb", 200, 10, day="31/Feb/2026")),
    ("bytes-overflow", clf("10.2.0.27", 18, "GET", "/overflow", 200, 99999999999999999999)),
    ("valid", VALID[7]),
    ("non-utf8", b'10.2.0.28 - - [30/Sep/2026:10:19:00 +0300] "GET /x HTTP/1.1" 200 10 "-" "Mozilla \xc3\x28"'),
    ("unknown-method", clf("10.2.0.29", 20, "BREW", "/coffee", 418, 10)),
    ("valid", VALID[8]),
    ("negative-bytes", clf("10.2.0.30", 21, "GET", "/neg2", 200, -1)),
    ("valid", VALID[9]),
    ("truncated-no-newline", VALID[0][:30]),
]

if __name__ == "__main__":
    data = b"\n".join(line for _, line in LINES)  # son satır yeni satırla bitmez (kesilmiş dosya)
    (ROOT / "data" / "access-dirty.log").write_bytes(data)
    (ROOT / "data" / "access-empty.log").write_bytes(b"")
    kinds = {}
    for kind, _ in LINES:
        kinds[kind] = kinds.get(kind, 0) + 1
    print(f"{len(LINES)} lines:", ", ".join(f"{k}={v}" for k, v in kinds.items()))
