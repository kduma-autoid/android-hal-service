<script setup lang="ts">
import { ref, shallowRef, computed, watch, onUnmounted } from 'vue';
import { useHalClient } from '../composables/useHalClient';
import { useToast } from '../composables/useToast';
import { bindBackend, enabledBackends, serialized } from '../composables/backendBinding';
import { ScaleClient } from '@kduma-autoid/hal-client-plugin-scale-facade';
import type { InterfaceProvider, ScaleReading, ScaleStatus } from '@kduma-autoid/hal-client-common';
import { DymoM10Client, type DymoM10Info } from '@kduma-autoid/hal-client-plugin-dymo-m10';

const { client, isConnected } = useHalClient();
const toast = useToast();

const MAX_LOG = 30;
const STORAGE_KEY = 'hal-demo.scale.counting';

function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

// ---------------------------------------------------------------------------------------------
// Backend binding (scale interface)
// ---------------------------------------------------------------------------------------------

const scale = shallowRef<ScaleClient | null>(null);
const backends = ref<InterfaceProvider[]>([]);
const selectedBackend = ref('');
const detecting = ref(false);
const bindError = ref('');
// Subscribing can fail on its own (the HTTP transport has no event channel) while binding worked.
const eventError = ref('');
const usable = computed(() => enabledBackends(backends.value));

let unsubscribers: Array<() => Promise<void>> = [];
let unsubscribeChanges: (() => Promise<void>) | null = null;

async function stopSubscriptions() {
  for (const off of unsubscribers) {
    try {
      await off();
    } catch {
      /* ignore */
    }
  }
  unsubscribers = [];
}

const bind = serialized((pluginId?: string) => bindOnce(pluginId));

async function bindOnce(pluginId?: string) {
  if (!client.value || !isConnected.value) {
    await stopSubscriptions();
    scale.value = null;
    backends.value = [];
    return;
  }
  const c = client.value;
  detecting.value = true;
  try {
    backends.value = await ScaleClient.listBackends(c);
    const { bound, pinFailed } = await bindBackend(
      pluginId,
      (id) => ScaleClient.forBackend(c, id),
      () => ScaleClient.create(c),
    );
    scale.value = bound;
    selectedBackend.value = bound.backend;
    bindError.value = '';
    if (pinFailed) toast.info(`${pinFailed} is not available — using ${bound.backend}`);
  } catch (e) {
    await stopSubscriptions();
    scale.value = null;
    selectedBackend.value = '';
    reading.value = null;
    bindError.value = message(e);
    return;
  } finally {
    detecting.value = false;
  }

  await refresh();

  try {
    await stopSubscriptions();
    unsubscribers.push(await scale.value.onWeight((r) => onReading(r)));
    unsubscribers.push(
      await scale.value.onStatus((s) => {
        log(`status ${s.previous} → ${s.status}`);
        if (s.status === 'offline') {
          reading.value = null;
          readingError.value = 'The scale stopped sending readings.';
        }
      }),
    );
    eventError.value = '';
  } catch (e) {
    await stopSubscriptions();
    eventError.value = message(e);
  }
}

function onBackendChange(e: Event) {
  bind((e.target as HTMLSelectElement).value);
}

const isReady = computed(() => isConnected.value && scale.value !== null);
const subscription = computed(() =>
  selectedBackend.value ? `scale.onWeight@${selectedBackend.value}` : 'scale.onWeight',
);

// ---------------------------------------------------------------------------------------------
// Readings
// ---------------------------------------------------------------------------------------------

const reading = ref<ScaleReading | null>(null);
const readingError = ref('');
const info = ref<DymoM10Info | null>(null);

interface LogEntry {
  at: Date;
  text: string;
}
const events = ref<LogEntry[]>([]);

function log(text: string) {
  events.value.unshift({ at: new Date(), text });
  if (events.value.length > MAX_LOG) events.value.length = MAX_LOG;
}

function onReading(r: ScaleReading) {
  reading.value = r;
  readingError.value = '';
  log(`${fmtGrams(r.grams)} (${r.value} ${r.unit}) ${r.status}${r.stable ? ' · stable' : ''}`);
}

