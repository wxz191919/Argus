#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Argus 性能压测脚本（阶段 6 基线）。

场景（均排除 LLM 生成类接口，聚焦 DB/检索基础设施路径）：
  S1 登录接口（BCrypt + DB 读写）
  S2 群组列表 /api/groups/my（成员联表查询）
  S3 文档列表 /api/documents?groupId=N（权限过滤联表查询）
  S4 健康检查 /actuator/health（无 DB 路径，作为网络基线）

输出：每场景 P50/P95/P99 延迟、RPS、错误率。

用法：python load_test.py [并发数] [每场景请求数]
默认：并发 10，每场景 200 请求。
"""
import json
import statistics
import sys
import threading
import time
import urllib.request
import urllib.error
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor

BASE_URL = "http://127.0.0.1:10001"
LOGIN = {"loginId": "evaladmin", "password": "Eval@2026"}


def http(method, path, token=None, json_body=None):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None
    if json_body is not None:
        data = json.dumps(json_body).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    req = urllib.request.Request(BASE_URL + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except Exception:
        return 0, b""


def login():
    code, body = http("POST", "/api/auth/login", json_body=LOGIN)
    d = json.loads(body)
    if code != 200 or not d.get("success"):
        raise RuntimeError("登录失败: %s" % body[:100])
    return d["data"]["accessToken"]


def run_scenario(name, fn, concurrency, total):
    latencies = []
    errors = []
    lock = threading.Lock()
    per_thread = total // concurrency

    def worker(_):
        local = []
        local_err = 0
        for _ in range(per_thread):
            t0 = time.time()
            try:
                code, _ = fn()
                if code >= 400 or code == 0:
                    local_err += 1
            except Exception:
                local_err += 1
                code = 0
            local.append((time.time() - t0) * 1000)
        with lock:
            latencies.extend(local)
            errors.append(local_err)

    start = time.time()
    with ThreadPoolExecutor(concurrency) as pool:
        list(pool.map(worker, range(concurrency)))
    wall = time.time() - start

    latencies.sort()
    n = len(latencies)
    r = {
        "scenario": name,
        "concurrency": concurrency,
        "requests": n,
        "error_count": sum(errors),
        "error_rate": round(sum(errors) / max(1, n), 4),
        "rps": round(n / wall, 1),
        "p50_ms": round(latencies[n // 2], 1),
        "p95_ms": round(latencies[int(n * 0.95)], 1),
        "p99_ms": round(latencies[min(n - 1, int(n * 0.99))], 1),
        "mean_ms": round(statistics.mean(latencies), 1),
        "wall_s": round(wall, 2),
    }
    print("%-14s C=%-3d RPS=%-7s P50=%-7s P95=%-7s P99=%-7s err=%d" % (
        r["scenario"], r["concurrency"], r["rps"], r["p50_ms"], r["p95_ms"], r["p99_ms"], r["error_count"]))
    return r


def main():
    concurrency = int(sys.argv[1]) if len(sys.argv) > 1 else 10
    total = int(sys.argv[2]) if len(sys.argv) > 2 else 200
    token = login()

    # 预热（JIT/连接池）
    for _ in range(10):
        http("GET", "/api/groups/my", token=token)

    results = []
    results.append(run_scenario("S1-登录", lambda: http("POST", "/api/auth/login", json_body=LOGIN), concurrency, total))
    results.append(run_scenario("S2-群组列表", lambda: http("GET", "/api/groups/my", token=token), concurrency, total))
    results.append(run_scenario("S3-文档列表", lambda: http("GET", "/api/documents?groupId=3&page=1&size=20", token=token), concurrency, total))
    results.append(run_scenario("S4-健康检查", lambda: http("GET", "/actuator/health"), concurrency, total))

    import io
    out = io.open(__file__.replace("load_test.py", "") + "../docs/load-test-result.json", "w", encoding="utf-8")
    out.write(json.dumps({"timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
                          "env": "local单机(4C/ES/PG/MySQL同机)", "results": results}, ensure_ascii=False, indent=1))
    out.close()
    print("已保存 docs/load-test-result.json")


if __name__ == "__main__":
    main()
