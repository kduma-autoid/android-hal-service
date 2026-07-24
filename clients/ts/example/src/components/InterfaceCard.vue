<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { InterfaceDescriptor } from '@kduma-autoid/hal-client-common';
import { PROVIDER_PARAM_KEY } from '@kduma-autoid/hal-client-common';
import { useHalClient } from '../composables/useHalClient';
import MethodExecutor from './MethodExecutor.vue';
import EventMonitor from './EventMonitor.vue';

interface ReceivedEvent {
  timestamp: number;
  data: unknown;
}

const props = defineProps<{
  iface: InterfaceDescriptor;
  receivedEvents?: Record<string, ReceivedEvent[]>;
}>();
const emit = defineEmits<{ (e: 'refresh'): void }>();

const { client } = useHalClient();

const defaultProviderId = computed(() => props.iface.providers.find((p) => p.isDefault)?.pluginId ?? '');
const selectedProvider = ref(defaultProviderId.value);
watch(
  () => props.iface,
  () => {
    if (!props.iface.providers.some((p) => p.pluginId === selectedProvider.value)) {
      selectedProvider.value = defaultProviderId.value;
    }
  },
);

// Params merged into every call from this card: pin the chosen provider unless it is the default.
const injectParams = computed<Record<string, unknown>>(() =>
  selectedProvider.value && selectedProvider.value !== defaultProviderId.value
    ? { [PROVIDER_PARAM_KEY]: selectedProvider.value }
    : {},
);

const expandedMethods = ref<Set<string>>(new Set());
function toggleMethod(name: string) {
  if (expandedMethods.value.has(name)) expandedMethods.value.delete(name);
  else expandedMethods.value.add(name);
}

const expandedEvents = ref<Set<string>>(new Set());
function toggleEvent(name: string) {
  if (expandedEvents.value.has(name)) expandedEvents.value.delete(name);
  else expandedEvents.value.add(name);
}
function getReceivedEvents(name: string): ReceivedEvent[] {
  return props.receivedEvents?.[name] ?? [];
}

async function moveProvider(index: number, delta: number) {
  if (!client.value) return;
  const ids = props.iface.providers.map((p) => p.pluginId);
  const target = index + delta;
  if (target < 0 || target >= ids.length) return;
  [ids[index], ids[target]] = [ids[target], ids[index]];
  await client.value.execute('system.interface.setOrder', {
    interfaceId: props.iface.interfaceId,
    order: ids,
  });
  emit('refresh');
}

async function setEnabled(pluginId: string, enabled: boolean) {
  if (!client.value) return;
  await client.value.execute('system.interface.setEnabled', {
    interfaceId: props.iface.interfaceId,
    pluginId,
    enabled,
  });
  emit('refresh');
}

function breakableName(name: string): string {
  return name.replace(/\./g, '.​');
}
</script>

