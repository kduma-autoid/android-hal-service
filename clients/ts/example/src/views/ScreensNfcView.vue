<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue';
import type { HalClient } from '@kduma-autoid/hal-client';
import type {
  NfcAntennaLocation,
  NfcAntennaLocationsSnapshot,
  NfcAntennaModule,
  NfcSnapshotAsyncRequest,
  NfcSnapshotAsyncState,
} from '@kduma-autoid/hal-client-plugin-sunmi-nfc';
import {
  isCurrentNfcSnapshotRequest,
  shouldApplyNfcGetterResult,
} from '@kduma-autoid/hal-client-plugin-sunmi-nfc';
import { useHalClient } from '../composables/useHalClient';
import { usePluginAvailability } from '../composables/usePluginAvailability';
import { useToast } from '../composables/useToast';
import {
  SunmiScreenClient,
  PLUGIN_ID as SCREEN_PLUGIN_ID,
} from '@kduma-autoid/hal-client-plugin-sunmi-screen';
import {
  SunmiNfcClient,
  PLUGIN_ID as NFC_PLUGIN_ID,
} from '@kduma-autoid/hal-client-plugin-sunmi-nfc';

type NfcDemoClient = Pick<HalClient, 'getDescribe' | 'onConnectionStateChange'> &
  Pick<HalClient, 'execute' | 'on'>;

const { client, isConnected, connect } = useHalClient();
const toast = useToast();
const {
  isAvailable: screenAvailable,
  plugins: screenPluginStatuses,
  loading: screenPluginLoading,
} = usePluginAvailability({
  pluginId: SCREEN_PLUGIN_ID,
  capabilities: ['sunmi.screen'],
});
const {
  isAvailable: nfcAvailable,
  plugins: nfcPluginStatuses,
  loading: nfcPluginLoading,
} = usePluginAvailability({
  pluginId: NFC_PLUGIN_ID,
  capabilities: ['sunmi.nfc'],
});

const screenReady = computed(() => isConnected.value && screenAvailable.value === true);
const nfcReady = computed(() => isConnected.value && nfcAvailable.value === true);

const screen = computed(() =>
  client.value ? new SunmiScreenClient(client.value) : null,
);
const nfc = computed(() =>
  client.value ? new SunmiNfcClient(client.value) : null,
);

const screens = ref<Record<string, string>>({});
const loading = ref(false);
const nfcModule = ref('');
const nfcAlpha = ref(100);
const brightnessValues = ref<Record<string, number>>({});
const antennaSnapshot = ref<NfcAntennaLocationsSnapshot | null>(null);
const antennaApiAvailable = ref<boolean | null>(null);
const antennaEventAvailable = ref(false);
const antennaLoading = ref(false);
const antennaError = ref<string | null>(null);
const antennaDataStale = ref(false);

let screenGeneration = 0;
let nfcGeneration = 0;
let antennaEventRevision = 0;
let unsubscribeAntenna: (() => Promise<void>) | null = null;
let removeConnectionStateListener: (() => void) | null = null;

const antennaModules = computed(() => antennaSnapshot.value?.modules ?? []);
const selectedAntennaModule = computed(() => antennaModules.value.find((module) => module.selected));
const screenEntries = computed(() => Object.entries(screens.value));
const nfcPickerEntries = computed(() => {
  if (antennaApiAvailable.value === true) {
    return antennaModules.value.map((module) => ({
      sn: module.sn,
      label: `${module.sn} (type ${module.sdkType})`,
      selected: module.selected,
    }));
  }
  // Older HAL services have no antenna API; retain the original screen-backed picker.
  return screenEntries.value.map(([sn, model]) => ({ sn, label: `${model} (${sn})`, selected: false }));
});

function applyAntennaSnapshot(snapshot: NfcAntennaLocationsSnapshot): void {
  const selected = snapshot.modules.find((module) => module.selected);
  const previousSelected = selectedAntennaModule.value?.sn;
  antennaSnapshot.value = snapshot;
  if (previousSelected === undefined || previousSelected !== selected?.sn) {
    nfcModule.value = selected?.sn ?? '';
  }
}

function getLocationLabel(module: NfcAntennaModule): string {
  if (module.location) {
    return module.location.estimated ? 'Estimated natural-coordinate interpretation' : 'Device coordinates';
  }
  switch (module.locationUnavailableReason) {
    case 'external_location_unknown': return 'External reader position unknown';
    case 'reader_identity_unknown': return 'Reader identity unknown';
    case 'missing_location': return 'No location reported';
    case 'invalid_location': return 'Invalid location reported';
    case 'settings_unreadable': return 'Device settings unreadable';
    case 'display_unavailable': return 'Display unavailable';
    case 'unsupported_display_metrics': return 'Display metrics unsupported';
    default: return 'Location unavailable';
  }
}

