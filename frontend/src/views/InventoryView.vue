<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { gateway } from '../api'
import type { Inventory } from '../api/types'
import AppIcon from '../components/AppIcon.vue'
import EmptyState from '../components/EmptyState.vue'
import UiModal from '../components/UiModal.vue'
import { errorMessage, formatDate, newRequestKey, numberFormatter } from '../domain/format'
import { useSession } from '../session'

const emit = defineEmits<{ notify: [message: string, tone?: string] }>()
const { session, canWrite } = useSession()
const items = ref<Inventory[]>([])
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const search = ref('')
const modal = ref<'create' | 'adjust' | null>(null)
const form = reactive({ sku: '', initialStock: 0, delta: 1, reason: '', key: '' })
const filtered = computed(() => {
  const query = search.value.trim().toLowerCase()
  return items.value.filter((item) => !query || item.sku.toLowerCase().includes(query))
})
const totals = computed(() => items.value.reduce((sum, item) => ({
  available: sum.available + item.available,
  reserved: sum.reserved + item.reserved,
}), { available: 0, reserved: 0 }))

async function load(): Promise<void> {
  const active = session.value
  if (!active) return
  loading.value = true
  error.value = ''
  try {
    items.value = await gateway.listInventory(active)
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  Object.assign(form, { sku: '', initialStock: 0, delta: 1, reason: '', key: newRequestKey('inv-create') })
  modal.value = 'create'
}

function openAdjust(item: Inventory): void {
  Object.assign(form, { sku: item.sku, initialStock: item.available, delta: 1, reason: '', key: newRequestKey('inv-adjust') })
  modal.value = 'adjust'
}

async function submit(): Promise<void> {
  const active = session.value
  if (!active || saving.value) return
  saving.value = true
  error.value = ''
  try {
    const result = modal.value === 'create'
      ? await gateway.createInventory(active, { sku: form.sku.trim(), initialStock: form.initialStock, reason: form.reason }, form.key)
      : await gateway.adjustInventory(active, form.sku, { delta: form.delta, reason: form.reason }, form.key)
    emit('notify', result.replayed ? '命中已有回执，库存未重复变化' : `库存命令已提交 · 可售 ${result.value.resultingAvailable}`, result.replayed ? 'info' : 'success')
    modal.value = null
    await load()
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    saving.value = false
  }
}

function availableRatio(item: Inventory): number {
  const total = item.available + item.reserved
  return total === 0 ? 0 : Math.round(item.available / total * 100)
}

onMounted(load)
</script>

<template>
  <div class="page-stack">
    <header class="page-header">
      <div><span class="eyebrow">INVENTORY / DOUBLE LEDGER</span><h1>库存台账</h1><p>可售与预占分别记账，所有修正都携带原因和唯一回执。</p></div>
      <button v-if="canWrite" class="button button-primary" @click="openCreate"><AppIcon name="plus" />新建 SKU</button>
    </header>

    <div class="summary-strip">
      <div><span>SKU 总数</span><strong>{{ items.length }}</strong></div><i />
      <div><span>可售合计</span><strong>{{ numberFormatter.format(totals.available) }}</strong></div><i />
      <div><span>预占合计</span><strong>{{ numberFormatter.format(totals.reserved) }}</strong></div><i />
      <div class="summary-note"><AppIcon name="shield" /><span>容量上限 / SKU <b>100,000,000</b></span></div>
    </div>

    <div class="content-grid inventory-grid">
      <section class="panel">
        <header class="panel-header table-toolbar">
          <div class="search-box"><AppIcon name="search" /><input v-model="search" aria-label="搜索 SKU" placeholder="搜索 SKU…" /></div>
          <button class="icon-button" aria-label="刷新库存" :disabled="loading" @click="load"><AppIcon name="refresh" :class="{ spinning: loading }" /></button>
        </header>
        <p v-if="error && !modal" class="inline-error" role="alert"><AppIcon name="warning" />{{ error }}<button @click="load">重试</button></p>
        <div v-if="loading" class="table-loading"><span v-for="n in 5" :key="n" /></div>
        <EmptyState v-else-if="!filtered.length" title="没有匹配的 SKU" description="调整搜索条件，或创建一条新的库存台账。" icon="inventory">
          <button v-if="canWrite" class="button button-secondary" @click="openCreate"><AppIcon name="plus" />新建 SKU</button>
        </EmptyState>
        <div v-else class="table-wrap">
          <table class="inventory-table"><thead><tr><th>SKU / 版本</th><th>库存构成</th><th class="numeric">可售</th><th class="numeric">预占</th><th>最后更新</th><th v-if="canWrite" class="action-column">操作</th></tr></thead>
            <tbody><tr v-for="item in filtered" :key="item.sku">
              <td><strong class="mono sku-name">{{ item.sku }}</strong><small class="cell-subtitle">VERSION {{ String(item.version).padStart(3, '0') }}</small></td>
              <td><div class="stock-composition"><div class="stock-bar"><span :style="{ width: `${availableRatio(item)}%` }" /></div><small>{{ availableRatio(item) }}% 可售 · 总量 {{ numberFormatter.format(item.available + item.reserved) }}</small></div></td>
              <td class="numeric"><strong class="stock-number">{{ numberFormatter.format(item.available) }}</strong></td>
              <td class="numeric"><span class="reserved-number">{{ numberFormatter.format(item.reserved) }}</span></td>
              <td class="muted mono">{{ formatDate(item.updatedAt) }}</td>
              <td v-if="canWrite" class="action-column"><button class="button button-small button-secondary" @click="openAdjust(item)">调整</button></td>
            </tr></tbody>
          </table>
        </div>
        <footer class="panel-footer"><span>显示 {{ filtered.length }} / {{ items.length }} 条台账</span><span class="mono">SOURCE: MYSQL</span></footer>
      </section>

      <aside class="panel ledger-panel">
        <header class="panel-header"><div><span class="eyebrow">LEDGER RULE</span><h2>库存守恒</h2></div><span class="tag">INV-02</span></header>
        <div class="ledger-equation"><span>AVAILABLE</span><b>+</b><span>RESERVED</span></div>
        <p>下单只把数量从可售移到预占，不直接销毁库存。进入终态时才决定释放或核销。</p>
        <div class="ledger-flow"><div><span class="ledger-dot green" />预占<small>available − q<br />reserved + q</small></div><div><span class="ledger-dot amber" />取消<small>available + q<br />reserved − q</small></div><div><span class="ledger-dot cyan" />确认<small>available 不变<br />reserved − q</small></div></div>
        <div class="rule-callout"><AppIcon name="warning" /><p><strong>调整只改变可售台账</strong>已预占数量不会被手工调整覆盖；如修正会导致负数，事务整体拒绝。</p></div>
        <div class="mini-contract"><span>WRITE</span><code>POST /inventory/{sku}/adjustments</code><span>PROOF</span><code>InventoryOperation + AuditEvent</code></div>
      </aside>
    </div>

    <UiModal v-if="modal" :title="modal === 'create' ? '创建库存台账' : `调整 ${form.sku}`" :eyebrow="modal === 'create' ? 'INVENTORY / CREATE' : 'INVENTORY / ADJUST'" @close="modal = null">
      <form id="inventory-command" class="command-form" @submit.prevent="submit">
        <label v-if="modal === 'create'" class="field-label" for="inventory-sku">SKU 标识</label>
        <input v-if="modal === 'create'" id="inventory-sku" v-model="form.sku" maxlength="64" placeholder="例如 SKU-CORE-01" required />
        <div v-if="modal === 'create'" class="field"><label class="field-label" for="initial-stock">初始可售库存</label><input id="initial-stock" v-model.number="form.initialStock" type="number" min="0" max="100000000" required /></div>
        <div v-else class="adjustment-control"><button type="button" :class="{ active: form.delta < 0 }" @click="form.delta = -Math.abs(form.delta || 1)">减少</button><input v-model.number="form.delta" aria-label="库存调整量" type="number" min="-100000000" max="100000000" required /><button type="button" :class="{ active: form.delta > 0 }" @click="form.delta = Math.abs(form.delta || 1)">增加</button></div>
        <p v-if="modal === 'adjust'" class="field-hint">当前可售 {{ form.initialStock }} · 调整后 {{ form.initialStock + form.delta }}（预占不变）</p>
        <div class="field"><label class="field-label" for="inventory-reason">审计原因 <span>必填</span></label><textarea id="inventory-reason" v-model="form.reason" rows="3" maxlength="200" placeholder="说明业务依据，例如：季度盘点差异修正" required /></div>
        <div class="idempotency-field"><div><label class="field-label">Idempotency-Key</label><code>{{ form.key }}</code></div><button type="button" class="icon-button" aria-label="重新生成幂等键" @click="form.key = newRequestKey(modal === 'create' ? 'inv-create' : 'inv-adjust')"><AppIcon name="refresh" /></button></div>
        <p class="field-hint">网络超时时使用同一个键重试；更改内容后必须生成新键。</p>
        <p v-if="error" class="form-error" role="alert"><AppIcon name="warning" />{{ error }}</p>
      </form>
      <template #footer><button class="button button-secondary" @click="modal = null">取消</button><button class="button button-primary" form="inventory-command" type="submit" :disabled="saving || !form.reason.trim()">{{ saving ? '正在提交事务…' : '提交并写入审计' }}</button></template>
    </UiModal>
  </div>
</template>
