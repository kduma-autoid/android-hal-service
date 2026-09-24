<script setup lang="ts">
import { computed } from 'vue';
import type { InterfaceDescriptor } from '@kduma-autoid/hal-client-common';

const props = defineProps<{ iface: InterfaceDescriptor }>();

const detailLink = computed(() => ({
  name: 'interface-detail',
  params: { interfaceId: props.iface.interfaceId },
}));

const defaultProvider = computed(() => props.iface.providers.find((p) => p.isDefault) ?? null);
</script>

<template>
  <router-link :to="detailLink" class="card">
    <div class="card-header">
      <h3 class="iface-name">{{ iface.interfaceId }}</h3>
      <span class="badge badge-version">v{{ iface.version }}</span>
      <span v-for="f in iface.features" :key="f.key" class="badge badge-feature">{{ f.key }}</span>
    </div>

    <p class="summary">
      {{ iface.methods.length }} method(s) · {{ iface.providers.length }} provider(s)
      <template v-if="defaultProvider">
        · default <code>{{ defaultProvider.pluginId }}</code>
      </template>
    </p>
  </router-link>
</template>

<style scoped>
.card {
  display: block;
  border: 1px solid #ddd;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 16px;
  background: #fff;
  text-decoration: none;
  color: inherit;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.card:hover {
  border-color: #0066cc;
  box-shadow: 0 2px 8px rgba(0, 102, 204, 0.1);
}
.card-header {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.iface-name {
  margin: 0;
  font-size: 18px;
  font-family: monospace;
}
.badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 600;
}
.badge-version { background: #e0e7ff; color: #3730a3; }
.badge-feature { background: #ecfdf5; color: #065f46; }
.summary {
  margin: 0;
  font-size: 13px;
  color: #888;
}
code {
  font-size: 12px;
  background: #f3f4f6;
  padding: 1px 4px;
  border-radius: 3px;
}
</style>
