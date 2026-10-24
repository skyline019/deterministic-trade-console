<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{ name: string; size?: number }>(), { size: 18 })
const paths: Record<string, string[]> = {
  overview: ['M4 13h6V4H4v9Z', 'M14 20h6v-9h-6v9Z', 'M4 20h6v-3H4v3Z', 'M14 7h6V4h-6v3Z'],
  inventory: ['M4 7.5 12 3l8 4.5v9L12 21l-8-4.5v-9Z', 'm4 7.5 8 4.5 8-4.5M12 12v9', 'm8 5.3 8 4.5'],
  orders: ['M7 3h10v4H7z', 'M5 5H3v16h18V5h-2', 'M7 12h10M7 16h7'],
  audit: ['M12 3 4 6v5c0 5 3.4 8.4 8 10 4.6-1.6 8-5 8-10V6l-8-3Z', 'm8.5 12 2.2 2.2 4.8-5'],
  rules: ['M5 4h14v16H5z', 'M8 8h8M8 12h8M8 16h5'],
  logout: ['M10 4H4v16h6M14 8l4 4-4 4M8 12h10'],
  plus: ['M12 5v14M5 12h14'],
  refresh: ['M20 7v5h-5', 'M19 12a7 7 0 1 0-2 5'],
  search: ['M10.5 4a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13ZM15.5 15.5 21 21'],
  arrow: ['M5 12h14M14 7l5 5-5 5'],
  check: ['m5 12 4 4L19 6'],
  close: ['m6 6 12 12M18 6 6 18'],
  shield: ['M12 3 4 6v5c0 5 3.4 8.4 8 10 4.6-1.6 8-5 8-10V6l-8-3Z'],
  lock: ['M6 10h12v11H6z', 'M8 10V7a4 4 0 0 1 8 0v3', 'M12 14v3'],
  database: ['M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3Z', 'M4 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6'],
  activity: ['M3 12h4l2-7 4 14 2-7h6'],
  box: ['M4 7.5 12 3l8 4.5v9L12 21l-8-4.5v-9Z', 'm4 7.5 8 4.5 8-4.5M12 12v9'],
  key: ['M14 3a7 7 0 1 0-6.7 9H3v4h3v3h4v-3h3v-3h2.7A7 7 0 0 0 14 3Z', 'M15 7h.01'],
  copy: ['M8 8h12v12H8z', 'M4 16V4h12'],
  user: ['M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM4 21c0-4 3.6-6 8-6s8 2 8 6'],
  chevron: ['m9 5 7 7-7 7'],
  warning: ['M12 3 2 21h20L12 3Z', 'M12 9v5M12 18h.01'],
}
const iconPaths = computed(() => paths[props.name] ?? paths.box)
</script>

<template>
  <svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path v-for="path in iconPaths" :key="path" :d="path" />
  </svg>
</template>
