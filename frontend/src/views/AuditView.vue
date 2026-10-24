<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { gateway } from '../api'
import type { AggregateType, AuditEvent, AuditVerification, Inventory, Order } from '../api/types'
import AppIcon from '../components/AppIcon.vue'
import EmptyState from '../components/EmptyState.vue'
import { actionLabel, errorMessage, formatFullDate, prettyPayload, shortHash } from '../domain/format'
import { useSession } from '../session'

const props = withDefaults(defineProps<{ initialOrderId?: string }>(), { initialOrderId: '' })
const emit = defineEmits<{ notify: [message: string, tone?: string] }>()
const { session, canAudit } = useSession()
const type = ref<AggregateType>('ORDER')
const aggregateId = ref('')
const events = ref<AuditEvent[]>([])
const orders = ref<Order[]>([])
const inventory = ref<Inventory[]>([])
const loading = ref(false)
const verifying = ref(false)
const loaded = ref(false)
const error = ref('')
const verification = ref<AuditVerification | null>(null)
const suggestions = computed(() => type.value === 'ORDER'
  ? orders.value.map((order) => order.id)
  : inventory.value.map((item) => item.sku))
const headHash = computed(() => events.value.at(-1)?.eventHash ?? '')

async function inspect(): Promise<void> {
  const active = session.value
  if (!active || !canAudit.value) return
  if (!aggregateId.value.trim()) {
    error.value = '请输入或选择聚合标识'
    return
  }
  loading.value = true
  loaded.value = false
  error.value = ''
  verification.value = null
  try {
    events.value = await gateway.auditEvents(active, type.value, aggregateId.value.trim())
    loaded.value = true
  } catch (cause) {
    error.value = errorMessage(cause)
    events.value = []
  } finally {
    loading.value = false
  }
}

async function verify(): Promise<void> {
  const active = session.value
  if (!active || !aggregateId.value.trim()) return
  verifying.value = true
  error.value = ''
  try {
    verification.value = await gateway.verifyAudit(active, type.value, aggregateId.value.trim())
    emit('notify', verification.value.valid ? `已验证 ${verification.value.checkedEvents} 个审计事件` : '审计链校验失败', verification.value.valid ? 'success' : 'error')
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    verifying.value = false
  }
}

function switchType(value: AggregateType): void {
  type.value = value
  aggregateId.value = ''
  events.value = []
  verification.value = null
  loaded.value = false
  error.value = ''
}

async function copyHash(hash: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(hash)
    emit('notify', '事件哈希已复制', 'info')
  } catch {
    emit('notify', '浏览器未允许复制，请手动选择哈希', 'error')
  }
}

watch(() => props.initialOrderId, (id) => {
  if (!id || !canAudit.value) return
  type.value = 'ORDER'
  aggregateId.value = id
  void inspect()
}, { immediate: true })

onMounted(async () => {
  const active = session.value
  if (!active || !canAudit.value) return
  try {
    const [orderPage, stock] = await Promise.all([
      gateway.listOrders(active, { limit: 100 }),
      gateway.listInventory(active),
    ])
    orders.value = orderPage.items
    inventory.value = stock
    if (!aggregateId.value && orderPage.items[0]) {
      aggregateId.value = orderPage.items[0].id
      await inspect()
    }
  } catch (cause) {
    error.value = errorMessage(cause)
  }
})
</script>

