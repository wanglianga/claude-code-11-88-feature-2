<template>
  <div>
    <!-- 账期操作与对账 -->
    <div class="card">
      <h2>共享影像湖仓账单分摊（双路线：有效引用权重 / Shapley 边际成本）</h2>
      <div class="hint">
        题设样例：共享源影像 <b>O1 100GB × ¥0.10 = ¥10.00</b>；专属派生 DERIV-A 30GB=¥3.00、DERIV-B 20GB=¥2.00；
        读取服务费 ¥0.02/GB（A 读 120GB=¥2.40、B 读 80GB=¥1.60），<b>账单总额 ¥19.00</b>。
        A 有 3 个、B 有 1 个有效研究分支引用 O1。计量口径 1GB=10⁹B、月=30 天。
      </div>
      <div class="toolbar">
        <label class="muted">账期 <input v-model="month" style="width:130px" placeholder="YYYY-MM" /></label>
        <button class="btn" @click="generate">生成/重算 {{ month }} 双路线账单</button>
        <button class="btn btn-ghost btn-sm" @click="capture">仅冻结成员快照</button>
        <button class="btn btn-ghost btn-sm" @click="resetAll">重置为题设初始状态</button>
        <span v-for="r in reconciliation" :key="r.method" class="tag"
              :class="r.reconciled ? 'tag-ok' : 'tag-danger'">
          {{ methodName(r.method) }}：{{ r.reconciled ? '对账通过' : '对账失败' }}
          （分摊 ¥{{ fmt(r.allocatedTotal) }} = 总额 ¥{{ fmt(r.grandTotalFee) }}）
        </span>
      </div>
      <div v-if="lastBill" class="grid grid-4">
        <div class="stat"><div class="k">存储费总额（共享+专属）</div><div class="v">¥{{ fmt(lastBill.totalStorageFee) }}</div></div>
        <div class="stat"><div class="k">读取服务费总额</div><div class="v">¥{{ fmt(lastBill.totalReadFee) }}</div></div>
        <div class="stat"><div class="k">账单总额（两路线一致）</div><div class="v">¥{{ fmt(lastBill.grandTotalFee) }}</div></div>
        <div class="stat"><div class="k">账期天数 / 字节时长账</div><div class="v">{{ lastBill.days }} 天 / {{ byteHours.length }} 行</div></div>
      </div>
    </div>

    <!-- 项目账单（路线切换） -->
    <div class="card">
      <h2>项目月度账单</h2>
      <div class="toolbar">
        <div class="flex">
          <button v-for="m in methods" :key="m" class="btn btn-sm"
                  :class="method === m ? '' : 'btn-ghost'" @click="method = m">
            {{ methodName(m) }}
          </button>
        </div>
        <span class="muted" style="font-size:12px">
          {{ method === 'REFERENCE_WEIGHT'
            ? '按有效研究分支权重 3:1 分摊 O1：A ¥7.50 / B ¥2.50（简单可复算，多分支项目承担较多）'
            : 'Shapley 边际成本：任一成员在场移除单个项目都省不下 O1 的 ¥10，故各 ¥5.00（反映不可避免的共享基础成本）' }}
        </span>
      </div>
      <div class="table-wrap">
        <table>
          <thead><tr>
            <th>项目</th><th>账期</th><th>共享存储费（O1 分摊）</th>
            <th>专属存储费</th><th>读取服务费</th><th>合计</th><th>路线</th>
          </tr></thead>
          <tbody>
          <tr v-for="b in visibleBills" :key="b.projectCode + b.method">
            <td>{{ b.projectCode }}</td><td class="mono">{{ b.billingMonth }}</td>
            <td>¥{{ fmt(b.sharedStorageFee) }}</td>
            <td>¥{{ fmt(b.exclusiveStorageFee) }}</td>
            <td>¥{{ fmt(b.readFee) }}</td>
            <td><b>¥{{ fmt(b.totalFee) }}</b></td>
            <td><span class="tag tag-info">{{ methodName(b.method) }}</span></td>
          </tr>
          </tbody>
        </table>
      </div>
      <div v-if="visibleBills.length" class="hint" style="margin-top:10px">
        本路线项目合计 <b>¥{{ fmt(visibleTotal) }}</b>，与账单总额
        <b :class="Math.abs(visibleTotal - Number(lastBill?.grandTotalFee || 0)) < 0.0001 ? 'pass' : 'fail'">
          {{ Math.abs(visibleTotal - Number(lastBill?.grandTotalFee || 0)) < 0.0001 ? '一致' : '不一致' }}
        </b>
      </div>
    </div>

    <!-- 对象级分摊明细 -->
    <div class="card">
      <h2>对象级分摊明细（共享对象双路线 + 专属资源账）</h2>
      <div class="table-wrap">
        <table>
          <thead><tr><th>对象</th><th>类型</th><th>GB</th><th>月存储费</th><th>路线</th><th>项目</th><th>权重</th><th>引用分支</th><th>Shapley 平均边际</th><th>分摊额</th></tr></thead>
          <tbody>
          <template v-for="g in allocationGroups" :key="g.uid">
            <tr v-for="(a, i) in g.rows" :key="g.uid + a.method + a.projectCode">
              <td class="mono">{{ i === 0 ? g.uid : '' }}</td>
              <td>{{ i === 0 ? (g.kind === 'SHARED' ? '共享' : '专属') : '' }}</td>
              <td>{{ i === 0 ? g.gb : '' }}</td>
              <td>{{ i === 0 ? '¥' + fmt(g.monthlyStorageFee) : '' }}</td>
              <td><span class="tag" :class="a.method === 'SHAPLEY' ? 'tag-warn' : 'tag-info'">{{ methodName(a.method) }}</span></td>
              <td>{{ a.projectCode }}</td>
              <td class="mono">{{ a.weight }}</td>
              <td>{{ a.referenceCount }}</td>
              <td>{{ a.marginalCost == null ? '—' : '¥' + fmt(a.marginalCost) }}</td>
              <td><b>¥{{ fmt(a.allocatedFee) }}</b></td>
            </tr>
          </template>
          <tr v-for="e in exclusiveLedger" :key="e.uid" style="background:var(--panel-2)">
            <td class="mono">{{ e.uid }}</td><td>专属</td><td>{{ (e.byteSize / 1e9).toFixed(0) }}</td>
            <td>¥{{ fmt(e.storageFee) }}</td><td><span class="tag tag-muted">直接归属</span></td>
            <td>{{ e.projectCode }}</td><td>1.000000</td><td>—</td><td>—</td><td><b>¥{{ fmt(e.storageFee) }}</b></td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 成员快照与撤销/恢复引用 -->
    <div class="card">
      <h2>对象-项目成员月度快照与引用变更（本月快照不变，下一周期重算）</h2>
      <div class="toolbar">
        <button class="btn btn-sm btn-ghost" @click="loadSnapshot">刷新 {{ month }} 快照</button>
        <span class="muted" style="font-size:12px">撤销末次引用后，{{ month }} 已冻结快照与账单保持不变；自 {{ nextMonth }} 起按新成员集重算</span>
      </div>
      <div class="table-wrap" style="max-height:320px">
        <table>
          <thead><tr><th>对象</th><th>项目</th><th>有效引用分支数</th><th>本账期成员</th><th>冻结时间</th><th>操作</th></tr></thead>
          <tbody>
          <tr v-for="s in snapshot" :key="s.uid + s.projectCode">
            <td class="mono">{{ s.uid }}</td><td>{{ s.projectCode }}</td>
            <td>{{ s.referenceCount }}</td>
            <td><span class="tag" :class="s.member ? 'tag-ok' : 'tag-muted'">{{ s.member ? '成员' : '已退出' }}</span></td>
            <td class="muted">{{ String(s.capturedAt).slice(0, 16) }}</td>
            <td>
              <button class="btn btn-sm btn-danger" :disabled="!s.member"
                      @click="revoke(s.uid, s.projectCode)">撤销全部引用（{{ nextMonth }} 生效）</button>
            </td>
          </tr>
          <tr v-if="!snapshot.length"><td colspan="6" class="muted">该账期尚未冻结快照，请先“生成账单”或“仅冻结成员快照”。</td></tr>
          </tbody>
        </table>
      </div>

      <div class="toolbar" style="margin-top:12px">
        <b style="font-size:13px">恢复/新增引用：</b>
        <select v-model="addForm.projectCode" style="width:130px">
          <option>PROJ-A</option><option>PROJ-B</option>
        </select>
        <input v-model.number="addForm.referenceCount" type="number" min="1" style="width:110px" placeholder="分支数" />
        <input v-model="addForm.effectiveMonth" style="width:130px" placeholder="生效 YYYY-MM" />
        <button class="btn btn-sm btn-ok" @click="addRef">登记引用并重算 {{ addForm.effectiveMonth }}</button>
      </div>

      <b style="font-size:13px">变更流水</b>
      <div class="table-wrap" style="max-height:220px;margin-top:6px">
        <table>
          <thead><tr><th>对象</th><th>项目</th><th>类型</th><th>生效账期</th><th>说明</th><th>请求时间</th></tr></thead>
          <tbody>
          <tr v-for="(c, i) in changes" :key="i">
            <td class="mono">{{ c.uid }}</td><td>{{ c.projectCode }}</td>
            <td><span class="tag" :class="c.changeType === 'REFERENCE_REVOKED' ? 'tag-danger' : 'tag-ok'">
              {{ c.changeType === 'REFERENCE_REVOKED' ? '撤销引用' : '新增引用' }}</span></td>
            <td class="mono">{{ c.effectiveMonth }}</td>
            <td class="muted" style="white-space:normal;max-width:360px">{{ c.detail }}</td>
            <td class="muted">{{ String(c.requestedAt).slice(0, 16) }}</td>
          </tr>
          <tr v-if="!changes.length"><td colspan="6" class="muted">暂无变更流水。</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 读取量账 -->
    <div class="card">
      <h2>读取量账（读取服务费按项目读取 GB 直接归属，不分摊）</h2>
      <div class="toolbar">
        <select v-model="readForm.projectCode" style="width:130px">
          <option>PROJ-A</option><option>PROJ-B</option>
        </select>
        <input v-model.number="readForm.readGb" type="number" min="0" step="0.001" style="width:130px" placeholder="读取 GB" />
        <input v-model="readForm.note" style="width:260px" placeholder="备注（可选）" />
        <button class="btn btn-sm" @click="saveRead">录入/覆盖 {{ month }} 读取量</button>
        <span class="muted" style="font-size:12px">录入后需重新“生成账单”才计入该账期</span>
      </div>
      <div class="table-wrap" style="max-height:280px">
        <table>
          <thead><tr><th>账期</th><th>项目</th><th>读取 GB</th><th>费率 元/GB</th><th>读取服务费</th><th>备注</th></tr></thead>
          <tbody>
          <tr v-for="r in readUsage" :key="r.billingMonth + r.projectCode">
            <td class="mono">{{ r.billingMonth }}</td><td>{{ r.projectCode }}</td>
            <td>{{ r.readGb }}</td><td>{{ r.readGbRate }}</td>
            <td><b>¥{{ fmt(r.readFee) }}</b></td>
            <td class="muted" style="white-space:normal;max-width:300px">{{ r.note }}</td>
          </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 字节时长账汇总 -->
    <div class="card">
      <h2>字节时长账（对象 × 天，byte_hours = bytes × 24）</h2>
      <div class="table-wrap" style="max-height:320px">
        <table>
          <thead><tr><th>对象</th><th>天数</th><th>月累计字节时长（byte·h）</th><th>月费率 元/GB</th><th>月存储费合计</th></tr></thead>
          <tbody>
          <tr v-for="g in byteHourSummary" :key="g.uid">
            <td class="mono">{{ g.uid }}</td><td>{{ g.days }}</td>
            <td class="mono">{{ g.byteHours.toLocaleString() }}</td>
            <td>{{ g.rate }}</td><td><b>¥{{ fmt(g.charge) }}</b></td>
          </tr>
          <tr v-if="!byteHourSummary.length"><td colspan="5" class="muted">暂无台账，请先生成账单。</td></tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import api from '../api'

