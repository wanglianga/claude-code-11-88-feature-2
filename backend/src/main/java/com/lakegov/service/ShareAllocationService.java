package com.lakegov.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 共享影像湖仓账单分摊服务（按月账期）。
 *
 * 台账组成：
 * - 成员快照 membership_snapshot：账期冻结的对象-项目有效引用（研究分支数）；
 * - 字节时长账 byte_hour_ledger：对象×天 byte_hours=bytes×24 与当日存储费；
 * - 读取量账 read_usage_ledger：项目读取 GB 与读取服务费（按项目直接归属，不分摊）；
 * - 专属资源账 exclusive_resource_ledger：项目独占派生对象存储费（直接归属）；
 * - 分摊明细 share_allocation + 项目账单 share_monthly_bill + 对账 share_reconciliation。
 *
 * 双路线：
 * - REFERENCE_WEIGHT：按有效引用分支权重分摊共享对象存储费（简单可复算，多分支项目承担较多）；
 * - SHAPLEY：项目联盟边际成本 Shapley 值。共享对象只要联盟中还有任一成员引用，
 *   移除单个项目都不能节省该费用，故各成员平均承担（反映不可避免的共享基础成本，求值随 n! 增长）。
 *
 * 周期规则：撤销末次引用只写入变更流水并调整下一周期意愿，本月已冻结快照与账单不变；
 * 下一周期按新成员集重算；若仍有其他项目引用，则在新成员集之上重新分摊。
 *
 * 计量口径：1 GB = 10^9 字节，月按 30 天。
 */
@Service
public class ShareAllocationService {

    public static final String DEFAULT_SCENARIO = "SCEN-IMGLAKE";
    public static final BigDecimal BYTES_PER_GB = new BigDecimal("1000000000");
    private static final int SCALE = 6;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ShareAllocationService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ==================== 场景与对象 ====================

    public Map<String, Object> scenarioView() {
        long scenarioId = scenarioId();
        Map<String, Object> scenario = jdbc.queryForMap(
                "SELECT id, code, name, storage_rate AS \"storageRate\", " +
                "read_gb_rate AS \"readGbRate\", rate_version AS \"rateVersion\", days_per_month AS \"daysPerMonth\" " +
                "FROM share_scenario WHERE id = ?", scenarioId);
        List<Map<String, Object>> objects = jdbc.queryForList("""
                SELECT so.id, so.object_uid AS uid, so.name, so.byte_size AS "byteSize",
                       so.object_kind AS "objectKind", p.code AS "ownerProjectCode",
                       (SELECT count(*) FROM membership_intent mi
                         WHERE mi.object_id = so.id AND mi.active_reference_count > 0) AS "activeMemberCount"
                FROM share_object so
                LEFT JOIN project p ON p.id = so.owning_project_id
                WHERE so.scenario_id = ? ORDER BY so.id
                """, scenarioId);
        List<Map<String, Object>> intents = jdbc.queryForList("""
                SELECT so.object_uid AS uid, p.code AS "projectCode",
                       mi.active_reference_count AS "referenceCount", mi.effective_from AS "effectiveFrom", mi.note
                FROM membership_intent mi
                JOIN share_object so ON so.id = mi.object_id
                JOIN project p ON p.id = mi.project_id
                WHERE mi.scenario_id = ?
                ORDER BY so.object_uid, p.code
                """, scenarioId);
        return Map.of("scenario", scenario, "objects", objects, "membershipIntents", intents);
    }

    // ==================== 成员快照 ====================

    /**
     * 冻结指定账期的对象-项目成员快照（幂等：已冻结行不覆盖，保证出账后不可变）。
     * 成员口径：effective_from <= 账期 的意愿记录；reference_count>0 即为本账期联盟成员。
     */
    @Transactional
    public Map<String, Object> captureSnapshot(String billingMonth) {
        long scenarioId = scenarioId();
        List<Map<String, Object>> sharedObjects = jdbc.queryForList(
                "SELECT id, object_uid AS uid FROM share_object WHERE scenario_id = ? AND object_kind = 'SHARED'",
                scenarioId);
        int inserted = 0;
        for (Map<String, Object> o : sharedObjects) {
            long objectId = ((Number) o.get("id")).longValue();
            List<Map<String, Object>> intents = jdbc.queryForList("""
                    SELECT project_id AS pid, active_reference_count AS cnt
                    FROM membership_intent
                    WHERE scenario_id = ? AND object_id = ? AND effective_from <= ?
                    """, scenarioId, objectId, billingMonth);
            for (Map<String, Object> mi : intents) {
                long pid = ((Number) mi.get("pid")).longValue();
                int count = ((Number) mi.get("cnt")).intValue();
                int n = jdbc.update("""
                        INSERT INTO membership_snapshot(scenario_id, billing_month, object_id, project_id,
                                                        reference_count, member)
                        VALUES (?,?,?,?,?,?)
                        ON CONFLICT (scenario_id, billing_month, object_id, project_id) DO NOTHING
                        """, scenarioId, billingMonth, objectId, pid, count, count > 0);
                inserted += n;
            }
        }
        return Map.of("billingMonth", billingMonth, "snapshotRowsTouched", inserted,
                "snapshot", getSnapshot(billingMonth));
    }