function locationRectStyle(location: NfcAntennaLocation): Record<string, string> {
  return {
    left: `${(location.rect.x / location.displayWidthPx) * 100}%`,
    top: `${(location.rect.y / location.displayHeightPx) * 100}%`,
    width: `${(location.rect.width / location.displayWidthPx) * 100}%`,
    height: `${(location.rect.height / location.displayHeightPx) * 100}%`,
  };
}

function currentNfcState(): NfcSnapshotAsyncState {
  return {
    generation: nfcGeneration,
    eventRevision: antennaEventRevision,
    client: client.value,
    ready: isConnected.value && nfcReady.value,
    stale: antennaDataStale.value,
  };
}

async function teardownAntennaSubscription(): Promise<void> {
  if (!unsubscribeAntenna) return;
  const unsubscribe = unsubscribeAntenna;
  unsubscribeAntenna = null;
  try {
    await unsubscribe();
  } catch {
    // The socket may already be gone; local teardown is still complete.
  }
}

function stopConnectionStateListener(): void {
  removeConnectionStateListener?.();
  removeConnectionStateListener = null;
}

function watchConnectionState(c: NfcDemoClient, generation: number): void {
  stopConnectionStateListener();
  try {
    removeConnectionStateListener = c.onConnectionStateChange((event) => {
      if (generation !== nfcGeneration) return;
      if (event.state === 'reconnecting' || event.state === 'disconnected') {
        nfcGeneration++;
        antennaDataStale.value = true;
        antennaLoading.value = false;
        antennaError.value = 'Connection lost; NFC location data is stale.';
        void teardownAntennaSubscription();
      }
    });
  } catch {
    // HTTP clients do not expose connection state.
  }
}

async function refreshAntennaApi(c: NfcDemoClient, generation: number): Promise<boolean> {
  const describe = await c.getDescribe();
  if (generation !== nfcGeneration || client.value !== c || antennaDataStale.value) return false;
  const plugin = describe.plugins.find((candidate) => candidate.pluginId === NFC_PLUGIN_ID);
  const methods = plugin?.groups.flatMap((group) => group.methods) ?? [];
  const events = plugin?.groups.flatMap((group) => group.events) ?? [];
  antennaApiAvailable.value = methods.some((method) => method.name === 'sunmi.nfc.getAntennaLocations');
  antennaEventAvailable.value = events.some((event) => event.name === 'sunmi.nfc.antennaLocationsChanged');
  return antennaApiAvailable.value;
}

async function syncAntennaLocations(): Promise<void> {
  const generation = ++nfcGeneration;
  const c = client.value as unknown as NfcDemoClient | null;
  stopConnectionStateListener();
  if (c) watchConnectionState(c, generation);
  await teardownAntennaSubscription();
  if (generation !== nfcGeneration || client.value !== c || !nfcReady.value) return;

  antennaSnapshot.value = null;
  antennaApiAvailable.value = null;
  antennaEventAvailable.value = false;
  antennaError.value = null;
  antennaDataStale.value = false;
  antennaEventRevision = 0;

  if (!c || !nfcReady.value) return;
  antennaLoading.value = true;
  try {
    if (!await refreshAntennaApi(c, generation)) return;
    const operationRequest: NfcSnapshotAsyncRequest = {
      generation,
      eventRevision: antennaEventRevision,
      client: c,
    };
    if (!isCurrentNfcSnapshotRequest(operationRequest, currentNfcState())) return;
    const facade = new SunmiNfcClient(c);

    // Subscribe before querying so a callback cannot be missed during startup.
    if (antennaEventAvailable.value) {
      const unsubscribe = await facade.onAntennaLocationsChanged((snapshot) => {
        if (generation !== nfcGeneration || client.value !== c || antennaDataStale.value) return;
        antennaEventRevision++;
        applyAntennaSnapshot(snapshot);
      });
      if (generation !== nfcGeneration || client.value !== c || antennaDataStale.value) {
        try {
          await unsubscribe();
        } catch {
          // The late handle belongs to a dead/replaced connection.
        }
        return;
      }
      unsubscribeAntenna = unsubscribe;
    }

    const request: NfcSnapshotAsyncRequest = {
      generation,
      eventRevision: antennaEventRevision,
      client: c,
    };
    const snapshot = await facade.getAntennaLocations();
    if (shouldApplyNfcGetterResult(request, currentNfcState())) {
      applyAntennaSnapshot(snapshot);
    }
  } catch (e) {
    if (generation === nfcGeneration && client.value === c && !antennaDataStale.value) {
      antennaError.value = e instanceof Error ? e.message : String(e);
    }
  } finally {
    if (generation === nfcGeneration) antennaLoading.value = false;
  }
}

