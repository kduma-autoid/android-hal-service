<script setup lang="ts">
import { computed } from 'vue';
import type { PluginDescriptor } from '@kduma-autoid/hal-client-common';
import { allMethods, allEvents } from '@kduma-autoid/hal-client-common';

const props = defineProps<{ plugin: PluginDescriptor }>();

const methodCount = computed(() => allMethods(props.plugin).length);
const eventCount = computed(() => allEvents(props.plugin).length);
const hasActiveExperimentalMethods = computed(() =>
  allMethods(props.plugin).some(m => m.experimental && m.experimentalActive)
);
</script>

<template>
  <div class="card">
    <div class="card-header">
      <h3 class="plugin-name">{{ plugin.name }}</h3>
      <code class="plugin-id">{{ plugin.pluginId }}</code>
      <span class="badge badge-version">v{{ plugin.version }}</span>
      <span v-if="plugin.experimental && plugin.experimentalActive" class="badge badge-experimental-active">experimental (enabled)</span>
      <span v-else-if="plugin.experimental" class="badge badge-experimental">experimental</span>
      <span v-else-if="hasActiveExperimentalMethods" class="badge badge-experimental-active">includes experimental</span>
    </div>

    <div v-if="plugin.capabilities.length" class="capabilities">
      <span v-for="cap in plugin.capabilities" :key="cap" class="badge badge-cap">{{ cap }}</span>
    </div>

    <template v-for="(group, gi) in plugin.groups" :key="gi">
      <template v-if="group.methods.length || group.events.length">
        <h4 v-if="group.name" class="group-name">{{ group.name }}</h4>

        <div v-if="group.methods.length" class="section">
          <h4 v-if="!group.name">Methods ({{ methodCount }})</h4>
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
                <tr v-for="m in group.methods" :key="m.name">
                  <td><code>{{ m.name }}</code></td>
                  <td>{{ m.description }}</td>
                  <td><code>{{ m.requiredPermission }}</code></td>
                  <td>
                    <span v-if="m.superRequired" class="badge badge-super">super</span>
                    <span v-if="m.experimental && m.experimentalActive" class="badge badge-experimental-active">exp</span>
                    <span v-else-if="m.experimental" class="badge badge-experimental">exp</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div v-if="group.events.length" class="section">
          <h4 v-if="!group.name">Events ({{ eventCount }})</h4>
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
                <tr v-for="e in group.events" :key="e.name">
                  <td><code>{{ e.name }}</code></td>
                  <td>{{ e.description }}</td>
                  <td><code>{{ e.requiredPermission }}</code></td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </template>
    </template>

    <p v-if="methodCount === 0 && eventCount === 0" class="empty">No methods or events</p>
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
.badge-experimental-active { background: #d1fae5; color: #065f46; }
.badge-super { background: #fce7f3; color: #9d174d; }
.badge-cap { background: #ecfdf5; color: #065f46; }
.capabilities {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.group-name {
  margin: 16px 0 4px;
  font-size: 15px;
  color: #1565c0;
  border-bottom: 1px solid #e0e7ff;
  padding-bottom: 4px;
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
