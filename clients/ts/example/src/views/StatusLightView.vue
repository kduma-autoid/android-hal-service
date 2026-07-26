<script setup lang="ts">
import { ref, shallowRef, computed, watch, onUnmounted } from 'vue';
import { useHalClient } from '../composables/useHalClient';
import { useToast } from '../composables/useToast';
import {
  SunmiLightClient,
  LIGHT_COLORS,
  type LightColor,
} from '@kduma-autoid/hal-client-plugin-sunmi-light-facade';
import type { InterfaceProvider } from '@kduma-autoid/hal-client-common';

const { client, isConnected } = useHalClient();
const toast = useToast();

// The facade resolves the `light` interface and binds a provider — the interface default, or one
// the user pins from the picker below, which matters when both the CPad LED and a FLEX status
// light provide the interface at once. Backend availability is dynamic (light plugged/unplugged,
// CPad LED confirmed after connect), so we re-resolve whenever the service reports a change.
const light = shallowRef<SunmiLightClient | null>(null);
const backends = ref<InterfaceProvider[]>([]);
const selectedBackend = ref<string>('');
const detecting = ref(false);
const detectError = ref<string | null>(null);
let unsubscribeChanges: (() => Promise<void>) | null = null;

async function bind(pluginId?: string) {
  if (!client.value || !isConnected.value) {
    light.value = null;
    backends.value = [];
    return;
  }
  detecting.value = true;
  detectError.value = null;
  try {
    backends.value = await SunmiLightClient.listBackends(client.value);
    light.value = pluginId
      ? await SunmiLightClient.forBackend(client.value, pluginId)
      : await SunmiLightClient.create(client.value);
    selectedBackend.value = light.value.backend;
  } catch (e) {
    // No provider (or the pinned one vanished) — the template shows the "no backend" banner.
    light.value = null;
    selectedBackend.value = '';
    detectError.value = e instanceof Error ? e.message : String(e);
  } finally {
    detecting.value = false;
  }
}

function onBackendChange(e: Event) {
  bind((e.target as HTMLSelectElement).value);
}

async function teardownChanges() {
  if (unsubscribeChanges) {
    try {
      await unsubscribeChanges();
    } catch {
      /* ignore */
    }
    unsubscribeChanges = null;
  }
}

async function connectAndWatch() {
  await bind();
  if (client.value && isConnected.value && !unsubscribeChanges) {
    // Hot-plug / interface reorder: re-resolve, keeping the user's pinned backend if it survives.
    unsubscribeChanges = await SunmiLightClient.onChanged(client.value, () => {
      bind(selectedBackend.value || undefined);
    });
  }
}

onUnmounted(teardownChanges);

watch(
  isConnected,
  (isConn) => {
    if (isConn) {
      connectAndWatch();
    } else {
      teardownChanges();
      light.value = null;
      backends.value = [];
      selectedBackend.value = '';
      detectError.value = null;
    }
  },
  { immediate: true },
);

const isReady = computed(() => isConnected.value && light.value !== null);
const caps = computed(() => light.value?.capabilities ?? { multiFlash: false, timeout: false });
// Friendly names for the backends we know about; any other provider of the `light` interface is
// shown by its raw pluginId rather than being mislabelled as one of these two.
const BACKEND_LABELS: Record<string, string> = {
  'sunmi.tms.led': 'sunmi.tms.led (CPad)',
  'sunmi.statuslight': 'sunmi.statuslight (FLEX 3)',
};
const backendLabel = computed(() => {
  const id = light.value?.backend;
  if (!id) return null;
  return BACKEND_LABELS[id] ?? id;
});

const flashColor = ref<LightColor>('red');
const flashOnMs = ref(500);
const flashOffMs = ref(500);
const rainbowOnMs = ref(400);
const rainbowOffMs = ref(100);
// Auto-release timeout (ms); only used when the active backend supports it. 0 = no timeout.
const timeoutMs = ref(0);

function timeoutOpts() {
  return caps.value.timeout ? { timeoutMs: timeoutMs.value } : undefined;
}

async function exec(fn: () => Promise<unknown>) {
  if (!light.value) return;
  try {
    await fn();
    toast.success('Success');
  } catch (e) {
    toast.error(e instanceof Error ? e.message : String(e));
  }
}

function setColor(color: LightColor) {
  exec(() => light.value!.on(color, timeoutOpts()));
}

function turnOff() {
  exec(() => light.value!.off());
}

function startFlash() {
  exec(() => light.value!.flash(flashColor.value, flashOnMs.value, flashOffMs.value, timeoutOpts()));
}

function startRainbow() {
  exec(() => light.value!.multiFlash!([...LIGHT_COLORS], rainbowOnMs.value, rainbowOffMs.value));
}

const colorStyles: Record<LightColor, string> = {
  red: '#e53935',
  green: '#43a047',
  blue: '#1e88e5',
  yellow: '#fdd835',
  magenta: '#d81b60',
  cyan: '#00acc1',
  white: '#eeeeee',
};

