<script setup lang="ts">
import type { PluginDescriptor } from '@kduma-autoid/hal-client-common';

defineProps<{ plugin: PluginDescriptor }>();
</script>

<template>
  <div class="card">
    <div class="card-header">
      <h3 class="plugin-name">{{ plugin.name }}</h3>
      <code class="plugin-id">{{ plugin.pluginId }}</code>
      <span class="badge badge-version">v{{ plugin.version }}</span>
      <span v-if="plugin.experimental" class="badge badge-experimental">experimental</span>
    </div>

    <div v-if="plugin.capabilities.length" class="capabilities">
      <span v-for="cap in plugin.capabilities" :key="cap" class="badge badge-cap">{{ cap }}</span>
    </div>

    <div v-if="plugin.methods.length" class="section">
      <h4>Methods ({{ plugin.methods.length }})</h4>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Name</th>
              <th>Description</th>
              <th>Permission</th>
              <th>Flags</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="m in plugin.methods" :key="m.name">
              <td><code>{{ m.name }}</code></td>
              <td>{{ m.description }}</td>
              <td><code>{{ m.requiredPermission }}</code></td>
              <td>
                <span v-if="m.superRequired" class="badge badge-super">super</span>
                <span v-if="m.experimental" class="badge badge-experimental">exp</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
    <p v-else class="empty">No methods</p>

    <div v-if="plugin.events.length" class="section">
      <h4>Events ({{ plugin.events.length }})</h4>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Name</th>
              <th>Description</th>
              <th>Permission</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="e in plugin.events" :key="e.name">
              <td><code>{{ e.name }}</code></td>
              <td>{{ e.description }}</td>
              <td><code>{{ e.requiredPermission }}</code></td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
    <p v-else class="empty">No events</p>
  </div>
</template>

<style scoped>
.card {
  border: 1px solid #ddd;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 16px;
  background: #fff;
}
.card-header {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.plugin-name {
  margin: 0;
  font-size: 18px;
}
.plugin-id {
  color: #666;
  font-size: 13px;
}
.badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 600;
}
.badge-version { background: #e0e7ff; color: #3730a3; }
.badge-experimental { background: #fef3c7; color: #92400e; }
.badge-super { background: #fce7f3; color: #9d174d; }
.badge-cap { background: #ecfdf5; color: #065f46; }
.capabilities {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.section { margin-top: 12px; }
.section h4 {
  margin: 0 0 8px;
  font-size: 14px;
  color: #444;
}
.table-wrap {
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
}
table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
th, td {
  text-align: left;
  padding: 6px 8px;
  border: 1px solid #e5e7eb;
}
th {
  background: #f9fafb;
  font-weight: 600;
}
code {
  font-size: 12px;
  background: #f3f4f6;
  padding: 1px 4px;
  border-radius: 3px;
}
.empty {
  color: #999;
  font-size: 13px;
  font-style: italic;
}
</style>