async function refresh() {
  const s = scale.value;
  if (!s) return;
  try {
    reading.value = await s.getWeight();
    readingError.value = '';
  } catch (e) {
    reading.value = null;
    readingError.value = message(e);
  }
  info.value = null;
  if (s.backend === 'dymo.m10' && client.value) {
    try {
      info.value = await new DymoM10Client(client.value).getInfo();
    } catch {
      /* native API optional */
    }
  }
}

const STATUS_LABEL: Record<ScaleStatus, string> = {
  zero: 'Zero',
  inMotion: 'In motion',
  stable: 'Stable',
  underZero: 'Under zero',
  overWeight: 'Overweight',
  fault: 'Fault',
  needCalibration: 'Needs calibration',
  needZeroing: 'Needs zeroing',
  unknown: 'Unknown',
  offline: 'Offline',
};
const ERROR_STATUSES: ScaleStatus[] = ['overWeight', 'fault', 'needCalibration', 'needZeroing', 'unknown', 'offline'];

function fmtGrams(g: number): string {
  return `${Number.isInteger(g) ? g : g.toFixed(1)} g`;
}

function time(d: Date): string {
  return d.toLocaleTimeString(undefined, { hour12: false });
}

// ---------------------------------------------------------------------------------------------
// Piece counting (demo only — the plugin just reports weight)
// ---------------------------------------------------------------------------------------------

interface Counting {
  /** Weight of the empty container, in grams. */
  container: number;
  /** Weight of one piece, in grams (0 = not calibrated). */
  unitWeight: number;
}

function loadCounting(): Counting {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const c = JSON.parse(raw) as Partial<Counting>;
      return { container: Number(c.container) || 0, unitWeight: Number(c.unitWeight) || 0 };
    }
  } catch {
    /* storage unavailable */
  }
  return { container: 0, unitWeight: 0 };
}

const counting = ref<Counting>(loadCounting());
const samplePieces = ref(10);

watch(
  counting,
  (c) => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(c));
    } catch {
      /* storage unavailable */
    }
  },
  { deep: true },
);

const net = computed(() => (reading.value ? reading.value.grams - counting.value.container : null));
const canMeasure = computed(() => !!reading.value?.stable && !ERROR_STATUSES.includes(reading.value.status));

/** Exact (fractional) pieces on the scale, or null when not calibrated / no reading. */
const pieces = computed(() =>
  net.value !== null && counting.value.unitWeight > 0 ? net.value / counting.value.unitWeight : null,
);
const count = computed(() => (pieces.value === null ? null : Math.max(0, Math.round(pieces.value))));
/** How far off a whole number of pieces the weight is (0 … 0.5). Large = wrong item or calibration. */
const deviation = computed(() => (pieces.value === null ? null : Math.abs(pieces.value - Math.round(pieces.value))));

/** The count frozen at the last stable reading. */
const stableCount = ref<number | null>(null);
watch([count, canMeasure], ([c, ok]) => {
  if (ok) stableCount.value = c;
});

function zeroContainer() {
  if (!reading.value) return;
  counting.value.container = reading.value.grams;
  toast.info(`Container: ${fmtGrams(reading.value.grams)}`);
}

function clearContainer() {
  counting.value.container = 0;
}

function calibrate() {
  const n = Math.floor(Number(samplePieces.value));
  if (!reading.value || net.value === null || n < 1) return;
  if (net.value <= 0) {
    toast.error('Put the sample pieces on the scale first');
    return;
  }
  counting.value.unitWeight = net.value / n;
  toast.info(`One piece = ${counting.value.unitWeight.toFixed(2)} g`);
}

function resetCalibration() {
  counting.value.unitWeight = 0;
  stableCount.value = null;
}

// ---------------------------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------------------------

async function teardown() {
  await stopSubscriptions();
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
  async (connected) => {
    if (connected) {
      await bind();
      if (client.value && !unsubscribeChanges) {
        // Hot-plug (the scale is USB) / interface reorder: re-resolve, keeping the pinned backend.
        try {
          unsubscribeChanges = await ScaleClient.onChanged(client.value, () => {
            bind(selectedBackend.value || undefined);
          });
        } catch {
          /* no event channel */
        }
      }
    } else {
      await teardown();
      scale.value = null;
      backends.value = [];
      reading.value = null;
    }
  },
  { immediate: true },
);
</script>

