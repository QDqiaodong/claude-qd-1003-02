<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from '../router'
import api from '../api'

// ------------------------------------------------------------------
// 页面操作范式：糖仓堆叠块 + 卡片翻转看品质。
// 三个糖仓各是一块货位，批次按吨位画成高度不一的糖堆块摞在仓里，
// 块越高代表这批糖越多；点块会 3D 翻面，背面是这一批的品质三指标
// （record 组件里的糖度/纯度/纤维分），再点一下翻回来。
//
// 质量复检—发运锁定：新建批次先「待复检」，值班员录入复检时间 / 糖度 /
// 纯度 / 纤维分 / 结论（不通过必须留原因）；复检按批号 + 版本保存，
// 一次提交一个 requestId，重复点击 / 超时重试只落一份。最新结论通过
// 才解锁入库与发运，不通过则两个动作一起锁死；已入库 / 已发运后只能
// 追加新版本，不能回写旧结果，历史逐版保留。
// ------------------------------------------------------------------

const routeQuery = computed(() => router.currentRoute.value.query || {})
const batches = ref([])
const stock = ref([])
const error = ref('')
const tip = ref('')
const flipped = ref([])
const submitting = ref(false)
const reviewFilter = ref('')
const form = ref({
  batchNo: '', quantityKg: 30000, warehouse: '1号糖仓',
  quality: { brix: 99.8, purity: 99.6, fiber: 0 }
})

const WAREHOUSES = ['1号糖仓', '2号糖仓', '3号糖仓']
const REVIEW_FILTERS = ['', '待复检', '通过', '不通过']

// 复检录入：先选批号，版本号取接口返回的当前版本 + 1
const inspectingNo = ref('')
const inspectForm = ref(null)
const inspectHistory = ref([])
const requestId = ref(newRequestId())

function newRequestId() {
  return window.crypto && crypto.randomUUID
    ? crypto.randomUUID()
    : String(Date.now()) + '-' + Math.random().toString(16).slice(2)
}

const inspectingBatch = computed(() =>
  batches.value.find((b) => b.batchNo === inspectingNo.value) || null)

const byWh = computed(() => {
  const m = {}
  for (const w of WAREHOUSES) m[w] = batches.value.filter((b) => b.warehouse === w && b.status !== '已发运')
  return m
})

async function load() {
  error.value = ''
  try {
    const params = {}
    const q = routeQuery.value.q
    if (q) params.warehouse = q
    if (reviewFilter.value) params.reviewStatus = reviewFilter.value
    batches.value = await api.listBatches(params)
    if (!batches.value.length && q && !reviewFilter.value) batches.value = await api.listBatches()
    stock.value = await api.stockByWarehouse()
    // 复检面板正开着：同步最新版本号 / 结论，保证刷新后与接口一致
    if (inspectingBatch.value) syncInspectForm(inspectingBatch.value)
  } catch (e) { error.value = e.message }
}

function fmtTime(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 16)
}

function nowLocal() {
  const d = new Date(Date.now() - new Date().getTimezoneOffset() * 60000)
  return d.toISOString().slice(0, 16)
}

function flip(id) {
  const i = flipped.value.indexOf(id)
  if (i >= 0) flipped.value.splice(i, 1)
  else flipped.value.push(id)
}

async function create() {
  error.value = ''; tip.value = ''
  try {
    const b = await api.createBatch(form.value)
    tip.value = `批次 ${b.batchNo} 已建立，当前「待复检」，请录入质量复检`
    form.value.batchNo = ''
    await load()
  } catch (e) { error.value = e.message }
}

// ------------------------------------------------------------------
// 质量复检
// ------------------------------------------------------------------

async function openInspect(b) {
  error.value = ''; tip.value = ''
  inspectingNo.value = b.batchNo
  syncInspectForm(b)
  try {
    inspectHistory.value = await api.reinspections(b.id)
  } catch (e) {
    inspectHistory.value = []
    error.value = e.message
  }
}