function textColor(color: LightColor): string {
  return color === 'yellow' || color === 'white' ? '#333' : '#fff';
}
</script>

<template>
  <h2>Status Light</h2>

  <div v-if="!isConnected" class="banner banner-info">
    Not connected.
    <router-link to="/settings">Go to Settings</router-link>
    to configure and connect.
  </div>
  <div v-else-if="detecting" class="banner banner-info">
    Detecting light backend...
  </div>
  <div v-else-if="!light" class="banner banner-warning">
    No light backend is available on the connected service.
    Neither <code>sunmi.tms.led</code> nor <code>sunmi.statuslight</code> is present.
    <router-link to="/">Go to Dashboard</router-link> to see available plugins.
  </div>
  <div v-else class="banner banner-info">
    <span>
      Active backend:
      <select v-if="backends.length > 1" :value="selectedBackend" @change="onBackendChange">
        <option v-for="b in backends" :key="b.pluginId" :value="b.pluginId">
          {{ b.pluginId }}{{ b.isDefault ? ' (default)' : '' }}
        </option>
      </select>
      <code v-else>{{ backendLabel }}</code>
    </span>
  </div>

  <div class="card">
    <h3>Set Color</h3>
    <div class="colors">
      <button
        v-for="color in LIGHT_COLORS"
        :key="color"
        class="color-btn"
        :disabled="!isReady"
        :style="{ background: colorStyles[color], color: textColor(color) }"
        @click="setColor(color)"
      >
        {{ color }}
      </button>
    </div>
    <div v-if="caps.timeout" class="flash-row timeout-row">
      <label class="timing-label">AUTO-OFF</label>
      <input v-model.number="timeoutMs" type="number" min="0" step="1000" :disabled="!isReady" /> ms
      <span class="hint">(0 = stay on until turned off)</span>
    </div>
  </div>

  <div class="card">
    <h3>Turn Off</h3>
    <button class="off-btn" :disabled="!isReady" @click="turnOff">Turn Off Light</button>
  </div>

  <div class="card">
    <h3>Single Color Flash</h3>
    <div class="flash-row">
      <select v-model="flashColor" :disabled="!isReady">
        <option v-for="c in LIGHT_COLORS" :key="c" :value="c">{{ c }}</option>
      </select>
      <label class="timing-label">ON</label>
      <input v-model.number="flashOnMs" type="number" min="50" step="50" :disabled="!isReady" /> ms
      <label class="timing-label">OFF</label>
      <input v-model.number="flashOffMs" type="number" min="50" step="50" :disabled="!isReady" /> ms
      <button class="btn btn-flash" :disabled="!isReady" @click="startFlash">Start</button>
    </div>
  </div>

  <div v-if="caps.multiFlash" class="card">
    <h3>Rainbow Flash</h3>
    <div class="flash-row">
      <label class="timing-label">ON</label>
      <input v-model.number="rainbowOnMs" type="number" min="50" step="50" :disabled="!isReady" /> ms
      <label class="timing-label">OFF</label>
      <input v-model.number="rainbowOffMs" type="number" min="50" step="50" :disabled="!isReady" /> ms
      <button class="btn btn-rainbow" :disabled="!isReady" @click="startRainbow">Start</button>
    </div>
  </div>
  <div v-else-if="light" class="banner banner-muted">
    Multi-color (rainbow) flashing is not supported by the <code>{{ backendLabel }}</code> backend.
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

.colors {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.color-btn {
  width: 72px;
  height: 56px;
  border-radius: 8px;
  border: 2px solid transparent;
  font-size: 12px;
  font-weight: 700;
  text-transform: uppercase;
  cursor: pointer;
  transition: border-color 0.15s, transform 0.1s;
}
.color-btn:hover { border-color: rgba(0,0,0,0.3); }
.color-btn:active { transform: scale(0.96); }

.off-btn {
  width: 100%;
  background: #f3f4f6;
  color: #555;
  border: 1px solid #ccc;
  border-radius: 6px;
  padding: 10px;
  font-size: 14px;
  cursor: pointer;
}
.off-btn:hover { background: #e5e7eb; }

.flash-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.timeout-row { margin-top: 12px; }
.flash-row select,
.flash-row input[type="number"] {
  padding: 6px 8px;
  border: 1px solid #ccc;
  border-radius: 6px;
  font-size: 13px;
}
.flash-row select { width: 100px; }
.flash-row input[type="number"] { width: 70px; }
.timing-label { font-size: 12px; color: #888; }
.hint { font-size: 12px; color: #aaa; }

.btn {
  padding: 6px 14px;
  border: none;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  color: #fff;
}
.btn-flash { background: #7c4dff; }
.btn-flash:hover { background: #651fff; }
.btn-rainbow { background: #e8710a; }
.btn-rainbow:hover { background: #d46208; }

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
.banner-muted {
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  color: #6b7280;
}

.color-btn:disabled,
.off-btn:disabled,
.btn:disabled,
.flash-row select:disabled,
.flash-row input:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
</style>
