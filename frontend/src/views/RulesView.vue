<script setup lang="ts">
import AppIcon from '../components/AppIcon.vue'

const rules = [
  { id: 'AR-001', title: '领域没有框架依赖', text: 'domain 不依赖 Spring、Jakarta、Redis 或 Web。库存与订单规则可以在纯 Java 测试中确定执行。', code: 'domain !→ framework' },
  { id: 'AR-002', title: '应用只依赖端口', text: '用例通过构造器接收 Repository、LockManager、TransactionRunner 与 AuditRecorder。', code: 'application → port + domain' },
  { id: 'AR-003', title: 'Web 不能绕过用例', text: 'Handler 不直接访问 JDBC 或业务仓库；认证身份在应用服务内再次鉴权。', code: 'web → application' },
  { id: 'AR-004', title: '适配器不能跨层串联', text: 'Persistence、Redis、Security 各自实现端口，不在适配器之间隐藏调用。', code: 'adapter !↔ adapter' },
  { id: 'AR-005', title: '组合根显式装配', text: 'WiringConfiguration 与 SecurityConfiguration 是唯一对象图入口，不使用隐式业务 AOP。', code: 'config → new + constructor' },
  { id: 'AR-006', title: '没有隐式业务定时器', text: '库存和订单不被后台任务偷偷改写；每次状态变化都来自可追踪命令。', code: 'no @Scheduled' },
]
const roles = [
  { role: 'ADMIN', inventory: '读写', orders: '读写', audit: '验证', metrics: '读取' },
  { role: 'OPERATOR', inventory: '读写', orders: '读写', audit: '—', metrics: '—' },
  { role: 'VIEWER', inventory: '只读', orders: '只读', audit: '—', metrics: '—' },
  { role: 'AUDITOR', inventory: '只读', orders: '只读', audit: '验证', metrics: '读取' },
]
</script>

<template>
  <div class="page-stack">
    <header class="page-header"><div><span class="eyebrow">CONCEPT RULES / CI GUARDED</span><h1>架构护栏</h1><p>前端展示的不是装饰性原则，而是后端代码与 CI 共同执行的依赖方向。</p></div><span class="tag tag-success"><AppIcon name="shield" :size="14" /> ARCHUNIT ENFORCED</span></header>

    <section class="architecture-map panel">
      <header class="panel-header"><div><span class="eyebrow">DEPENDENCY DIRECTION</span><h2>依赖只向内，装配只在边缘</h2></div><span class="mono muted">NO CYCLES</span></header>
      <div class="layer-map">
        <div class="layer outer"><span>ADAPTERS</span><strong>Web · JDBC · Redis · Security</strong><small>框架与协议细节</small></div><AppIcon name="arrow" />
        <div class="layer middle"><span>APPLICATION</span><strong>Use Cases + Ports</strong><small>事务边界与权限检查</small></div><AppIcon name="arrow" />
        <div class="layer core"><span>DOMAIN</span><strong>Order · Stock · Audit</strong><small>纯 Java 业务不变量</small></div>
      </div>
      <footer class="composition-root"><AppIcon name="rules" /><span>COMPOSITION ROOT</span><code>WiringConfiguration / SecurityConfiguration</code></footer>
    </section>

    <section class="rule-card-grid">
      <article v-for="rule in rules" :key="rule.id" class="panel rule-card"><header><span class="rule-id">{{ rule.id }}</span><AppIcon name="check" :size="16" /></header><h2>{{ rule.title }}</h2><p>{{ rule.text }}</p><code>{{ rule.code }}</code></article>
    </section>

    <div class="content-grid rules-bottom-grid">
      <section class="panel">
        <header class="panel-header"><div><span class="eyebrow">RBAC / SERVER RECHECKED</span><h2>角色与权限</h2></div><AppIcon name="lock" class="panel-header-icon" /></header>
        <div class="table-wrap"><table class="role-table"><thead><tr><th>角色</th><th>库存</th><th>订单</th><th>审计</th><th>指标</th></tr></thead><tbody><tr v-for="item in roles" :key="item.role"><td><strong class="mono">{{ item.role }}</strong></td><td>{{ item.inventory }}</td><td>{{ item.orders }}</td><td><span :class="item.audit === '—' ? 'muted' : 'permission-yes'">{{ item.audit }}</span></td><td>{{ item.metrics }}</td></tr></tbody></table></div>
      </section>
      <aside class="panel verification-stack">
        <header class="panel-header"><div><span class="eyebrow">VERIFICATION GATES</span><h2>交付验证</h2></div></header>
        <div class="gate"><span>01</span><div><strong>JUnit / ArchUnit</strong><p>分层、协议、Redis Lua 与规则测试</p></div><AppIcon name="check" /></div>
        <div class="gate"><span>02</span><div><strong>jtest Concepts</strong><p>并发、重放、生命周期与篡改检测</p></div><AppIcon name="check" /></div>
        <div class="gate"><span>03</span><div><strong>Integration Profile</strong><p>真实 MySQL / Redis 容器验证需 Docker</p></div><AppIcon name="database" /></div>
        <div class="gate"><span>04</span><div><strong>Vue Type Check</strong><p>严格 TypeScript、单元测试与生产构建</p></div><AppIcon name="check" /></div>
      </aside>
    </div>
  </div>
</template>