const methods = ['REFERENCE_WEIGHT', 'SHAPLEY']
const month = ref('2026-11')
const method = ref('REFERENCE_WEIGHT')
const lastBill = ref(null)
const bills = ref([])
const reconciliation = ref([])
const snapshot = ref([])
const changes = ref([])
const readUsage = ref([])
const exclusiveLedger = ref([])
const byteHours = ref([])
const addForm = ref({ projectCode: 'PROJ-B', referenceCount: 1, effectiveMonth: '2026-12' })
const readForm = ref({ projectCode: 'PROJ-A', readGb: 120, note: '' })

const methodName = (m) => m === 'REFERENCE_WEIGHT' ? '有效引用权重' : 'Shapley 边际成本'
const fmt = (v) => Number(v).toFixed(2)
const nextMonth = computed(() => {
  const [y, m] = month.value.split('-').map(Number)
  const d = new Date(y, m, 1)
  return d.toISOString().slice(0, 7)
})

const visibleBills = computed(() => bills.value.filter(b => b.method === method.value))
const visibleTotal = computed(() => visibleBills.value.reduce((s, b) => s + Number(b.totalFee), 0))

const allocationGroups = computed(() => {
  const map = new Map()
  for (const a of (lastBill.value?.allocations || [])) {
    if (!map.has(a.uid)) {
      const obj = (lastBill.value.objects || []).find(o => o.uid === a.uid) || {}
      map.set(a.uid, { uid: a.uid, kind: obj.objectKind, gb: obj.gb, monthlyStorageFee: obj.monthlyStorageFee, rows: [] })
    }
    map.get(a.uid).rows.push(a)
  }
  return [...map.values()]
})