<template>
  <h2>Scale</h2>

  <div v-if="!isConnected" class="banner banner-info">
    Not connected.
    <router-link to="/settings">Go to Settings</router-link>
    to configure and connect.
  </div>
  <div v-else-if="detecting && !scale" class="banner banner-info">Resolving scale backend...</div>
  <div v-else-if="!scale && usable.length" class="banner banner-warning">
    Could not bind a scale backend: {{ bindError }}
    <router-link to="/interfaces">Go to Interfaces</router-link> to inspect the registry.
  </div>
  <div v-else-if="!scale && backends.length" class="banner banner-warning">
    Every provider of the <code>scale</code> interface is disabled.
    <router-link to="/interfaces">Go to Interfaces</router-link> to enable one.
  </div>
  <div v-else-if="!scale" class="banner banner-warning">
    No scale is available — the <code>scale</code> interface has no provider. Plug in a Dymo M10 (and accept
    the USB permission prompt); on non-Sunmi builds install the Dymo plugin APK.
  </div>
  <template v-else>
    <div class="banner banner-info backend-bar">
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
    <div v-if="eventError" class="banner banner-warning">
      Live readings are unavailable: {{ eventError }}
      Use <em>Refresh</em>, or switch to the WebSocket transport in <router-link to="/settings">Settings</router-link>.
    </div>
  </template>

  <!-- Weight -->
  <div class="card">
    <div class="card-head">
      <h3>Weight</h3>
      <button class="btn btn-sm" :disabled="!isReady" @click="refresh">Refresh</button>
    </div>
    <template v-if="reading">
      <div class="weight" :class="{ moving: !reading.stable }">{{ fmtGrams(reading.grams) }}</div>
      <div class="row">
        <span class="pill" :class="reading.stable ? 'ok' : 'wait'">{{ reading.stable ? 'Stable' : 'Measuring…' }}</span>
        <span class="pill" :class="{ bad: ERROR_STATUSES.includes(reading.status) }">
          scale: {{ STATUS_LABEL[reading.status] }}
        </span>
        <span class="sub">display: <span class="mono">{{ reading.value }} {{ reading.unit }}</span></span>
      </div>
      <p class="hint">
        <em>Stable</em> is computed by the plugin (no change of 1 g or more for 500 ms). The scale's own status is
        shown for reference — the M10 reports “stable” nearly all the time.
      </p>
    </template>
    <p v-else-if="isReady" class="empty">{{ readingError || 'No reading yet.' }} Is the scale switched on?</p>
    <p v-else class="empty">Bind a scale to see its weight.</p>
    <dl v-if="info" class="compact top-gap">
      <dt>USB connection</dt>
      <dd>{{ info.connected ? 'open' : 'closed' }}</dd>
      <dt>USB permission</dt>
      <dd>{{ info.permission ? 'granted' : 'not granted' }}</dd>
    </dl>
  </div>

  <!-- Counting -->
  <div class="card">
    <h3>Piece counting <span class="plugin">demo only</span></h3>

    <div class="count-box" :class="{ dim: !canMeasure }">
      <template v-if="counting.unitWeight > 0">
        <div class="count">{{ stableCount ?? '–' }}</div>
        <div class="sub">
          pieces
          <template v-if="!canMeasure && count !== null"> · measuring ≈ {{ count }}</template>
          <span v-if="canMeasure && deviation !== null && deviation > 0.25" class="warn">
            · off by {{ deviation.toFixed(2) }} piece — check the item or recalibrate
          </span>
        </div>
      </template>
      <div v-else class="sub">Not calibrated — put sample pieces on the scale and calibrate.</div>
    </div>

    <dl class="compact">
      <dt>Container</dt>
      <dd>
        {{ fmtGrams(counting.container) }}
        <button class="btn btn-sm" :disabled="!canMeasure" @click="zeroContainer">Zero container</button>
        <button class="btn btn-sm" :disabled="!counting.container" @click="clearContainer">Clear</button>
      </dd>
      <dt>Net</dt>
      <dd>{{ net === null ? '–' : fmtGrams(Math.round(net * 10) / 10) }}</dd>
      <dt>One piece</dt>
      <dd>
        {{ counting.unitWeight > 0 ? `${counting.unitWeight.toFixed(2)} g` : '–' }}
        <button class="btn btn-sm" :disabled="!counting.unitWeight" @click="resetCalibration">Reset</button>
      </dd>
    </dl>

    <div class="row">
      <label class="inline">
        Sample
        <input v-model.number="samplePieces" type="number" min="1" class="num" /> pieces on the scale
      </label>
      <button class="btn btn-primary" :disabled="!canMeasure || !net || net <= 0" @click="calibrate">Calibrate</button>
    </div>
    <p class="hint">
      1. Put the empty container on the scale and press <em>Zero container</em>. 2. Add a known number of pieces and
      press <em>Calibrate</em>. 3. Fill the container — the count updates whenever the weight settles.
    </p>
  </div>

  <!-- Events -->
  <div class="card">
    <div class="card-head">
      <h3>Events <span class="count-badge">{{ events.length }}</span></h3>
      <button class="btn btn-sm" :disabled="!events.length" @click="events = []">Clear</button>
    </div>
    <p v-if="!events.length" class="empty">No events yet — put something on the scale.</p>
    <ul v-else class="log">
      <li v-for="(e, i) in events" :key="`${e.at.getTime()}-${i}`">
        <span class="mono at">{{ time(e.at) }}</span> {{ e.text }}
      </li>
    </ul>
  </div>
