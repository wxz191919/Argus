#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Argus 权限越权自动测试（阶段 5）。

针对运行中的服务执行 HTTP 级越权用例，验证「服务端统一校验，不依赖前端隐藏」：
  A. 未认证访问（无 token / 伪造 token）→ 401
  B. 跨群组越权（读取/问答/预览/下载/删除他人群组资源）→ 403 或数据不可见
  C. 资源伪造（不存在的群组/文档 ID）→ 4xx，不泄漏信息
  D. 角色越权（普通成员执行管理员操作）→ 403
  E. 检索与答案隔离（越权问答不得泄漏跨群内容）

用法：python test_authz.py
前置：后端运行中；评测账号已由 run_eval.py setup 创建。
"""
import json
import sys
import urllib.request
import urllib.error
from pathlib import Path

BASE_URL = "http://127.0.0.1:10001"

EVAL_ADMIN = {"loginId": "evaladmin", "password": "Eval@2026"}
SECRET_OWNER = {"loginId": "evalhr", "password": "Secret@2026"}
PLAIN_MEMBER = {"loginId": "tester01", "password": "Test@2026"}

EVAL_GROUP_NAME = "RAG评测群"
SECRET_GROUP_NAME = "机密资料群（评测）"
SECRET_DOC_NAMES = ["机密薪酬带宽.md", "机密产品代号.md"]


def http(method, path, token=None, json_body=None):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None
    if json_body is not None:
        data = json.dumps(json_body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    req = urllib.request.Request(BASE_URL + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")


def login(login_id, password):
    code, body = http("POST", "/api/auth/login", json_body={"loginId": login_id, "password": password})
    d = json.loads(body)
    if code != 200 or not d.get("success"):
        raise RuntimeError("登录失败 %s: %s" % (login_id, body[:120]))
    return d["data"]["accessToken"]


def find_group(token, name):
    code, body = http("GET", "/api/groups/my", token=token)
    d = json.loads(body)
    for g in (d["data"].get("ownedGroups") or []) + (d["data"].get("joinedGroups") or []):
        if g.get("groupName") == name:
            return g["groupId"]
    raise RuntimeError("群组不存在: " + name)


RESULTS = []


def record(case, expect, actual_ok, detail=""):
    status = "PASS" if actual_ok else "FAIL"
    RESULTS.append({"case": case, "expect": expect, "result": status, "detail": detail})
    print("[%s] %-38s 期望:%s %s" % (status, case, expect, detail))


def deny(code, body):
    """判定响应是否为明确的拒绝（401/403 或业务 false）。"""
    if code in (401, 403):
        return True, "HTTP %d" % code
    try:
        d = json.loads(body)
        if d.get("success") is False:
            return True, "success=false(%s)" % d.get("message", "")[:40]
    except Exception:
        pass
    return False, "HTTP %d body=%s" % (code, body[:80])


def main():
    admin = login(EVAL_ADMIN["loginId"], EVAL_ADMIN["password"])
    hr = login(SECRET_OWNER["loginId"], SECRET_OWNER["password"])
    member = login(PLAIN_MEMBER["loginId"], PLAIN_MEMBER["password"])
    eval_gid = find_group(admin, EVAL_GROUP_NAME)
    secret_gid = find_group(hr, SECRET_GROUP_NAME)

    fake_token = admin[:-6] + "abcdef"

    print("===== A. 未认证访问 =====")
    for path, method, label in [
        ("/api/groups/my", "GET", "群组列表"),
        ("/api/documents?groupId=%d" % eval_gid, "GET", "文档列表"),
        ("/api/qa/ask", "POST", "知识问答"),
        ("/api/documents/4/download?groupId=%d" % eval_gid, "GET", "文档下载"),
    ]:
        code, body = http(method, path, json_body={"groupId": eval_gid, "question": "测试"} if method == "POST" else None)
        ok, detail = deny(code, body)
        record("未登录访问-[%s]" % label, "401", ok, detail)

    print("===== A2. 伪造 token =====")
    code, body = http("GET", "/api/groups/my", token=fake_token)
    ok, detail = deny(code, body)
    record("伪造 JWT 访问群组列表", "401", ok, detail)

    print("===== B. 跨群组越权（evaladmin 无权访问机密群 %d）=====" % secret_gid)
    code, body = http("GET", "/api/documents?groupId=%d" % secret_gid, token=admin)
    leaked = any(s in body for s in SECRET_DOC_NAMES)
    ok, detail = deny(code, body)
    ok = ok and not leaked
    record("跨群读取文档列表", "403/不可见", ok, detail + ("[泄漏!]" if leaked else ""))

    code, body = http("POST", "/api/qa/ask", token=admin, json_body={"groupId": secret_gid, "question": "高级工程师月薪范围是多少？"})
    leaked = any(s in body for s in ["25K", "35K", "薪酬"])
    ok, detail = deny(code, body)
    ok = ok and not leaked
    record("跨群问答（问机密薪酬）", "403/拒绝", ok, detail + ("[泄漏!]" if leaked else ""))

    code, body = http("GET", "/api/documents/12/preview?groupId=%d" % secret_gid, token=admin)
    ok, detail = deny(code, body)
    record("跨群预览机密文档(id=12)", "403", ok, detail)

    code, body = http("GET", "/api/documents/12/download?groupId=%d" % secret_gid, token=admin)
    ok, detail = deny(code, body)
    record("跨群下载机密文档(id=12)", "403", ok, detail)

    code, body = http("DELETE", "/api/documents/12?groupId=%d" % secret_gid, token=admin)
    ok, detail = deny(code, body)
    record("跨群删除机密文档(id=12)", "403", ok, detail)

    code, body = http("GET", "/api/groups/%d/members" % secret_gid, token=admin)
    ok, detail = deny(code, body)
    record("跨群读取成员列表", "403", ok, detail)

    print("===== B2. 普通成员越权（tester01 不属于评测群 %d）=====" % eval_gid)
    code, body = http("GET", "/api/documents?groupId=%d" % eval_gid, token=member)
    ok, detail = deny(code, body)
    record("非成员读取文档列表", "403/不可见", ok, detail)

    code, body = http("POST", "/api/qa/ask", token=member, json_body={"groupId": eval_gid, "question": "Argus 版本号是多少？"})
    ok, detail = deny(code, body)
    record("非成员发起问答", "403/拒绝", ok, detail)

    code, body = http("DELETE", "/api/documents/4?groupId=%d" % eval_gid, token=member)
    ok, detail = deny(code, body)
    record("非成员删除他人群组文档", "403", ok, detail)

    print("===== C. 资源伪造 =====")
    code, body = http("GET", "/api/documents/99999/preview?groupId=%d" % eval_gid, token=admin)
    ok, detail = deny(code, body)
    record("伪造文档 ID 预览", "4xx", ok, detail)

    code, body = http("DELETE", "/api/documents/4?groupId=99999", token=admin)
    ok, detail = deny(code, body)
    record("伪造群组 ID 删除", "4xx", ok, detail)

    code, body = http("POST", "/api/qa/ask", token=admin, json_body={"groupId": 99999, "question": "测试"})
    ok, detail = deny(code, body)
    record("伪造群组 ID 问答", "4xx", ok, detail)

    print("===== D. 角色越权（成员账号执行管理员操作）=====")
    # tester01 是群 1（回归测试群）的 owner，对群 3 无角色；用 hr 账号尝试管理评测群
    code, body = http("POST", "/api/groups/%d/invitations" % eval_gid, token=hr, json_body={"inviteeUserId": 1})
    ok, detail = deny(code, body)
    record("外人向评测群发邀请", "403", ok, detail)

    print("===== E. 汇总 =====")
    passed = sum(1 for r in RESULTS if r["result"] == "PASS")
    print("通过 %d / %d" % (passed, len(RESULTS)))
    fails = [r for r in RESULTS if r["result"] == "FAIL"]
    if fails:
        print("失败用例：")
        for r in fails:
            print("  -", r["case"], r["detail"])

    out = Path(__file__).parent / ("authz-report-%s.json" % __import__("time").strftime("%Y%m%d-%H%M%S"))
    out.write_text(json.dumps(RESULTS, ensure_ascii=False, indent=1), encoding="utf-8")
    print("报告已保存:", out)
    sys.exit(0 if not fails else 1)


if __name__ == "__main__":
    main()
