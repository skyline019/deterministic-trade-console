<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import AppIcon from './AppIcon.vue'

const props = withDefaults(defineProps<{ title: string; eyebrow?: string; wide?: boolean }>(), { eyebrow: 'EXPLICIT COMMAND', wide: false })
const emit = defineEmits<{ close: [] }>()
const panel = ref<HTMLElement | null>(null)
let previousFocus: Element | null = null

function onKey(event: KeyboardEvent): void {
  if (event.key === 'Escape') emit('close')
}

onMounted(() => {
  previousFocus = document.activeElement
  document.addEventListener('keydown', onKey)
  void nextTick(() => panel.value?.querySelector<HTMLElement>('input, select, textarea, button')?.focus())
})
onUnmounted(() => {
  document.removeEventListener('keydown', onKey)
  if (previousFocus instanceof HTMLElement) previousFocus.focus()
})
</script>

<template>
  <Teleport to="body">
    <div class="modal-backdrop" @mousedown.self="emit('close')">
      <section ref="panel" class="modal-panel" :class="{ 'modal-wide': props.wide }" role="dialog" aria-modal="true" :aria-label="title">
        <header class="modal-header">
          <div><span class="eyebrow">{{ eyebrow }}</span><h2>{{ title }}</h2></div>
          <button class="icon-button" aria-label="关闭" @click="emit('close')"><AppIcon name="close" /></button>
        </header>
        <div class="modal-body"><slot /></div>
        <footer v-if="$slots.footer" class="modal-footer"><slot name="footer" /></footer>
      </section>
    </div>
  </Teleport>
</template>
