<script setup lang="ts">
import { ref, shallowRef, computed, watch, onUnmounted } from 'vue';
import { useHalClient } from '../composables/useHalClient';
import { useToast } from '../composables/useToast';
import { SunmiBarcodeScannerClient } from '@kduma-autoid/hal-client-plugin-sunmi-barcode-scanner-facade';
import type { InterfaceProvider, ScanResult } from '@kduma-autoid/hal-client-common';
import { bindBackend, enabledBackends, serialized } from '../composables/backendBinding';

const { client, isConnected } = useHalClient();
const toast = useToast();

const MAX_SCANS = 50;
const SS2222_BACKEND = 'sunmi.scanner.ss2222';
const SS2222_STATE_EVENT = 'sunmi.scanner.ss2222.stateChanged@sunmi.scanner.ss2222';

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
// Subscribing can fail on its own (HTTP has no event channel, and the permission gate can refuse)
// while the scanner itself resolved fine — kept apart so the view does not claim there is no backend.
const scanError = ref('');
const bindError = ref('');
const usable = computed(() => enabledBackends(backends.value));

let unsubscribeScan: (() => Promise<void>) | null = null;
let unsubscribeState: (() => Promise<void>) | null = null;
let unsubscribeChanges: (() => Promise<void>) | null = null;
let ss2222StateVersion = 0;

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

