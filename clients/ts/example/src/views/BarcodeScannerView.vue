<script setup lang="ts">
import { ref, shallowRef, computed, watch, onUnmounted } from 'vue';
import { useHalClient } from '../composables/useHalClient';
import { useToast } from '../composables/useToast';
import { SunmiBarcodeScannerClient } from '@kduma-autoid/hal-client-plugin-sunmi-barcode-scanner-facade';
import type { InterfaceProvider, ScanResult } from '@kduma-autoid/hal-client-common';

const { client, isConnected } = useHalClient();
const toast = useToast();

const MAX_SCANS = 50;

interface LoggedScan extends ScanResult {
  at: Date;
}

// The facade resolves the `barcodeScanner` interface and subscribes to
// `barcodeScanner.onScan@<backend>` — the
// event source filter — so this view only ever shows barcodes from the bound scanner, even when
// several scanners (built-in / camera / external) are providing the interface at once.
const scanner = shallowRef<SunmiBarcodeScannerClient | null>(null);
const backends = ref<InterfaceProvider[]>([]);
const selectedBackend = ref<string>('');
const detecting = ref(false);
const scanning = ref(false);
const scans = ref<LoggedScan[]>([]);

let unsubscribeScan: (() => Promise<void>) | null = null;
let unsubscribeChanges: (() => Promise<void>) | null = null;

async function stopScanSubscription() {
  if (unsubscribeScan) {
    try {
      await unsubscribeScan();
    } catch {
      /* ignore */
    }
    unsubscribeScan = null;
  }
}

async function subscribeScans() {
  await stopScanSubscription();
  if (!scanner.value) return;
  unsubscribeScan = await scanner.value.onScan((scan) => {
    scans.value.unshift({ ...scan, at: new Date() });
    if (scans.value.length > MAX_SCANS) scans.value.length = MAX_SCANS;
  });
}

async function bind(pluginId?: string) {
  if (!client.value || !isConnected.value) {
    await stopScanSubscription();
    scanner.value = null;
    backends.value = [];
    return;
  }
  detecting.value = true;
  try {
    backends.value = await SunmiBarcodeScannerClient.listBackends(client.value);
    scanner.value = pluginId
      ? await SunmiBarcodeScannerClient.forBackend(client.value, pluginId)
      : await SunmiBarcodeScannerClient.create(client.value);
    selectedBackend.value = scanner.value.backend;
    await subscribeScans();
  } catch {
    // No provider (or the pinned one vanished) — the template shows the "no backend" banner.
    await stopScanSubscription();
    scanner.value = null;
    selectedBackend.value = '';
  } finally {
    detecting.value = false;
    scanning.value = false;
  }
}

async function teardown() {
  await stopScanSubscription();
  if (unsubscribeChanges) {
    try {
      await unsubscribeChanges();
    } catch {
      /* ignore */
    }
    unsubscribeChanges = null;
  }
}

onUnmounted(teardown);

watch(
  isConnected,
  async (isConn) => {
    if (isConn) {
      await bind();
      if (client.value && !unsubscribeChanges) {
        // Hot-plug / interface reorder: re-resolve, keeping the user's pinned backend if it survives.
        unsubscribeChanges = await SunmiBarcodeScannerClient.onChanged(client.value, () => {
          bind(selectedBackend.value || undefined);
        });
      }
    } else {
      await teardown();
      scanner.value = null;
      backends.value = [];
      scanning.value = false;
    }
  },
  { immediate: true },
);

function onBackendChange(e: Event) {
  bind((e.target as HTMLSelectElement).value);
}

const isReady = computed(() => isConnected.value && scanner.value !== null);
const subscription = computed(() =>
  selectedBackend.value ? `barcodeScanner.onScan@${selectedBackend.value}` : 'barcodeScanner.onScan',
);

async function trigger() {
  try {
    await scanner.value!.trigger();
    scanning.value = true;
  } catch (e) {
    toast.error(e instanceof Error ? e.message : String(e));
  }
}

async function stop() {
  try {
    await scanner.value!.stop();
    scanning.value = false;
  } catch (e) {
    toast.error(e instanceof Error ? e.message : String(e));
  }
}

function clearScans() {
  scans.value = [];
}

function time(d: Date): string {
  return d.toLocaleTimeString(undefined, { hour12: false });
}
</script>