async function reconnectForFreshNfcData(): Promise<void> {
  antennaDataStale.value = true;
  await connect();
}

async function exec(fn: () => Promise<unknown>) {
  try {
    await fn();
    toast.success('Success');
  } catch (e) {
    toast.error(e instanceof Error ? e.message : String(e));
  }
}

async function refreshScreens() {
  const c = client.value;
  const generation = ++screenGeneration;
  if (!c || !screenReady.value) return;
  loading.value = true;
  try {
    const res = await new SunmiScreenClient(c).getDeviceInfo();
    if (generation === screenGeneration && client.value === c && res?.info) {
      const data: Record<string, string> = {};
      for (const [k, v] of Object.entries(res.info)) {
        data[k] = String(v);
      }
      screens.value = data;
      for (const sn of Object.keys(data)) {
        if (!(sn in brightnessValues.value)) {
          brightnessValues.value[sn] = 80;
        }
      }
    }
  } catch (e) {
    toast.error(e instanceof Error ? e.message : String(e));
  } finally {
    if (generation === screenGeneration) loading.value = false;
  }
}

function setScreenSwitch(sn: string, enabled: boolean) {
  exec(() => screen.value!.setScreenSwitch(sn, enabled));
}

function setTouchSwitch(sn: string, enabled: boolean) {
  exec(() => screen.value!.setTouchSwitch(sn, enabled));
}

function setBrightness(sn: string) {
  const val = brightnessValues.value[sn] ?? 80;
  exec(() => screen.value!.setBrightness(sn, val));
}

function setNfcModule() {
  exec(() => nfc.value!.switchModule(nfcModule.value || undefined));
}

function setNfcWatermark() {
  if (!nfc.value) return;
  exec(() => nfc.value!.setWatermarkAlpha(nfcAlpha.value));
}

onMounted(() => {
  if (screenReady.value) void refreshScreens();
  if (nfcReady.value) void syncAntennaLocations();
});

watch(screenReady, (ready) => {
  if (ready) void refreshScreens();
  else {
    screenGeneration++;
    screens.value = {};
  }
});

watch([nfcReady, client], ([ready]) => {
  if (ready) void syncAntennaLocations();
  else {
    nfcGeneration++;
    stopConnectionStateListener();
    void teardownAntennaSubscription();
    antennaSnapshot.value = null;
    antennaApiAvailable.value = null;
    antennaError.value = null;
    antennaDataStale.value = false;
    antennaLoading.value = false;
  }
});

onUnmounted(() => {
  screenGeneration++;
  nfcGeneration++;
  stopConnectionStateListener();
  void teardownAntennaSubscription();
});
</script>

