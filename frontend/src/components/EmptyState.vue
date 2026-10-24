<script setup lang="ts">
import AppIcon from './AppIcon.vue'
withDefaults(defineProps<{ title: string; description: string; icon?: string }>(), { icon: 'box' })
</script>

<template>
  <div class="empty-state">
    <span class="empty-icon"><AppIcon :name="icon" :size="24" /></span>
    <h3>{{ title }}</h3>
    <p>{{ description }}</p>
    <slot />
  </div>
</template>
