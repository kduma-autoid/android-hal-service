<script setup lang="ts">
import { ref, shallowRef, computed, watch, onUnmounted } from 'vue';
import { SunmiBarcodeScannerClient } from '@kduma-autoid/hal-client-plugin-sunmi-barcode-scanner-facade';
import type { InterfaceProvider } from '@kduma-autoid/hal-client-common';
import { useHalClient } from '../composables/useHalClient';
import { useToast } from '../composables/useToast';
import { usePageLifetime } from '../composables/usePageLifetime';
import { bindBackend, enabledBackends, serialized } from '../composables/backendBinding';
import { parseFrame, RollingQrAssembler } from '../utils/rollingQr';

const { client, isConnected } = useHalClient();
const toast = useToast();
const page = usePageLifetime();

/** The SS2222 reads continuously and can hold its own beep until the payload is complete. */
const SS2222 = 'sunmi.scanner.ss2222';

// ---------------------------------------------------------------------------------- backend
const scanner = shallowRef<SunmiBarcodeScannerClient | null>(null);
const backends = ref<InterfaceProvider[]>([]);
const selectedBackend = ref('');
const bindError = ref('');
const scanError = ref('');
const usable = computed(() => enabledBackends(backends.value));
let unsubscribeScan: (() => Promise<void>) | null = null;
let unsubscribeChanges: (() => Promise<void>) | null = null;

async function stopScanSubscription() {
  if (!unsubscribeScan) return;
  try {
    await unsubscribeScan();
  } catch {
    /* ignore */
  }
  unsubscribeScan = null;
}

const bind = serialized(async (pluginId?: string) => {
  if (!page.isAlive()) return;
  if (!client.value || !isConnected.value) {
    await stopScanSubscription();
    scanner.value = null;
    backends.value = [];
    return;
  }
  const c = client.value;
  try {
    backends.value = await SunmiBarcodeScannerClient.listBackends(c);
    const { bound } = await bindBackend(
      pluginId,
      (id) => SunmiBarcodeScannerClient.forBackend(c, id),
      () => SunmiBarcodeScannerClient.create(c),
    );
    scanner.value = bound;
    selectedBackend.value = bound.backend;
    bindError.value = '';
  } catch (e) {
    await stopScanSubscription();
    scanner.value = null;
    selectedBackend.value = '';
    bindError.value = message(e);
    return;
  }
  try {
    await stopScanSubscription();
    unsubscribeScan = await page.keep(scanner.value.onScan((scan) => onCode(scan.data)));
    scanError.value = '';
  } catch (e) {
    scanError.value = message(e);
  }
});

async function onBackendChange(e: Event) {
  if (reading.value) await stopReading();
  bind((e.target as HTMLSelectElement).value);
}

// ---------------------------------------------------------------------------------- reading
const asm = new RollingQrAssembler();
/** Bumped on every change to [asm], which is not reactive itself. */
const version = ref(0);
const reading = ref(false);
const mode = ref<'continuous' | 'retrigger' | null>(null);
const beepAtEnd = ref(true);
const startedAt = ref(0);
const finishedAt = ref(0);
const now = ref(Date.now());
const reads = ref(0);
const ignored = ref(0);
const lastIgnored = ref('');
const otherSequence = ref('');
let muted = false;
let clock: ReturnType<typeof setInterval> | null = null;

const isSs2222 = computed(() => selectedBackend.value === SS2222);
const progress = computed(() => (version.value, { received: asm.received, total: asm.total, missing: asm.missing }));
const complete = computed(() => (version.value, asm.isComplete));
const decoded = computed(() => {
  void version.value;
  if (!asm.isComplete) return { text: '', error: '' };
  try {
    return { text: asm.payload, error: '' };
  } catch (e) {
    return { text: '', error: `Cannot decode the Base64 payload: ${message(e)}` };
  }
});
const payload = computed(() => decoded.value.text);
const encodingLabel = computed(() => (version.value, asm.total ? (asm.encoding === 'base64' ? 'Base64 (AQRB)' : 'text (AQR)') : ''));
/** NULs in text frames mean the scanner dropped characters it could not convert (SS2222: outside GB2312). */
const mangled = computed(() => payload.value.includes('\u0000'));
const elapsedMs = computed(() => (startedAt.value ? (finishedAt.value || now.value) - startedAt.value : 0));
const rate = computed(() => (elapsedMs.value > 0 ? (reads.value * 1000) / elapsedMs.value : 0));

function message(e: unknown) {
  return e instanceof Error ? e.message : String(e);
}

async function ss2222(method: string, params: Record<string, unknown> = {}) {
  return client.value!.execute(`${SS2222}.${method}`, params);
}