    public List<Map<String, Object>> getSnapshot(String billingMonth) {
        return jdbc.queryForList("""
                SELECT so.object_uid AS uid, so.name AS "objectName", p.code AS "projectCode",
                       s.reference_count AS "referenceCount", s.member, s.captured_at AS "capturedAt"
                FROM membership_snapshot s
                JOIN share_object so ON so.id = s.object_id
                JOIN project p ON p.id = s.project_id
                WHERE s.scenario_id = ? AND s.billing_month = ?
                ORDER BY so.object_uid, p.code
                """, scenarioId(), billingMonth);
    }

    /**
     * 撤销项目对共享对象的最后一个（全部）有效引用。
     * 本月已冻结快照不变；意愿自 effectiveMonth 起置为 0，并登记变更流水。
     */
    @Transactional
    public Map<String, Object> revokeReference(String objectUid, String projectCode, String effectiveMonth) {
        long scenarioId = scenarioId();
        long objectId = requireObject(objectUid);
        long projectId = requireProject(projectCode);
        String eff = effectiveMonth != null ? effectiveMonth : defaultNextMonth();
        int updated = jdbc.update("""
                UPDATE membership_intent SET active_reference_count = 0, effective_from = ?,
                       note = '引用已撤销（自 ' || ? || ' 起退出联盟）'
                WHERE scenario_id = ? AND object_id = ? AND project_id = ?
                """, eff, eff, scenarioId, objectId, projectId);
        if (updated == 0) {
            throw new IllegalArgumentException("未找到成员意愿记录: " + objectUid + " / " + projectCode);
        }
        jdbc.update("""
                INSERT INTO membership_change(scenario_id, object_id, project_id, change_type,
                                              effective_month, detail)
                VALUES (?,?,?, 'REFERENCE_REVOKED', ?, ?)
                """, scenarioId, objectId, projectId, eff,
                "撤销 " + projectCode + " 对 " + objectUid + " 的全部有效引用；本月快照不变，自 " + eff + " 起重算");
        return Map.of("objectUid", objectUid, "projectCode", projectCode,
                "changeType", "REFERENCE_REVOKED", "effectiveMonth", eff,
                "currentMonthSnapshot", "UNCHANGED", "nextMonthMembership", "EXCLUDED");
    }

    /** 新增/恢复有效引用（演示“仍有其他项目引用，按新成员集重算”）。 */
    @Transactional
    public Map<String, Object> addReference(String objectUid, String projectCode, int referenceCount,
                                            String effectiveMonth) {
        if (referenceCount <= 0) {
            throw new IllegalArgumentException("有效引用数必须大于 0");
        }
        long scenarioId = scenarioId();
        long objectId = requireObject(objectUid);
        long projectId = requireProject(projectCode);
        String eff = effectiveMonth != null ? effectiveMonth : defaultNextMonth();
        jdbc.update("""
                INSERT INTO membership_intent(scenario_id, object_id, project_id,
                                              active_reference_count, effective_from, note)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT (scenario_id, object_id, project_id)
                DO UPDATE SET active_reference_count = EXCLUDED.active_reference_count,
                              effective_from = EXCLUDED.effective_from,
                              note = EXCLUDED.note
                """, scenarioId, objectId, projectId, referenceCount, eff,
                "登记/恢复 " + referenceCount + " 个有效引用，自 " + eff + " 起生效");
        jdbc.update("""
                INSERT INTO membership_change(scenario_id, object_id, project_id, change_type,
                                              effective_month, detail)
                VALUES (?,?,?, 'REFERENCE_ADDED', ?, ?)
                """, scenarioId, objectId, projectId, eff,
                projectCode + " 恢复对 " + objectUid + " 的引用（" + referenceCount + " 个分支）");
        return Map.of("objectUid", objectUid, "projectCode", projectCode,
                "changeType", "REFERENCE_ADDED", "referenceCount", referenceCount,
                "effectiveMonth", eff);
    }