<template>
  <h2>Screens & NFC</h2>

  <div v-if="!isConnected" class="banner banner-info">
    Not connected.
    <router-link to="/settings">Go to Settings</router-link>
    to configure and connect.
  </div>
  <div v-else-if="screenPluginLoading || nfcPluginLoading" class="banner banner-info">
    Checking plugin availability...
  </div>
  <template v-else>
    <div
      v-for="(p, i) in screenPluginStatuses.filter(p => p.available === false)"
      :key="p.pluginId ?? i"
      class="banner banner-warning"
    >
      Plugin <code>{{ p.pluginId }}</code> is not available on the connected service.
      <router-link to="/">Go to Dashboard</router-link> to see available plugins.
    </div>
    <div
      v-for="(p, i) in nfcPluginStatuses.filter(p => p.available === false)"
      :key="`nfc-${p.pluginId ?? i}`"
      class="banner banner-warning"
    >
      Plugin <code>{{ p.pluginId }}</code> is not available; NFC controls are disabled.
      <router-link to="/">Go to Dashboard</router-link> to see available plugins.
    </div>
  </template>

  <!-- Screens -->
  <div class="card">
    <h3>Screens</h3>
    <div class="refresh-row">
      <button class="btn btn-refresh" :disabled="!screenReady || loading" @click="refreshScreens">
        {{ loading ? 'Loading...' : 'Refresh' }}
      </button>
    </div>

    <div v-if="screenEntries.length === 0" class="empty-msg">
      {{ screenReady ? 'No screens detected.' : isConnected ? 'Screen controls are unavailable.' : 'Connect to load screens...' }}
    </div>

    <div v-for="[sn, model] in screenEntries" :key="sn" class="screen-card">
      <div class="screen-header">
        <strong>{{ model }}</strong>
        <code class="sn">{{ sn }}</code>
      </div>

      <div class="control-row">
        <span class="control-label">Screen</span>
        <div class="toggle-row">
          <button class="btn btn-on" :disabled="!screenReady" @click="setScreenSwitch(sn, true)">Enable</button>
          <button class="btn btn-off" :disabled="!screenReady" @click="setScreenSwitch(sn, false)">Disable</button>
        </div>
      </div>

      <div class="control-row">
        <span class="control-label">Touch</span>
        <div class="toggle-row">
          <button class="btn btn-on" :disabled="!screenReady" @click="setTouchSwitch(sn, true)">Enable</button>
          <button class="btn btn-off" :disabled="!screenReady" @click="setTouchSwitch(sn, false)">Disable</button>
        </div>
      </div>

      <div class="control-row">
        <span class="control-label">Brightness</span>
        <div class="slider-row">
          <input
            type="range"
            min="0"
            max="100"
            v-model.number="brightnessValues[sn]"
            :disabled="!screenReady"
          />
          <span class="slider-val">{{ brightnessValues[sn] ?? 80 }}</span>
          <button class="btn btn-set" :disabled="!screenReady" @click="setBrightness(sn)">Set</button>
        </div>
      </div>
    </div>
  </div>

  <!-- NFC -->
  <div class="card">
    <h3>NFC</h3>

    <div v-if="nfcReady && antennaDataStale" class="banner banner-warning">
      NFC location data is stale because the WebSocket connection was lost.
      <button class="btn btn-refresh" @click="reconnectForFreshNfcData">Reconnect</button>
    </div>
    <div v-if="nfcReady && antennaApiAvailable === false" class="banner banner-info">
      This HAL service predates the antenna-location API. Legacy NFC controls remain available.
    </div>
    <div v-if="nfcReady && antennaError" class="banner banner-warning">
      {{ antennaError }}
    </div>

    <div class="nfc-row">
      <label class="control-label">NFC reader</label>
      <select v-model="nfcModule" :disabled="!nfcReady">
        <option value="">Automatic / main reader</option>
        <option v-for="module in nfcPickerEntries" :key="module.sn" :value="module.sn">
          {{ module.label }}{{ module.selected ? ' — active' : '' }}
        </option>
      </select>
      <button class="btn btn-set" :disabled="!nfcReady" @click="setNfcModule">Set</button>
    </div>

    <div v-if="selectedAntennaModule" class="nfc-status">
      Active reader: <strong>{{ selectedAntennaModule.sn }}</strong>
      <span class="muted">({{ selectedAntennaModule.reader }})</span>
    </div>
    <div v-else-if="antennaSnapshot?.state === 'initializing'" class="empty-msg">
      Waiting for the NFC SDK to report its module list...
    </div>
    <div v-else-if="antennaSnapshot?.state === 'unavailable'" class="empty-msg">
      NFC location data is unavailable.
    </div>

    <div class="nfc-row">
      <label class="control-label">Watermark</label>
      <div class="slider-row">
        <input
          type="range"
          min="0"
          max="100"
          v-model.number="nfcAlpha"
          :disabled="!nfcReady"
        />
        <span class="slider-val">{{ nfcAlpha }}</span>
        <button class="btn btn-set" :disabled="!nfcReady" @click="setNfcWatermark">Set</button>
      </div>
    </div>

    <div v-if="antennaLoading" class="empty-msg">Loading antenna locations...</div>
    <div v-if="antennaModules.length" class="antenna-list">
      <div v-for="module in antennaModules" :key="module.sn" class="antenna-card">
        <div class="screen-header">
          <strong>{{ module.sn }}</strong>
          <span v-if="module.selected" class="active-pill">ACTIVE</span>
          <code class="sn">SDK type {{ module.sdkType }} · {{ module.reader }}</code>
        </div>
        <template v-if="module.location">
          <div
            class="device-preview"
            :style="{ aspectRatio: `${module.location.displayWidthPx} / ${module.location.displayHeightPx}` }"
          >
            <div class="device-preview-label">
              Device display {{ module.location.displayWidthPx }}×{{ module.location.displayHeightPx }} px
            </div>
            <div class="antenna-rect" :style="locationRectStyle(module.location)">
              NFC antenna
            </div>
          </div>
          <div class="preview-note">
            {{ getLocationLabel(module) }} · rotation {{ module.location.rotationDegrees }}° · display {{ module.location.displayId }}
          </div>
          <code class="rect-values">
            rect: x={{ module.location.rect.x }}, y={{ module.location.rect.y }},
            width={{ module.location.rect.width }}, height={{ module.location.rect.height }} px
          </code>
        </template>
        <div v-else class="unknown-location">
          {{ getLocationLabel(module) }}
        </div>
      </div>
    </div>
    <div v-else-if="antennaSnapshot?.state === 'ready'" class="empty-msg">
      The SDK reported no NFC modules.
    </div>
    <div class="preview-note" v-if="antennaModules.length">
      Preview is scaled from full-device pixels; it is not the remote browser’s screen and does not use browser DPR.
    </div>
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

