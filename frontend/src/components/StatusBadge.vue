<script setup lang="ts">
import type { OrderStatus } from '../api/types'
import { statusLabel } from '../domain/format'
defineProps<{ status: OrderStatus }>()
</script>

<template>
  <span class="status-badge" :class="`status-${status.toLowerCase()}`">
    <span class="status-dot" />{{ statusLabel(status) }}
  </span>
</template>
