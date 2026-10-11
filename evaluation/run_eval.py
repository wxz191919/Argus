#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Argus RAG 检索质量自动评测脚本（阶段 4 基线）。

用法：
  1. 准备环境：python run_eval.py setup    # 注册账号、建群、上传评测文档（幂等，已上传的会跳过）
  2. 执行评测：python run_eval.py eval     # 跑全部问题并输出指标
  3. 一键执行：python run_eval.py all

依赖：仅 Python 3 标准库。
"""
import json
import hashlib
import sys
import time
import urllib.request
import urllib.error
from pathlib import Path

BASE_URL = "http://127.0.0.1:10001"
EVAL_DIR = Path(__file__).resolve().parent
DOCS_DIR = EVAL_DIR / "eval_docs"
RESULT_DIR = EVAL_DIR / "results"

MAIN_USER = {"username": "evaladmin", "email": "evaladmin@argus.local", "password": "Eval@2026", "displayName": "评测管理员"}
SECRET_USER = {"username": "evalhr", "email": "evalhr@argus.local", "password": "Secret@2026", "displayName": "机密群组管理员"}

EVAL_GROUP_NAME = "RAG评测群"
SECRET_GROUP_NAME = "机密资料群（评测）"

EVAL_DOCS = [
    "argus平台概述.md", "mysql迁移规范.md", "etl可靠性设计.md", "检索质量规范.md",
    "权限模型说明.md", "部署运维手册.md", "术语表.md", "产品路线图.md",
]
SECRET_DOCS = ["机密薪酬带宽.md", "机密产品代号.md"]

CHUNK_SIZE = 1048576


# ---------- HTTP 基础 ----------

def http(method, path, token=None, json_body=None, form=None, raw_body=None, content_type=None):
    url = BASE_URL + path
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None
    if json_body is not None:
        data = json.dumps(json_body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    elif form is not None:
        boundary = "----ArgusEvalBoundary7f3a9c2b"
        parts = []
        for name, value in form.get("fields", {}).items():
            parts.append(("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n" % (boundary, name, value)).encode("utf-8"))
        if "file" in form:
            fname, fbytes, ftype = form["file"]
            parts.append(("--%s\r\nContent-Disposition: form-data; name=\"chunk\"; filename=\"%s\"\r\nContent-Type: %s\r\n\r\n" % (boundary, fname, ftype)).encode("utf-8"))
            parts.append(fbytes)
            parts.append(("\r\n--%s--\r\n" % boundary).encode("utf-8"))
        data = b"".join(parts)
        headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
    elif raw_body is not None:
        data = raw_body
        headers["Content-Type"] = content_type or "application/octet-stream"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(body)
        except Exception:
            return e.code, {"raw": body}


def api_ok(payload):
    return payload.get("success") is True


# ---------- 业务操作 ----------

def register(user):
    code, payload = http("POST", "/api/auth/register", json_body=user)
    return code, payload


def login(login_id, password):
    code, payload = http("POST", "/api/auth/login", json_body={"loginId": login_id, "password": password})
    if not api_ok(payload):
        raise RuntimeError("登录失败: %s %s" % (login_id, payload))
    return payload["data"]["accessToken"]


def create_group(token, name, description):
    code, payload = http("POST", "/api/groups", token=token, json_body={"name": name, "description": description})
    if api_ok(payload):
        return payload["data"]
    if "已存在" in str(payload.get("message", "")) or "重名" in str(payload.get("message", "")):
        return find_group(token, name)
    raise RuntimeError("建群失败: %s" % payload)


def find_group(token, name):
    code, payload = http("GET", "/api/groups/my", token=token)
    if api_ok(payload):
        data = payload["data"] or {}
        for g in (data.get("ownedGroups") or []) + (data.get("joinedGroups") or []):
            if g.get("groupName") == name or g.get("name") == name:
                return g.get("groupId") or g.get("id")
    raise RuntimeError("未找到群组: %s, resp=%s" % (name, str(payload)[:200]))


def upload_document(token, group_id, file_path):
    file_path = Path(file_path)
    content = file_path.read_bytes()
    size = len(content)
    file_hash = hashlib.sha256(content).hexdigest()
    chunk_count = max(1, (size + CHUNK_SIZE - 1) // CHUNK_SIZE)

    # 秒传检查：init 可能直接返回 instantUpload
    code, payload = http("POST", "/api/documents/upload/init", token=token, json_body={
        "groupId": group_id, "fileName": file_path.name, "fileSize": size,
        "contentType": "text/markdown", "fileHash": file_hash,
        "chunkSize": CHUNK_SIZE, "chunkCount": chunk_count,
    })
    if not api_ok(payload):
        raise RuntimeError("init 失败 %s: %s" % (file_path.name, payload))
    if payload["data"].get("instantUpload"):
        return {"name": file_path.name, "instant": True}
    upload_id = payload["data"]["uploadId"]

    for idx in range(chunk_count):
        blob = content[idx * CHUNK_SIZE:(idx + 1) * CHUNK_SIZE]
        chunk_hash = hashlib.sha256(blob).hexdigest()
        code, payload = http("POST", "/api/documents/upload/chunks", token=token, form={
            "fields": {"uploadId": upload_id, "chunkIndex": str(idx), "chunkHash": chunk_hash},
            "file": (file_path.name, blob, "text/markdown"),
        })
        if not api_ok(payload):
            raise RuntimeError("分片上传失败 %s#%d: %s" % (file_path.name, idx, payload))

    code, payload = http("POST", "/api/documents/upload/%s/complete" % upload_id, token=token)
    if not api_ok(payload):
        raise RuntimeError("complete 失败 %s: %s" % (file_path.name, payload))
    return {"name": file_path.name, "documentId": payload["data"]}


def wait_all_ready(token, group_id, file_names, timeout_s=180):
    """轮询文档列表直到指定文件全部 READY/FAILED。"""
    deadline = time.time() + timeout_s
    pending = set(file_names)
    while time.time() < deadline and pending:
        time.sleep(6)
        code, payload = http("GET", "/api/documents?groupId=%s&page=1&size=50" % group_id, token=token)
        if not api_ok(payload):
            continue
        data = payload["data"]
        rows = data if isinstance(data, list) else data.get("records", data.get("list", []))
        for row in rows:
            if row.get("fileName") in pending and row.get("status") in ("READY", "FAILED"):
                pending.discard(row["fileName"])
    return pending


# ---------- 环境准备 ----------

def setup():
    print("== 注册账号 ==")
    for user in (MAIN_USER, SECRET_USER):
        code, payload = register(user)
        print("  注册 %s: %s" % (user["username"], "OK" if api_ok(payload) else "已存在/跳过(%s)" % payload.get("message", "")))

    print("== 登录 ==")
    main_token = login(MAIN_USER["username"], MAIN_USER["password"])
    secret_token = login(SECRET_USER["username"], SECRET_USER["password"])
    print("  OK")

    print("== 建群 ==")
    eval_gid = create_group(main_token, EVAL_GROUP_NAME, "RAG 检索质量评测专用群")
    secret_gid = create_group(secret_token, SECRET_GROUP_NAME, "权限隔离评测用机密群")
    print("  评测群 id=%s, 机密群 id=%s" % (eval_gid, secret_gid))

    print("== 上传评测文档（公开群）==")
    names = [upload_document(main_token, eval_gid, DOCS_DIR / n) for n in EVAL_DOCS]
    for n in names:
        print("  %s: %s" % (n["name"], "秒传" if n.get("instant") else "documentId=%s" % n.get("documentId")))

    print("== 上传机密文档（机密群，另一账号）==")
    for n in (upload_document(secret_token, secret_gid, DOCS_DIR / s) for s in SECRET_DOCS):
        print("  %s: %s" % (n["name"], "秒传" if n.get("instant") else "documentId=%s" % n.get("documentId")))

    print("== 等待 ETL 完成 ==")
    remaining = wait_all_ready(main_token, eval_gid, EVAL_DOCS)
    if remaining:
        print("  警告: 未在超时内就绪: %s" % remaining)
    else:
        print("  全部就绪")


# ---------- 评测执行 ----------

def ask(token, group_id, question):
    start = time.time()
    code, payload = http("POST", "/api/qa/ask", token=token, json_body={"groupId": group_id, "question": question})
    latency = time.time() - start
    # /api/qa/ask 直接返回 AskQuestionResponse（无 ApiResponse 包装层）
    if isinstance(payload, dict) and "answered" in payload:
        data = payload
    elif api_ok(payload) and isinstance(payload.get("data"), dict):
        data = payload["data"]
    else:
        return {"answered": False, "reasonCode": "HTTP_%s" % code, "latency": latency, "raw": payload}
    data["latency"] = latency
    return data


def eval_group_names(resp):
    names = set()
    for g in (resp.get("evidenceOverview") or {}).get("groups") or []:
        if g.get("fileName"):
            names.add(g["fileName"])
    for c in resp.get("citations") or []:
        if c.get("fileName"):
            names.add(c["fileName"])
    return names


def contains_all(answer, keywords):
    low = (answer or "").lower()
    return all(str(k).lower() in low for k in keywords)


def run_eval():
    eval_set = json.loads((EVAL_DIR / "rag-eval-set.json").read_text(encoding="utf-8"))
    main_token = login(MAIN_USER["username"], MAIN_USER["password"])
    eval_gid = find_group(main_token, EVAL_GROUP_NAME)

    results = []
    counters = {"fact": {}, "term": {}, "cross": {}, "unanswerable": {}, "permission": {}}
    for q in eval_set["questions"]:
        resp = ask(main_token, eval_gid, q["question"])
        cat = q["category"]
        rec = {"id": q["id"], "category": cat, "question": q["question"], "latency": round(resp.get("latency", 0), 2)}

        retrieved = eval_group_names(resp)
        rec["retrieved"] = sorted(retrieved)
        rec["answered"] = resp.get("answered", False)
        rec["reasonCode"] = resp.get("reasonCode")

        if cat in ("fact", "term", "cross"):
            expected = set(q["expected_docs"])
            hit = bool(expected & retrieved)
            rec["retrieval_hit"] = hit
            # MRR：按 evidenceOverview.groups 返回顺序（服务端已按相关性排序）
            groups = (resp.get("evidenceOverview") or {}).get("groups") or []
            rank = next((i + 1 for i, g in enumerate(groups) if g.get("fileName") in expected), 0)
            rec["mrr_rank"] = rank
            rec["answer_keyword_hit"] = contains_all(resp.get("answer"), q.get("expect_keywords", [])) if rec["answered"] else False
            if not rec["answered"]:
                rec["answer_keyword_hit"] = False
            counters[cat].setdefault("hit", 0)
            counters[cat]["hit"] += 1 if hit else 0
            counters[cat].setdefault("kw", 0)
            counters[cat]["kw"] += 1 if rec["answer_keyword_hit"] else 0
            counters[cat].setdefault("rr", 0.0)
            counters[cat]["rr"] += (1.0 / rank) if rank else 0.0
            counters[cat].setdefault("n", 0)
            counters[cat]["n"] += 1
        elif cat == "unanswerable":
            rec["refusal_correct"] = not rec["answered"]
            counters[cat].setdefault("ok", 0)
            counters[cat]["ok"] += 1 if rec["refusal_correct"] else 0
            counters[cat].setdefault("n", 0)
            counters[cat]["n"] += 1
        elif cat == "permission":
            secret_names = set(q.get("secret_docs", []))
            leaked = bool(secret_names & retrieved)
            rec["leaked"] = leaked
            rec["refusal_correct"] = not rec["answered"]
            counters[cat].setdefault("leak", 0)
            counters[cat]["leak"] += 1 if leaked else 0
            counters[cat].setdefault("n", 0)
            counters[cat]["n"] += 1

        results.append(rec)
        print("[%02d/%02d] %-12s hit=%s kw=%s refused=%s leak=%s %.2fs | %s" % (
            q["id"], len(eval_set["questions"]), cat,
            rec.get("retrieval_hit", "-"), rec.get("answer_keyword_hit", "-"),
            rec.get("refusal_correct", "-"), rec.get("leaked", "-"), rec["latency"], q["question"][:24]))

    # 汇总
    summary = {"timestamp": time.strftime("%Y-%m-%d %H:%M:%S"), "config": {
        "chunking": "target 240 / max 320 / overlap 32",
        "top_k": "vector 10 / keyword 10", "evidence_max": 6, "score_threshold": 0.30,
        "embedding": "text-embedding-v3(512)", "llm": "qwen-plus",
    }, "metrics": {}, "latency": {}}

    all_rr = [1.0 / r["mrr_rank"] for r in results if "mrr_rank" in r and r["mrr_rank"]]
    answerable = [r for r in results if "retrieval_hit" in r]
    summary["metrics"]["retrieval_hit_rate"] = round(sum(1 for r in answerable if r["retrieval_hit"]) / max(1, len(answerable)), 4)
    summary["metrics"]["MRR"] = round(sum(all_rr) / max(1, len(all_rr)), 4)
    summary["metrics"]["answer_keyword_accuracy"] = round(sum(1 for r in answerable if r["answer_keyword_hit"]) / max(1, len(answerable)), 4)
    unans = [r for r in results if "refusal_correct" in r and r["category"] == "unanswerable"]
    summary["metrics"]["refusal_correct_rate"] = round(sum(1 for r in unans if r["refusal_correct"]) / max(1, len(unans)), 4)
    perm = [r for r in results if r["category"] == "permission"]
    summary["metrics"]["permission_leak_rate"] = round(sum(1 for r in perm if r["leaked"]) / max(1, len(perm)), 4)
    summary["metrics"]["permission_refusal_rate"] = round(sum(1 for r in perm if r["refusal_correct"]) / max(1, len(perm)), 4)
    lats = [r["latency"] for r in results if r.get("latency")]
    lats.sort()
    summary["latency"] = {
        "p50": round(lats[len(lats) // 2], 2) if lats else None,
        "p95": round(lats[int(len(lats) * 0.95)], 2) if lats else None,
        "mean": round(sum(lats) / max(1, len(lats)), 2),
    }

    RESULT_DIR.mkdir(exist_ok=True)
    out = RESULT_DIR / ("eval-%s.json" % time.strftime("%Y%m%d-%H%M%S"))
    out.write_text(json.dumps({"summary": summary, "results": results}, ensure_ascii=False, indent=1), encoding="utf-8")

    print("\n===== 基线指标 =====")
    print(json.dumps(summary, ensure_ascii=False, indent=1))
    print("结果已保存: %s" % out)


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "all"
    if mode in ("setup", "all"):
        setup()
    if mode in ("eval", "all"):
        run_eval()


if __name__ == "__main__":
    main()