function syncInspectForm(b) {
  const nextVersion = (b.reviewVersion || 0) + 1
  inspectForm.value = {
    version: nextVersion,
    inspectTime: nowLocal(),
    quality: {
      brix: b.quality ? Number(b.quality.brix) : 99.8,
      purity: b.quality ? Number(b.quality.purity) : 99.6,
      fiber: b.quality ? Number(b.quality.fiber) : 0
    },
    result: '通过',
    reason: ''
  }
  requestId.value = newRequestId()
}

function closeInspect() {
  inspectingNo.value = ''
  inspectForm.value = null
  inspectHistory.value = []
  requestId.value = newRequestId()
}

const inspectFingerprint = computed(() => {
  const f = inspectForm.value
  if (!f) return ''
  return [f.version, f.inspectTime, f.quality.brix, f.quality.purity, f.quality.fiber, f.result, f.reason].join('|')
})

// 录入内容变动即视为新的提交意图，换新幂等键；提交中不换，失败后原样重试仍带同一键
watch(inspectFingerprint, () => {
  if (inspectForm.value && !submitting.value) requestId.value = newRequestId()
})

async function submitInspect() {
  error.value = ''; tip.value = ''
  const b = inspectingBatch.value
  const f = inspectForm.value
  if (!b || !f) return
  submitting.value = true
  try {
    const r = await api.reinspectBatch(b.id, {
      version: f.version,
      inspectTime: f.inspectTime.length === 16 ? f.inspectTime + ':00' : f.inspectTime,
      quality: {
        brix: Number(f.quality.brix),
        purity: Number(f.quality.purity),
        fiber: Number(f.quality.fiber)
      },
      result: f.result,
      reason: f.result === '不通过' ? f.reason : '',
      requestId: requestId.value
    })
    tip.value = r.result === '通过'
      ? `${b.batchNo} 第 ${r.version} 版复检通过，入库 / 发运已解锁`
      : `${b.batchNo} 第 ${r.version} 版复检不通过，入库 / 发运已锁定`
    await load()
    inspectHistory.value = await api.reinspections(b.id)
    // 成功后才换新请求键，版本号 / 表单进入下一版；失败保留原键以便幂等重试
    syncInspectForm(inspectingBatch.value)
  } catch (e) {
    // 网络 / 超时类失败：后端单事务保证不落半成品，同一 requestId 重试不会重复落记录
    if (/timeout|Network Error/.test(e.message)) {
      error.value = e.message + '；如不确定是否提交成功，可直接重试（同一请求幂等，不会重复落记录），或刷新列表确认'
    } else {
      error.value = e.message
    }
  } finally { submitting.value = false }
}

async function store(b) {
  error.value = ''; tip.value = ''
  try {
    await api.storeBatch(b.id)
    tip.value = `${b.batchNo} 已入库`
    await load()
  } catch (e) { error.value = e.message }
}

async function ship(b) {
  error.value = ''; tip.value = ''
  try {
    await api.shipBatch(b.id)
    tip.value = `${b.batchNo} 已发运`
    await load()
  } catch (e) { error.value = e.message }
}

const maxKg = computed(() => {
  let m = 1
  for (const b of batches.value) m = Math.max(m, Number(b.quantityKg))
  return m
})

function stackH(b) {
  return Math.max(42, (Number(b.quantityKg) / maxKg.value) * 120) + 'px'
}

// 糖堆配色：待复检（未检 / 最新不通过）琥珀，可发运（在仓且通过）柠檬，已发运灰
function stackClass(b) {
  return {
    flip: flipped.includes(b.id),
    wait: b.reviewStatus === '待复检',
    pass: b.reviewStatus === '通过' && b.status !== '已发运',
    gone: b.status === '已发运'
  }
}

function countReview(s) {
  return batches.value.filter((b) => b.reviewStatus === s).length
}

