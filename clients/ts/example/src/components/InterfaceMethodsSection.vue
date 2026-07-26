<script setup lang="ts">
import { ref, computed } from 'vue';
import type { InterfaceDescriptor } from '@kduma-autoid/hal-client-common';
import MethodExecutor from './MethodExecutor.vue';
import EventMonitor from './EventMonitor.vue';

interface ReceivedEvent {
  timestamp: number;
  data: unknown;
  source?: string;
}

const props = defineProps<{
  contract: InterfaceDescriptor;
  /** 'provider' — this plugin implements the interface (methods callable); 'definer' — read-only. */
  mode: 'provider' | 'definer';
  /** For provider mode: the plugin id to pin via the `method@providerId` suffix, and its features. */
  providerId?: string;
  providerFeatures?: string[];
  receivedEvents?: Record<string, ReceivedEvent[]>;
}>();

const expandedMethods = ref<Set<string>>(new Set());
const expandedEvents = ref<Set<string>>(new Set());
function toggleMethod(n: string) {
  expandedMethods.value.has(n) ? expandedMethods.value.delete(n) : expandedMethods.value.add(n);
}
function toggleEvent(n: string) {
  expandedEvents.value.has(n) ? expandedEvents.value.delete(n) : expandedEvents.value.add(n);
}

const pinnedProvider = computed<string | undefined>(() =>
  props.mode === 'provider' ? props.providerId : undefined,
);

// Feature a method is gated by (if any), and whether the current provider supports it.
function featureFor(methodName: string): string | null {
  return props.contract.features.find((f) => f.methods.includes(methodName))?.key ?? null;
}
function isSupported(methodName: string): boolean {
  if (props.mode !== 'provider') return true;
  const feature = featureFor(methodName);
  if (!feature) return true;
  return (props.providerFeatures ?? []).includes(feature);
}

function getReceivedEvents(name: string): ReceivedEvent[] {
  return props.receivedEvents?.[name] ?? [];
}
function breakableName(name: string): string {
  return name.replace(/\./g, '.​');
}
function prettyJson(raw: string): string {
  if (!raw) return '';
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}
</script>

<template>
  <div class="section">
    <h4 class="iface-head">
      <span class="badge badge-iface">interface</span>
      <code>{{ contract.interfaceId }}</code>
      <span class="mode">{{ mode === 'provider' ? 'provided' : 'defined' }}</span>
      <span v-for="f in contract.features" :key="f.key" class="badge badge-feature" :title="f.description">
        {{ f.key }}
      </span>
    </h4>

    <div v-if="contract.methods.length" class="table-wrap">
      <table>
        <thead>
          <tr><th>Name</th><th>Description</th><th>Permission</th><th>Flags</th></tr>
        </thead>
        <tbody>
          <template v-for="m in contract.methods" :key="m.name">
            <tr class="row" :class="{ expanded: expandedMethods.has(m.name), muted: !isSupported(m.name) }" @click="toggleMethod(m.name)">
              <td data-label="Name">
                <span class="name-cell">
                  <span class="expand-icon">{{ expandedMethods.has(m.name) ? '▼' : '▶' }}</span>
                  <code>{{ breakableName(m.name) }}</code>
                </span>
              </td>
              <td data-label="Description">{{ m.description }}</td>
              <td data-label="Permission"><code>{{ breakableName(m.requiredPermission) }}</code></td>
              <td data-label="Flags" class="flags-cell">
                <span v-if="featureFor(m.name)" class="badge badge-feature">needs {{ featureFor(m.name) }}</span>
                <span v-if="!isSupported(m.name)" class="badge badge-unsupported">unsupported here</span>
              </td>
            </tr>
            <tr v-if="expandedMethods.has(m.name)" class="detail-row">
              <td colspan="4">
                <MethodExecutor v-if="mode === 'provider' && isSupported(m.name)" :method="m" :provider-id="pinnedProvider" />
                <div v-else class="readonly">
                  <template v-if="mode === 'provider'">
                    <p class="note">This provider does not support the “{{ featureFor(m.name) }}” feature, so this method is unavailable here.</p>
                  </template>
                  <div v-if="m.exampleParameters" class="ro-block">
                    <label>Parameters</label>
                    <pre>{{ prettyJson(m.exampleParameters) }}</pre>
                  </div>
                  <div v-if="m.exampleOutput" class="ro-block">
                    <label>Output</label>
                    <pre>{{ prettyJson(m.exampleOutput) }}</pre>
                  </div>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>

    <div v-if="contract.events.length" class="table-wrap events">
      <table>
        <thead>
          <tr><th>Event</th><th>Description</th><th>Permission</th></tr>
        </thead>
        <tbody>
          <template v-for="ev in contract.events" :key="ev.name">
            <tr class="row" :class="{ expanded: expandedEvents.has(ev.name) }" @click="toggleEvent(ev.name)">
              <td data-label="Event">
                <span class="name-cell">
                  <span class="expand-icon">{{ expandedEvents.has(ev.name) ? '▼' : '▶' }}</span>
                  <code>{{ breakableName(ev.name) }}</code>
                </span>
                <span v-if="getReceivedEvents(ev.name).length" class="badge badge-count">{{ getReceivedEvents(ev.name).length }}</span>
              </td>
              <td data-label="Description">{{ ev.description }}</td>
              <td data-label="Permission"><code>{{ breakableName(ev.requiredPermission) }}</code></td>
            </tr>
            <tr v-if="expandedEvents.has(ev.name)" class="detail-row">
              <td colspan="3">
                <EventMonitor v-if="mode === 'provider'" :event="ev" :received-events="getReceivedEvents(ev.name)" />
                <div v-else class="readonly">
                  <div v-if="ev.exampleEvent" class="ro-block">
                    <label>Example Event</label>
                    <pre>{{ prettyJson(ev.exampleEvent) }}</pre>
                  </div>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.section { margin-top: 16px; }
