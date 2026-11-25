#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
科研影像湖仓存储治理平台 —— 端到端业务验证脚本（仅标准库）。
覆盖题目全部硬性场景：
  1. 对象目录与研究A数据量
  2. 血缘反向依赖闭包
  3. 保留策略：10年原始影像、算法包/校准延展、冻结/校验失败传播
  4. 恢复计划：72h预约、4h RTO、缺件（移除校准）阻断、恢复包校验
  5. 成本：GB月费、取回费、恢复计算、共享分摊总额=月账单
  6. 两路线盈亏平衡（年1次 vs 月1次）
  7. 项目退出共享对象保护
  8. 归档快照回放
  9. 共享影像湖仓账单双路线分摊（引用权重 3:1 / Shapley 边际成本；总额 19 元；撤销引用跨周期重算）
用法: python3 verify_e2e.py [base_url]
"""
import json
import sys
import urllib.request
import urllib.error

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080") + "/api"
PASS, FAIL = [], []


def call(method, path, body=None, token=None, allow_fail=False):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        payload = e.read().decode()
        if allow_fail:
            return {"__http_status__": e.code, "__body__": payload}
        raise AssertionError(f"{method} {path} -> {e.code}: {payload}")


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(f"[{'PASS' if cond else 'FAIL'}] {name} {detail}")


def money(x):
    return round(float(x), 2)


# ---------- 登录 ----------
tok = call("POST", "/auth/login", {"username": "admin", "password": "admin123"})["token"]
H = tok
check("登录获取令牌", bool(tok))

# ---------- 1. 对象目录 ----------
objs = {o["uid"]: o for o in call("GET", "/catalog/objects", token=H)}
check("目录对象数=10", len(objs) == 10, f"actual={len(objs)}")
expect_sizes = {
    "RAW-IMG-A01": 2_000_000_000_000, "ANN-A01": 80_000_000_000,
    "CAL-A01": 12_000_000_000, "MASK-A01": 200_000_000_000,
    "FEAT-A01": 5_000_000_000, "RESULT-A01": 1_000_000_000,
}
for uid, size in expect_sizes.items():
    check(f"对象 {uid} 字节数={size/1e9:.0f}GB", objs[uid]["byteSize"] == size)
check("每个对象有 sha256 校验摘要", all(len(o["checksum"]) == 64 for o in objs.values()))

# ---------- 2. 依赖闭包 ----------
closure = call("GET", "/retention/closure/RESULT-A01", token=H)
cuids = {c["uid"] for c in closure}
expect_closure = {"RESULT-A01", "FEAT-A01", "MASK-A01", "RAW-IMG-A01",
                  "ANN-A01", "CAL-A01", "ALGO-PKG-01"}
check("RESULT-A01 完整依赖闭包=7个对象且含算法包/校准", cuids == expect_closure, str(sorted(cuids)))
depth = {c["uid"]: c["depth"] for c in closure}
check("闭包深度：RESULT=0, FEAT=1, MASK=2, RAW=3",
      depth["RESULT-A01"] == 0 and depth["FEAT-A01"] == 1
      and depth["MASK-A01"] == 2 and depth["RAW-IMG-A01"] == 3, str(depth))

# ---------- 3. 保留策略 ----------
call("POST", "/retention/recalculate", token=H)
ov = {r["uid"]: r for r in call("GET", "/retention/overview", token=H)}
check("原始影像保留10年（2036-01-15）",
      str(ov["RAW-IMG-A01"]["baseDeadline"]) == "2036-01-15", str(ov["RAW-IMG-A01"]["baseDeadline"]))
check("标注与原始同期限10年", str(ov["ANN-A01"]["baseDeadline"]) == "2036-01-20")
# 算法包基础5年(2031)，延展到最后依赖结果期限：FEAT 2034 / MASK 2034
check("算法包至少保留至最后依赖结果期限届满（≥2034）",
      str(ov["ALGO-PKG-01"]["effectiveDeadline"]) >= "2034-02-05",
      str(ov["ALGO-PKG-01"]["effectiveDeadline"]))
check("校准证据同样延展（≥2034）",
      str(ov["CAL-A01"]["effectiveDeadline"]) >= "2034-02-01",
      str(ov["CAL-A01"]["effectiveDeadline"]))
check("派生物不早于源对象到期（MASK 截止≥RAW）",
      ov["MASK-A01"]["effectiveDeadline"] is not None
      and ov["MASK-A01"]["effectiveDeadline"] >= ov["RAW-IMG-A01"]["effectiveDeadline"])

# 争议冻结传播
hold = call("POST", "/retention/hold",
            {"uid": "RAW-IMG-A01", "litigationHold": True, "reason": "e2e冻结测试"}, token=H)
check("冻结传播到全部下游依赖结果",
      set(hold["propagatedTo"]) >= {"RAW-IMG-A01", "MASK-A01", "FEAT-A01", "RESULT-A01"},
      str(hold["propagatedTo"]))
ov2 = {r["uid"]: r for r in call("GET", "/retention/overview", token=H)}
check("冻结对象无截止日（无限期保留）", ov2["RAW-IMG-A01"]["disposition"] == "BLOCKED_HOLD")
check("冻结下游结果同样不可销毁", ov2["RESULT-A01"]["disposition"] == "BLOCKED_HOLD")
call("POST", "/retention/hold",
     {"uid": "RAW-IMG-A01", "litigationHold": False, "reason": "解除冻结"}, token=H)

# 校验失败传播
cf = call("POST", "/retention/checksum-failed",
          {"uid": "CAL-A01", "reason": "e2e注入校验失败"}, token=H)
check("校验失败传播到 MASK/FEAT/RESULT",
      set(cf["propagatedTo"]) >= {"CAL-A01", "MASK-A01", "FEAT-A01", "RESULT-A01"},
      str(cf["propagatedTo"]))
call("POST", "/retention/checksum-ok", {"uid": "CAL-A01"}, token=H)

# ---------- 4. 恢复计划（年1次，提前72h，4h RTO） ----------
plan_y = call("POST", "/restore/plans",
              {"targetUid": "RESULT-A01", "strategy": "SCHEDULED_TIERING",
               "frequencyPerYear": 1}, token=H)
check("年1次预约计划可满足", plan_y["status"] == "SATISFIABLE", str(plan_y.get("missingItems")))
check("预约提前72小时", plan_y["leadHours"] == 72)
check("预计恢复时间≤4小时 RTO", plan_y["estimatedHours"] <= 4,
      f"{plan_y['estimatedHours']}h")
check("闭包对象数=7", plan_y["closureCount"] == 7)
per_run = money(plan_y["perRunRetrievalFee"] + plan_y["perRunComputeFee"])
print(f"    单次恢复：取回费={money(plan_y['perRunRetrievalFee'])} "
      f"恢复计算费={money(plan_y['perRunComputeFee'])} 合计={per_run} 元")

# 72h 预取日程
sched = call("GET", "/restore/prefetch-schedule?targetUid=RESULT-A01", token=H)
check("72h窗口内完成全部预取", all(s["within72hLead"] for s in sched),
      f"累计={sched[-1]['windowEndHour']}h")

# ---------- 5. 月账单与分摊（未发生取回，仅存储） ----------
bill1 = call("POST", "/cost/billing/generate", {"billingMonth": "2026-11"}, token=H)
check("分摊总额等于月账单（allocationCheck）", bill1["allocationCheck"] is True,
      f"总额={bill1['grandTotal']}")
bills_a = [b for b in bill1["bills"] if b["projectCode"] == "PROJ-A"][0]
bills_b = [b for b in bill1["bills"] if b["projectCode"] == "PROJ-B"][0]
print(f"    月存储费：PROJ-A={money(bills_a['storageFee'])} PROJ-B={money(bills_b['storageFee'])} "
      f"合计={money(bill1['grandTotal'])} 元/月")
# 手算研究A独占+共享存储费（GB×费率），含算法包 2GB×0.45（A占60%份额）
manual_exclusive = (2000 * 0.120 + 80 * 0.450 + 12 * 0.450 + 5 * 0.120
                    + 1 * 0.450 + 30 * 0.180)
manual_mask_a = 200 * 0.120 * 0.70          # 掩膜A承担70%
manual_algo_a = 2 * 0.450 * 0.60            # 算法包A承担60%
manual_shared = manual_exclusive + manual_mask_a + manual_algo_a
check("PROJ-A 存储费与手工口径一致（含共享份额扣减）",
      abs(money(bills_a["storageFee"]) - money(manual_shared)) < 0.05,
      f"api={money(bills_a['storageFee'])} manual={money(manual_shared)}")
# 共享份额校验：MASK 30% 应计入 PROJ-B
mask_alloc = [a for a in bill1["objectAllocations"] if a["uid"] == "MASK-A01"][0]
share_b = [x for x in mask_alloc["allocations"] if x["projectId"] == 2]
check("共享掩膜按冻结份额 30% 分摊给 PROJ-B",
      bool(share_b) and abs(float(share_b[0]["share"]) - 0.30) < 1e-6)
# 份额之和=1
for a in bill1["objectAllocations"]:
    if len(a["allocations"]) > 1:
        s = sum(float(x["share"]) for x in a["allocations"])
        check(f"共享对象 {a['uid']} 份额之和=1", abs(s - 1.0) < 1e-9)

# ---------- 6. 执行恢复计划，取回费/恢复计算入账 ----------
exe = call("POST", f"/restore/plans/{plan_y['planId']}/execute", token=H)
check("恢复计划执行成功且在RTO内", exe["status"] == "EXECUTED" and exe["completedWithinRto"])
check("恢复对象数=7", exe["restoredCount"] == 7)
bill2 = call("POST", "/cost/billing/generate", {"billingMonth": "2026-11"}, token=H)
b2a = [b for b in bill2["bills"] if b["projectCode"] == "PROJ-A"][0]
check("取回费已计入月账单", money(b2a["retrievalFee"]) > 0, f"{b2a['retrievalFee']}")
check("恢复计算费已计入月账单", money(b2a["restoreComputeFee"]) > 0, f"{b2a['restoreComputeFee']}")
check("含取回后分摊核对仍通过", bill2["allocationCheck"] is True)
annual_storage = money(bill2["grandTotal"]) * 12
print(f"    年存储费（两项目合计口径）≈{annual_storage} 元；"
      f"研究A单次恢复合计={per_run} 元，年1次取回年成本={per_run} 元")

# ---------- 7. 两路线盈亏平衡 ----------
cmp1 = call("GET", "/cost/strategy-comparison?targetUid=RESULT-A01&frequencies=1", token=H)
sc1 = cmp1["scenarios"][0]
check("年1次：按需预取路线更省",
      sc1["cheaperStrategy"] == "SCHEDULED_TIERING",
      f"按需={sc1['scheduledAnnualFee']} 恢复包={sc1['packageAnnualFee']}")
cmp12 = call("GET", "/cost/strategy-comparison?targetUid=RESULT-A01&frequencies=12", token=H)
sc12 = cmp12["scenarios"][0]
check("月1次：恢复包路线更省（额外占用低于重复取回）",
      sc12["cheaperStrategy"] == "RESTORE_PACKAGE",
      f"按需={sc12['scheduledAnnualFee']} 恢复包={sc12['packageAnnualFee']}")
print(f"    盈亏平衡频次≈{cmp1['breakevenFrequencyPerYear']} 次/年；"
      f"恢复包月费={cmp1['packageMonthlyFee']} 元（30GB×0.18）")
be = float(cmp1["breakevenFrequencyPerYear"])
check("盈亏平衡点落在年1次与月1次之间", 1 < be < 12, f"f*={be}")

# ---------- 8. 故意移除校准文件 → 阻断 ----------
call("POST", "/restore/availability", {"uid": "CAL-A01", "available": False}, token=H)
bad = call("POST", "/restore/plans",
           {"targetUid": "RESULT-A01", "strategy": "SCHEDULED_TIERING",
            "frequencyPerYear": 1}, token=H)
check("缺校准件时计划启动前标记 UNSATISFIABLE", bad["status"] == "UNSATISFIABLE")
missing_uids = {m["uid"] for m in bad["missingItems"]}
check("缺件清单列出 CAL-A01", "CAL-A01" in missing_uids, str(missing_uids))
blocked = call("POST", f"/restore/plans/{bad['planId']}/execute", token=H, allow_fail=True)
check("不可满足计划执行被阻断（HTTP 409）", blocked.get("__http_status__") == 409,
      str(blocked.get("__http_status__")))
# 恢复包校验同样失败
pv = call("POST", "/restore/packages/validate", {"packageUid": "PKG-RESULT-A01"}, token=H)
check("恢复包校验失败并指出校准缺件", pv["valid"] is False and
      any(m["uid"] == "CAL-A01" for m in pv["missingItems"]))
# 恢复后重新可满足
call("POST", "/restore/availability", {"uid": "CAL-A01", "available": True}, token=H)
pv2 = call("POST", "/restore/packages/validate", {"packageUid": "PKG-RESULT-A01"}, token=H)
check("校准恢复后恢复包校验通过", pv2["valid"] is True)
good = call("POST", "/restore/plans",
            {"targetUid": "RESULT-A01", "strategy": "RESTORE_PACKAGE",
             "frequencyPerYear": 12}, token=H)
check("恢复包路线月频计划可满足且≤4h",
      good["status"] == "SATISFIABLE" and good["estimatedHours"] <= 4)

# 算法包缺失场景：仅结果文件迁移，哈希通过也不可复现
call("POST", "/restore/availability", {"uid": "ALGO-PKG-01", "available": False}, token=H)
no_algo = call("POST", "/restore/plans",
               {"targetUid": "RESULT-A01", "strategy": "SCHEDULED_TIERING",
                "frequencyPerYear": 1}, token=H)
check("缺算法包时即使哈希通过也判定不可复现",
      any(m["category"] == "SOFTWARE_PACKAGE" for m in no_algo["missingItems"]))
call("POST", "/restore/availability", {"uid": "ALGO-PKG-01", "available": True}, token=H)

# ---------- 9. 项目退出与共享保护 ----------
# 研究A退出，但其共享掩膜/算法包仍被活跃的研究B引用 → 不得整体删除
wd = call("POST", "/projects/PROJ-A/withdraw", {"reason": "e2e项目退出"}, token=H)
protected = {p["uid"] for p in wd["protectedSharedObjects"]}
check("项目A退出后共享掩膜/算法包被研究B引用保护，不得整体删除",
      {"MASK-A01", "ALGO-PKG-01"} <= protected, str(protected))
check("项目A独占对象标记退出（RAW-IMG-A01）", "RAW-IMG-A01" in set(wd["withdrawnObjects"]))
check("研究B对象不受项目A退出影响", "FEAT-B01" not in set(wd["withdrawnObjects"]))
dc = call("GET", "/retention/deletion-check/MASK-A01", token=H)
check("共享掩膜销毁检查=BLOCKED_SHARED_REFERENCE",
      dc["verdict"] == "BLOCKED_SHARED_REFERENCE", dc["verdict"])
call("POST", "/projects/PROJ-A/reactivate", token=H)

# ---------- 10. 归档快照回放 ----------
snap = call("POST", "/snapshots", token=H)
check("快照固定目录水位/依赖/费率版本",
      snap["catalogObjects"] >= 10 and snap["lineageEdges"] >= 9
      and snap["rateVersion"] == "RV-2026Q4", str(snap))
replay = call("GET", f"/snapshots/{snap['snapshotId']}/replay", token=H)
decisions = replay["decisions"] if isinstance(replay["decisions"], list) else []
kinds = {d.get("kind") for d in decisions if isinstance(d, dict)}
check("快照可回放保留/冻结/迁移/退出等治理决定",
      {"RETENTION", "LITIGATION_HOLD", "CHECKSUM_FAILED", "PROJECT_WITHDRAWN"} <= kinds,
      str(kinds))

# ---------- 11. 共享影像湖仓账单双路线分摊 ----------
# 先重置为题设初始状态（A=3 分支、B=1 分支；读取 A 120GB / B 80GB）
call("POST", "/cost-share/scenario/reset", token=H)
share_bill = call("POST", "/cost-share/billing/generate", {"billingMonth": "2026-11"}, token=H)
check("共享账单总额=19 元", money(share_bill["grandTotalFee"]) == 19.00,
      f"grand={money(share_bill['grandTotalFee'])}")
for r in share_bill["reconciliation"]:
    check(f"双路线对账通过（{r['method']}：分摊=总额）",
          r["reconciled"] is True and money(r["allocatedTotal"]) == money(r["grandTotalFee"]),
          str(r))

sb = {(b["method"], b["projectCode"]): b for b in share_bill["bills"]}
# 路线一：有效引用权重 3:1 → O1 A 7.50/B 2.50；项目合计 A 12.90、B 6.10
check("权重路线 O1 按 3:1 分摊：A=7.50", money(sb[("REFERENCE_WEIGHT", "PROJ-A")]["sharedStorageFee"]) == 7.50)
check("权重路线 O1 按 3:1 分摊：B=2.50", money(sb[("REFERENCE_WEIGHT", "PROJ-B")]["sharedStorageFee"]) == 2.50)
check("权重路线项目合计 A=12.90（7.50+3.00+2.40）",
      money(sb[("REFERENCE_WEIGHT", "PROJ-A")]["totalFee"]) == 12.90,
      str(sb[("REFERENCE_WEIGHT", "PROJ-A")]))
check("权重路线项目合计 B=6.10（2.50+2.00+1.60）",
      money(sb[("REFERENCE_WEIGHT", "PROJ-B")]["totalFee"]) == 6.10)
# 路线二：Shapley 边际成本 → O1 各 5.00；项目合计 A 10.40、B 8.60
check("Shapley 路线 O1 各承担 5.00（移除单项目省不下共享基础成本）",
      money(sb[("SHAPLEY", "PROJ-A")]["sharedStorageFee"]) == 5.00
      and money(sb[("SHAPLEY", "PROJ-B")]["sharedStorageFee"]) == 5.00)
check("Shapley 路线项目合计 A=10.40", money(sb[("SHAPLEY", "PROJ-A")]["totalFee"]) == 10.40)
check("Shapley 路线项目合计 B=8.60", money(sb[("SHAPLEY", "PROJ-B")]["totalFee"]) == 8.60)
# 读取费/专属费两路线一致且按项目直接归属
for m in ("REFERENCE_WEIGHT", "SHAPLEY"):
    check(f"{m} 读取费直接归属 A=2.40/B=1.60",
          money(sb[(m, "PROJ-A")]["readFee"]) == 2.40
          and money(sb[(m, "PROJ-B")]["readFee"]) == 1.60)
    check(f"{m} 专属资源账 A=3.00/B=2.00",
          money(sb[(m, "PROJ-A")]["exclusiveStorageFee"]) == 3.00
          and money(sb[(m, "PROJ-B")]["exclusiveStorageFee"]) == 2.00)
# O1 对象级明细：权重 0.75/0.25；Shapley 平均边际成本各 5
o1_alloc = [a for a in share_bill["allocations"] if a["uid"] == "O1"]
o1w = {a["projectCode"]: a for a in o1_alloc if a["method"] == "REFERENCE_WEIGHT"}
o1s = {a["projectCode"]: a for a in o1_alloc if a["method"] == "SHAPLEY"}
check("O1 引用权重 A=0.75/B=0.25",
      abs(float(o1w["PROJ-A"]["weight"]) - 0.75) < 1e-9
      and abs(float(o1w["PROJ-B"]["weight"]) - 0.25) < 1e-9)
check("O1 Shapley 平均边际成本各=5.00",
      money(o1s["PROJ-A"]["marginalCost"]) == 5.00
      and money(o1s["PROJ-B"]["marginalCost"]) == 5.00)
# 字节时长账：3 对象 × 30 天 = 90 行，O1 月费合计精确 10 元
bh = call("GET", "/cost-share/ledger/byte-hours?billingMonth=2026-11", token=H)
check("字节时长账 3 对象×30 天=90 行", len(bh) == 90, f"rows={len(bh)}")
check("O1 字节时长账月存储费合计=10.00",
      money(sum(r["storageCharge"] for r in bh if r["uid"] == "O1")) == 10.00)
excl = call("GET", "/cost-share/ledger/exclusive?billingMonth=2026-11", token=H)
check("专属资源账 DERIV-A=3.00/DERIV-B=2.00",
      {r["uid"]: money(r["storageFee"]) for r in excl} == {"DERIV-A": 3.00, "DERIV-B": 2.00})

# 撤销 B 的最后一个引用：本月快照不变，下一周期 O1 仅属 A
rev = call("POST", "/cost-share/membership/revoke",
           {"objectUid": "O1", "projectCode": "PROJ-B", "effectiveMonth": "2026-12"}, token=H)
check("撤销引用登记为下月生效且本月快照不变",
      rev["effectiveMonth"] == "2026-12" and rev["currentMonthSnapshot"] == "UNCHANGED")
nov = call("POST", "/cost-share/billing/generate", {"billingMonth": "2026-11"}, token=H)
nov_b = {b["projectCode"]: b for b in nov["bills"] if b["method"] == "REFERENCE_WEIGHT"}
check("撤销后 2026-11 账单不变（A=12.90/B=6.10）",
      money(nov_b["PROJ-A"]["totalFee"]) == 12.90 and money(nov_b["PROJ-B"]["totalFee"]) == 6.10)
nov_snap = {s["projectCode"]: s for s in call("GET", "/cost-share/snapshots?billingMonth=2026-11", token=H)}
check("2026-11 成员快照仍含 PROJ-B", nov_snap["PROJ-B"]["member"] is True)
dec = call("POST", "/cost-share/billing/generate", {"billingMonth": "2026-12"}, token=H)
dec_b = {b["projectCode"]: b for b in dec["bills"] if b["method"] == "REFERENCE_WEIGHT"}
check("2026-12 O1 全部 10 元归 A、B 不再分摊共享费",
      money(dec_b["PROJ-A"]["sharedStorageFee"]) == 10.00
      and money(dec_b["PROJ-B"]["sharedStorageFee"]) == 0.00)
check("2026-12 单成员联盟两路线仍对账", all(r["reconciled"] for r in dec["reconciliation"]))
dec_snap = {s["projectCode"]: s for s in call("GET", "/cost-share/snapshots?billingMonth=2026-12", token=H)}
check("2026-12 快照 PROJ-B 已退出联盟", dec_snap["PROJ-B"]["member"] is False)
# 恢复 B 引用 → 仍有其他项目引用时按新成员集重算回 3:1（在未冻结的新账期 2027-01 生效）
call("POST", "/cost-share/membership/add",
     {"objectUid": "O1", "projectCode": "PROJ-B", "referenceCount": 1, "effectiveMonth": "2027-01"}, token=H)
jan = call("POST", "/cost-share/billing/generate", {"billingMonth": "2027-01"}, token=H)
jan_w = {b["projectCode"]: b for b in jan["bills"] if b["method"] == "REFERENCE_WEIGHT"}
check("恢复引用后新账期按新成员集重算：O1 回到 A 7.50/B 2.50",
      money(jan_w["PROJ-A"]["sharedStorageFee"]) == 7.50
      and money(jan_w["PROJ-B"]["sharedStorageFee"]) == 2.50)
# 已冻结的 2026-12 账期不受恢复引用影响（快照不可变）
dec_frozen = call("GET", "/cost-share/billing?billingMonth=2026-12", token=H)
dec_frozen_b = {b["projectCode"]: b for b in dec_frozen if b["method"] == "REFERENCE_WEIGHT"}
check("已出账的 2026-12 快照/账单保持不可变（B 分摊仍为 0）",
      money(dec_frozen_b["PROJ-B"]["sharedStorageFee"]) == 0.00)
# 读取量直接归属：把 A 调整为 100GB → 读取费 2.00，总额变为 18.60
call("POST", "/cost-share/read-usage",
     {"billingMonth": "2026-11", "projectCode": "PROJ-A", "readGb": 100, "note": "e2e 调整读取量"}, token=H)
adj = call("POST", "/cost-share/billing/generate", {"billingMonth": "2026-11"}, token=H)
check("读取量按项目直接归属（A=100GB→2.00，总额 18.60 仍对账）",
      money(adj["grandTotalFee"]) == 18.60 and all(r["reconciled"] for r in adj["reconciliation"]))
# 重置回题设初始状态并复算 19 元
call("POST", "/cost-share/scenario/reset", token=H)
back = call("POST", "/cost-share/billing/generate", {"billingMonth": "2026-11"}, token=H)
check("场景重置后恢复题设 19 元与 A=12.90/B=6.10",
      money(back["grandTotalFee"]) == 19.00
      and money({b["projectCode"]: b for b in back["bills"]
                 if b["method"] == "REFERENCE_WEIGHT"}["PROJ-A"]["totalFee"]) == 12.90)

# ---------- 汇总 ----------
print("\n================ 验证汇总 ================")
print(f"通过 {len(PASS)} 项，失败 {len(FAIL)} 项")
if FAIL:
    print("失败项：")
    for f in FAIL:
        print(" -", f)
    sys.exit(1)
print("全部场景验证通过")
