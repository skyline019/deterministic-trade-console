<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { gateway } from '../api'
import type { DashboardStats, Order } from '../api/types'
import AppIcon from '../components/AppIcon.vue'
import StatusBadge from '../components/StatusBadge.vue'
import EmptyState from '../components/EmptyState.vue'
import { errorMessage, formatDate, numberFormatter, shortId } from '../domain/format'
import { useSession } from '../session'

const emit = defineEmits<{ navigate: [view: string]; notify: [message: string, tone?: string] }>()
const { session } = useSession()
const stats = ref<DashboardStats | null>(null)
const orders = ref<Order[]>([])
const loading = ref(true)
const error = ref('')

async function load(): Promise<void> {
  const active = session.value
  if (!active) return
  loading.value = true
  error.value = ''
  try {
    const [dashboard, page] = await Promise.all([
      gateway.dashboard(active),
      gateway.listOrders(active, { limit: 6 }),
    ])
    stats.value = dashboard
    orders.value = page.items
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page-stack">
    <header class="page-header">
      <div><span class="eyebrow">SYSTEM / OVERVIEW</span><h1>运行概览</h1><p>事实源、预占台账和审计链的同一时刻快照。</p></div>
      <button class="button button-secondary" :disabled="loading" @click="load"><AppIcon name="refresh" :class="{ spinning: loading }" />刷新快照</button>
    </header>

    <p v-if="error" class="inline-error" role="alert"><AppIcon name="warning" />{{ error }}<button @click="load">重试</button></p>

    <section class="metric-grid" aria-label="交易指标">
      <article class="metric-card accent-green"><div class="metric-top"><span>可售库存</span><AppIcon name="inventory" /></div><strong>{{ stats ? numberFormatter.format(stats.availableStock) : '—' }}</strong><footer><span class="metric-line" /><small>{{ stats?.skuCount ?? '—' }} 个 SKU · 原子条件更新</small></footer></article>
      <article class="metric-card accent-cyan"><div class="metric-top"><span>预占库存</span><AppIcon name="lock" /></div><strong>{{ stats ? numberFormatter.format(stats.reservedStock) : '—' }}</strong><footer><span class="metric-line" /><small>{{ stats?.reservedOrders ?? '—' }} 笔订单等待终态</small></footer></article>
      <article class="metric-card accent-violet"><div class="metric-top"><span>订单总量</span><AppIcon name="orders" /></div><strong>{{ stats ? numberFormatter.format(stats.totalOrders) : '—' }}</strong><footer><span class="metric-line" /><small>唯一请求键保证一次效果</small></footer></article>
      <article class="metric-card accent-amber"><div class="metric-top"><span>已确认订单</span><AppIcon name="check" /></div><strong>{{ stats ? numberFormatter.format(stats.confirmedOrders) : '—' }}</strong><footer><span class="metric-line" /><small>{{ stats?.cancelledOrders ?? '—' }} 笔已取消并释放</small></footer></article>
    </section>

    <section class="panel execution-panel">
      <header class="panel-header"><div><span class="eyebrow">DETERMINISTIC PATH</span><h2>一笔预占如何发生</h2></div><span class="tag tag-success"><span class="live-dot" /> EXPLICIT FLOW</span></header>
      <div class="execution-flow">
        <article><span class="step-number">01</span><div class="step-icon"><AppIcon name="key" /></div><h3>命令鉴权</h3><p>Bearer 身份与写权限</p><code>ACTOR + REQUEST ID</code></article>
        <span class="execution-link"><AppIcon name="arrow" /></span>
        <article><span class="step-number">02</span><div class="step-icon"><AppIcon name="lock" /></div><h3>同键互斥</h3><p>Lua 原子获取与所有权释放</p><code>SET NX + TOKEN</code></article>
        <span class="execution-link"><AppIcon name="arrow" /></span>
        <article><span class="step-number">03</span><div class="step-icon"><AppIcon name="database" /></div><h3>事务预占</h3><p>条件更新、唯一约束、双台账</p><code>MYSQL / COMMIT</code></article>
        <span class="execution-link"><AppIcon name="arrow" /></span>
        <article><span class="step-number">04</span><div class="step-icon"><AppIcon name="audit" /></div><h3>同事务审计</h3><p>按聚合分链，无全局链锁</p><code>SHA-256 / APPEND</code></article>
      </div>
    </section>

    <div class="content-grid overview-grid">
      <section class="panel recent-orders">
        <header class="panel-header"><div><span class="eyebrow">LATEST COMMANDS</span><h2>最新订单</h2></div><button class="text-button" @click="emit('navigate', 'orders')">查看全部<AppIcon name="arrow" :size="15" /></button></header>
        <div v-if="loading" class="table-loading"><span v-for="n in 4" :key="n" /></div>
        <EmptyState v-else-if="!orders.length" title="还没有订单" description="创建第一笔预占后，订单会出现在这里。" />
        <div v-else class="table-wrap">
          <table><thead><tr><th>订单</th><th>SKU</th><th class="numeric">数量</th><th>状态</th><th>创建时间</th></tr></thead>
            <tbody><tr v-for="order in orders" :key="order.id"><td><span class="mono cell-id">{{ shortId(order.id) }}</span><small class="cell-subtitle">{{ order.requestId }}</small></td><td class="mono">{{ order.sku }}</td><td class="numeric mono">{{ order.quantity }}</td><td><StatusBadge :status="order.status" /></td><td class="muted mono">{{ formatDate(order.createdAt) }}</td></tr></tbody>
          </table>
        </div>
      </section>

      <aside class="panel invariant-panel">
        <header class="panel-header"><div><span class="eyebrow">CORE INVARIANTS</span><h2>不可突破的边界</h2></div><AppIcon name="shield" class="panel-header-icon" /></header>
        <ul class="invariant-list">
          <li><AppIcon name="check" /><div><strong>库存永不为负</strong><p>数据库条件更新是最后防线，不依赖缓存值。</p></div></li>
          <li><AppIcon name="check" /><div><strong>请求只有一个效果</strong><p>相同内容安全重放；不同内容复用键会冲突。</p></div></li>
          <li><AppIcon name="check" /><div><strong>预占与订单同进退</strong><p>取消释放、确认核销，终态不可再次迁移。</p></div></li>
          <li><AppIcon name="check" /><div><strong>审计可以独立验证</strong><p>顺序、前序哈希和内容哈希共同检测篡改。</p></div></li>
        </ul>
        <button class="button button-quiet button-full" @click="emit('navigate', 'rules')">查看架构护栏<AppIcon name="arrow" /></button>
      </aside>
    </div>
  </div>
</template>
