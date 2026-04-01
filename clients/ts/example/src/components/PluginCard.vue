<script setup lang="ts">
import { computed, ref } from 'vue';
import type { PluginDescriptor } from '@kduma-autoid/hal-client-common';
import { allMethods, allEvents } from '@kduma-autoid/hal-client-common';
import MethodExecutor from './MethodExecutor.vue';
import EventMonitor from './EventMonitor.vue';

interface ReceivedEvent {
  timestamp: number;
  data: unknown;
}

const props = defineProps<{
  plugin: PluginDescriptor;
  receivedEvents?: Record<string, ReceivedEvent[]>;
}>();

const methodCount = computed(() => allMethods(props.plugin).length);
const eventCount = computed(() => allEvents(props.plugin).length);
const hasActiveExperimental = computed(() =>
  allMethods(props.plugin).some(m => m.experimental && m.experimentalActive) ||
  allEvents(props.plugin).some(e => e.experimental && e.experimentalActive)
);

const expandedMethods = ref<Set<string>>(new Set());
function toggleMethod(name: string) {
  if (expandedMethods.value.has(name)) {
    expandedMethods.value.delete(name);
  } else {
    expandedMethods.value.add(name);
  }
}

const expandedEvents = ref<Set<string>>(new Set());
function toggleEvent(name: string) {
  if (expandedEvents.value.has(name)) {
    expandedEvents.value.delete(name);
  } else {
    expandedEvents.value.add(name);
  }
}

function getReceivedEvents(eventName: string): ReceivedEvent[] {
  return props.receivedEvents?.[eventName] ?? [];
}

function breakableName(name: string): string {
  return name.replace(/\./g, '.\u200B');
}
</script>

<template>
  <div class="card">
    <div class="card-header">
      <h3 class="plugin-name">{{ plugin.name }}</h3>
      <code class="plugin-id">{{ plugin.pluginId }}</code>
      <span class="badge badge-version">v{{ plugin.version }}</span>
      <span v-if="plugin.experimental && plugin.experimentalActive" class="badge badge-experimental-active">experimental (enabled)</span>
      <span v-else-if="plugin.experimental" class="badge badge-experimental">experimental</span>
      <span v-else-if="hasActiveExperimental" class="badge badge-experimental-active">includes experimental</span>
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
                <template v-for="m in group.methods" :key="m.name">
                  <tr class="method-row" :class="{ expanded: expandedMethods.has(m.name) }" @click="toggleMethod(m.name)">
                    <td data-label="Name"><span class="name-cell"><span class="expand-icon">{{ expandedMethods.has(m.name) ? '▼' : '▶' }}</span> <code>{{ breakableName(m.name) }}</code></span></td>
                    <td data-label="Description">{{ m.description }}</td>
                    <td data-label="Permission"><code>{{ breakableName(m.requiredPermission) }}</code></td>
                    <td data-label="Flags" class="flags-cell">
                      <span v-if="m.superRequired" class="badge badge-super">super</span>
                      <span v-if="m.experimental && m.experimentalActive" class="badge badge-experimental-active">exp</span>
                      <span v-else-if="m.experimental" class="badge badge-experimental">exp</span>
                    </td>
                  </tr>
                  <tr v-if="expandedMethods.has(m.name)" class="executor-row">
                    <td colspan="4"><MethodExecutor :method="m" /></td>
                  </tr>
                </template>
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
                  <th>Flags</th>
                </tr>
              </thead>
              <tbody>
                <template v-for="e in group.events" :key="e.name">
                  <tr class="event-row" :class="{ expanded: expandedEvents.has(e.name) }" @click="toggleEvent(e.name)">
                    <td data-label="Name">
                      <span class="name-cell"><span class="expand-icon">{{ expandedEvents.has(e.name) ? '▼' : '▶' }}</span> <code>{{ breakableName(e.name) }}</code></span>
                      <span v-if="getReceivedEvents(e.name).length" class="badge badge-event-count">{{ getReceivedEvents(e.name).length }}</span>
                    </td>
                    <td data-label="Description">{{ e.description }}</td>
                    <td data-label="Permission"><code>{{ breakableName(e.requiredPermission) }}</code></td>
                    <td data-label="Flags" class="flags-cell">
                      <span v-if="e.experimental && e.experimentalActive" class="badge badge-experimental-active">exp</span>
                      <span v-else-if="e.experimental" class="badge badge-experimental">exp</span>
                    </td>
                  </tr>
                  <tr v-if="expandedEvents.has(e.name)" class="executor-row">
                    <td colspan="4">
                      <EventMonitor :event="e" :received-events="getReceivedEvents(e.name)" />
                    </td>
                  </tr>
                </template>
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
.method-row {
  cursor: pointer;
}
.method-row:hover {
  background: #f0f7ff;
}
.method-row.expanded {
  background: #e8f0fe;
}
.name-cell {
  display: inline-flex;
  align-items: flex-start;
}
.expand-icon {
  font-size: 10px;
  margin-right: 4px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 12px;
  flex-shrink: 0;
  align-self: center;
}
.event-row {
  cursor: pointer;
}
.event-row:hover {
  background: #f0f7ff;
}
.event-row.expanded {
  background: #e8f0fe;
}
.badge-event-count {
  background: #dbeafe;
  color: #1e40af;
  margin-left: 6px;
}
.executor-row td {
  padding: 0 !important;
  border-top: none !important;
}

@media (max-width: 640px) {
  table, thead, tbody, tr, th, td {
    display: block;
  }
  thead {
    display: none;
  }
  tbody tr {
    margin-bottom: 8px;
    border: 1px solid #e5e7eb;
    border-radius: 6px;
    overflow: hidden;
  }
  td {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    padding: 6px 10px;
    border: none;
    border-bottom: 1px solid #f0f0f0;
  }
  td:last-child {
    border-bottom: none;
  }
  td::before {
    content: attr(data-label);
    font-weight: 600;
    font-size: 11px;
    color: #666;
    min-width: 80px;
    margin-right: 8px;
    flex-shrink: 0;
  }
  .expand-icon {
    display: none;
  }
  .flags-cell:not(:has(.badge)) {
    display: none;
  }
  .executor-row td {
    display: block;
    border-bottom: none;
  }
  .executor-row td::before {
    display: none;
  }
}
</style>