const byteHourSummary = computed(() => {
  const map = new Map()
  for (const r of byteHours.value) {
    if (!map.has(r.uid)) map.set(r.uid, { uid: r.uid, days: 0, byteHours: 0, rate: r.gbMonthRate, charge: 0 })
    const g = map.get(r.uid)
    g.days += 1
    g.byteHours += Number(r.byteHours)
    g.charge += Number(r.storageCharge)
  }
  return [...map.values()]
})

async function generate() {
  lastBill.value = (await api.post('/cost-share/billing/generate', { billingMonth: month.value })).data
  await reloadAll()
}
async function capture() {
  await api.post('/cost-share/snapshots/capture', { billingMonth: month.value })
  await loadSnapshot()
}
async function revoke(uid, projectCode) {
  if (!window.confirm(`确认撤销 ${projectCode} 对 ${uid} 的全部有效引用？${month.value} 快照不变，${nextMonth.value} 起退出联盟。`)) return
  await api.post('/cost-share/membership/revoke', { objectUid: uid, projectCode, effectiveMonth: nextMonth.value })
  await reloadAll()
}
async function addRef() {
  await api.post('/cost-share/membership/add', { ...addForm.value, objectUid: 'O1' })
  await reloadAll()
}
async function saveRead() {
  await api.post('/cost-share/read-usage', { billingMonth: month.value, ...readForm.value })
  await loadReadUsage()
  window.alert('读取量已录入，请重新生成账单以计入该账期。')
}
async function resetAll() {
  if (!window.confirm('确认重置为题设初始状态（A=3 分支、B=1 分支；读取 120/80GB）？将清除该场景全部账期产出。')) return
  await api.post('/cost-share/scenario/reset')
  month.value = '2026-11'
  await generate()
}

async function loadBills() {
  bills.value = (await api.get('/cost-share/billing', { params: { billingMonth: month.value } })).data
  reconciliation.value = (await api.get('/cost-share/reconciliation', { params: { billingMonth: month.value } })).data
}
async function loadSnapshot() {
  snapshot.value = (await api.get('/cost-share/snapshots', { params: { billingMonth: month.value } })).data
  changes.value = (await api.get('/cost-share/membership/changes')).data
}
async function loadReadUsage() {
  readUsage.value = (await api.get('/cost-share/read-usage', { params: { billingMonth: month.value } })).data
}
async function loadLedgers() {
  exclusiveLedger.value = (await api.get('/cost-share/ledger/exclusive', { params: { billingMonth: month.value } })).data
  byteHours.value = (await api.get('/cost-share/ledger/byte-hours', { params: { billingMonth: month.value } })).data
}
async function reloadAll() {
  await Promise.all([loadBills(), loadSnapshot(), loadReadUsage(), loadLedgers()])
}

onMounted(reloadAll)
</script>