<template>
  <div class="page-stack">
    <header class="page-header">
      <div><span class="eyebrow">AUDIT / AGGREGATE CHAINS</span><h1>审计验证</h1><p>按聚合独立分链，逐条验证顺序、前序哈希与 SHA-256 内容摘要。</p></div>
      <span v-if="verification" class="verification-pill" :class="verification.valid ? 'valid' : 'invalid'"><AppIcon :name="verification.valid ? 'shield' : 'warning'" />{{ verification.valid ? 'CHAIN VERIFIED' : 'CHAIN BROKEN' }}</span>
    </header>

    <EmptyState v-if="!canAudit" title="当前角色没有审计权限" description="请使用 ADMIN 或 AUDITOR 角色。前端可见性不是权限边界，应用服务也会拒绝请求。" icon="lock" />
    <template v-else>
      <section class="panel audit-query-panel">
        <div class="segmented-control" aria-label="聚合类型"><button :class="{ active: type === 'ORDER' }" @click="switchType('ORDER')"><AppIcon name="orders" />订单链</button><button :class="{ active: type === 'INVENTORY' }" @click="switchType('INVENTORY')"><AppIcon name="inventory" />库存链</button></div>
        <div class="audit-id-field"><label class="field-label" for="aggregate-id">聚合标识</label><div><input id="aggregate-id" v-model="aggregateId" list="aggregate-options" class="mono" :placeholder="type === 'ORDER' ? '订单 UUID' : '库存 SKU'" @keydown.enter="inspect" /><datalist id="aggregate-options"><option v-for="suggestion in suggestions" :key="suggestion" :value="suggestion" /></datalist><button class="button button-secondary" :disabled="loading" @click="inspect"><AppIcon name="search" />读取事件</button></div></div>
        <button class="button button-primary verify-button" :disabled="verifying || !aggregateId.trim()" @click="verify"><AppIcon name="shield" />{{ verifying ? '正在验证…' : '验证完整链' }}</button>
      </section>

      <p v-if="error" class="inline-error" role="alert"><AppIcon name="warning" />{{ error }}</p>
      <div v-if="verification" class="verification-result" :class="verification.valid ? 'valid' : 'invalid'">
        <AppIcon :name="verification.valid ? 'check' : 'warning'" :size="22" /><div><strong>{{ verification.valid ? '校验通过' : '校验失败' }}</strong><p>{{ verification.message }}</p></div><span class="mono">{{ verification.checkedEvents }} EVENTS CHECKED</span>
      </div>

      <div class="content-grid audit-grid">
        <section class="panel audit-events-panel">
          <header class="panel-header"><div><span class="eyebrow">APPEND-ONLY TIMELINE</span><h2>事件时间线 <span v-if="loaded" class="count-badge">{{ events.length }}</span></h2></div><span v-if="headHash" class="tag mono" :title="headHash">HEAD {{ shortHash(headHash) }}</span></header>
          <div v-if="loading" class="table-loading"><span v-for="n in 4" :key="n" /></div>
          <EmptyState v-else-if="!loaded" title="选择一个聚合" description="订单使用 UUID，库存使用 SKU。每条链从 sequence 1 独立开始。" icon="audit" />
          <EmptyState v-else-if="!events.length" title="该聚合暂无事件" description="执行一笔业务命令后，事件会与业务数据在同一事务内出现。" icon="audit" />
          <ol v-else class="audit-timeline">
            <li v-for="event in events" :key="event.id">
              <div class="sequence-rail"><span>{{ String(event.sequence).padStart(2, '0') }}</span><i /></div>
              <article class="audit-event">
                <header><div><span class="event-action">{{ actionLabel(event.action) }}</span><code>{{ event.action }}</code></div><time>{{ formatFullDate(event.occurredAt) }}</time></header>
                <div class="event-meta"><span><AppIcon name="user" :size="13" />{{ event.actorName }}</span><span><AppIcon name="key" :size="13" /><span class="mono">{{ event.requestId }}</span></span></div>
                <details class="payload-details"><summary>查看规范化负载 <AppIcon name="chevron" :size="13" /></summary><pre>{{ prettyPayload(event.payloadJson) }}</pre></details>
                <footer><div><span>EVENT HASH</span><button class="hash-value mono" :title="event.eventHash" @click="copyHash(event.eventHash)">{{ shortHash(event.eventHash) }}<AppIcon name="copy" :size="12" /></button></div><div><span>PREVIOUS</span><code :title="event.previousHash">{{ shortHash(event.previousHash) }}</code></div></footer>
              </article>
            </li>
          </ol>
        </section>

        <aside class="panel audit-method-panel">
          <header class="panel-header"><div><span class="eyebrow">VERIFICATION METHOD</span><h2>链式完整性</h2></div><span class="tag">AUD-03</span></header>
          <div class="hash-formula"><span>SHA-256</span><code>sequence + action<br />+ actor + requestId<br />+ canonicalPayload<br />+ previousHash</code></div>
          <ol class="method-list"><li><span>1</span><p><strong>顺序必须连续</strong>每个聚合从 1 开始，不能出现缺口或重复。</p></li><li><span>2</span><p><strong>前序必须衔接</strong>当前 previousHash 等于上一事件的 eventHash。</p></li><li><span>3</span><p><strong>内容必须一致</strong>重新计算摘要，识别负载、行为者或时间篡改。</p></li></ol>
          <div class="rule-callout"><AppIcon name="activity" /><p><strong>没有全局审计锁</strong>不同订单和 SKU 使用不同链，避免把高并发写入串行化。</p></div>
          <small class="audit-disclaimer">哈希链用于篡改检测，不替代数据库访问控制、备份与日志留存。</small>
        </aside>
      </div>
    </template>
  </div>
</template>
