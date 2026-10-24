<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { gateway } from '../api'
import type { Inventory, Order, OrderStatus } from '../api/types'
import AppIcon from '../components/AppIcon.vue'
import EmptyState from '../components/EmptyState.vue'
import StatusBadge from '../components/StatusBadge.vue'
import UiModal from '../components/UiModal.vue'
import { errorMessage, formatDate, newRequestKey, shortId } from '../domain/format'
import { useSession } from '../session'

const emit = defineEmits<{
  notify: [message: string, tone?: string]
  audit: [id: string]
}>()
const { session, canWrite, canAudit } = useSession()
const orders = ref<Order[]>([])
const inventory = ref<Inventory[]>([])
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const inventoryError = ref('')
const nextCursor = ref<string | null>(null)
const cursor = ref<string | undefined>()
const cursorStack = ref<string[]>([])
const filters = reactive<{ status: '' | OrderStatus; sku: string; limit: number }>({ status: '', sku: '', limit: 20 })
const placeModal = ref(false)
const placeForm = reactive({ sku: '', quantity: 1, key: '' })
const transitionOrder = ref<Order | null>(null)
const transitionTarget = ref<'CONFIRMED' | 'CANCELLED'>('CONFIRMED')
const transitionForm = reactive({ reason: '', key: '', quantity: 1 })
const rescheduleOrder = ref<Order | null>(null)
const rescheduleForm = reactive({ expiresAtLocal: '', reason: '', key: '' })
let loadSequence = 0

