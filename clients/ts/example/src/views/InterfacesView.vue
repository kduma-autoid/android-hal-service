<script setup lang="ts">
import { ref, onMounted, watch } from 'vue';
import type {
  DescribeResponse,
  InterfaceDescriptor,
  MethodDescriptor,
} from '@kduma-autoid/hal-client-common';
import { PROVIDER_PARAM_KEY } from '@kduma-autoid/hal-client-common';
import { useHalClient } from '../composables/useHalClient';

const { client, isConnected } = useHalClient();

const interfaces = ref<InterfaceDescriptor[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);
const selectedProvider = ref<Record<string, string>>({});
const paramsByMethod = ref<Record<string, string>>({});
const responseByMethod = ref<Record<string, { text: string; error: boolean }>>({});

function paramsKey(interfaceId: string, method: string): string {
  return `${interfaceId}::${method}`;
}

function prettyJson(raw: string): string {
  if (!raw) return '{}';
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

async function load(): Promise<void> {
  if (!client.value) return;
  loading.value = true;
  error.value = null;
  try {
    const res = await client.value.execute<DescribeResponse>('system.describe', {});
    interfaces.value = res.interfaces ?? [];
    for (const iface of interfaces.value) {
      const def = iface.providers.find((p) => p.isDefault);
      selectedProvider.value[iface.interfaceId] = def?.pluginId ?? '';
      for (const m of iface.methods) {
        const key = paramsKey(iface.interfaceId, m.name);
        if (!(key in paramsByMethod.value)) {
          paramsByMethod.value[key] = prettyJson(m.exampleParameters);
        }
      }
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
}

async function moveProvider(iface: InterfaceDescriptor, index: number, delta: number): Promise<void> {
  if (!client.value) return;
  const ids = iface.providers.map((p) => p.pluginId);
  const target = index + delta;
  if (target < 0 || target >= ids.length) return;
  [ids[index], ids[target]] = [ids[target], ids[index]];
  await client.value.execute('system.interface.setOrder', { interfaceId: iface.interfaceId, order: ids });
  await load();
}

async function setProviderEnabled(iface: InterfaceDescriptor, pluginId: string, enabled: boolean): Promise<void> {
  if (!client.value) return;
  await client.value.execute('system.interface.setEnabled', {
    interfaceId: iface.interfaceId,
    pluginId,
    enabled,
  });
  await load();
}

async function callMethod(iface: InterfaceDescriptor, method: MethodDescriptor): Promise<void> {
  if (!client.value) return;
  const key = paramsKey(iface.interfaceId, method.name);
  try {
    const raw = paramsByMethod.value[key] ?? '{}';
    const parsed = (raw.trim() ? JSON.parse(raw) : {}) as Record<string, unknown>;
    const provider = selectedProvider.value[iface.interfaceId];
    const def = iface.providers.find((p) => p.isDefault)?.pluginId;
    if (provider && provider !== def) parsed[PROVIDER_PARAM_KEY] = provider;
    const result = await client.value.execute(method.name, parsed);
    responseByMethod.value[key] = { text: JSON.stringify(result, null, 2), error: false };
  } catch (e) {
    responseByMethod.value[key] = { text: e instanceof Error ? e.message : String(e), error: true };
  }
}

onMounted(load);
watch(isConnected, (connected) => {
  if (connected) load();
});
</script>

<template>
  <div class="view">
    <div class="view-header">
      <h1>Interfaces</h1>
      <button class="btn" :disabled="!client || loading" @click="load">Refresh</button>
    </div>

    <p v-if="!isConnected" class="muted">Not connected.</p>
    <p v-else-if="error" class="error">{{ error }}</p>
    <p v-else-if="loading" class="muted">Loading…</p>
    <p v-else-if="interfaces.length === 0" class="muted">No interfaces registered.</p>

    <div v-for="iface in interfaces" :key="iface.interfaceId" class="card">
      <div class="card-head">
        <h2>{{ iface.interfaceId }} <span class="ver">v{{ iface.version }}</span></h2>
        <span v-if="iface.features.length" class="muted small">
          features: {{ iface.features.map((f) => f.key).join(', ') }}
        </span>
      </div>

      <h3>Providers</h3>
      <p v-if="iface.providers.length === 0" class="muted">No available providers.</p>
      <ul v-else class="providers">
        <li
          v-for="(p, i) in iface.providers"
          :key="p.pluginId"
          class="provider"
          :class="{ disabled: !p.enabled }"
        >
          <span class="order">
            <button class="btn-xs" :disabled="i === 0" @click="moveProvider(iface, i, -1)">↑</button>
            <button class="btn-xs" :disabled="i === iface.providers.length - 1" @click="moveProvider(iface, i, 1)">↓</button>
          </span>
          <input
            type="checkbox"
            class="en"
            :checked="p.enabled"
            title="Enabled"
            @change="setProviderEnabled(iface, p.pluginId, ($event.target as HTMLInputElement).checked)"
          />
          <label class="pick">
            <input
              type="radio"
              :name="'prov-' + iface.interfaceId"
              :value="p.pluginId"
              v-model="selectedProvider[iface.interfaceId]"
            />
            <span class="mono">{{ p.pluginId }}</span>
            <span v-if="p.isDefault" class="badge">default</span>
            <span v-if="p.features.length" class="muted small"> · {{ p.features.join(', ') }}</span>
          </label>
        </li>
      </ul>
      <p class="muted small">
        Reorder sets the server default (top). The selected provider pins calls below via
        <code>__provider</code>.
      </p>

      <h3>Methods</h3>
      <div v-for="m in iface.methods" :key="m.name" class="method">
        <div class="method-head">
          <span class="mono bold">{{ m.name }}</span>
          <span class="muted small">{{ m.description }}</span>
        </div>
        <textarea
          v-model="paramsByMethod[paramsKey(iface.interfaceId, m.name)]"
          class="params"
          rows="3"
          spellcheck="false"
        />
        <div class="method-actions">
          <button class="btn" :disabled="!client" @click="callMethod(iface, m)">Execute</button>
          <span class="muted small">provider: {{ selectedProvider[iface.interfaceId] || '(default)' }}</span>
        </div>
        <pre
          v-if="responseByMethod[paramsKey(iface.interfaceId, m.name)]"
          class="response"
          :class="{ error: responseByMethod[paramsKey(iface.interfaceId, m.name)].error }"
        >{{ responseByMethod[paramsKey(iface.interfaceId, m.name)].text }}</pre>
      </div>
    </div>
  </div>
</template>

<style scoped>
.view {
  max-width: 960px;
  margin: 0 auto;
  padding: 16px;
}
.view-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.card {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 16px;
  background: #fff;
}
.card-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.ver {
  font-size: 12px;
  color: #6b7280;
  font-weight: 400;
}
.providers {
  list-style: none;
  padding: 0;
  margin: 4px 0;
}
.provider {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
}
.order {
  display: flex;
  gap: 2px;
}
.provider.disabled .pick {
  opacity: 0.55;
}
.en {
  margin: 0 2px;
}
.pick {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}
.badge {
  font-size: 11px;
  background: #dbeafe;
  color: #1e40af;
  border-radius: 4px;
  padding: 1px 6px;
}
.method {
  border-top: 1px solid #f0f0f0;
  padding: 8px 0;
}
.method-head {
  display: flex;
  gap: 8px;
  align-items: baseline;
  flex-wrap: wrap;
}
.params {
  width: 100%;
  font-family: monospace;
  font-size: 12px;
  padding: 6px;
  border: 1px solid #d1d5db;
  border-radius: 4px;
  box-sizing: border-box;
  margin: 6px 0;
  resize: vertical;
}
.method-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.response {
  margin: 8px 0 0;
  padding: 8px;
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  border-radius: 4px;
  font-size: 12px;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
.response.error {
  background: #fef2f2;
  border-color: #fecaca;
  color: #dc2626;
}
.mono {
  font-family: monospace;
}
.bold {
  font-weight: 600;
}
.muted {
  color: #6b7280;
}
.small {
  font-size: 12px;
}
.error {
  color: #dc2626;
}
.btn {
  padding: 6px 14px;
  border: 1px solid #ccc;
  border-radius: 6px;
  background: #fff;
  font-size: 13px;
  cursor: pointer;
}
.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn-xs {
  padding: 0 6px;
  border: 1px solid #ccc;
  border-radius: 4px;
  background: #fff;
  cursor: pointer;
}
.btn-xs:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
code {
  font-family: monospace;
  background: #f3f4f6;
  padding: 0 4px;
  border-radius: 3px;
}
</style>