async function startReading() {
  if (!scanner.value || reading.value) return;
  asm.reset();
  version.value++;
  reads.value = 0;
  ignored.value = 0;
  lastIgnored.value = '';
  otherSequence.value = '';
  finishedAt.value = 0;
  startedAt.value = Date.now();
  reading.value = true;
  clock = setInterval(() => (now.value = Date.now()), 100);

  if (isSs2222.value) {
    try {
      if (beepAtEnd.value) {
        await ss2222('setConfig', { goodReadBeep: false });
        muted = true;
      }
      try {
        await ss2222('startContinuous');
      } catch (e) {
        // Already reading on its own ("Disable the scanner between scans" turned off): use that session.
        if ((e as { code?: string }).code !== 'busy') throw e;
      }
      mode.value = 'continuous';
      return;
    } catch (e) {
      // Older HAL without these methods: fall back to the generic interface.
      toast.info(`SS2222 continuous reading unavailable (${message(e)}) — re-triggering instead`);
      await restoreBeep();
    }
  }
  mode.value = 'retrigger';
  await retrigger();
}

async function retrigger() {
  if (!reading.value || mode.value !== 'retrigger' || !scanner.value) return;
  try {
    await scanner.value.trigger();
  } catch (e) {
    toast.error(message(e));
    await stopReading();
  }
}

async function restoreBeep() {
  if (!muted) return;
  muted = false;
  try {
    await ss2222('setConfig', { goodReadBeep: true });
  } catch {
    /* the scanner may be gone */
  }
}

async function stopReading(done = false) {
  if (!reading.value) return;
  reading.value = false;
  if (clock) clearInterval(clock);
  clock = null;
  finishedAt.value = Date.now();
  try {
    if (mode.value === 'continuous') await ss2222('stop');
    else await scanner.value?.stop();
  } catch {
    /* already idle */
  }
  if (done && muted) {
    try {
      await ss2222('beep', { kind: 'ok' });
    } catch {
      /* no beep, no harm */
    }
  }
  await restoreBeep();
  mode.value = null;
}

function onCode(text: string) {
  if (!reading.value) return;
  reads.value++;
  const frame = parseFrame(text);
  if (!frame) {
    ignored.value++;
    lastIgnored.value = text;
  } else {
    const result = asm.add(frame);
    if (result === 'other-sequence') otherSequence.value = frame.id;
    version.value++;
    if (result === 'complete') {
      stopReading(true);
      return;
    }
  }
  // A single-shot scanner stops after each code: ask for the next one.
  if (mode.value === 'retrigger') retrigger();
}

async function copyPayload() {
  try {
    await navigator.clipboard.writeText(payload.value);
    toast.success('Payload copied');
  } catch (e) {
    toast.error(message(e));
  }
}

// ---------------------------------------------------------------------------------- lifecycle
async function teardown() {
  await stopReading();
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
  async (connected) => {
    if (connected) {
      await bind();
      if (client.value && !unsubscribeChanges) {
        try {
          unsubscribeChanges = await page.keep(
            SunmiBarcodeScannerClient.onChanged(client.value, () => bind(selectedBackend.value || undefined)),
          );
        } catch {
          /* no event channel */
        }
      }
    } else {
      await teardown();
      scanner.value = null;
      backends.value = [];
    }
  },
  { immediate: true },
);
</script>