<template>
  <div class="card">
    <div class="card-header">
      <h3 class="iface-name">{{ iface.interfaceId }}</h3>
      <span class="badge badge-version">v{{ iface.version }}</span>
      <span v-for="f in iface.features" :key="f.key" class="badge badge-feature" :title="f.description">
        {{ f.key }}
      </span>
    </div>

    <div class="section">
      <h4>Providers ({{ iface.providers.length }})</h4>
      <p v-if="!iface.providers.length" class="empty">
        No available providers. (Unavailable/unsupported implementors are hidden here; the HAL Service
        app shows them all.)
      </p>
      <ul v-else class="providers">
        <li
          v-for="(p, i) in iface.providers"
          :key="p.pluginId"
          class="provider"
          :class="{ disabled: !p.enabled }"
        >
          <span class="order">
            <button class="btn-xs" :disabled="i === 0" title="Move up" @click="moveProvider(i, -1)">↑</button>
            <button
              class="btn-xs"
              :disabled="i === iface.providers.length - 1"
              title="Move down"
              @click="moveProvider(i, 1)"
            >↓</button>
          </span>
          <input
            type="checkbox"
            class="en"
            :checked="p.enabled"
            title="Enabled"
            @change="setEnabled(p.pluginId, ($event.target as HTMLInputElement).checked)"
          />
          <label class="pick">
            <input type="radio" :name="'prov-' + iface.interfaceId" :value="p.pluginId" v-model="selectedProvider" />
            <code>{{ p.pluginId }}</code>
          </label>
          <span v-if="p.isDefault" class="badge badge-default">default</span>
          <span v-if="!p.enabled" class="badge badge-disabled">disabled</span>
          <span class="badge badge-source">{{ p.source }}</span>
          <span v-for="f in p.features" :key="f" class="badge badge-feature">{{ f }}</span>
        </li>
      </ul>
      <p class="hint">
        Order sets the server default (top). The selected radio pins calls below via
        <code>__provider</code>.
      </p>
    </div>

    <div v-if="iface.methods.length" class="section">
      <h4>Methods ({{ iface.methods.length }})</h4>
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
            <template v-for="m in iface.methods" :key="m.name">
              <tr class="method-row" :class="{ expanded: expandedMethods.has(m.name) }" @click="toggleMethod(m.name)">
                <td data-label="Name">
                  <span class="name-cell">
                    <span class="expand-icon">{{ expandedMethods.has(m.name) ? '▼' : '▶' }}</span>
                    <code>{{ breakableName(m.name) }}</code>
                  </span>
                </td>
                <td data-label="Description">{{ m.description }}</td>
                <td data-label="Permission"><code>{{ breakableName(m.requiredPermission) }}</code></td>
              </tr>
              <tr v-if="expandedMethods.has(m.name)" class="executor-row">
                <td colspan="3"><MethodExecutor :method="m" :inject-params="injectParams" /></td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="iface.events.length" class="section">
      <h4>Events ({{ iface.events.length }})</h4>
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
            <template v-for="ev in iface.events" :key="ev.name">
              <tr class="method-row" :class="{ expanded: expandedEvents.has(ev.name) }" @click="toggleEvent(ev.name)">
                <td data-label="Name">
                  <span class="name-cell">
                    <span class="expand-icon">{{ expandedEvents.has(ev.name) ? '▼' : '▶' }}</span>
                    <code>{{ breakableName(ev.name) }}</code>
                  </span>
                  <span v-if="getReceivedEvents(ev.name).length" class="badge badge-default">{{ getReceivedEvents(ev.name).length }}</span>
                </td>
                <td data-label="Description">{{ ev.description }}</td>
                <td data-label="Permission"><code>{{ breakableName(ev.requiredPermission) }}</code></td>
              </tr>
              <tr v-if="expandedEvents.has(ev.name)" class="executor-row">
                <td colspan="3"><EventMonitor :event="ev" :received-events="getReceivedEvents(ev.name)" /></td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
    </div>

    <p v-if="!iface.methods.length && !iface.events.length" class="empty">No methods or events</p>
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
.badge-default { background: #dbeafe; color: #1e40af; }
.badge-disabled { background: #fee2e2; color: #991b1b; }
.badge-source { background: #f3f4f6; color: #555; }
.section { margin-top: 12px; }
.section h4 {
  margin: 0 0 8px;
  font-size: 14px;
  color: #444;
}
.providers {
  list-style: none;
  padding: 0;
  margin: 0;
}
.provider {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 6px 0;
  border-bottom: 1px solid #f0f0f0;
}
.provider.disabled .pick {
  opacity: 0.5;
}
.order {
  display: flex;
  gap: 2px;
}
.pick {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}
.hint {
  font-size: 12px;
  color: #888;
  margin: 8px 0 0;
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
.method-row { cursor: pointer; }
.method-row:hover { background: #f0f7ff; }
.method-row.expanded { background: #e8f0fe; }
.name-cell { display: inline-flex; align-items: center; }
.expand-icon {
  font-size: 10px;
  margin-right: 4px;
  width: 12px;
  flex-shrink: 0;
}
.btn-xs {
  padding: 0 6px;
  border: 1px solid #ccc;
  border-radius: 4px;
  background: #fff;
  cursor: pointer;
}
.btn-xs:disabled { opacity: 0.4; cursor: not-allowed; }
.executor-row td {
  padding: 0 !important;
  border-top: none !important;
}

@media (max-width: 640px) {
  table, thead, tbody, tr, th, td { display: block; }
  thead { display: none; }
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
  td:last-child { border-bottom: none; }
  td::before {
    content: attr(data-label);
    font-weight: 600;
    font-size: 11px;
    color: #666;
    min-width: 80px;
    margin-right: 8px;
    flex-shrink: 0;
  }
  .expand-icon { display: none; }
  .executor-row td { display: block; border-bottom: none; }
  .executor-row td::before { display: none; }
}
</style>