    public List<Map<String, Object>> membershipChanges() {
        return jdbc.queryForList("""
                SELECT so.object_uid AS uid, p.code AS "projectCode", c.change_type AS "changeType",
                       c.effective_month AS "effectiveMonth", c.detail, c.requested_at AS "requestedAt"
                FROM membership_change c
                JOIN share_object so ON so.id = c.object_id
                JOIN project p ON p.id = c.project_id
                WHERE c.scenario_id = ? ORDER BY c.id
                """, scenarioId());
    }

    // ==================== 读取量账 ====================

    @Transactional
    public Map<String, Object> recordReadUsage(String billingMonth, String projectCode,
                                               BigDecimal readGb, String note) {
        if (readGb == null || readGb.signum() < 0) {
            throw new IllegalArgumentException("读取 GB 必须为非负数");
        }
        long scenarioId = scenarioId();
        long projectId = requireProject(projectCode);
        BigDecimal rate = jdbc.queryForObject(
                "SELECT read_gb_rate FROM share_scenario WHERE id = ?", BigDecimal.class, scenarioId);
        BigDecimal fee = readGb.multiply(rate).setScale(SCALE, RoundingMode.HALF_UP);
        jdbc.update("""
                INSERT INTO read_usage_ledger(scenario_id, billing_month, project_id, read_gb,
                                              read_gb_rate, read_fee, note)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (scenario_id, billing_month, project_id) DO UPDATE SET
                    read_gb = EXCLUDED.read_gb, read_gb_rate = EXCLUDED.read_gb_rate,
                    read_fee = EXCLUDED.read_fee, note = EXCLUDED.note
                """, scenarioId, billingMonth, projectId, readGb, rate, fee,
                note == null ? "" : note);
        return Map.of("billingMonth", billingMonth, "projectCode", projectCode,
                "readGb", readGb, "readGbRate", rate, "readFee", fee);
    }

    public List<Map<String, Object>> readUsage(String billingMonth) {
        return jdbc.queryForList("""
                SELECT p.code AS "projectCode", r.billing_month AS "billingMonth",
                       r.read_gb AS "readGb", r.read_gb_rate AS "readGbRate",
                       r.read_fee AS "readFee", r.note
                FROM read_usage_ledger r JOIN project p ON p.id = r.project_id
                WHERE r.scenario_id = ? AND (? IS NULL OR r.billing_month = ?)
                ORDER BY r.billing_month, p.code
                """, scenarioId(), billingMonth, billingMonth);
    }

    // ==================== 月度出账（双路线） ====================