.refresh-row {
  margin-bottom: 12px;
}

.empty-msg {
  color: #888;
  font-style: italic;
  font-size: 14px;
}

.screen-card {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 14px;
  margin-bottom: 10px;
  background: #f9fafb;
}
.screen-card:last-child {
  margin-bottom: 0;
}

.screen-header {
  margin-bottom: 10px;
}
.screen-header strong {
  font-size: 15px;
}
.sn {
  display: block;
  font-size: 11px;
  color: #888;
  margin-top: 2px;
}

.control-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.control-row:last-child {
  margin-bottom: 0;
}

.control-label {
  width: 80px;
  font-size: 12px;
  color: #888;
  flex-shrink: 0;
}

.toggle-row {
  display: flex;
  gap: 6px;
  flex: 1;
}
.toggle-row .btn {
  flex: 1;
}

.slider-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
}
.slider-row input[type="range"] {
  flex: 1;
  accent-color: #1e88e5;
}
.slider-val {
  font-family: monospace;
  font-size: 14px;
  width: 32px;
  text-align: right;
}

.nfc-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.nfc-row:last-child {
  margin-bottom: 0;
}
.nfc-row select {
  flex: 1;
  padding: 6px 8px;
  border: 1px solid #ccc;
  border-radius: 6px;
  font-size: 13px;
}

.nfc-status {
  margin: 4px 0 12px 90px;
  font-size: 13px;
}
.muted,
.preview-note {
  color: #777;
  font-size: 12px;
}
.antenna-list {
  display: grid;
  gap: 10px;
  margin-top: 14px;
}
.antenna-card {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 12px;
  background: #f9fafb;
}
.active-pill {
  display: inline-block;
  margin-left: 8px;
  padding: 2px 6px;
  border-radius: 999px;
  background: #dcfce7;
  color: #166534;
  font-size: 10px;
  font-weight: 700;
  vertical-align: 2px;
}
.device-preview {
  position: relative;
  width: 100%;
  overflow: hidden;
  margin-top: 10px;
  border: 2px solid #64748b;
  border-radius: 6px;
  background: linear-gradient(135deg, #f8fafc, #e2e8f0);
}
.device-preview-label {
  padding: 6px;
  color: #64748b;
  font-size: 10px;
  font-family: monospace;
}
.antenna-rect {
  position: absolute;
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 28px;
  min-height: 22px;
  box-sizing: border-box;
  border: 2px solid #1e88e5;
  border-radius: 4px;
  background: rgba(30, 136, 229, 0.22);
  color: #0f4c81;
  font-size: 10px;
  text-align: center;
}
.unknown-location {
  margin-top: 10px;
  padding: 10px;
  border-radius: 5px;
  background: #fff7ed;
  color: #9a3412;
  font-size: 13px;
}
.rect-values {
  display: block;
  margin-top: 4px;
  color: #475569;
  font-size: 11px;
}
.banner .btn {
  margin-left: 8px;
}

.btn {
  padding: 6px 14px;
  border: none;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  color: #fff;
}
.btn-on { background: #22c55e; }
.btn-on:hover { background: #16a34a; }
.btn-off { background: #ef4444; }
.btn-off:hover { background: #dc2626; }
.btn-set { background: #1e88e5; }
.btn-set:hover { background: #1976d2; }
.btn-refresh { background: #6366f1; }
.btn-refresh:hover { background: #4f46e5; }

.banner {
  padding: 12px 16px;
  border-radius: 6px;
  font-size: 14px;
  margin-bottom: 16px;
}
.banner a { color: #0066cc; }
.banner code { font-size: 12px; background: rgba(0,0,0,0.06); padding: 1px 4px; border-radius: 3px; }
.banner-info {
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  color: #1e40af;
}
.banner-warning {
  background: #fffbeb;
  border: 1px solid #fde68a;
  color: #92400e;
}

.btn:disabled,
select:disabled,
input:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
</style>