<template>
  <h2>Barcode Scanner</h2>

  <div v-if="!isConnected" class="banner banner-info">
    Not connected.
    <router-link to="/settings">Go to Settings</router-link>
    to configure and connect.
  </div>
  <div v-else-if="detecting && !scanner" class="banner banner-info">Resolving scanner backend...</div>
  <div v-else-if="!scanner" class="banner banner-warning">
    No barcode scanner backend is available — the <code>barcodeScanner</code> interface has no
    provider on this
    service. <router-link to="/interfaces">Go to Interfaces</router-link> to inspect the registry.
  </div>
  <div v-else class="banner banner-info backend-bar">
    <span>
      Active backend:
      <select v-if="backends.length > 1" :value="selectedBackend" @change="onBackendChange">
        <option v-for="b in backends" :key="b.pluginId" :value="b.pluginId">
          {{ b.pluginId }}{{ b.isDefault ? ' (default)' : '' }}
        </option>
      </select>
      <code v-else>{{ selectedBackend }}</code>
    </span>
    <span class="sub">listening on <code>{{ subscription }}</code></span>
  </div>

  <div class="card">
    <h3>Scan Control</h3>
    <div class="row">
      <button class="btn btn-primary" :disabled="!isReady" @click="trigger">Trigger scan</button>
      <button class="btn" :disabled="!isReady" @click="stop">Stop</button>
      <span v-if="scanning" class="scanning">
        <span class="pulse" /> scanning…
      </span>
      <span class="hint">
        Results arrive asynchronously as <code>barcodeScanner.onScan</code> events, filtered to this backend.
      </span>
    </div>
  </div>

  <div class="card">
    <div class="card-head">
      <h3>Scans <span class="count">{{ scans.length }}</span></h3>
      <button class="btn btn-sm" :disabled="!scans.length" @click="clearScans">Clear</button>
    </div>

    <p v-if="!scans.length" class="empty">
      No barcodes yet. Trigger a scan, or use the device's hardware scan button.
    </p>
    <table v-else class="scans">
      <thead>
        <tr>
          <th class="col-time">Time</th>
          <th>Data</th>
          <th class="col-format">Format</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(scan, i) in scans" :key="`${scan.at.getTime()}-${i}`">
          <td class="col-time mono">{{ time(scan.at) }}</td>
          <td>
            <span class="mono data">{{ scan.data }}</span>
            <div v-if="scan.rawData" class="raw mono">raw: {{ scan.rawData }}</div>
          </td>
          <td class="col-format"><span class="badge">{{ scan.format || '—' }}</span></td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
h2 { margin-top: 0; }
h3 { margin: 0 0 12px; font-size: 14px; color: #444; text-transform: uppercase; letter-spacing: 1px; }

.card {
  border: 1px solid #ddd;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 16px;
  background: #fff;
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.card-head h3 { margin-bottom: 0; }
.count {
  font-size: 11px;
  background: #eef2ff;
  color: #4338ca;
  border-radius: 10px;
  padding: 1px 7px;
  margin-left: 6px;
}

.row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.hint { font-size: 12px; color: #aaa; }

.btn {
  padding: 6px 14px;
  border: 1px solid #ccc;
  border-radius: 6px;
  background: #fff;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.btn:hover { background: #f5f5f5; }
.btn-primary { background: #0066cc; color: #fff; border-color: #0066cc; }
.btn-primary:hover { background: #0055aa; }
.btn-sm { padding: 4px 10px; font-weight: 500; }
.btn:disabled { opacity: 0.45; cursor: not-allowed; }

.scanning { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: #16a34a; }
.pulse {
  width: 8px; height: 8px; border-radius: 50%; background: #16a34a;
  animation: pulse 1s ease-in-out infinite;
}
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.25; } }

.empty { color: #9ca3af; font-size: 13px; margin: 0; }

.scans { width: 100%; border-collapse: collapse; font-size: 13px; }
.scans th {
  text-align: left;
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  color: #9ca3af;
  border-bottom: 1px solid #eee;
  padding: 6px 8px;
}
.scans td { padding: 8px; border-bottom: 1px solid #f3f4f6; vertical-align: top; }
.col-time { width: 82px; white-space: nowrap; color: #9ca3af; }
.col-format { width: 110px; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.data { word-break: break-all; }
.raw { font-size: 11px; color: #9ca3af; margin-top: 3px; word-break: break-all; }
.badge {
  font-size: 11px;
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  border-radius: 4px;
  padding: 1px 6px;
  color: #475569;
}

.backend-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.backend-bar select {
  padding: 4px 8px;
  border: 1px solid #bfdbfe;
  border-radius: 6px;
  font-size: 13px;
}
.sub { font-size: 12px; }

.banner {
  padding: 12px 16px;
  border-radius: 6px;
  font-size: 14px;
  margin-bottom: 16px;
}
.banner a { color: #0066cc; }
.banner code { font-size: 12px; background: rgba(0,0,0,0.06); padding: 1px 4px; border-radius: 3px; }
.banner-info { background: #eff6ff; border: 1px solid #bfdbfe; color: #1e40af; }
.banner-warning { background: #fffbeb; border: 1px solid #fde68a; color: #92400e; }
</style>