<template>
  <div v-if="!isConnected" class="banner banner-info">
    Not connected. <router-link to="/settings">Go to Settings</router-link> to configure and connect.
  </div>
  <div v-else-if="!scanner && usable.length" class="banner banner-warning">
    Could not bind a barcode scanner backend: {{ bindError }}
  </div>
  <div v-else-if="!scanner" class="banner banner-warning">
    No barcode scanner backend is available — the <code>barcodeScanner</code> interface has no provider.
    <router-link to="/interfaces">Go to Interfaces</router-link>.
  </div>
  <div v-else-if="scanError" class="banner banner-warning">
    Bound to <code>{{ selectedBackend }}</code>, but live scans are unavailable: {{ scanError }}
    Switch to the WebSocket transport in <router-link to="/settings">Settings</router-link>.
  </div>
  <div v-else class="banner banner-info backend-bar">
    <span>
      Scanner:
      <select v-if="backends.length > 1" :value="selectedBackend" @change="onBackendChange">
        <option v-for="b in backends" :key="b.pluginId" :value="b.pluginId" :disabled="b.enabled === false">
          {{ b.pluginId }}{{ b.isDefault ? ' (default)' : '' }}
        </option>
      </select>
      <code v-else>{{ selectedBackend }}</code>
    </span>
    <span class="sub">
      {{ isSs2222 ? 'reads continuously' : 'one trigger per frame' }}
    </span>
  </div>

  <div class="card">
    <h3>Reader</h3>
    <div class="row">
      <button class="btn btn-primary" :disabled="!scanner || reading" @click="startReading">Start reading</button>
      <button class="btn" :disabled="!reading" @click="stopReading()">Stop</button>
      <label v-if="isSs2222" class="inline">
        <input v-model="beepAtEnd" type="checkbox" :disabled="reading" /> Beep only when complete
      </label>
      <span v-if="reading" class="scanning"><span class="pulse" /> reading{{ mode ? ` (${mode})` : '' }}…</span>
    </div>
    <p class="hint">
      Point the scanner at a rolling QR code (e.g. the Generator tab on another screen). Frames
      <code>AQR|id|n/total|chunk</code> are collected in any order until the payload is complete.
    </p>
  </div>

  <div class="card">
    <div class="card-head">
      <h3>Progress</h3>
      <span class="stats mono">
        {{ progress.received }}/{{ progress.total || '?' }} frames{{ encodingLabel ? ` · ${encodingLabel}` : '' }} ·
        {{ (elapsedMs / 1000).toFixed(1) }} s · {{ reads }} reads ({{ rate.toFixed(1) }}/s)
      </span>
    </div>
    <div v-if="progress.total" class="grid">
      <span
        v-for="i in progress.total"
        :key="i"
        class="cell"
        :class="{ got: !progress.missing.includes(i) }"
        :title="`Frame ${i}`"
      >{{ i }}</span>
    </div>
    <p v-else class="empty">No frames yet.</p>
    <div class="bar"><div class="fill" :style="{ width: progress.total ? `${(100 * progress.received) / progress.total}%` : '0%' }" /></div>
    <p v-if="otherSequence" class="warn">
      A frame of another sequence (<code>{{ otherSequence }}</code>) was skipped — Start again to read that one.
    </p>
    <p v-if="ignored" class="hint">Ignored {{ ignored }} other code(s), last: <code>{{ lastIgnored }}</code></p>
  </div>

  <div v-if="complete && decoded.error" class="banner banner-warning">{{ decoded.error }}</div>
  <div v-if="complete && !decoded.error" class="card done">
    <div class="card-head">
      <h3>Payload ({{ Array.from(payload).length }} characters)</h3>
      <button class="btn btn-sm" @click="copyPayload">Copy</button>
    </div>
    <p v-if="mangled" class="warn">
      The payload contains NUL characters: the scanner could not pass some characters through (the SS2222
      converts text to GB2312 and drops what it lacks). Generate the code with Base64 encoding.
    </p>
    <pre class="payload">{{ payload.replace(/\u0000/g, '␀') }}</pre>
  </div>
</template>

<style scoped>
h3 { margin: 0 0 12px; font-size: 14px; color: #444; text-transform: uppercase; letter-spacing: 1px; }
.card { border: 1px solid #ddd; border-radius: 8px; padding: 16px; margin-bottom: 16px; background: #fff; }
.card.done { border-color: #86efac; background: #f0fdf4; }
.card-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; }
.card-head h3 { margin-bottom: 0; }
.row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.inline { font-size: 13px; color: #555; display: inline-flex; align-items: center; gap: 6px; }
.hint { font-size: 12px; color: #888; margin: 10px 0 0; }
.hint code, .warn code { font-size: 12px; background: #f1f5f9; padding: 1px 4px; border-radius: 3px; word-break: break-all; }
.warn { font-size: 13px; color: #92400e; margin: 10px 0 0; }
.empty { color: #9ca3af; font-size: 13px; margin: 12px 0; }
.stats { font-size: 12px; color: #555; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.btn {
  padding: 6px 14px; border: 1px solid #ccc; border-radius: 6px; background: #fff;
  font-size: 13px; font-weight: 600; cursor: pointer;
}
.btn:hover { background: #f5f5f5; }
.btn-primary { background: #0066cc; color: #fff; border-color: #0066cc; }
.btn-primary:hover { background: #0055aa; }
.btn-sm { padding: 4px 10px; font-weight: 500; }
.btn:disabled { opacity: 0.45; cursor: not-allowed; }
.scanning { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: #16a34a; }
.pulse { width: 8px; height: 8px; border-radius: 50%; background: #16a34a; animation: pulse 1s ease-in-out infinite; }
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.25; } }
.grid { display: flex; flex-wrap: wrap; gap: 4px; margin: 12px 0; }
.cell {
  min-width: 28px; height: 28px; display: inline-flex; align-items: center; justify-content: center;
  border-radius: 4px; background: #f3f4f6; color: #9ca3af; font-size: 11px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.cell.got { background: #16a34a; color: #fff; }
.bar { height: 6px; background: #f3f4f6; border-radius: 3px; overflow: hidden; margin-top: 8px; }
.fill { height: 100%; background: #16a34a; transition: width 0.2s; }
.payload {
  white-space: pre-wrap; word-break: break-word; margin: 12px 0 0; font-size: 13px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.banner { padding: 12px 16px; border-radius: 6px; font-size: 14px; margin-bottom: 16px; }
.banner a { color: #0066cc; }
.banner code { font-size: 12px; background: rgba(0,0,0,0.06); padding: 1px 4px; border-radius: 3px; }
.banner-info { background: #eff6ff; border: 1px solid #bfdbfe; color: #1e40af; }
.banner-warning { background: #fffbeb; border: 1px solid #fde68a; color: #92400e; }
.backend-bar { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.backend-bar select { padding: 4px 8px; border: 1px solid #bfdbfe; border-radius: 6px; font-size: 13px; }
.sub { font-size: 12px; }
</style>