async function stopStateSubscription() {
  if (unsubscribeState) {
    try {
      await unsubscribeState();
    } catch {
      /* ignore */
    }
    unsubscribeState = null;
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

async function subscribeSs2222State() {
  await stopStateSubscription();
  if (!scanner.value || scanner.value.backend !== SS2222_BACKEND || !client.value) return;
  const boundScanner = scanner.value;

  unsubscribeState = await client.value.on(SS2222_STATE_EVENT, (_name, data) => {
    // An unsubscribe can race an already queued event during rebind. Do not let an old bound
    // scanner mutate the state of the new one.
    if (scanner.value !== boundScanner) return;
    if (!data || typeof data !== 'object') return;
    const state = (data as { state?: unknown }).state;
    if (typeof state !== 'string') return;

    ss2222StateVersion += 1;
    // The SS2222 backend publishes uppercase lifecycle states. Unknown/future states are treated
    // as non-scanning until a known active state arrives.
    scanning.value = state === 'STARTING' || state === 'SCANNING';
  });
}

// Two `system.interfaces.changed` in a row would otherwise run bind() concurrently and leave two
// onScan subscriptions, one of which nothing ever unsubscribes.
const bind = serialized((pluginId?: string) => bindOnce(pluginId));

async function bindOnce(pluginId?: string) {
  // Invalidate an optimistic operation from the previous binding, even when this bind ends up
  // disconnected or without a provider.
  ss2222StateVersion += 1;
  if (!client.value || !isConnected.value) {
    await stopScanSubscription();
    await stopStateSubscription();
    scanner.value = null;
    backends.value = [];
    return;
  }
  const c = client.value;
  detecting.value = true;
  try {
    backends.value = await SunmiBarcodeScannerClient.listBackends(c);
    const { bound, pinFailed } = await bindBackend(
      pluginId,
      (id) => SunmiBarcodeScannerClient.forBackend(c, id),
      () => SunmiBarcodeScannerClient.create(c),
    );
    scanner.value = bound;
    selectedBackend.value = bound.backend;
    bindError.value = '';
    if (pinFailed) toast.info(`${pinFailed} is not available — using ${bound.backend}`);
  } catch (e) {
    // Nothing bindable. The template tells "no provider at all" apart from "all disabled" and from
    // a failure while enabled providers exist — only the first is "no backend".
    await stopScanSubscription();
    await stopStateSubscription();
    scanner.value = null;
    selectedBackend.value = '';
    bindError.value = e instanceof Error ? e.message : String(e);
    return;
  } finally {
    detecting.value = false;
    scanning.value = false;
  }

  // Subscribing is a separate failure: on the HTTP transport there is no event channel at all, and
  // folding that into the catch above cleared a scanner that listBackends had just resolved, so the
  // view claimed there was no provider while showing a list of them.
  try {
    await subscribeScans();
    scanError.value = '';
  } catch (e) {
    await stopScanSubscription();
    scanError.value = e instanceof Error ? e.message : String(e);
  }

  // This is deliberately independent of the scan subscription: HTTP can resolve the backend but
  // cannot carry events, while the SS2222 lifecycle event is useful on the WebSocket transport.
  try {
    await subscribeSs2222State();
  } catch {
    await stopStateSubscription();
  }
}

async function teardown() {
  await stopScanSubscription();
  await stopStateSubscription();
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
  const boundScanner = scanner.value!;
  const isSs2222 = boundScanner.backend === SS2222_BACKEND;
  const optimisticStateVersion = ss2222StateVersion;
  const previousScanning = scanning.value;
  // The scanner may publish SCANNING before the trigger ACK resolves. Set this first and do not
  // write it again after the ACK for this backend, otherwise the ACK can race and revert the UI.
  if (isSs2222) scanning.value = true;
  try {
    await boundScanner.trigger();
    if (!isSs2222) scanning.value = true;
  } catch (e) {
    if (isSs2222) {
      await reconcileSs2222AfterError(boundScanner, optimisticStateVersion, previousScanning);
    }
    toast.error(e instanceof Error ? e.message : String(e));
  }
}

async function stop() {
  const boundScanner = scanner.value!;
  const isSs2222 = boundScanner.backend === SS2222_BACKEND;
  const optimisticStateVersion = ss2222StateVersion;
  const previousScanning = scanning.value;
  if (isSs2222) scanning.value = false;
  try {
    await boundScanner.stop();
    if (!isSs2222) scanning.value = false;
  } catch (e) {
    if (isSs2222) {
      await reconcileSs2222AfterError(boundScanner, optimisticStateVersion, previousScanning);
    }
    toast.error(e instanceof Error ? e.message : String(e));
  }
}

async function reconcileSs2222AfterError(
  boundScanner: SunmiBarcodeScannerClient,
  optimisticStateVersion: number,
  previousScanning: boolean,
) {
  // A state event is authoritative. Only query/rollback while this is still the same binding and
  // no event has arrived since the optimistic write.
  if (scanner.value !== boundScanner || ss2222StateVersion !== optimisticStateVersion) return;

  try {
    const status = await client.value?.execute<{ state?: unknown }>(
      `${SS2222_BACKEND}.getStatus`,
      {},
    );
    if (scanner.value !== boundScanner || ss2222StateVersion !== optimisticStateVersion) return;
    const state = status && typeof status.state === 'string' ? status.state : undefined;
    if (state) {
      scanning.value = state === 'STARTING' || state === 'SCANNING';
    } else {
      scanning.value = previousScanning;
    }
  } catch {
    if (scanner.value === boundScanner && ss2222StateVersion === optimisticStateVersion) {
      scanning.value = previousScanning;
    }
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
  <div v-else-if="scanner && scanError" class="banner banner-warning">
    Bound to <code>{{ selectedBackend }}</code>, but live scans are unavailable: {{ scanError }}
    Trigger still works; switch to the WebSocket transport in
    <router-link to="/settings">Settings</router-link> to receive <code>onScan</code>.
  </div>
  <div v-else-if="!scanner && usable.length" class="banner banner-warning">
    Could not bind a barcode scanner backend: {{ bindError }}
    <router-link to="/interfaces">Go to Interfaces</router-link> to inspect the registry.
  </div>
  <div v-else-if="!scanner && backends.length" class="banner banner-warning">
    Every provider of the <code>barcodeScanner</code> interface is disabled.
    <router-link to="/interfaces">Go to Interfaces</router-link> to enable one.
  </div>
  <div v-else-if="!scanner" class="banner banner-warning">
    No barcode scanner backend is available — the <code>barcodeScanner</code> interface has no
    provider on this
    service. <router-link to="/interfaces">Go to Interfaces</router-link> to inspect the registry.
  </div>
  <div v-else class="banner banner-info backend-bar">
    <span>
      Active backend:
      <select v-if="backends.length > 1" :value="selectedBackend" @change="onBackendChange">
        <option v-for="b in backends" :key="b.pluginId" :value="b.pluginId" :disabled="b.enabled === false">
          {{ b.pluginId }}{{ b.isDefault ? ' (default)' : '' }}{{ b.enabled === false ? ' (disabled)' : '' }}
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
