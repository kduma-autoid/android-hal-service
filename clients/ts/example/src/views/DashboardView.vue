<script setup lang="ts">
import { ref, onMounted, watch } from 'vue';
import type { HealthResponse, StatusResponse } from '@kduma-autoid/hal-client-common';
import { useHalClient } from '../composables/useHalClient';

const { client, isConnected } = useHalClient();

const health = ref<HealthResponse | null>(null);
const status = ref<StatusResponse | null>(null);
const loading = ref(false);
const error = ref<string | null>(null);

async function fetchData() {
  if (!client.value || !isConnected.value) return;

  loading.value = true;
  error.value = null;
  try {
    const [h, s] = await Promise.all([
      client.value.getHealth(),
      client.value.getStatus(),
    ]);
    health.value = h;
    status.value = s;
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
}

function formatUptime(seconds: number): string {
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  const parts: string[] = [];
  if (d > 0) parts.push(`${d}d`);
  if (h > 0) parts.push(`${h}h`);
  if (m > 0) parts.push(`${m}m`);
  parts.push(`${s}s`);
  return parts.join(' ');
}

onMounted(() => {
  if (isConnected.value) fetchData();
});

watch(isConnected, (connected) => {
  if (connected) fetchData();
  else { health.value = null; status.value = null; }
});
</script>

<template>
  <h2>Dashboard</h2>

  <div v-if="!isConnected" class="not-connected">
    Not connected.
    <router-link to="/settings">Go to Settings</router-link>
    to configure and connect.
  </div>

  <template v-else>
    <button class="btn" @click="fetchData" :disabled="loading">
      {{ loading ? 'Loading...' : 'Refresh' }}
    </button>

    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="loading && !health" class="loading">Loading...</div>

    <template v-if="health || status">
      <!-- Health -->
      <div v-if="health" class="card">
        <h3>Health</h3>
        <div class="info-grid">
          <span class="label">Ping</span>
          <span><span class="dot ok" /> OK</span>
          <span class="label">Timestamp</span>
          <span>{{ new Date(health.timestamp * 1000).toLocaleString() }}</span>
        </div>
      </div>

      <!-- Status -->
      <div v-if="status" class="card">
        <h3>Service Status</h3>
        <div class="info-grid">
          <span class="label">Uptime</span>
          <span>{{ formatUptime(status.uptime) }}</span>
          <template v-if="status.version">
            <span class="label">Version</span>
            <span>{{ status.version.name ?? '?' }}<template v-if="status.version.code"> ({{ status.version.code }})</template></span>
          </template>
        </div>

        <h4>Plugins ({{ Object.keys(status.plugins).length }})</h4>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Plugin ID</th>
                <th>Version</th>
                <th>Source</th>
                <th>Capabilities</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(info, id) in status.plugins" :key="id">
                <td><code>{{ id }}</code></td>
                <td>{{ info.version }}</td>
                <td>{{ info.source }}</td>
                <td>
                  <span v-for="cap in info.capabilities" :key="cap" class="badge">{{ cap }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <h4>Transports ({{ Object.keys(status.transports).length }})</h4>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Transport</th>
                <th>Running</th>
                <th>Enabled</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(info, id) in status.transports" :key="id">
                <td>{{ id }}</td>
                <td>
                  <span class="dot" :class="info.running ? 'ok' : 'off'" />
                  {{ info.running ? 'Yes' : 'No' }}
                </td>
                <td>
                  <template v-if="info.enabled !== undefined">
                    {{ info.enabled ? 'Yes' : 'No' }}
                  </template>
                  <template v-else>-</template>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>
  </template>
</template>

<style scoped>
h2 { margin-top: 0; }
h3 { margin: 0 0 12px; }
h4 { margin: 16px 0 8px; font-size: 14px; color: #444; }
.card {
  border: 1px solid #ddd;
  border-radius: 8px;
  padding: 16px;
  margin-top: 16px;
  background: #fff;
}
.info-grid {
  display: grid;
  grid-template-columns: 120px 1fr;
  gap: 6px 12px;
  font-size: 14px;
}
.label { font-weight: 600; color: #555; }
.dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 4px;
}
.dot.ok { background: #22c55e; }
.dot.off { background: #ef4444; }
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
th { background: #f9fafb; font-weight: 600; }
code {
  font-size: 12px;
  background: #f3f4f6;
  padding: 1px 4px;
  border-radius: 3px;
}
.badge {
  display: inline-block;
  padding: 1px 6px;
  margin-right: 4px;
  border-radius: 8px;
  font-size: 11px;
  background: #ecfdf5;
  color: #065f46;
}
.btn {
  padding: 6px 14px;
  border: 1px solid #ccc;
  border-radius: 6px;
  background: #fff;
  font-size: 13px;
  cursor: pointer;
}
.btn:hover { background: #f5f5f5; }
.btn:disabled { opacity: 0.5; cursor: default; }
.not-connected {
  padding: 24px;
  text-align: center;
  color: #666;
  font-size: 15px;
}
.not-connected a { color: #0066cc; }
.error {
  margin-top: 12px;
  padding: 10px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 6px;
  color: #dc2626;
  font-size: 13px;
}
.loading {
  text-align: center;
  padding: 24px;
  color: #888;
}
</style>
