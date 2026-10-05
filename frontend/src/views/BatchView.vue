<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from '../router'
import api from '../api'

// ------------------------------------------------------------------
// 页面操作范式：糖仓堆叠块 + 卡片翻转看品质。
// 三个糖仓各是一块货位，批次按吨位画成高度不一的糖堆块摞在仓里，
// 块越高代表这批糖越多；点块会 3D 翻面，背面是这一批的品质三指标
// （record 组件里的糖度/纯度/纤维分），再点一下翻回来。
// 入库 / 发运在块下方的操作条上完成。
// ------------------------------------------------------------------

const routeQuery = computed(() => router.currentRoute.value.query || {})
const batches = ref([])
const stock = ref([])
const error = ref('')
const tip = ref('')
const flipped = ref([])
const form = ref({
  batchNo: '', quantityKg: 30000, warehouse: '1号糖仓',
  quality: { brix: 99.8, purity: 99.6, fiber: 0 }
})

const WAREHOUSES = ['1号糖仓', '2号糖仓', '3号糖仓']
const byWh = computed(() => {
  const m = {}
  for (const w of WAREHOUSES) m[w] = batches.value.filter((b) => b.warehouse === w && b.status !== '已发运')
  return m
})

async function load() {
  error.value = ''
  try {
    const q = routeQuery.value.q
    batches.value = await api.listBatches(q ? { warehouse: q } : {})
    if (!batches.value.length && q) batches.value = await api.listBatches()
    stock.value = await api.stockByWarehouse()
  } catch (e) { error.value = e.message }
}

function flip(id) {
  const i = flipped.value.indexOf(id)
  if (i >= 0) flipped.value.splice(i, 1)
  else flipped.value.push(id)
}

async function create() {
  error.value = ''; tip.value = ''
  try {
    await api.createBatch(form.value)
    tip.value = `批次 ${form.value.batchNo} 已建立`
    form.value.batchNo = ''
    await load()
  } catch (e) { error.value = e.message }
}

async function store(id) {
  error.value = ''; tip.value = ''
  try {
    await api.storeBatch(id)
    tip.value = '已入库'
    await load()
  } catch (e) { error.value = e.message }
}

async function ship(id) {
  error.value = ''; tip.value = ''
  try {
    await api.shipBatch(id)
    tip.value = '已发运'
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

watch(() => routeQuery.value.q, () => load())
onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">成品糖 · 糖仓堆叠</span>
        <span class="panel-desc">块高按吨位归一；点块翻面看品质三指标</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">批次</div><div class="v">{{ batches.length }}</div></div>
        <div class="stat"><div class="k">待检</div><div class="v">{{ batches.filter(b => b.status === '待检').length }}</div></div>
        <div class="stat"><div class="k">已发运</div><div class="v">{{ batches.filter(b => b.status === '已发运').length }}</div></div>
      </div>

      <div class="wh-row">
        <div v-for="w in WAREHOUSES" :key="w" class="wh">
          <div class="wh-head">
            {{ w }}
            <span>{{ (byWh[w] || []).length }} 批</span>
          </div>
          <div class="wh-body">
            <div
              v-for="b in (byWh[w] || [])"
              :key="b.id"
              class="stack"
              :class="{ flip: flipped.includes(b.id), wait: b.status === '待检' }"
              :style="{ height: stackH(b) }"
              @click="flip(b.id)"
            >
              <div class="face front">
                <div class="s-no">{{ b.batchNo }}</div>
                <div class="s-grade">{{ b.grade }}</div>
                <div class="s-q">{{ b.quantityTon }}t</div>
              </div>
              <div class="face back">
                <div class="b-t">品质指标</div>
                <div class="b-i">糖度 {{ b.quality.brix }}°</div>
                <div class="b-i">纯度 {{ b.quality.purity }}%</div>
                <div class="b-i">纤维 {{ b.quality.fiber }}%</div>
              </div>
            </div>
            <div v-if="!(byWh[w] || []).length" class="wh-empty">空仓</div>
          </div>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">批次流转</span>
        <span class="panel-desc">待检 → 已入库 → 已发运，逐级推进</span>
      </div>
      <div class="bl">
        <div v-for="b in batches" :key="b.id" class="bl-row">
          <span class="bl-no">{{ b.batchNo }}</span>
          <span class="bl-g">{{ b.grade }}</span>
          <span class="bl-q">{{ b.quantityKg }}kg</span>
          <span class="bl-p">纯度 {{ b.quality.purity }}%</span>
          <span class="bl-st">{{ b.status }}</span>
          <button class="act sm" :disabled="b.status !== '待检'" @click="store(b.id)">入库</button>
          <button class="act sm" :disabled="b.status !== '已入库'" @click="ship(b.id)">发运</button>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">新建批次</span>
        <span class="panel-desc">纯度不足 99.5% 不能判白砂糖；单批上限 60000kg</span>
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
.stack.wait .face.front { background: linear-gradient(180deg, #fff9c4, #ffd54f); }
.face.back { background: var(--primary-deep); color: #fff; transform: rotateY(180deg); }

.s-no { font-size: 10px; font-weight: 800; color: #4a5008; }
.s-grade { font-size: 11px; font-weight: 700; }
.s-q { font-size: 10px; opacity: 0.85; }
.b-t { font-size: 9px; opacity: 0.8; }
.b-i { font-size: 10px; }

.bl { display: flex; flex-direction: column; gap: 5px; }
.bl-row { display: flex; align-items: center; gap: 8px; padding: 6px 10px; border: 1px solid var(--line); border-radius: 8px; font-size: 12px; }
.bl-no { font-weight: 800; color: var(--primary-deep); width: 108px; }
.bl-g, .bl-q, .bl-p, .bl-st { color: var(--muted); }
.bl-g { width: 62px; }
.bl-q { width: 86px; }
.bl-p { width: 92px; }
.bl-st { width: 62px; }

.form-row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
</style>
