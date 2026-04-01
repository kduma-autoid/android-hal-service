<script setup lang="ts">
import { ref, computed } from 'vue';
import { useHalClient } from '../composables/useHalClient';
import { usePluginAvailability } from '../composables/usePluginAvailability';
import { useToast } from '../composables/useToast';
import {
  SunmiStatusLightClient,
  PLUGIN_ID,
  STATUS_LIGHT_COLORS,
  type StatusLightColor,
} from '@kduma-autoid/hal-client-plugin-sunmi-statuslight';

const { client, isConnected } = useHalClient();
const toast = useToast();
const { isAvailable, plugins: pluginStatuses, loading: pluginLoading } = usePluginAvailability({
  pluginId: PLUGIN_ID,
  capabilities: ['sunmi.statuslight'],
});

const isReady = computed(() => isConnected.value && isAvailable.value === true);

const light = computed(() =>
  client.value ? new SunmiStatusLightClient(client.value) : null,
);

const flashColor = ref<StatusLightColor>('red');
const flashOnMs = ref(500);
const flashOffMs = ref(500);
const rainbowOnMs = ref(400);
const rainbowOffMs = ref(100);

async function exec(fn: () => Promise<unknown>) {
  if (!light.value) return;
  try {
    await fn();
    toast.success('Success');
  } catch (e) {
    toast.error(e instanceof Error ? e.message : String(e));
  }
}

function setColor(color: StatusLightColor) {
  exec(() => light.value!.setColor(color));
}

function turnOff() {
  exec(() => light.value!.turnOff());
}

function startFlash() {
  exec(() => light.value!.setFlashing(flashColor.value, flashOnMs.value, flashOffMs.value));
}

function startRainbow() {
  exec(() =>
    light.value!.setMultiFlashing(
      STATUS_LIGHT_COLORS.map((color) => ({
        color,
        onMs: rainbowOnMs.value,
        offMs: rainbowOffMs.value,
      })),
    ),
  );
}

const colorStyles: Record<StatusLightColor, string> = {
  red: '#e53935',
  green: '#43a047',
  blue: '#1e88e5',
  yellow: '#fdd835',
  magenta: '#d81b60',
  cyan: '#00acc1',
  white: '#eeeeee',
};

function textColor(color: StatusLightColor): string {
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

  <div class="card">
    <h3>Set Color</h3>
    <div class="colors">
      <button
        v-for="color in STATUS_LIGHT_COLORS"
        :key="color"
        class="color-btn"
        :disabled="!isReady"
        :style="{ background: colorStyles[color], color: textColor(color) }"
        @click="setColor(color)"
      >
        {{ color }}
      </button>
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
        <option v-for="c in STATUS_LIGHT_COLORS" :key="c" :value="c">{{ c }}</option>
      </select>
      <label class="timing-label">ON</label>
      <input v-model.number="flashOnMs" type="number" min="50" step="50" :disabled="!isReady" /> ms
      <label class="timing-label">OFF</label>
      <input v-model.number="flashOffMs" type="number" min="50" step="50" :disabled="!isReady" /> ms
      <button class="btn btn-flash" :disabled="!isReady" @click="startFlash">Start</button>
    </div>
  </div>

  <div class="card">
    <h3>Rainbow Flash</h3>
    <div class="flash-row">
      <label class="timing-label">ON</label>
      <input v-model.number="rainbowOnMs" type="number" min="50" step="50" :disabled="!isReady" /> ms
      <label class="timing-label">OFF</label>
      <input v-model.number="rainbowOffMs" type="number" min="50" step="50" :disabled="!isReady" /> ms
      <button class="btn btn-rainbow" :disabled="!isReady" @click="startRainbow">Start</button>
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

.color-btn:disabled,
.off-btn:disabled,
.btn:disabled,
.flash-row select:disabled,
.flash-row input:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
</style>
