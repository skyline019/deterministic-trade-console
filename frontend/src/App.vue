<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { isDemoMode } from './api'
import AppIcon from './components/AppIcon.vue'
import LoginView from './views/LoginView.vue'
import OverviewView from './views/OverviewView.vue'
import InventoryView from './views/InventoryView.vue'
import OrdersView from './views/OrdersView.vue'
import AuditView from './views/AuditView.vue'
import RulesView from './views/RulesView.vue'
import { errorMessage, roleLabel } from './domain/format'
import { useSession } from './session'

type ViewKey = 'overview' | 'inventory' | 'orders' | 'audit' | 'rules'
type ToastTone = 'success' | 'error' | 'info'
interface Toast { id: number; message: string; tone: ToastTone }

const { session, actor, canAudit, logout, expire } = useSession()
const activeView = ref<ViewKey>('overview')
const auditSeed = ref('')
const mobileOpen = ref(false)
const toasts = ref<Toast[]>([])
let toastSequence = 0
const navigation: { key: ViewKey; label: string; caption: string; icon: string; audit?: boolean }[] = [
  { key: 'overview', label: '运行概览', caption: 'Overview', icon: 'overview' },
  { key: 'inventory', label: '库存台账', caption: 'Inventory', icon: 'inventory' },
  { key: 'orders', label: '订单生命周期', caption: 'Orders', icon: 'orders' },
  { key: 'audit', label: '审计验证', caption: 'Audit chains', icon: 'audit', audit: true },
  { key: 'rules', label: '架构护栏', caption: 'Concept rules', icon: 'rules' },
]
const currentNavigation = computed(() => navigation.find((item) => item.key === activeView.value) ?? navigation[0])

function notify(message: string, tone = 'info'): void {
  const normalized: ToastTone = tone === 'success' || tone === 'error' ? tone : 'info'
  const id = ++toastSequence
  toasts.value.push({ id, message, tone: normalized })
  window.setTimeout(() => { toasts.value = toasts.value.filter((toast) => toast.id !== id) }, 4500)
}

function navigate(view: string): void {
  if (!navigation.some((item) => item.key === view)) return
  activeView.value = view as ViewKey
  mobileOpen.value = false
}

function openAudit(id: string): void {
  auditSeed.value = id
  navigate('audit')
}

async function signOut(): Promise<void> {
  try {
    await logout()
  } catch (cause) {
    notify(errorMessage(cause), 'error')
  }
}

function onSessionExpired(): void {
  if (!session.value) return
  expire()
  activeView.value = 'overview'
  notify('会话已失效，请重新登录', 'error')
}

onMounted(() => window.addEventListener('trade:session-expired', onSessionExpired))
onUnmounted(() => window.removeEventListener('trade:session-expired', onSessionExpired))
</script>

<template>
  <LoginView v-if="!session" />
  <div v-else class="app-shell">
    <div v-if="mobileOpen" class="sidebar-backdrop" @click="mobileOpen = false" />
    <aside class="sidebar" :class="{ 'mobile-open': mobileOpen }">
      <div class="sidebar-brand"><span class="brand-mark"><span>D</span><span>T</span></span><div><strong>DETERMINISTIC<br />TRADE</strong><small>CONTROL CONSOLE</small></div><button class="icon-button mobile-close" aria-label="关闭导航" @click="mobileOpen = false"><AppIcon name="close" /></button></div>
      <div class="environment-card"><span class="environment-icon"><AppIcon :name="isDemoMode ? 'activity' : 'database'" /></span><div><strong>{{ isDemoMode ? 'LOCAL DEMO' : 'HTTP API' }}</strong><small>{{ isDemoMode ? '隔离演示数据' : '同源业务服务' }}</small></div><span class="live-dot" /></div>
      <nav class="main-nav" aria-label="主导航">
        <span class="nav-section-label">WORKSPACE / 工作区</span>
        <button v-for="item in navigation" :key="item.key" :class="{ active: activeView === item.key, restricted: item.audit && !canAudit }" @click="navigate(item.key)">
          <span class="nav-icon"><AppIcon :name="item.icon" /></span><span><strong>{{ item.label }}</strong><small>{{ item.caption }}</small></span><AppIcon v-if="item.audit && !canAudit" name="lock" :size="13" /><span v-else-if="activeView === item.key" class="nav-active-line" />
        </button>
      </nav>
      <div class="sidebar-contract"><span class="nav-section-label">ACTIVE CONTRACT</span><div><span class="live-dot" /><strong>MySQL 是事实源</strong></div><p>Redis 不提供库存正确性<br />所有命令写入审计事件</p><code>v2.0 / java 17</code></div>
      <div class="sidebar-user"><span class="user-avatar">{{ actor?.username.slice(0, 2).toUpperCase() }}</span><div><strong>{{ actor?.username }}</strong><small>{{ actor?.roles.map(roleLabel).join(' · ') }}</small></div><button class="icon-button" aria-label="注销" title="立即注销" @click="signOut"><AppIcon name="logout" /></button></div>
    </aside>

    <div class="app-main">
      <header class="topbar">
        <div class="topbar-left"><button class="icon-button mobile-menu" aria-label="打开导航" @click="mobileOpen = true"><AppIcon name="orders" /></button><span class="breadcrumb">CONSOLE <AppIcon name="chevron" :size="12" /> {{ currentNavigation.caption.toUpperCase() }}</span></div>
        <div class="topbar-right"><span class="security-label"><AppIcon name="shield" :size="14" />RBAC ACTIVE</span><span class="topbar-separator" /><span class="actor-label"><span class="live-dot" />{{ actor?.username }}</span></div>
      </header>
      <div v-if="isDemoMode" class="demo-banner"><AppIcon name="activity" :size="14" /><span>演示适配器：数据仅在当前页面内变化，不连接 MySQL / Redis，也不用于安全结论。</span></div>
      <main class="workspace">
        <OverviewView v-if="activeView === 'overview'" @navigate="navigate" @notify="notify" />
        <InventoryView v-else-if="activeView === 'inventory'" @notify="notify" />
        <OrdersView v-else-if="activeView === 'orders'" @notify="notify" @audit="openAudit" />
        <AuditView v-else-if="activeView === 'audit'" :initial-order-id="auditSeed" @notify="notify" />
        <RulesView v-else @notify="notify" />
      </main>
      <footer class="app-footer"><span>DETERMINISTIC TRADE / EXPLICIT BY DESIGN</span><span>Vue 3 · TypeScript · Same-origin API</span></footer>
    </div>
  </div>

  <Teleport to="body"><div class="toast-region" aria-live="polite"><TransitionGroup name="toast"><article v-for="toast in toasts" :key="toast.id" class="toast" :class="`toast-${toast.tone}`"><AppIcon :name="toast.tone === 'success' ? 'check' : toast.tone === 'error' ? 'warning' : 'activity'" /><span>{{ toast.message }}</span><button aria-label="关闭通知" @click="toasts = toasts.filter((item) => item.id !== toast.id)"><AppIcon name="close" :size="14" /></button></article></TransitionGroup></div></Teleport>
</template>