    @Transactional
    public Map<String, Object> generateBilling(String billingMonth) {
        long scenarioId = scenarioId();
        YearMonth ym = YearMonth.parse(billingMonth);
        int days = ym.lengthOfMonth();
        Map<String, Object> scenario = jdbc.queryForMap(
                "SELECT storage_rate AS storageRate, read_gb_rate AS readGbRate, " +
                "rate_version AS rateVersion FROM share_scenario WHERE id = ?", scenarioId);
        BigDecimal storageRate = (BigDecimal) scenario.get("storageRate");

        // 1) 若尚未冻结则先冻结成员快照（已冻结行保持不变）
        captureSnapshot(billingMonth);

        // 2) 幂等清理本账期产出（快照不在清理范围）
        for (String table : List.of("byte_hour_ledger", "exclusive_resource_ledger",
                "share_allocation", "share_monthly_bill", "share_reconciliation")) {
            jdbc.update("DELETE FROM " + table + " WHERE scenario_id = ? AND billing_month = ?",
                    scenarioId, billingMonth);
        }

        List<Map<String, Object>> objects = jdbc.queryForList("""
                SELECT so.id, so.object_uid AS uid, so.name, so.byte_size AS bytes,
                       so.object_kind AS kind, p.code AS "ownerProjectCode"
                FROM share_object so
                LEFT JOIN project p ON p.id = so.owning_project_id
                WHERE so.scenario_id = ? ORDER BY so.id
                """, scenarioId);

        List<Map<String, Object>> objectViews = new ArrayList<>();
        // project -> method -> 共享存储费累计
        Map<Long, Map<String, BigDecimal>> sharedAgg = new LinkedHashMap<>();
        List<Map<String, Object>> allocationViews = new ArrayList<>();
        BigDecimal totalStorageFee = BigDecimal.ZERO;

        for (Map<String, Object> o : objects) {
            long objectId = ((Number) o.get("id")).longValue();
            BigDecimal bytes = new BigDecimal(o.get("bytes").toString());
            BigDecimal gb = bytes.divide(BYTES_PER_GB, SCALE, RoundingMode.HALF_UP);
            BigDecimal monthlyStorage = gb.multiply(storageRate).setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal dailyStorage = monthlyStorage.divide(BigDecimal.valueOf(30), SCALE, RoundingMode.HALF_UP);
            totalStorageFee = totalStorageFee.add(monthlyStorage);

            // 字节时长账（对象 × 天）；末日补差，保证日费合计精确等于月存储费
            BigDecimal priorDailySum = BigDecimal.ZERO;
            for (int d = 1; d <= days; d++) {
                LocalDate day = ym.atDay(d);
                BigDecimal byteHours = bytes.multiply(BigDecimal.valueOf(24));
                BigDecimal dayCharge = d == days
                        ? monthlyStorage.subtract(priorDailySum)
                        : dailyStorage;
                if (d < days) {
                    priorDailySum = priorDailySum.add(dailyStorage);
                }
                jdbc.update("""
                        INSERT INTO byte_hour_ledger(scenario_id, billing_month, object_id, day,
                                                     byte_hours, gb_month_rate, storage_charge)
                        VALUES (?,?,?,?,?,?,?)
                        ON CONFLICT (scenario_id, object_id, day) DO UPDATE SET
                            billing_month = EXCLUDED.billing_month,
                            byte_hours = EXCLUDED.byte_hours,
                            gb_month_rate = EXCLUDED.gb_month_rate,
                            storage_charge = EXCLUDED.storage_charge
                        """, scenarioId, billingMonth, objectId, java.sql.Date.valueOf(day),
                        byteHours, storageRate, dayCharge);
            }

            List<Map<String, Object>> memberViews = new ArrayList<>();
            if ("EXCLUSIVE".equals(o.get("kind"))) {
                // 专属资源账：直接归属所属项目
                long ownerId = jdbc.queryForObject(
                        "SELECT owning_project_id FROM share_object WHERE id = ?", Long.class, objectId);
                jdbc.update("""
                        INSERT INTO exclusive_resource_ledger(scenario_id, billing_month, object_id,
                                                              project_id, byte_size, gb_month_rate, storage_fee)
                        VALUES (?,?,?,?,?,?,?)
                        """, scenarioId, billingMonth, objectId, ownerId, o.get("bytes"),
                        storageRate, monthlyStorage);
                memberViews.add(Map.of("projectCode", o.get("ownerProjectCode"),
                        "referenceCount", 0, "member", true, "basis", "EXCLUSIVE"));
            } else {
                // 共享对象：取本账期冻结成员
                List<Map<String, Object>> members = jdbc.queryForList("""
                        SELECT project_id AS pid, reference_count AS cnt, member
                        FROM membership_snapshot
                        WHERE scenario_id = ? AND billing_month = ? AND object_id = ? AND member = true
                        ORDER BY project_id
                        """, scenarioId, billingMonth, objectId);
                if (members.isEmpty()) {
                    throw new IllegalStateException(
                            "共享对象 " + o.get("uid") + " 在 " + billingMonth + " 无任何联盟成员，无法出账");
                }
                Map<String, List<Map<String, Object>>> perMethod =
                        computeSharedAllocations(objectId, o.get("uid").toString(), members,
                                monthlyStorage, billingMonth, scenarioId);
                for (Map.Entry<String, List<Map<String, Object>>> e : perMethod.entrySet()) {
                    allocationViews.addAll(e.getValue());
                    for (Map<String, Object> a : e.getValue()) {
                        long pid = ((Number) a.get("projectId")).longValue();
                        sharedAgg.computeIfAbsent(pid, k -> new LinkedHashMap<>())
                                .merge(e.getKey(), (BigDecimal) a.get("allocatedFee"), BigDecimal::add);
                    }
                }
                for (Map<String, Object> m : members) {
                    memberViews.add(Map.of("projectCode", projectCode(((Number) m.get("pid")).longValue()),
                            "referenceCount", m.get("cnt"), "member", true, "basis", "SHARED_MEMBER"));
                }
            }

            Map<String, Object> view = new LinkedHashMap<>();
            view.put("uid", o.get("uid"));
            view.put("name", o.get("name"));
            view.put("objectKind", o.get("kind"));
            view.put("byteSize", o.get("bytes"));
            view.put("gb", gb);
            view.put("monthlyStorageFee", monthlyStorage);
            view.put("ownerProjectCode", o.get("ownerProjectCode"));
            view.put("members", memberViews);
            objectViews.add(view);
        }

        // 3) 读取量账（按项目直接归属）
        List<Map<String, Object>> readRows = jdbc.queryForList("""
                SELECT project_id AS pid, read_gb AS "readGb", read_gb_rate AS "readGbRate",
                       read_fee AS "readFee", note
                FROM read_usage_ledger
                WHERE scenario_id = ? AND billing_month = ?
                """, scenarioId, billingMonth);
        Map<Long, BigDecimal> readFeeByProject = new LinkedHashMap<>();
        for (Map<String, Object> r : readRows) {
            readFeeByProject.put(((Number) r.get("pid")).longValue(), (BigDecimal) r.get("readFee"));
        }
        BigDecimal totalReadFee = readRows.stream()
                .map(r -> (BigDecimal) r.get("readFee"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 4) 专属费按项目汇总
        Map<Long, BigDecimal> exclusiveByProject = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT project_id AS pid, SUM(storage_fee) AS fee
                FROM exclusive_resource_ledger
                WHERE scenario_id = ? AND billing_month = ?
                GROUP BY project_id
                """, scenarioId, billingMonth)
                .forEach(r -> exclusiveByProject.put(((Number) r.get("pid")).longValue(),
                        ((BigDecimal) r.get("fee"))));

        // 5) 项目集合 = 共享成员 ∪ 专属归属 ∪ 读取量归属
        java.util.TreeSet<Long> projectIdSet = new java.util.TreeSet<>();
        projectIdSet.addAll(sharedAgg.keySet());
        projectIdSet.addAll(exclusiveByProject.keySet());
        projectIdSet.addAll(readFeeByProject.keySet());
        List<Long> projectIds = new ArrayList<>(projectIdSet);

        List<Map<String, Object>> billViews = new ArrayList<>();
        Map<String, BigDecimal> allocatedByMethod = new LinkedHashMap<>();
        for (String method : List.of("REFERENCE_WEIGHT", "SHAPLEY")) {
            allocatedByMethod.put(method, BigDecimal.ZERO);
            for (Long pid : projectIds) {
                BigDecimal sharedFee = sharedAgg.getOrDefault(pid, Map.of())
                        .getOrDefault(method, BigDecimal.ZERO);
                BigDecimal exclusiveFee = exclusiveByProject.getOrDefault(pid, BigDecimal.ZERO);
                BigDecimal readFee = readFeeByProject.getOrDefault(pid, BigDecimal.ZERO);
                BigDecimal total = sharedFee.add(exclusiveFee).add(readFee)
                        .setScale(SCALE, RoundingMode.HALF_UP);
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("projectCode", projectCode(pid));
                detail.put("billingMonth", billingMonth);
                detail.put("method", method);
                detail.put("sharedStorageFee", sharedFee);
                detail.put("exclusiveStorageFee", exclusiveFee);
                detail.put("readFee", readFee);
                detail.put("totalFee", total);
                jdbc.update("""
                        INSERT INTO share_monthly_bill(scenario_id, billing_month, project_id, method,
                                                       shared_storage_fee, exclusive_storage_fee,
                                                       read_fee, total_fee, detail_json)
                        VALUES (?,?,?,?,?,?,?,?,?::jsonb)
                        """, scenarioId, billingMonth, pid, method, sharedFee, exclusiveFee,
                        readFee, total, writeJson(detail));
                Map<String, Object> bill = new LinkedHashMap<>(detail);
                bill.put("projectId", pid);
                billViews.add(bill);
                allocatedByMethod.merge(method, total, BigDecimal::add);
            }
        }

        // 6) 对账约束：每路线项目账单加总必须等于账单总额
        BigDecimal grandTotal = totalStorageFee.add(totalReadFee).setScale(SCALE, RoundingMode.HALF_UP);
        List<Map<String, Object>> reconciliationViews = new ArrayList<>();
        for (String method : List.of("REFERENCE_WEIGHT", "SHAPLEY")) {
            BigDecimal allocated = allocatedByMethod.get(method).setScale(SCALE, RoundingMode.HALF_UP);
            boolean reconciled = allocated.compareTo(grandTotal) == 0;
            jdbc.update("""
                    INSERT INTO share_reconciliation(scenario_id, billing_month, method,
                                                     total_storage_fee, total_read_fee,
                                                     grand_total_fee, allocated_total, reconciled)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, scenarioId, billingMonth, method, totalStorageFee, totalReadFee,
                    grandTotal, allocated, reconciled);
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("method", method);
            rec.put("totalStorageFee", totalStorageFee);
            rec.put("totalReadFee", totalReadFee);
            rec.put("grandTotalFee", grandTotal);
            rec.put("allocatedTotal", allocated);
            rec.put("reconciled", reconciled);
            reconciliationViews.add(rec);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scenarioCode", DEFAULT_SCENARIO);
        response.put("billingMonth", billingMonth);
        response.put("days", days);
        response.put("rateVersion", scenario.get("rateVersion"));
        response.put("storageRate", storageRate);
        response.put("readGbRate", scenario.get("readGbRate"));
        response.put("objects", objectViews);
        response.put("readUsage", readRows);
        response.put("allocations", allocationViews);
        response.put("bills", billViews);
        response.put("reconciliation", reconciliationViews);
        response.put("totalStorageFee", totalStorageFee);
        response.put("totalReadFee", totalReadFee);
        response.put("grandTotalFee", grandTotal);
        return response;
    }