.iface-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin: 0 0 8px;
  font-size: 14px;
  color: #444;
  border-top: 1px solid #e0e7ff;
  padding-top: 12px;
}
.mode { font-size: 12px; color: #888; font-weight: 400; }
.badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 600;
}
.badge-iface { background: #ede9fe; color: #5b21b6; }
.badge-feature { background: #ecfdf5; color: #065f46; }
.badge-unsupported { background: #fee2e2; color: #991b1b; }
.badge-count { background: #dbeafe; color: #1e40af; margin-left: 6px; }
.table-wrap { overflow-x: auto; -webkit-overflow-scrolling: touch; }
.table-wrap.events { margin-top: 8px; }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th, td { text-align: left; padding: 6px 8px; border: 1px solid #e5e7eb; }
th { background: #f9fafb; font-weight: 600; }
code { font-size: 12px; background: #f3f4f6; padding: 1px 4px; border-radius: 3px; }
.row { cursor: pointer; }
.row:hover { background: #f0f7ff; }
.row.expanded { background: #e8f0fe; }
.row.muted { opacity: 0.6; }
.name-cell { display: inline-flex; align-items: center; }
.expand-icon { font-size: 10px; margin-right: 4px; width: 12px; flex-shrink: 0; }
.detail-row td { padding: 0 !important; border-top: none !important; }
.readonly { padding: 12px 16px; background: #fafbfc; border-top: 1px solid #e5e7eb; }
.readonly .note { margin: 0 0 8px; font-size: 12px; color: #b45309; }
.ro-block { margin-bottom: 8px; }
.ro-block label { display: block; font-size: 12px; font-weight: 600; color: #555; margin-bottom: 4px; }
.ro-block pre {
  margin: 0;
  padding: 8px 12px;
  font-family: monospace;
  font-size: 12px;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 4px;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-word;
}

@media (max-width: 640px) {
  table, thead, tbody, tr, th, td { display: block; }
  thead { display: none; }
  tbody tr { margin-bottom: 8px; border: 1px solid #e5e7eb; border-radius: 6px; overflow: hidden; }
  td { display: flex; justify-content: space-between; align-items: flex-start; padding: 6px 10px; border: none; border-bottom: 1px solid #f0f0f0; }
  td:last-child { border-bottom: none; }
  td::before { content: attr(data-label); font-weight: 600; font-size: 11px; color: #666; min-width: 80px; margin-right: 8px; flex-shrink: 0; }
  .expand-icon { display: none; }
  .detail-row td { display: block; border-bottom: none; }
  .detail-row td::before { display: none; }
}
</style>
