<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
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

const { client, isConnected } = useHalClient();
const toast = useToast();
const { isAvailable, plugins: pluginStatuses, loading: pluginLoading } = usePluginAvailability([
  { pluginId: SCREEN_PLUGIN_ID, capabilities: ['sunmi.screen'] },
  { pluginId: NFC_PLUGIN_ID, capabilities: ['sunmi.nfc'] },
]);

const isReady = computed(() => isConnected.value && isAvailable.value === true);

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

async function exec(fn: () => Promise<unknown>) {
  try {
    await fn();
    toast.success('Success');
  } catch (e) {
    toast.error(e instanceof Error ? e.message : String(e));
  }
}

async function refreshScreens() {
  if (!screen.value) return;
  loading.value = true;
  try {
    const res = await screen.value.getDeviceInfo();
    if (res?.info) {
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
    loading.value = false;
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
  exec(() => nfc.value!.setWatermarkAlpha(nfcAlpha.value));
}

const screenEntries = computed(() => Object.entries(screens.value));

onMounted(() => {
  if (isReady.value) refreshScreens();
});

watch(isReady, (ready) => {
  if (ready) refreshScreens();
  else screens.value = {};
});
</script>

<template>
  <h2>Screens & NFC</h2>

  <div v-if="!isConnected" class="banner banner-info">
    Not connected.
    <router-link to="/settings">Go to Settings</router-link>
    to configure and connect.
  </div>
  <div v-else-if="pluginLoading" class="banner banner-info">
    Checking plugin availability...
  </div>
  <template v-else-if="isAvailable === false">
    <div
      v-for="(p, i) in pluginStatuses.filter(p => p.available === false)"
      :key="p.pluginId ?? i"
      class="banner banner-warning"
    >
      Plugin <code>{{ p.pluginId }}</code> is not available on the connected service.
      <router-link to="/">Go to Dashboard</router-link> to see available plugins.
    </div>
  </template>

  <!-- Screens -->
  <div class="card">
    <h3>Screens</h3>
    <div class="refresh-row">
      <button class="btn btn-refresh" :disabled="!isReady || loading" @click="refreshScreens">
        {{ loading ? 'Loading...' : 'Refresh' }}
      </button>
    </div>

    <div v-if="screenEntries.length === 0" class="empty-msg">
      {{ isReady ? 'No screens detected.' : 'Connect to load screens...' }}
    </div>

    <div v-for="[sn, model] in screenEntries" :key="sn" class="screen-card">
      <div class="screen-header">
        <strong>{{ model }}</strong>
        <code class="sn">{{ sn }}</code>
      </div>

      <div class="control-row">
        <span class="control-label">Screen</span>
        <div class="toggle-row">
          <button class="btn btn-on" :disabled="!isReady" @click="setScreenSwitch(sn, true)">Enable</button>
          <button class="btn btn-off" :disabled="!isReady" @click="setScreenSwitch(sn, false)">Disable</button>
        </div>
      </div>

      <div class="control-row">
        <span class="control-label">Touch</span>
        <div class="toggle-row">
          <button class="btn btn-on" :disabled="!isReady" @click="setTouchSwitch(sn, true)">Enable</button>
          <button class="btn btn-off" :disabled="!isReady" @click="setTouchSwitch(sn, false)">Disable</button>
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
            :disabled="!isReady"
          />
          <span class="slider-val">{{ brightnessValues[sn] ?? 80 }}</span>
          <button class="btn btn-set" :disabled="!isReady" @click="setBrightness(sn)">Set</button>
        </div>
      </div>
    </div>
  </div>

  <!-- NFC -->
  <div class="card">
    <h3>NFC</h3>

    <div class="nfc-row">
      <label class="control-label">NFC Screen</label>
      <select v-model="nfcModule" :disabled="!isReady">
        <option value="">Main screen</option>
        <option v-for="[sn, model] in screenEntries" :key="sn" :value="sn">
          {{ model }} ({{ sn }})
        </option>
      </select>
      <button class="btn btn-set" :disabled="!isReady" @click="setNfcModule">Set</button>
    </div>

    <div class="nfc-row">
      <label class="control-label">Watermark</label>
      <div class="slider-row">
        <input
          type="range"
          min="0"
          max="100"
          v-model.number="nfcAlpha"
          :disabled="!isReady"
        />
        <span class="slider-val">{{ nfcAlpha }}</span>
        <button class="btn btn-set" :disabled="!isReady" @click="setNfcWatermark">Set</button>
      </div>
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