watch(() => routeQuery.value.q, () => load())
watch(reviewFilter, () => load())
onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">成品糖 · 糖仓堆叠</span>
        <span class="panel-desc">块高按吨位归一；点块翻面看品质三指标；琥珀待复检、柠檬可发运、灰色已发运</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">批次</div><div class="v">{{ batches.length }}</div></div>
        <div class="stat warn"><div class="k">待复检</div><div class="v">{{ countReview('待复检') }}</div></div>
        <div class="stat ok"><div class="k">复检通过</div><div class="v">{{ countReview('通过') }}</div></div>
        <div class="stat bad"><div class="k">复检不通过</div><div class="v">{{ countReview('不通过') }}</div></div>
        <div class="stat"><div class="k">已发运</div><div class="v">{{ batches.filter(b => b.status === '已发运').length }}</div></div>
      </div>

      <div class="filter-row">
        <span class="flt-k">复检状态</span>
        <button
          v-for="f in REVIEW_FILTERS"
          :key="f || 'all'"
          class="chip-flt"
          :class="{ on: reviewFilter === f }"
          @click="reviewFilter = f"
        >{{ f || '全部' }}</button>
      </div>

      <div class="wh-row">
        <div v-for="w in WAREHOUSES" :key="w" class="wh">
          <div class="wh-head">
            {{ w }}
            <span>{{ (byWh[w] || []).length }} 批在仓</span>
          </div>
          <div class="wh-body">
            <div
              v-for="b in (byWh[w] || [])"
              :key="b.id"
              class="stack"
              :class="stackClass(b)"
              :style="{ height: stackH(b) }"
              @click="flip(b.id)"
            >
              <div class="face front">
                <div class="s-no">{{ b.batchNo }}</div>
                <div class="s-grade">{{ b.grade }}</div>
                <div class="s-q">{{ b.quantityTon }}t</div>
                <div class="s-rv" :class="'rv-' + b.reviewStatus">{{ b.reviewStatus }}</div>
              </div>
              <div class="face back">
                <div class="b-t">品质指标（建批）</div>
                <div class="b-i">糖度 {{ b.quality.brix }}°</div>
                <div class="b-i">纯度 {{ b.quality.purity }}%</div>
                <div class="b-i">纤维 {{ b.quality.fiber }}%</div>
                <div class="b-t" style="margin-top:4px">复检 {{ b.reviewStatus }}</div>
                <div class="b-i">最近 {{ fmtTime(b.lastInspectTime) }}</div>
              </div>
            </div>
            <div v-if="!(byWh[w] || []).length" class="wh-empty">空仓</div>
          </div>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">糖仓汇总 · 复检口径</span>
        <span class="panel-desc">待复检（未检 / 最新不通过）、可发运（最新通过在仓）、已发运分别计数</span>
      </div>
      <div class="stock-grid">
        <div class="stock-head"><span>糖仓</span><span>待复检</span><span>可发运</span><span>已发运</span><span>在仓合计</span></div>
        <div v-for="s in stock" :key="s.warehouse" class="stock-row">
          <span class="sw">{{ s.warehouse }}</span>
          <span class="cell wait-c">{{ s.waitingBatches }} 批 / {{ s.waitingTon }} t</span>
          <span class="cell ok-c">{{ s.shippableBatches }} 批 / {{ s.shippableTon }} t</span>
          <span class="cell gone-c">{{ s.shippedBatches }} 批 / {{ s.shippedTon }} t</span>
          <span class="cell">{{ s.batches }} 批 / {{ s.quantityTon }} t</span>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">批次流转 · 质量复检</span>
        <span class="panel-desc">待复检 → 已入库 → 已发运；最新复检通过才解锁入库 / 发运，不通过按钮禁用并给出原因</span>
      </div>
      <div class="bl">
        <div v-for="b in batches" :key="b.id" class="bl-row">
          <span class="bl-no">{{ b.batchNo }}</span>
          <span class="bl-g">{{ b.grade }}</span>
          <span class="bl-q">{{ b.quantityKg }}kg</span>
          <span class="bl-st" :class="'rv-tag-' + b.reviewStatus">{{ b.reviewStatus }}</span>
          <span class="bl-time">复检 {{ fmtTime(b.lastInspectTime) }}</span>
          <span class="bl-ship" :class="{ off: !b.shippable }">
            发运资格：{{ b.status === '已发运' ? '已发运' : (b.shippable ? '可发运' : '锁定') }}
            <em v-if="b.shipLockReason && b.status !== '已发运'">（{{ b.shipLockReason }}）</em>
          </span>
          <span class="bl-actions">
            <button class="act sm" @click="openInspect(b)">
              {{ b.reviewVersion ? '重新复检 v' + (b.reviewVersion + 1) : '复检' }}
            </button>
            <button
              class="act sm"
              :disabled="b.status !== '待复检' || b.reviewStatus !== '通过'"
              :title="b.status === '待复检' && b.reviewStatus !== '通过' ? (b.shipLockReason || '') : ''"
              @click="store(b)"
            >入库</button>
            <button
              class="act sm primary"
              :disabled="!b.shippable"
              :title="!b.shippable ? (b.shipLockReason || '') : ''"
              @click="ship(b)"
            >发运</button>
          </span>
          <div v-if="b.reviewStatus === '不通过'" class="bl-reason">
            最新不通过（第 {{ b.reviewVersion }} 版）：{{ b.lastRejectReason || '未填原因' }}
          </div>
        </div>
        <div v-if="!batches.length" class="empty">暂无符合筛选条件的批次</div>
      </div>
    </div>

    <!-- 复检录入抽屉（同页内联面板） -->
    <div v-if="inspectForm && inspectingBatch" class="panel inspect-panel">
      <div class="panel-head">
        <span class="panel-title">质量复检 · {{ inspectingBatch.batchNo }}（第 {{ inspectForm.version }} 版）</span>
        <span class="panel-desc">
          当前状态「{{ inspectingBatch.status }}」、已有第 {{ inspectingBatch.reviewVersion || 0 }} 版；
          复检只追加不改写，已入库 / 已发运也只能落新版本，历史保留
        </span>
        <button class="act sm close" @click="closeInspect">收起</button>
      </div>

      <div class="form-row">
        <label class="lab">复检时间
          <input v-model="inspectForm.inspectTime" type="datetime-local" class="fld" step="1" />
        </label>
        <label class="lab">糖度°
          <input v-model.number="inspectForm.quality.brix" type="number" step="0.01" class="fld n" />
        </label>
        <label class="lab">纯度%
          <input v-model.number="inspectForm.quality.purity" type="number" step="0.01" class="fld n" />
        </label>
        <label class="lab">纤维分%
          <input v-model.number="inspectForm.quality.fiber" type="number" step="0.01" class="fld n" />
        </label>
        <label class="lab">结论
          <select v-model="inspectForm.result" class="fld">
            <option value="通过">通过</option>
            <option value="不通过">不通过</option>
          </select>
        </label>
      </div>
      <div class="form-row" v-if="inspectForm.result === '不通过'">
        <input v-model="inspectForm.reason" class="fld reason" placeholder="不通过原因（必填，如：色值偏高 / 水分超标）" />
      </div>
      <div class="form-row">
        <button class="act primary" :disabled="submitting || (inspectForm.result === '不通过' && !inspectForm.reason.trim())" @click="submitInspect">
          {{ submitting ? '提交中…' : '提交复检（v' + inspectForm.version + '）' }}
        </button>
        <span class="idemp-hint">重复点击 / 请求重试共用同一请求标识，只保留一份有效记录</span>
      </div>

      <div class="hist">
        <div class="hist-title">复检历史（版本倒序）</div>
        <div v-for="r in inspectHistory" :key="r.id" class="hist-row" :class="{ latest: r.latest }">
          <span class="h-v">v{{ r.version }}{{ r.latest ? ' ·最新' : '' }}</span>
          <span class="h-time">{{ fmtTime(r.inspectTime) }}</span>
          <span class="h-q">糖度 {{ r.brix }}° / 纯度 {{ r.purity }}% / 纤维 {{ r.quality.fiber }}%</span>
          <span class="h-r" :class="'rv-tag-' + r.result">{{ r.result }}</span>
          <span v-if="r.reason" class="h-reason">{{ r.reason }}</span>
        </div>
        <div v-if="!inspectHistory.length" class="empty">该批尚未复检，本版为首检</div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">新建批次</span>
        <span class="panel-desc">建立后即为「待复检」；纯度不足 99.5% 不能判白砂糖；单批上限 60000kg</span>
      </div>
      <div class="form-row">
        <input v-model="form.batchNo" class="fld" placeholder="批号" />
        <input v-model.number="form.quantityKg" class="fld n" placeholder="数量kg" />
        <select v-model="form.warehouse" class="fld">
          <option v-for="w in WAREHOUSES" :key="w" :value="w">{{ w }}</option>
        </select>
        <input v-model.number="form.quality.brix" class="fld n" placeholder="糖度" />
        <input v-model.number="form.quality.purity" class="fld n" placeholder="纯度" />
        <input v-model.number="form.quality.fiber" class="fld n" placeholder="纤维分" />
        <button class="act primary" @click="create">建立批次</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.wh-row { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.wh { border: 1px solid var(--line); border-radius: 9px; overflow: hidden; }
.wh-head {
  background: var(--primary-wash); color: var(--primary-deep);
  font-weight: 800; font-size: 13px; padding: 7px 10px;
  display: flex; justify-content: space-between;
}
.wh-head span { font-weight: 600; font-size: 12px; color: var(--primary-soft); }

.wh-body {
  display: flex; flex-direction: column-reverse; gap: 5px;
  padding: 10px; min-height: 150px; justify-content: flex-start; background: #fff;
}
.wh-empty { font-size: 12px; color: var(--muted); align-self: center; }

.stack {
  position: relative; cursor: pointer;
  transform-style: preserve-3d; transition: transform 0.45s;
  border-radius: 6px;
}
.stack.flip { transform: rotateY(180deg); }

.face {
  position: absolute; inset: 0; backface-visibility: hidden;
  border-radius: 6px; border: 1px solid #d9dfae;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 4px;
}
.face.front { background: linear-gradient(180deg, #e6eeb8, var(--primary-soft)); }
/* 待复检（未检 / 最新不通过）：琥珀 */
.stack.wait .face.front { background: linear-gradient(180deg, #fff9c4, #ffd54f); }
/* 复检通过在仓：深柠檬绿 */
.stack.pass .face.front { background: linear-gradient(180deg, #dcedc8, #aed581); }
.face.back { background: var(--primary-deep); color: #fff; transform: rotateY(180deg); }

.s-no { font-size: 10px; font-weight: 800; color: #4a5008; }
.s-grade { font-size: 11px; font-weight: 700; }
.s-q { font-size: 10px; opacity: 0.85; }
.s-rv { font-size: 9px; font-weight: 800; padding: 0 5px; border-radius: 7px; margin-top: 1px; }
.s-rv.rv-待复检 { background: #ffe082; color: #e65100; }
.s-rv.rv-通过 { background: #c5e1a5; color: #33691e; }
.s-rv.rv-不通过 { background: #ef9a9a; color: #b71c1c; }
.b-t { font-size: 9px; opacity: 0.8; }
.b-i { font-size: 10px; }

.filter-row { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.flt-k { font-size: 12px; color: var(--muted); font-weight: 700; }
.chip-flt {
  border: 1px solid var(--line); background: #fff; border-radius: 16px;
  padding: 4px 14px; font-size: 12px; cursor: pointer; color: var(--ink);
}
.chip-flt.on { background: var(--primary); border-color: var(--primary); color: #fff; font-weight: 700; }

.stat.warn { background: #fff8e1; border-color: #ffe082; }
.stat.warn .k, .stat.warn .v { color: #ef6c00; }
.stat.ok { background: #f1f8e9; border-color: #c5e1a5; }
.stat.ok .k, .stat.ok .v { color: #33691e; }
.stat.bad { background: #ffebee; border-color: #ef9a9a; }
.stat.bad .k, .stat.bad .v { color: #b71c1c; }

.stock-grid { border: 1px solid var(--line); border-radius: 8px; overflow: hidden; }
.stock-head, .stock-row {
  display: grid; grid-template-columns: 1.1fr 1.2fr 1.2fr 1.2fr 1.2fr;
  align-items: center; font-size: 12px; padding: 8px 10px;
}
.stock-head { background: var(--primary-wash); color: var(--primary-deep); font-weight: 800; }
.stock-row { border-top: 1px solid var(--line); }
.stock-row .sw { font-weight: 800; color: var(--primary-deep); }
.cell.wait-c { color: #ef6c00; }
.cell.ok-c { color: #33691e; font-weight: 700; }
.cell.gone-c { color: var(--muted); }

.bl { display: flex; flex-direction: column; gap: 6px; }
.bl-row {
  display: flex; align-items: center; gap: 8px; padding: 7px 10px;
  border: 1px solid var(--line); border-radius: 8px; font-size: 12px; flex-wrap: wrap;
}
.bl-no { font-weight: 800; color: var(--primary-deep); width: 104px; }
.bl-g { color: var(--muted); width: 58px; }
.bl-q { color: var(--muted); width: 82px; }
.bl-time { color: var(--muted); width: 138px; }
.bl-st { font-size: 11px; font-weight: 800; padding: 1px 8px; border-radius: 9px; width: 60px; text-align: center; }
.rv-tag-待复检 { background: #ffe082; color: #e65100; }
.rv-tag-通过 { background: #c5e1a5; color: #33691e; }
.rv-tag-不通过 { background: #ef9a9a; color: #b71c1c; }
.bl-ship { font-size: 11px; color: #33691e; font-weight: 700; }
.bl-ship.off { color: #b71c1c; }
.bl-ship em { font-style: normal; font-weight: 400; color: var(--muted); }
.bl-actions { margin-left: auto; display: flex; gap: 6px; }
.bl-reason {
  flex-basis: 100%; font-size: 11px; color: #b71c1c;
  background: #ffebee; border-radius: 6px; padding: 4px 8px;
}

.inspect-panel { border: 2px solid var(--primary-soft); box-shadow: 0 4px 14px rgba(175,180,43,0.18); }
.inspect-panel .close { margin-left: auto; }
.lab { display: flex; flex-direction: column; gap: 3px; font-size: 11px; color: var(--muted); font-weight: 700; }
.lab .fld.n { width: 110px; }
.fld.reason { flex: 1; min-width: 320px; border-color: #ef9a9a; }
.idemp-hint { font-size: 11px; color: var(--muted); }

.hist { margin-top: 10px; border-top: 1px dashed var(--line); padding-top: 8px; }
.hist-title { font-size: 12px; font-weight: 800; color: var(--primary-deep); margin-bottom: 6px; }
.hist-row {
  display: flex; align-items: center; gap: 10px; font-size: 11px;
  padding: 4px 8px; border-radius: 6px; color: var(--muted); flex-wrap: wrap;
}
.hist-row.latest { background: var(--primary-wash); }
.h-v { font-weight: 800; color: var(--primary-deep); width: 72px; }
.h-r { font-size: 10px; font-weight: 800; padding: 0 7px; border-radius: 8px; }
.h-reason { color: #b71c1c; flex-basis: 100%; padding-left: 82px; }
.empty { font-size: 12px; color: var(--muted); padding: 8px 2px; }

.form-row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin-bottom: 8px; }
</style>
