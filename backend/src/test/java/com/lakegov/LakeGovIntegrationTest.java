package com.lakegov;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集成测试：依赖本地 PostgreSQL（ccdev 库），应用启动时自动建表+种子。
 * 与 scripts/verify_e2e.py 覆盖相同核心规则，供 mvn test 使用。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LakeGovIntegrationTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;

    private HttpHeaders auth() {
        Map<String, Object> resp = rest.postForEntity("/api/auth/login",
                Map.of("username", "admin", "password", "admin123"), Map.class).getBody();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth((String) resp.get("token"));
        return h;
    }

    @Test
    @Order(1)
    void catalogSeededWithResearchASizes() {
        Map[] objects = rest.exchange("/api/catalog/objects", HttpMethod.GET,
                new HttpEntity<>(auth()), Map[].class).getBody();
        assertNotNull(objects);
        assertEquals(10, objects.length);
        Map<String, Object> raw = List.of(objects).stream()
                .filter(o -> "RAW-IMG-A01".equals(o.get("uid"))).findFirst().orElseThrow();
        assertEquals(2_000_000_000_000L, ((Number) raw.get("byteSize")).longValue());
        assertEquals(64, ((String) raw.get("checksum")).length());
    }

    @Test
    @Order(2)
    void dependencyClosureContainsSevenObjectsWithSoftwareAndCalibration() {
        Map[] closure = rest.exchange("/api/retention/closure/RESULT-A01", HttpMethod.GET,
                new HttpEntity<>(auth()), Map[].class).getBody();
        assertNotNull(closure);
        assertEquals(7, closure.length);
        var uids = List.of(closure).stream().map(o -> (String) o.get("uid")).toList();
        assertTrue(uids.containsAll(List.of(
                "RESULT-A01", "FEAT-A01", "MASK-A01", "RAW-IMG-A01",
                "ANN-A01", "CAL-A01", "ALGO-PKG-01")));
    }

    @Test
    @Order(3)
    void retentionRawImageTenYearsAndSoftwareExtended() {
        rest.exchange("/api/retention/recalculate", HttpMethod.POST,
                new HttpEntity<>(auth()), List.class);
        Map[] overview = rest.exchange("/api/retention/overview", HttpMethod.GET,
                new HttpEntity<>(auth()), Map[].class).getBody();
        Map<String, Map> byUid = new java.util.HashMap<>();
        for (Map o : overview) byUid.put((String) o.get("uid"), o);
        assertEquals("2036-01-15", String.valueOf(byUid.get("RAW-IMG-A01").get("baseDeadline")));
        assertTrue(String.valueOf(byUid.get("ALGO-PKG-01").get("effectiveDeadline"))
                .compareTo("2034-02-05") >= 0);
    }

    @Test
    @Order(4)
    void litigationHoldPropagatesDownstreamAndReleases() {
        HttpHeaders h = auth();
        Map hold = rest.exchange("/api/retention/hold", HttpMethod.POST,
                new HttpEntity<>(Map.of("uid", "RAW-IMG-A01", "litigationHold", true,
                        "reason", "junit"), h), Map.class).getBody();
        @SuppressWarnings("unchecked")
        List<String> propagated = (List<String>) hold.get("propagatedTo");
        assertTrue(propagated.containsAll(List.of("MASK-A01", "FEAT-A01", "RESULT-A01")));
        Map[] overview = rest.exchange("/api/retention/overview", HttpMethod.GET,
                new HttpEntity<>(h), Map[].class).getBody();
        Map<String, Map> byUid = new java.util.HashMap<>();
        for (Map o : overview) byUid.put((String) o.get("uid"), o);
        assertEquals("BLOCKED_HOLD", byUid.get("RESULT-A01").get("disposition"));
        rest.exchange("/api/retention/hold", HttpMethod.POST,
                new HttpEntity<>(Map.of("uid", "RAW-IMG-A01", "litigationHold", false,
                        "reason", "junit release"), h), Map.class);
    }

    @Test
    @Order(5)
    void missingCalibrationBlocksRestoreBeforeStart() {
        HttpHeaders h = auth();
        rest.exchange("/api/restore/availability", HttpMethod.POST,
                new HttpEntity<>(Map.of("uid", "CAL-A01", "available", false), h), Map.class);
        Map plan = rest.exchange("/api/restore/plans", HttpMethod.POST,
                new HttpEntity<>(Map.of("targetUid", "RESULT-A01",
                        "strategy", "SCHEDULED_TIERING", "frequencyPerYear", 1), h), Map.class).getBody();
        assertEquals("UNSATISFIABLE", plan.get("status"));
        @SuppressWarnings("unchecked")
        List<Map> missing = (List<Map>) plan.get("missingItems");
        assertTrue(missing.stream().anyMatch(m -> "CAL-A01".equals(m.get("uid"))));
        rest.exchange("/api/restore/availability", HttpMethod.POST,
                new HttpEntity<>(Map.of("uid", "CAL-A01", "available", true), h), Map.class);
    }

    @Test
    @Order(6)
    void strategyComparisonBreakevenBetweenYearlyAndMonthly() {
        Map cmp = rest.exchange(
                "/api/cost/strategy-comparison?targetUid=RESULT-A01&frequencies=1&frequencies=12",
                HttpMethod.GET, new HttpEntity<>(auth()), Map.class).getBody();
        double breakeven = Double.parseDouble(String.valueOf(cmp.get("breakevenFrequencyPerYear")));
        assertTrue(breakeven > 1 && breakeven < 12, "breakeven=" + breakeven);
        @SuppressWarnings("unchecked")
        List<Map> scenarios = (List<Map>) cmp.get("scenarios");
        assertEquals("SCHEDULED_TIERING", scenarios.get(0).get("cheaperStrategy"));
        assertEquals("RESTORE_PACKAGE", scenarios.get(1).get("cheaperStrategy"));
    }

    @Test
    @Order(7)
    void monthlyBillingAllocationsReconcile() {
        HttpHeaders h = auth();
        Map bill = rest.exchange("/api/cost/billing/generate", HttpMethod.POST,
                new HttpEntity<>(Map.of("billingMonth", "2026-11"), h), Map.class).getBody();
        assertEquals(Boolean.TRUE, bill.get("allocationCheck"));
        assertNotNull(bill.get("grandTotal"));
    }

    @Test
    @Order(8)
    void snapshotCapturesCatalogLineageAndDecisions() {
        Map snap = rest.exchange("/api/snapshots", HttpMethod.POST,
                new HttpEntity<>(auth()), Map.class).getBody();
        assertEquals("RV-2026Q4", snap.get("rateVersion"));
        assertTrue(((Number) snap.get("catalogObjects")).intValue() >= 10);
        assertTrue(((Number) snap.get("lineageEdges")).intValue() >= 9);
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM archive_snapshot", Integer.class);
        assertNotNull(rows);
        assertTrue(rows > 0);
    }

    // ---------- 共享影像湖仓账单双路线分摊 ----------

    private Map postShare(String path, Object body) {
        return rest.exchange("/api/cost-share" + path, HttpMethod.POST,
                new HttpEntity<>(body, auth()), Map.class).getBody();
    }

    private Map[] getShare(String path) {
        return rest.exchange("/api/cost-share" + path, HttpMethod.GET,
                new HttpEntity<>(auth()), Map[].class).getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map> billsByMethod(Map billing) {
        Map<String, Map> out = new java.util.HashMap<>();
        for (Map b : (List<Map>) billing.get("bills")) {
            out.put(b.get("method") + ":" + b.get("projectCode"), b);
        }
        return out;
    }

    @Test
    @Order(9)
    void shareBillingDualMethodsBothReconcileToNineteen() {
        HttpHeaders h = auth();
        postShare("/scenario/reset", Map.of());
        Map billing = postShare("/billing/generate", Map.of("billingMonth", "2026-11"));
        // 总额 19 元，两路线均对账通过
        assertEquals(19.0, ((Number) billing.get("grandTotalFee")).doubleValue(), 1e-9);
        @SuppressWarnings("unchecked")
        List<Map> recs = (List<Map>) billing.get("reconciliation");
        assertEquals(2, recs.size());
        recs.forEach(r -> assertEquals(Boolean.TRUE, r.get("reconciled")));

        Map<String, Map> byKey = billsByMethod(billing);
        // 路线一：有效引用权重 3:1 → A 12.90、B 6.10
        Map wA = byKey.get("REFERENCE_WEIGHT:PROJ-A");
        Map wB = byKey.get("REFERENCE_WEIGHT:PROJ-B");
        assertEquals(7.50, ((Number) wA.get("sharedStorageFee")).doubleValue(), 1e-9);
        assertEquals(12.90, ((Number) wA.get("totalFee")).doubleValue(), 1e-9);
        assertEquals(2.50, ((Number) wB.get("sharedStorageFee")).doubleValue(), 1e-9);
        assertEquals(6.10, ((Number) wB.get("totalFee")).doubleValue(), 1e-9);
        // 读取费与专属费直接归属
        assertEquals(2.40, ((Number) wA.get("readFee")).doubleValue(), 1e-9);
        assertEquals(1.60, ((Number) wB.get("readFee")).doubleValue(), 1e-9);
        assertEquals(3.00, ((Number) wA.get("exclusiveStorageFee")).doubleValue(), 1e-9);
        assertEquals(2.00, ((Number) wB.get("exclusiveStorageFee")).doubleValue(), 1e-9);

        // 路线二：Shapley 边际成本 → O1 各 5 元 → A 10.40、B 8.60
        Map sA = byKey.get("SHAPLEY:PROJ-A");
        Map sB = byKey.get("SHAPLEY:PROJ-B");
        assertEquals(5.00, ((Number) sA.get("sharedStorageFee")).doubleValue(), 1e-9);
        assertEquals(10.40, ((Number) sA.get("totalFee")).doubleValue(), 1e-9);
        assertEquals(5.00, ((Number) sB.get("sharedStorageFee")).doubleValue(), 1e-9);
        assertEquals(8.60, ((Number) sB.get("totalFee")).doubleValue(), 1e-9);

        // O1 对象级明细：权重 0.75/0.25；Shapley 平均边际成本各 5
        @SuppressWarnings("unchecked")
        List<Map> allocations = (List<Map>) billing.get("allocations");
        Map<String, Map> o1 = new java.util.HashMap<>();
        for (Map a : allocations) {
            if ("O1".equals(a.get("uid"))) {
                o1.put(a.get("method") + ":" + a.get("projectCode"), a);
            }
        }
        Map o1WeightB = o1.get("REFERENCE_WEIGHT:PROJ-B");
        assertEquals(0.25, ((Number) o1WeightB.get("weight")).doubleValue(), 1e-9);
        Map o1ShapleyA = o1.get("SHAPLEY:PROJ-A");
        assertEquals(5.00, ((Number) o1ShapleyA.get("marginalCost")).doubleValue(), 1e-9);
        assertEquals(5.00, ((Number) o1ShapleyA.get("allocatedFee")).doubleValue(), 1e-9);

        // 字节时长账：3 对象 × 30 天 = 90 行；O1 月费合计 10 元
        Map[] ledger = getShare("/ledger/byte-hours?billingMonth=2026-11");
        assertEquals(90, ledger.length);
        double o1Charge = java.util.Arrays.stream(ledger)
                .filter(r -> "O1".equals(r.get("uid")))
                .mapToDouble(r -> ((Number) r.get("storageCharge")).doubleValue()).sum();
        assertEquals(10.0, o1Charge, 1e-6);
    }

    @Test
    @Order(10)
    void revokeLastReferenceKeepsCurrentMonthAndRecomputesNextPeriod() {
        // 撤销 B 对 O1 的最后引用，下一周期生效
        Map revoked = postShare("/membership/revoke",
                Map.of("objectUid", "O1", "projectCode", "PROJ-B", "effectiveMonth", "2026-12"));
        assertEquals("UNCHANGED", revoked.get("currentMonthSnapshot"));

        // 本月（2026-11）快照与账单不变
        Map nov = postShare("/billing/generate", Map.of("billingMonth", "2026-11"));
        Map<String, Map> novBills = billsByMethod(nov);
        assertEquals(12.90, ((Number) novBills.get("REFERENCE_WEIGHT:PROJ-A").get("totalFee")).doubleValue(), 1e-9);
        assertEquals(6.10, ((Number) novBills.get("REFERENCE_WEIGHT:PROJ-B").get("totalFee")).doubleValue(), 1e-9);
        Map[] novSnap = getShare("/snapshots?billingMonth=2026-11");
        Map bNov = java.util.Arrays.stream(novSnap)
                .filter(s -> "PROJ-B".equals(s.get("projectCode"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, bNov.get("member"));

        // 下一周期（2026-12）：O1 仅属 A，A 承担全部 10 元，B 不再分摊共享费
        Map dec = postShare("/billing/generate", Map.of("billingMonth", "2026-12"));
        Map<String, Map> decBills = billsByMethod(dec);
        Map aDec = decBills.get("REFERENCE_WEIGHT:PROJ-A");
        Map bDec = decBills.get("REFERENCE_WEIGHT:PROJ-B");
        assertEquals(10.00, ((Number) aDec.get("sharedStorageFee")).doubleValue(), 1e-9);
        assertEquals(0.00, ((Number) bDec.get("sharedStorageFee")).doubleValue(), 1e-9);
        assertEquals(2.00, ((Number) bDec.get("exclusiveStorageFee")).doubleValue(), 1e-9);
        // 单成员联盟下两条路线结果相同且仍对账
        @SuppressWarnings("unchecked")
        List<Map> recs = (List<Map>) dec.get("reconciliation");
        recs.forEach(r -> assertEquals(Boolean.TRUE, r.get("reconciled")));
        Map[] decSnap = getShare("/snapshots?billingMonth=2026-12");
        Map bDecSnap = java.util.Arrays.stream(decSnap)
                .filter(s -> "PROJ-B".equals(s.get("projectCode"))).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, bDecSnap.get("member"));
    }

    @Test
    @Order(11)
    void readUsageDirectAttributionAndScenarioReset() {
        // 录入读取量直接归属项目：A 读取 100GB → 2.00 元
        postShare("/read-usage", Map.of("billingMonth", "2026-11",
                "projectCode", "PROJ-A", "readGb", 100, "note", "junit 调整读取量"));
        Map billing = postShare("/billing/generate", Map.of("billingMonth", "2026-11"));
        Map a = billsByMethod(billing).get("REFERENCE_WEIGHT:PROJ-A");
        assertEquals(2.00, ((Number) a.get("readFee")).doubleValue(), 1e-9);
        // 总额随读取费变化：存储 15 + 读取 (2.00 + 1.60) = 18.60，仍对账
        assertEquals(18.60, ((Number) billing.get("grandTotalFee")).doubleValue(), 1e-9);

        // 恢复 B 引用（新成员集重算），再重置回题设初始状态
        postShare("/membership/add", Map.of("objectUid", "O1", "projectCode", "PROJ-B",
                "referenceCount", 1, "effectiveMonth", "2026-12"));
        Map reset = postShare("/scenario/reset", Map.of());
        assertEquals(Boolean.TRUE, reset.get("reset"));
        Map back = postShare("/billing/generate", Map.of("billingMonth", "2026-11"));
        assertEquals(19.0, ((Number) back.get("grandTotalFee")).doubleValue(), 1e-9);
        assertEquals(12.90,
                ((Number) billsByMethod(back).get("REFERENCE_WEIGHT:PROJ-A").get("totalFee")).doubleValue(), 1e-9);
    }
}