    /**
     * 计算单个共享对象在两条路线下对各成员项目的分摊。
     */
    private Map<String, List<Map<String, Object>>> computeSharedAllocations(
            long objectId, String uid, List<Map<String, Object>> members,
            BigDecimal monthlyStorage, String billingMonth, long scenarioId) {

        List<Long> pids = members.stream().map(m -> ((Number) m.get("pid")).longValue()).toList();
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> m : members) {
            counts.put(((Number) m.get("pid")).longValue(), ((Number) m.get("cnt")).intValue());
        }
        int totalRefs = counts.values().stream().mapToInt(Integer::intValue).sum();

        // ---- 路线一：有效引用权重 ----
        Map<Long, BigDecimal> weightFees = new LinkedHashMap<>();
        BigDecimal weightAllocated = BigDecimal.ZERO;
        for (int i = 0; i < pids.size(); i++) {
            long pid = pids.get(i);
            BigDecimal fee;
            if (i == pids.size() - 1) {
                fee = monthlyStorage.subtract(weightAllocated); // 末位补差，保证合计分毫不差
            } else {
                BigDecimal weight = BigDecimal.valueOf(counts.get(pid))
                        .divide(BigDecimal.valueOf(totalRefs), SCALE, RoundingMode.HALF_UP);
                fee = monthlyStorage.multiply(weight).setScale(SCALE, RoundingMode.HALF_UP);
                weightAllocated = weightAllocated.add(fee);
            }
            weightFees.put(pid, fee);
        }