</template>

<style scoped>
h2 { margin-top: 0; }
h3 { margin: 0 0 12px; font-size: 14px; color: #444; text-transform: uppercase; letter-spacing: 1px; }

.card { border: 1px solid #ddd; border-radius: 8px; padding: 16px; margin-bottom: 16px; background: #fff; }
.card-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; margin-bottom: 12px; }
.card-head h3 { margin-bottom: 0; }
.count-badge { font-size: 11px; background: #eef2ff; color: #4338ca; border-radius: 10px; padding: 1px 7px; margin-left: 6px; }
.plugin { font-size: 11px; text-transform: none; letter-spacing: 0; background: #f1f5f9; padding: 1px 5px; border-radius: 3px; }

.row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 8px; }
.top-gap { margin-top: 12px; }
.inline { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: #444; }
.num { width: 56px; padding: 4px 6px; border: 1px solid #ccc; border-radius: 4px; }
.hint { font-size: 12px; color: #888; margin: 8px 0 0; }
.empty { color: #9ca3af; font-size: 13px; margin: 0; }
.sub { font-size: 12px; color: #6b7280; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.warn { color: #b45309; }

.btn { padding: 6px 14px; border: 1px solid #ccc; border-radius: 6px; background: #fff; font-size: 13px; font-weight: 600; cursor: pointer; }
.btn:hover { background: #f5f5f5; }
.btn-primary { background: #0066cc; color: #fff; border-color: #0066cc; }
.btn-primary:hover { background: #0055aa; }
.btn-sm { padding: 4px 10px; font-weight: 500; }
.btn:disabled { opacity: 0.45; cursor: not-allowed; }

.weight { font-size: 44px; font-weight: 700; font-variant-numeric: tabular-nums; margin-bottom: 8px; }
.weight.moving { color: #9ca3af; }

.pill { font-size: 11px; border-radius: 10px; padding: 2px 8px; background: #f3f4f6; color: #374151; }
.pill.ok { background: #dcfce7; color: #166534; }
.pill.wait { background: #fef3c7; color: #92400e; }
.pill.bad { background: #fee2e2; color: #991b1b; }

.count-box { border: 1px solid #e0e7ff; background: #f8faff; border-radius: 8px; padding: 12px 14px; margin-bottom: 12px; }
.count-box.dim .count { color: #9ca3af; }
.count { font-size: 40px; font-weight: 700; font-variant-numeric: tabular-nums; }

dl { display: grid; grid-template-columns: 120px 1fr; gap: 6px 12px; margin: 0 0 12px; font-size: 13px; }
dt { color: #6b7280; }
dd { margin: 0; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }

.log { list-style: none; margin: 0; padding: 0; font-size: 13px; max-height: 260px; overflow-y: auto; }
.log li { padding: 3px 0; border-bottom: 1px solid #f3f4f6; }
.at { color: #9ca3af; margin-right: 6px; }

.backend-bar { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.backend-bar select { padding: 4px 8px; border: 1px solid #bfdbfe; border-radius: 6px; font-size: 13px; }

.banner { padding: 12px 16px; border-radius: 6px; font-size: 14px; margin-bottom: 16px; }
.banner-info { background: #eff6ff; border: 1px solid #bfdbfe; color: #1e40af; }
.banner-warning { background: #fffbeb; border: 1px solid #fde68a; color: #92400e; }
</style>
