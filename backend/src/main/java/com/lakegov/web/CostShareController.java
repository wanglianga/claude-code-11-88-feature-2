package com.lakegov.web;

import com.lakegov.service.ShareAllocationService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 共享影像湖仓账单分摊接口（双路线：有效引用权重 / Shapley 边际成本）。
 * 统一挂在 /api/** 下，由 AuthInterceptor 执行 Token 认证与 REVIEWER 只读约束。
 */
@RestController
@RequestMapping("/api/cost-share")
public class CostShareController {

    private final ShareAllocationService share;

    public CostShareController(ShareAllocationService share) {
        this.share = share;
    }

    /** 场景、对象与当前成员意愿 */
    @GetMapping("/scenario")
    public Map<String, Object> scenario() {
        return share.scenarioView();
    }

    /** 重置为题设初始状态（A=3 分支、B=1 分支；读取 120/80GB） */
    @PostMapping("/scenario/reset")
    public Map<String, Object> reset() {
        return share.resetScenario();
    }

    /** 冻结指定账期的对象-项目成员快照 */
    @PostMapping("/snapshots/capture")
    public Map<String, Object> capture(@RequestBody Map<String, String> body) {
        return share.captureSnapshot(body.getOrDefault("billingMonth", "2026-11"));
    }

    @GetMapping("/snapshots")
    public List<Map<String, Object>> snapshots(@RequestParam(required = false) String billingMonth) {
        return share.getSnapshot(billingMonth);
    }

    /** 成员关系变更流水 */
    @GetMapping("/membership/changes")
    public List<Map<String, Object>> changes() {
        return share.membershipChanges();
    }

    /** 撤销项目对共享对象的全部有效引用（本月快照不变，下一周期重算） */
    @PostMapping("/membership/revoke")
    public Map<String, Object> revoke(@RequestBody Map<String, String> body) {
        return share.revokeReference(body.get("objectUid"), body.get("projectCode"),
                body.get("effectiveMonth"));
    }

    /** 新增/恢复有效引用（按新成员集重算） */
    @PostMapping("/membership/add")
    public Map<String, Object> add(@RequestBody Map<String, Object> body) {
        return share.addReference((String) body.get("objectUid"), (String) body.get("projectCode"),
                ((Number) body.getOrDefault("referenceCount", 1)).intValue(),
                (String) body.get("effectiveMonth"));
    }

    /** 录入/覆盖某账期项目读取量（读取服务费按项目直接归属） */
    @PostMapping("/read-usage")
    public Map<String, Object> recordReadUsage(@RequestBody Map<String, Object> body) {
        return share.recordReadUsage((String) body.get("billingMonth"), (String) body.get("projectCode"),
                new BigDecimal(String.valueOf(body.get("readGb"))), (String) body.get("note"));
    }

    @GetMapping("/read-usage")
    public List<Map<String, Object>> readUsage(@RequestParam(required = false) String billingMonth) {
        return share.readUsage(billingMonth);
    }

    /** 生成指定账期双路线账单与对账（幂等重算；成员快照已冻结则保持不变） */
    @PostMapping("/billing/generate")
    public Map<String, Object> generate(@RequestBody Map<String, String> body) {
        return share.generateBilling(body.getOrDefault("billingMonth", "2026-11"));
    }

    /** 项目共享账单（账期 × 项目 × 路线） */
    @GetMapping("/billing")
    public List<Map<String, Object>> billing(@RequestParam(required = false) String billingMonth) {
        return share.monthlyBills(billingMonth);
    }

    /** 对账约束：每路线项目账单合计 = 账单总额 */
    @GetMapping("/reconciliation")
    public List<Map<String, Object>> reconciliation(@RequestParam(required = false) String billingMonth) {
        return share.reconciliations(billingMonth);
    }

    /** 字节时长账（对象 × 天） */
    @GetMapping("/ledger/byte-hours")
    public List<Map<String, Object>> byteHours(@RequestParam(required = false) String billingMonth) {
        return share.byteHourLedger(billingMonth);
    }

    /** 专属资源账（独占派生对象） */
    @GetMapping("/ledger/exclusive")
    public List<Map<String, Object>> exclusive(@RequestParam(required = false) String billingMonth) {
        return share.exclusiveLedger(billingMonth);
    }
}