function toLocalInputValue(iso: string): string {
  const ms = Date.parse(iso)
  if (Number.isNaN(ms)) return ''
  const d = new Date(ms)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function fromLocalInputValue(local: string): string {
  const ms = Date.parse(local)
  if (Number.isNaN(ms)) throw new Error('过期时间无效')
  return new Date(ms).toISOString()
}

async function load(): Promise<void> {
  const active = session.value
  if (!active) return
  const sequence = ++loadSequence
  loading.value = true
  error.value = ''
  try {
    const page = await gateway.listOrders(active, {
      status: filters.status || undefined,
      sku: filters.sku || undefined,
      limit: filters.limit,
      cursor: cursor.value,
    })
    if (sequence !== loadSequence) return
    orders.value = page.items
    nextCursor.value = page.nextCursor
  } catch (cause) {
    if (sequence === loadSequence) error.value = errorMessage(cause)
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

async function applyFilters(): Promise<void> {
  cursor.value = undefined
  cursorStack.value = []
  await load()
}

async function nextPage(): Promise<void> {
  if (!nextCursor.value) return
  cursorStack.value.push(cursor.value ?? '')
  cursor.value = nextCursor.value
  await load()
}

async function previousPage(): Promise<void> {
  if (!cursorStack.value.length) return
  const previous = cursorStack.value.pop()
  cursor.value = previous || undefined
  await load()
}

function openPlace(): void {
  Object.assign(placeForm, { sku: inventory.value.find((item) => item.available > 0)?.sku ?? inventory.value[0]?.sku ?? '', quantity: 1, key: newRequestKey('order') })
  error.value = ''
  placeModal.value = true
}

async function submitPlace(): Promise<void> {
  const active = session.value
  if (!active || saving.value) return
  saving.value = true
  error.value = ''
  try {
    const result = await gateway.placeOrder(active, { sku: placeForm.sku, quantity: placeForm.quantity }, placeForm.key)
    emit('notify', result.replayed ? `重放订单 ${shortId(result.value.id)}，未重复预占` : `订单 ${shortId(result.value.id)} 已完成预占`, result.replayed ? 'info' : 'success')
    placeModal.value = false
    await applyFilters()
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    saving.value = false
  }
}

function openTransition(order: Order, target: 'CONFIRMED' | 'CANCELLED'): void {
  transitionOrder.value = order
  transitionTarget.value = target
  Object.assign(transitionForm, {
    reason: '',
    key: newRequestKey(target === 'CONFIRMED' ? 'confirm' : 'cancel'),
    quantity: order.quantity,
  })
  error.value = ''
}

async function submitTransition(): Promise<void> {
  const active = session.value
  const order = transitionOrder.value
  if (!active || !order || saving.value) return
  saving.value = true
  error.value = ''
  try {
    const quantity = transitionTarget.value === 'CONFIRMED' ? transitionForm.quantity : undefined
    const result = await gateway.transitionOrder(active, order.id, transitionTarget.value, transitionForm.reason, transitionForm.key, quantity)
    const partial = transitionTarget.value === 'CONFIRMED' && result.value.status === 'RESERVED'
    emit('notify', result.replayed
      ? '命中已有迁移回执，未重复改变库存'
      : (partial
        ? `已部分确认 ${quantity}，订单仍预占 ${result.value.quantity}`
        : `订单已${transitionTarget.value === 'CONFIRMED' ? '确认并核销预占' : '取消并释放预占'}`),
    result.replayed ? 'info' : 'success')
    transitionOrder.value = null
    await load()
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    saving.value = false
  }
}

function openReschedule(order: Order): void {
  rescheduleOrder.value = order
  Object.assign(rescheduleForm, {
    expiresAtLocal: toLocalInputValue(order.expiresAt ?? new Date(Date.now() + 30 * 60_000).toISOString()),
    reason: '',
    key: newRequestKey('reschedule'),
  })
  error.value = ''
}

async function submitReschedule(): Promise<void> {
  const active = session.value
  const order = rescheduleOrder.value
  if (!active || !order || saving.value) return
  saving.value = true
  error.value = ''
  try {
    const expiresAt = fromLocalInputValue(rescheduleForm.expiresAtLocal)
    const result = await gateway.rescheduleOrder(active, order.id, expiresAt, rescheduleForm.reason, rescheduleForm.key)
    emit('notify', result.replayed ? '命中已有改期回执' : `预留过期已改为 ${formatDate(result.value.expiresAt ?? expiresAt)}`,
      result.replayed ? 'info' : 'success')
    rescheduleOrder.value = null
    await load()
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    saving.value = false
  }
}

async function loadInventory(): Promise<void> {
  const active = session.value
  if (!active) return
  inventoryError.value = ''
  try {
    inventory.value = await gateway.listInventory(active)
  } catch (cause) {
    inventory.value = []
    inventoryError.value = errorMessage(cause)
  }
}

onMounted(async () => {
  await loadInventory()
  await load()
})
</script>

<template>
  <div class="page-stack">
    <header class="page-header">
      <div><span class="eyebrow">ORDERS / EXPLICIT LIFECYCLE</span><h1>订单生命周期</h1><p>预占、确认、取消都是可重放命令；状态只能沿允许的方向迁移。</p></div>
      <button v-if="canWrite" class="button button-primary" :disabled="!inventory.length" @click="openPlace"><AppIcon name="plus" />创建预占订单</button>
    </header>

    <p v-if="inventoryError" class="inline-error" role="alert"><AppIcon name="warning" />库存目录加载失败：{{ inventoryError }}<button @click="loadInventory">重试</button></p>
    <section class="panel filter-panel">
      <div class="filter-fields">
        <div class="field compact"><label class="field-label" for="order-status">订单状态</label><select id="order-status" v-model="filters.status"><option value="">全部状态</option><option value="RESERVED">已预占</option><option value="CONFIRMED">已确认</option><option value="CANCELLED">已取消</option></select></div>
        <div class="field compact"><label class="field-label" for="order-sku">库存 SKU</label><select id="order-sku" v-model="filters.sku"><option value="">全部 SKU</option><option v-for="item in inventory" :key="item.sku" :value="item.sku">{{ item.sku }}</option></select></div>
        <div class="field compact limit-field"><label class="field-label" for="order-limit">每页</label><select id="order-limit" v-model.number="filters.limit"><option :value="10">10</option><option :value="20">20</option><option :value="50">50</option></select></div>
        <button class="button button-secondary filter-submit" :disabled="loading" @click="applyFilters"><AppIcon name="search" />应用筛选</button>
      </div>
      <span class="keyset-note"><AppIcon name="key" :size="14" /> KEYSET · created_at + id</span>
    </section>

    <section class="panel orders-panel">
      <header class="panel-header table-toolbar"><div><span class="eyebrow">ORDER LEDGER</span><h2>订单记录 <span class="count-badge">{{ orders.length }}</span></h2></div><button class="icon-button" aria-label="刷新订单" :disabled="loading" @click="load"><AppIcon name="refresh" :class="{ spinning: loading }" /></button></header>
      <p v-if="error && !placeModal && !transitionOrder && !rescheduleOrder" class="inline-error" role="alert"><AppIcon name="warning" />{{ error }}<button @click="load">重试</button></p>
      <div v-if="loading" class="table-loading"><span v-for="n in 7" :key="n" /></div>
      <EmptyState v-else-if="!orders.length" title="当前筛选下没有订单" description="重置筛选，或创建一笔新的库存预占。" icon="orders">
        <button class="button button-secondary" @click="filters.status = ''; filters.sku = ''; applyFilters()">重置筛选</button>
      </EmptyState>
      <div v-else class="table-wrap">
        <table class="orders-table"><thead><tr><th>订单 / 请求键</th><th>SKU</th><th class="numeric">数量</th><th>状态</th><th>过期</th><th>创建时间</th><th class="action-column">操作</th></tr></thead>
          <tbody><tr v-for="order in orders" :key="order.id">
            <td><strong class="mono cell-id">{{ shortId(order.id) }}</strong><small class="cell-subtitle request-cell" :title="order.requestId">{{ order.requestId }}</small></td>
            <td class="mono sku-name">{{ order.sku }}</td><td class="numeric mono quantity-cell">{{ order.quantity }}</td>
            <td><StatusBadge :status="order.status" /><small class="cell-subtitle">V{{ order.version }}</small></td>
            <td class="muted mono">{{ order.expiresAt ? formatDate(order.expiresAt) : '—' }}</td>
            <td class="muted mono">{{ formatDate(order.createdAt) }}</td>
            <td class="action-column"><div class="row-actions">
              <template v-if="canWrite && order.status === 'RESERVED'"><button class="button button-small button-secondary" @click="openTransition(order, 'CONFIRMED')">确认</button><button class="button button-small button-secondary" @click="openReschedule(order)">改期</button><button class="button button-small button-danger-quiet" @click="openTransition(order, 'CANCELLED')">取消</button></template>
              <button v-if="canAudit" class="icon-button small" :aria-label="`审计订单 ${order.id}`" title="查看审计链" @click="emit('audit', order.id)"><AppIcon name="audit" :size="15" /></button>
              <span v-if="!canWrite && !canAudit" class="muted">只读</span>
            </div></td>
          </tr></tbody>
        </table>
      </div>
      <footer class="panel-footer pagination-footer"><span>游标分页 · 不受新增记录偏移影响</span><div><button class="button button-small button-secondary" :disabled="!cursorStack.length || loading" @click="previousPage">上一页</button><button class="button button-small button-secondary" :disabled="!nextCursor || loading" @click="nextPage">下一页<AppIcon name="arrow" :size="14" /></button></div></footer>
    </section>

    <UiModal v-if="placeModal" title="创建预占订单" eyebrow="ORDER / RESERVE" @close="placeModal = false">
      <form id="place-order-command" class="command-form" @submit.prevent="submitPlace">
        <div class="command-context"><AppIcon name="lock" /><p>事务将执行 <code>available − q</code> 与 <code>reserved + q</code>。库存不足时不会创建订单。</p></div>
        <div class="field"><label class="field-label" for="place-sku">库存 SKU</label><select id="place-sku" v-model="placeForm.sku" required><option v-for="item in inventory" :key="item.sku" :value="item.sku" :disabled="item.available < 1">{{ item.sku }} · 可售 {{ item.available }}</option></select></div>
        <div class="field"><label class="field-label" for="place-quantity">预占数量</label><input id="place-quantity" v-model.number="placeForm.quantity" type="number" min="1" max="10000" required /></div>
        <div class="idempotency-field"><div><label class="field-label">Idempotency-Key</label><code>{{ placeForm.key }}</code></div><button type="button" class="icon-button" aria-label="重新生成幂等键" @click="placeForm.key = newRequestKey('order')"><AppIcon name="refresh" /></button></div>
        <p class="field-hint">Redis 只互斥同一请求键；MySQL 唯一约束仍是最终幂等防线。</p>
        <p v-if="error" class="form-error" role="alert"><AppIcon name="warning" />{{ error }}</p>
      </form>
      <template #footer><button class="button button-secondary" @click="placeModal = false">取消</button><button class="button button-primary" form="place-order-command" type="submit" :disabled="saving || !placeForm.sku">{{ saving ? '正在执行事务…' : '执行库存预占' }}</button></template>
    </UiModal>

    <UiModal v-if="transitionOrder" :title="transitionTarget === 'CONFIRMED' ? '确认并核销预占' : '取消订单并释放预占'" :eyebrow="`ORDER / ${transitionTarget}`" @close="transitionOrder = null">
      <form id="transition-order-command" class="command-form" @submit.prevent="submitTransition">
        <div class="order-transition-summary"><div><span>订单</span><strong class="mono">{{ shortId(transitionOrder.id) }}</strong></div><div><span>SKU / 剩余</span><strong class="mono">{{ transitionOrder.sku }} × {{ transitionOrder.quantity }}</strong></div><StatusBadge status="RESERVED" /><AppIcon name="arrow" /><StatusBadge :status="transitionTarget === 'CONFIRMED' && transitionForm.quantity < transitionOrder.quantity ? 'RESERVED' : transitionTarget" /></div>
        <div class="command-context" :class="transitionTarget === 'CANCELLED' ? 'context-amber' : ''"><AppIcon :name="transitionTarget === 'CONFIRMED' ? 'check' : 'refresh'" /><p>{{ transitionTarget === 'CONFIRMED' ? '可部分确认：小于剩余数量时订单仍为 RESERVED 并缩量；available 不会回升。' : '从 reserved 减去全部剩余，并等额归还 available。' }}</p></div>
        <div v-if="transitionTarget === 'CONFIRMED'" class="field"><label class="field-label" for="transition-quantity">确认数量</label><input id="transition-quantity" v-model.number="transitionForm.quantity" type="number" min="1" :max="transitionOrder.quantity" required /><p class="field-hint">默认全量；小于 {{ transitionOrder.quantity }} 为部分确认（ADR 002）。</p></div>
        <div class="field"><label class="field-label" for="transition-reason">审计原因 <span>必填</span></label><textarea id="transition-reason" v-model="transitionForm.reason" rows="3" maxlength="200" :placeholder="transitionTarget === 'CONFIRMED' ? '例如：先发 2 件，其余继续预占' : '例如：客户主动取消，释放库存'" required /></div>
        <div class="idempotency-field"><div><label class="field-label">Idempotency-Key</label><code>{{ transitionForm.key }}</code></div><button type="button" class="icon-button" aria-label="重新生成幂等键" @click="transitionForm.key = newRequestKey(transitionTarget === 'CONFIRMED' ? 'confirm' : 'cancel')"><AppIcon name="refresh" /></button></div>
        <p v-if="error" class="form-error" role="alert"><AppIcon name="warning" />{{ error }}</p>
      </form>
      <template #footer><button class="button button-secondary" @click="transitionOrder = null">返回</button><button :class="['button', transitionTarget === 'CONFIRMED' ? 'button-primary' : 'button-danger']" form="transition-order-command" type="submit" :disabled="saving || !transitionForm.reason.trim() || (transitionTarget === 'CONFIRMED' && (!transitionForm.quantity || transitionForm.quantity < 1 || transitionForm.quantity > transitionOrder.quantity))">{{ saving ? '正在提交事务…' : transitionTarget === 'CONFIRMED' ? (transitionForm.quantity < transitionOrder.quantity ? '部分确认' : '确认并核销') : '取消并释放' }}</button></template>
    </UiModal>

    <UiModal v-if="rescheduleOrder" title="预留改期" eyebrow="ORDER / RESCHEDULE" @close="rescheduleOrder = null">
      <form id="reschedule-order-command" class="command-form" @submit.prevent="submitReschedule">
        <div class="order-transition-summary"><div><span>订单</span><strong class="mono">{{ shortId(rescheduleOrder.id) }}</strong></div><div><span>当前过期</span><strong class="mono">{{ rescheduleOrder.expiresAt ? formatDate(rescheduleOrder.expiresAt) : '—' }}</strong></div><StatusBadge status="RESERVED" /></div>
        <div class="command-context"><AppIcon name="refresh" /><p>仅未过期的 RESERVED 可改期；新时间须晚于现在且不超过预留 TTL。库存台账不变。</p></div>
        <div class="field"><label class="field-label" for="reschedule-expires">新过期时间</label><input id="reschedule-expires" v-model="rescheduleForm.expiresAtLocal" type="datetime-local" required /></div>
        <div class="field"><label class="field-label" for="reschedule-reason">审计原因 <span>必填</span></label><textarea id="reschedule-reason" v-model="rescheduleForm.reason" rows="3" maxlength="200" placeholder="例如：客户延期提货" required /></div>
        <div class="idempotency-field"><div><label class="field-label">Idempotency-Key</label><code>{{ rescheduleForm.key }}</code></div><button type="button" class="icon-button" aria-label="重新生成幂等键" @click="rescheduleForm.key = newRequestKey('reschedule')"><AppIcon name="refresh" /></button></div>
        <p v-if="error" class="form-error" role="alert"><AppIcon name="warning" />{{ error }}</p>
      </form>
      <template #footer><button class="button button-secondary" @click="rescheduleOrder = null">返回</button><button class="button button-primary" form="reschedule-order-command" type="submit" :disabled="saving || !rescheduleForm.reason.trim() || !rescheduleForm.expiresAtLocal">{{ saving ? '正在提交事务…' : '确认改期' }}</button></template>
    </UiModal>
  </div>
</template>