        // ---- 路线二：Shapley 边际成本（全排列枚举） ----
        List<List<Long>> permutations = permutations(pids);
        Map<Long, BigDecimal> marginalSum = new LinkedHashMap<>();
        Map<Long, Integer> firstPositionHits = new LinkedHashMap<>();
        pids.forEach(pid -> {
            marginalSum.put(pid, BigDecimal.ZERO);
            firstPositionHits.put(pid, 0);
        });
        for (List<Long> perm : permutations) {
            java.util.Set<Long> before = new java.util.HashSet<>();
            for (Long pid : perm) {
                BigDecimal vBefore = before.isEmpty() ? BigDecimal.ZERO : monthlyStorage;
                before.add(pid);
                BigDecimal vAfter = monthlyStorage; // 加入后联盟非空，对象费用必然发生
                BigDecimal marginal = vAfter.subtract(vBefore);
                if (marginal.signum() > 0) {
                    firstPositionHits.merge(pid, 1, Integer::sum);
                }
                marginalSum.merge(pid, marginal, BigDecimal::add);
            }
        }
        BigDecimal nFactorial = BigDecimal.valueOf(permutations.size());
        Map<Long, BigDecimal> shapleyMarginal = new LinkedHashMap<>();
        Map<Long, BigDecimal> shapleyFees = new LinkedHashMap<>();
        BigDecimal shapleyAllocated = BigDecimal.ZERO;
        for (int i = 0; i < pids.size(); i++) {
            long pid = pids.get(i);
            BigDecimal avgMarginal = marginalSum.get(pid)
                    .divide(nFactorial, SCALE, RoundingMode.HALF_UP);
            shapleyMarginal.put(pid, avgMarginal);
            BigDecimal fee;
            if (i == pids.size() - 1) {
                fee = monthlyStorage.subtract(shapleyAllocated);
            } else {
                fee = avgMarginal.setScale(SCALE, RoundingMode.HALF_UP);
                shapleyAllocated = shapleyAllocated.add(fee);
            }
            shapleyFees.put(pid, fee);
        }

        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        List<Map<String, Object>> weightRows = new ArrayList<>();
        List<Map<String, Object>> shapleyRows = new ArrayList<>();
        for (Long pid : pids) {
            BigDecimal weight = BigDecimal.valueOf(counts.get(pid))
                    .divide(BigDecimal.valueOf(totalRefs), SCALE, RoundingMode.HALF_UP);
            Map<String, Object> weightDetail = Map.of(
                    "method", "REFERENCE_WEIGHT",
                    "referenceCount", counts.get(pid),
                    "totalReferenceCount", totalRefs,
                    "weight", weight,
                    "rule", "按有效研究分支数占比分摊");
            persistAllocation(scenarioId, billingMonth, objectId, pid, "REFERENCE_WEIGHT",
                    weight, counts.get(pid), null, weightFees.get(pid), weightDetail);
            weightRows.add(allocationView(uid, pid, "REFERENCE_WEIGHT", weight,
                    counts.get(pid), null, weightFees.get(pid), weightDetail));

            BigDecimal shapleyWeight = BigDecimal.ONE.divide(BigDecimal.valueOf(pids.size()),
                    SCALE, RoundingMode.HALF_UP);
            Map<String, Object> shapleyDetail = Map.of(
                    "method", "SHAPLEY",
                    "permutationCount", permutations.size(),
                    "firstPositionCount", firstPositionHits.get(pid),
                    "averageMarginalCost", shapleyMarginal.get(pid),
                    "coalitionRule", "联盟非空即发生全部对象费；仅排列首位成员承担边际成本",
                    "rule", "各成员 Shapley 值 = 平均边际成本 = 对象费 / 成员数");
            persistAllocation(scenarioId, billingMonth, objectId, pid, "SHAPLEY",
                    shapleyWeight, counts.get(pid), shapleyMarginal.get(pid),
                    shapleyFees.get(pid), shapleyDetail);
            shapleyRows.add(allocationView(uid, pid, "SHAPLEY", shapleyWeight,
                    counts.get(pid), shapleyMarginal.get(pid), shapleyFees.get(pid), shapleyDetail));
        }
        result.put("REFERENCE_WEIGHT", weightRows);
        result.put("SHAPLEY", shapleyRows);
        return result;
    }

    private void persistAllocation(long scenarioId, String billingMonth, long objectId, long pid,
                                   String method, BigDecimal weight, int referenceCount,
                                   BigDecimal marginalCost, BigDecimal fee, Map<String, Object> detail) {
        jdbc.update("""
                INSERT INTO share_allocation(scenario_id, billing_month, object_id, project_id, method,
                                             weight, reference_count, marginal_cost, allocated_fee, detail_json)
                VALUES (?,?,?,?,?,?,?,?,?,?::jsonb)
                """, scenarioId, billingMonth, objectId, pid, method, weight, referenceCount,
                marginalCost, fee, writeJson(detail));
    }

    private Map<String, Object> allocationView(String uid, long pid, String method, BigDecimal weight,
                                               int referenceCount, BigDecimal marginalCost,
                                               BigDecimal fee, Map<String, Object> detail) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("uid", uid);
        view.put("projectId", pid);
        view.put("projectCode", projectCode(pid));
        view.put("method", method);
        view.put("weight", weight);
        view.put("referenceCount", referenceCount);
        view.put("marginalCost", marginalCost);
        view.put("allocatedFee", fee);
        view.put("detail", detail);
        return view;
    }

    // ====================  persisted 查询 ====================

    public List<Map<String, Object>> monthlyBills(String billingMonth) {
        return jdbc.queryForList("""
                SELECT b.billing_month AS "billingMonth", p.code AS "projectCode", b.method,
                       b.shared_storage_fee AS "sharedStorageFee",
                       b.exclusive_storage_fee AS "exclusiveStorageFee",
                       b.read_fee AS "readFee", b.total_fee AS "totalFee", b.detail_json AS detail
                FROM share_monthly_bill b JOIN project p ON p.id = b.project_id
                WHERE b.scenario_id = ? AND (? IS NULL OR b.billing_month = ?)
                ORDER BY b.billing_month DESC, b.method, p.code
                """, scenarioId(), billingMonth, billingMonth);
    }

    public List<Map<String, Object>> reconciliations(String billingMonth) {
        return jdbc.queryForList("""
                SELECT billing_month AS "billingMonth", method,
                       total_storage_fee AS "totalStorageFee", total_read_fee AS "totalReadFee",
                       grand_total_fee AS "grandTotalFee", allocated_total AS "allocatedTotal",
                       reconciled, checked_at AS "checkedAt"
                FROM share_reconciliation
                WHERE scenario_id = ? AND (? IS NULL OR billing_month = ?)
                ORDER BY billing_month DESC, method
                """, scenarioId(), billingMonth, billingMonth);
    }

    public List<Map<String, Object>> byteHourLedger(String billingMonth) {
        return jdbc.queryForList("""
                SELECT so.object_uid AS uid, l.day, l.byte_hours AS "byteHours",
                       l.gb_month_rate AS "gbMonthRate", l.storage_charge AS "storageCharge"
                FROM byte_hour_ledger l JOIN share_object so ON so.id = l.object_id
                WHERE l.scenario_id = ? AND (? IS NULL OR l.billing_month = ?)
                ORDER BY l.day, so.object_uid
                """, scenarioId(), billingMonth, billingMonth);
    }

    public List<Map<String, Object>> exclusiveLedger(String billingMonth) {
        return jdbc.queryForList("""
                SELECT l.billing_month AS "billingMonth", so.object_uid AS uid,
                       p.code AS "projectCode", l.byte_size AS "byteSize",
                       l.gb_month_rate AS "gbMonthRate", l.storage_fee AS "storageFee"
                FROM exclusive_resource_ledger l
                JOIN share_object so ON so.id = l.object_id
                JOIN project p ON p.id = l.project_id
                WHERE l.scenario_id = ? AND (? IS NULL OR l.billing_month = ?)
                ORDER BY l.billing_month, so.object_uid
                """, scenarioId(), billingMonth, billingMonth);
    }

    /** 重置为题设初始状态（撤销/重算演示后一键复原）。 */
    @Transactional
    public Map<String, Object> resetScenario() {
        long scenarioId = scenarioId();
        for (String table : List.of("share_reconciliation", "share_monthly_bill", "share_allocation",
                "exclusive_resource_ledger", "byte_hour_ledger", "membership_snapshot",
                "membership_change", "read_usage_ledger", "membership_intent")) {
            jdbc.update("DELETE FROM " + table + " WHERE scenario_id = ?", scenarioId);
        }
        long o1 = requireObject("O1");
        long projA = requireProject("PROJ-A");
        long projB = requireProject("PROJ-B");
        jdbc.update("""
                INSERT INTO membership_intent(scenario_id, object_id, project_id,
                                              active_reference_count, effective_from, note)
                VALUES (?,?,?,3,'2026-11','研究A 3 个有效研究分支引用 O1'),
                       (?,?,?,1,'2026-11','研究B 1 个有效研究分支引用 O1')
                """, scenarioId, o1, projA, scenarioId, o1, projB);
        recordReadUsage("2026-11", "PROJ-A", new BigDecimal("120.000"), "研究A 本月读取 120GB");
        recordReadUsage("2026-11", "PROJ-B", new BigDecimal("80.000"), "研究B 本月读取 80GB");
        return Map.of("scenarioCode", DEFAULT_SCENARIO, "reset", true,
                "membership", "PROJ-A=3 分支, PROJ-B=1 分支", "readUsage202611", "A=120GB, B=80GB");
    }

    // ==================== 辅助 ====================

    private long scenarioId() {
        Long id = jdbc.queryForObject(
                "SELECT id FROM share_scenario WHERE code = ?", Long.class, DEFAULT_SCENARIO);
        if (id == null) {
            throw new IllegalStateException("分摊场景不存在: " + DEFAULT_SCENARIO);
        }
        return id;
    }

    private long requireObject(String uid) {
        Long id = jdbc.queryForObject(
                "SELECT id FROM share_object WHERE scenario_id = ? AND object_uid = ?",
                Long.class, scenarioId(), uid);
        if (id == null) {
            throw new IllegalArgumentException("分摊对象不存在: " + uid);
        }
        return id;
    }

    private long requireProject(String code) {
        Long id = jdbc.queryForObject("SELECT id FROM project WHERE code = ?", Long.class, code);
        if (id == null) {
            throw new IllegalArgumentException("项目不存在: " + code);
        }
        return id;
    }

    private String projectCode(long pid) {
        return jdbc.queryForObject("SELECT code FROM project WHERE id = ?", String.class, pid);
    }

    /** 默认下一账期：取已冻结快照的最大账期 +1 月，否则 2026-12。 */
    private String defaultNextMonth() {
        List<String> months = jdbc.queryForList(
                "SELECT DISTINCT billing_month FROM membership_snapshot WHERE scenario_id = ?",
                String.class, scenarioId());
        if (months.isEmpty()) {
            return "2026-12";
        }
        return months.stream().max(String::compareTo)
                .map(m -> YearMonth.parse(m).plusMonths(1).toString())
                .orElse("2026-12");
    }

    /** 全排列（Shapley 枚举；成员数通常很小，复杂度 n!）。 */
    private <T> List<List<T>> permutations(List<T> items) {
        List<List<T>> result = new ArrayList<>();
        if (items.isEmpty()) {
            result.add(new ArrayList<>());
            return result;
        }
        for (int i = 0; i < items.size(); i++) {
            T current = items.get(i);
            List<T> rest = new ArrayList<>(items);
            rest.remove(i);
            for (List<T> perm : permutations(rest)) {
                List<T> p = new ArrayList<>();
                p.add(current);
                p.addAll(perm);
                result.add(p);
            }
        }
        return result;
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
