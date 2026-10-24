<script setup lang="ts">
import { ref } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import { isDemoMode } from '../api'
import { errorMessage } from '../domain/format'
import { useSession } from '../session'

const { login, authenticating } = useSession()
const username = ref(isDemoMode ? 'admin' : '')
const password = ref(isDemoMode ? 'deterministic-demo' : '')
const showPassword = ref(false)
const error = ref('')

async function submit(): Promise<void> {
  error.value = ''
  if (!username.value.trim() || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  try {
    await login(username.value, password.value)
  } catch (cause) {
    error.value = errorMessage(cause)
  }
}

function chooseAccount(account: string): void {
  username.value = account
  password.value = 'deterministic-demo'
  error.value = ''
}
</script>

<template>
  <main class="login-page">
    <section class="login-story">
      <div class="login-brand">
        <span class="brand-mark"><span>D</span><span>T</span></span>
        <div><strong>DETERMINISTIC TRADE</strong><small>TRANSACTION CONTROL / 02</small></div>
      </div>
      <div class="story-copy">
        <span class="eyebrow"><span class="live-dot" /> SYSTEM OF RECORD · MYSQL</span>
        <h1>每一次变化，<br /><em>都有确定的结果。</em></h1>
        <p>面向高并发交易与库存场景的控制台。命令、事务、状态和审计沿同一条明确路径执行。</p>
      </div>
      <div class="login-flow" aria-label="交易执行路径">
        <div class="flow-node"><span>01</span><AppIcon name="key" /><strong>认证命令</strong><small>BCrypt + Bearer</small></div>
        <AppIcon name="arrow" class="flow-arrow" />
        <div class="flow-node"><span>02</span><AppIcon name="lock" /><strong>请求互斥</strong><small>Redis Lua</small></div>
        <AppIcon name="arrow" class="flow-arrow" />
        <div class="flow-node"><span>03</span><AppIcon name="database" /><strong>原子事务</strong><small>MySQL</small></div>
        <AppIcon name="arrow" class="flow-arrow" />
        <div class="flow-node"><span>04</span><AppIcon name="audit" /><strong>分链审计</strong><small>SHA-256</small></div>
      </div>
      <div class="story-foot"><span>AVAILABLE ≥ 0</span><span>RESERVED ≥ 0</span><span>ONE KEY · ONE EFFECT</span></div>
    </section>

    <section class="login-form-wrap">
      <form class="login-form" @submit.prevent="submit">
        <div class="mobile-brand"><span class="brand-mark"><span>D</span><span>T</span></span><strong>DETERMINISTIC TRADE</strong></div>
        <span class="eyebrow">AUTHORIZED ACCESS ONLY</span>
        <h2>进入交易控制台</h2>
        <p class="form-intro">身份在每个应用服务中再次鉴权，不依赖前端隐藏按钮。</p>

        <div v-if="isDemoMode" class="demo-notice">
          <AppIcon name="activity" />
          <div><strong>本地演示模式</strong><span>不连接后端，不产生真实交易。</span></div>
        </div>

        <label class="field-label" for="username">用户名</label>
        <div class="input-with-icon"><AppIcon name="user" /><input id="username" v-model="username" name="username" autocomplete="username" maxlength="64" placeholder="输入用户名" /></div>
        <label class="field-label" for="password">密码</label>
        <div class="input-with-icon"><AppIcon name="lock" /><input id="password" v-model="password" name="password" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" maxlength="72" placeholder="输入密码" /><button type="button" class="input-action" @click="showPassword = !showPassword">{{ showPassword ? '隐藏' : '显示' }}</button></div>

        <p v-if="error" class="form-error" role="alert"><AppIcon name="warning" />{{ error }}</p>
        <button class="button button-primary login-submit" type="submit" :disabled="authenticating">
          <span>{{ authenticating ? '正在验证身份…' : '验证并进入' }}</span><AppIcon v-if="!authenticating" name="arrow" />
        </button>

        <div v-if="isDemoMode" class="demo-accounts">
          <span>切换演示角色</span>
          <div><button v-for="account in ['admin', 'operator', 'viewer', 'auditor']" :key="account" type="button" :class="{ active: username === account }" @click="chooseAccount(account)">{{ account }}</button></div>
          <small>统一演示密码：deterministic-demo</small>
        </div>
        <div v-else class="login-security"><AppIcon name="shield" :size="15" /><span>Token 仅保存在当前标签页 · 注销立即失效</span></div>
      </form>
      <footer class="login-footer">SPRING BOOT 3 · VUE 3 · EXPLICIT ARCHITECTURE</footer>
    </section>
  </main>
</template>
