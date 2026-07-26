<script setup lang="ts">
import { ref, shallowRef, computed, watch, onUnmounted } from 'vue';
import { useHalClient } from '../composables/useHalClient';
import { useToast } from '../composables/useToast';
import {
  SunmiPrinterClient,
  PRINTER_FEATURES,
  type PrinterFeature,
} from '@kduma-autoid/hal-client-plugin-sunmi-printer-facade';
import type { InterfaceProvider } from '@kduma-autoid/hal-client-common';

const { client, isConnected } = useHalClient();
const toast = useToast();

// The facade resolves the `printer` interface: it picks the default provider (or a pinned one) and
// exposes only the methods whose feature the backend advertises — so this view renders a card per
// supported command format and greys out the rest, mirroring the server-side feature gate.
const printer = shallowRef<SunmiPrinterClient | null>(null);
const backends = ref<InterfaceProvider[]>([]);
const selectedBackend = ref<string>('');
const detecting = ref(false);
let unsubscribeChanges: (() => Promise<void>) | null = null;

async function bind(pluginId?: string) {
  if (!client.value || !isConnected.value) {
    printer.value = null;
    backends.value = [];
    return;
  }
  detecting.value = true;
  try {
    backends.value = await SunmiPrinterClient.listBackends(client.value);
    printer.value = pluginId
      ? await SunmiPrinterClient.forBackend(client.value, pluginId)
      : await SunmiPrinterClient.create(client.value);
    selectedBackend.value = printer.value.backend;
  } catch {
    // No provider (or the pinned one vanished) — the template shows the "no backend" banner.
    printer.value = null;
    selectedBackend.value = '';
  } finally {
    detecting.value = false;
  }
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

onUnmounted(teardownChanges);

watch(
  isConnected,
  async (isConn) => {
    if (isConn) {
      await bind();
      if (client.value && !unsubscribeChanges) {
        // Hot-plug / interface reorder: re-resolve, keeping the user's pinned backend if it survives.
        unsubscribeChanges = await SunmiPrinterClient.onChanged(client.value, () => {
          bind(selectedBackend.value || undefined);
        });
      }
    } else {
      await teardownChanges();
      printer.value = null;
      backends.value = [];
    }
  },
  { immediate: true },
);

function onBackendChange(e: Event) {
  bind((e.target as HTMLSelectElement).value);
}

const isReady = computed(() => isConnected.value && printer.value !== null);
const caps = computed(
  () => printer.value?.capabilities ?? { escpos: false, tspl: false, zpl: false, image: false, cut: false },
);
const unsupported = computed(() => PRINTER_FEATURES.filter((f) => !caps.value[f as PrinterFeature]));

// --- base64 helpers (the interface takes raw bytes as base64) ---

function bytesToBase64(bytes: Uint8Array): string {
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin);
}

function textToBase64(text: string): string {
  return bytesToBase64(new TextEncoder().encode(text));
}

/** Wraps text in a minimal ESC/POS job: ESC @ (init), the text, then a short feed. */
function escPosJob(text: string): string {
  const body = new TextEncoder().encode(text.endsWith('\n') ? text : `${text}\n`);
  const bytes = new Uint8Array(2 + body.length + 3);
  bytes[0] = 0x1b; // ESC
  bytes[1] = 0x40; // @  -> initialize printer
  bytes.set(body, 2);
  bytes.set([0x0a, 0x0a, 0x0a], 2 + body.length); // feed
  return bytesToBase64(bytes);
}

async function exec(label: string, fn: () => Promise<unknown>) {
  try {
    await fn();
    toast.success(`${label} sent`);
  } catch (e) {
    toast.error(e instanceof Error ? e.message : String(e));
  }
}

// --- ESC/POS ---
const escposText = ref('HAL demo receipt\nThank you!');
const escposRaw = ref('');
function printEscPosText() {
  exec('ESC/POS', () => printer.value!.printEscPos!(escPosJob(escposText.value)));
}
function printEscPosRaw() {
  exec('ESC/POS (raw)', () => printer.value!.printEscPos!(escposRaw.value.trim()));
}

// --- TSPL / ZPL ---
const tsplText = ref('SIZE 40 mm,30 mm\nCLS\nTEXT 20,20,"3",0,1,1,"HAL demo"\nPRINT 1,1\n');
const zplText = ref('^XA^FO50,50^ADN,36,20^FDHAL demo^FS^XZ');
function printTspl() {
  exec('TSPL', () => printer.value!.printTspl!(textToBase64(tsplText.value)));
}
function printZpl() {
  exec('ZPL', () => printer.value!.printZpl!(textToBase64(zplText.value)));
}

// --- Image ---
const imageBase64 = ref('');
const imageName = ref('');
const imagePreview = ref('');
const imageAlgorithm = ref('BINARIZATION');
const imageValue = ref(200);

function onImagePicked(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0];
  if (!file) return;
  imageName.value = file.name;
  const reader = new FileReader();
  reader.onload = () => {
    const dataUrl = String(reader.result ?? '');
    imagePreview.value = dataUrl;
    // The interface wants the bare base64 payload, not the data: URL wrapper.
    imageBase64.value = dataUrl.slice(dataUrl.indexOf(',') + 1);
  };
  reader.onerror = () => toast.error(`Could not read ${file.name}`);
  reader.readAsDataURL(file);
}

function printImage() {
  exec('Image', () =>
    printer.value!.printImage!(imageBase64.value, {
      algorithm: imageAlgorithm.value,
      value: imageValue.value,
    }),
  );
}

// --- Cut ---
function cut() {
  exec('Cut', () => printer.value!.cut!());
}
</script>

<template>
  <h2>Printer</h2>

  <div v-if="!isConnected" class="banner banner-info">
    Not connected.
    <router-link to="/settings">Go to Settings</router-link>
    to configure and connect.
  </div>
  <div v-else-if="detecting && !printer" class="banner banner-info">Resolving printer backend...</div>
  <div v-else-if="!printer" class="banner banner-warning">
    No printer backend is available — the <code>printer</code> interface has no provider on this
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
    <span class="chips">
      <span v-for="f in PRINTER_FEATURES" :key="f" class="chip" :class="{ on: caps[f] }">{{ f }}</span>
    </span>
  </div>

  <div v-if="caps.escpos" class="card">
    <h3>ESC/POS</h3>
    <textarea v-model="escposText" rows="3" :disabled="!isReady" />
    <div class="row">
      <button class="btn btn-primary" :disabled="!isReady" @click="printEscPosText">Print text</button>
      <span class="hint">Wrapped as <code>ESC @</code> + text + feed, sent as base64.</span>
    </div>
    <details class="raw">
      <summary>Send raw base64</summary>
      <div class="row">
        <input v-model="escposRaw" placeholder="G0A=" :disabled="!isReady" />
        <button class="btn" :disabled="!isReady || !escposRaw.trim()" @click="printEscPosRaw">Send</button>
      </div>
    </details>
  </div>

  <div v-if="caps.tspl" class="card">
    <h3>TSPL</h3>
    <textarea v-model="tsplText" rows="5" :disabled="!isReady" />
    <div class="row">
      <button class="btn btn-primary" :disabled="!isReady" @click="printTspl">Print label</button>
      <span class="hint">Command text is base64-encoded before sending.</span>
    </div>
  </div>

  <div v-if="caps.zpl" class="card">
    <h3>ZPL</h3>
    <textarea v-model="zplText" rows="3" :disabled="!isReady" />
    <div class="row">
      <button class="btn btn-primary" :disabled="!isReady" @click="printZpl">Print label</button>
    </div>
  </div>

  <div v-if="caps.image" class="card">
    <h3>Image</h3>
    <div class="row">
      <input type="file" accept="image/*" :disabled="!isReady" @change="onImagePicked" />
    </div>
    <div v-if="imagePreview" class="row">
      <img :src="imagePreview" :alt="imageName" class="preview" />
    </div>
    <div class="row">
      <label class="timing-label">ALGORITHM</label>
      <select v-model="imageAlgorithm" :disabled="!isReady">
        <option value="BINARIZATION">BINARIZATION</option>
        <option value="DITHERING">DITHERING</option>
      </select>
      <label class="timing-label">VALUE</label>
      <input v-model.number="imageValue" type="number" min="0" max="255" :disabled="!isReady" />
      <button class="btn btn-primary" :disabled="!isReady || !imageBase64" @click="printImage">Print image</button>
    </div>
  </div>

  <div v-if="caps.cut" class="card">
    <h3>Paper</h3>
    <button class="btn btn-cut" :disabled="!isReady" @click="cut">Cut</button>
    <span class="hint">On hardware with a combined feed+cut this feeds and cuts.</span>
  </div>

  <div v-if="printer && unsupported.length" class="banner banner-muted">
    Not supported by <code>{{ selectedBackend }}</code>:
    <code v-for="f in unsupported" :key="f" class="missing">{{ f }}</code>
    — the interface omits these methods, so the service would reject them as
    <code>unavailable</code>.
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

textarea {
  width: 100%;
  box-sizing: border-box;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 13px;
  padding: 8px;
  border: 1px solid #ccc;
  border-radius: 6px;
  resize: vertical;
}

.row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 10px;
}
.row input[type="text"],
.row input:not([type]) { flex: 1; min-width: 180px; }
.row input,
.row select {
  padding: 6px 8px;
  border: 1px solid #ccc;
  border-radius: 6px;
  font-size: 13px;
}
.row input[type="number"] { width: 80px; }
.timing-label { font-size: 12px; color: #888; }
.hint { font-size: 12px; color: #aaa; }

.preview {
  max-width: 240px;
  max-height: 160px;
  border: 1px solid #eee;
  border-radius: 4px;
}

.raw { margin-top: 12px; }
.raw summary { font-size: 12px; color: #888; cursor: pointer; }

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
.btn-cut { background: #7c4dff; color: #fff; border-color: #7c4dff; }
.btn-cut:hover { background: #651fff; }
.btn:disabled { opacity: 0.45; cursor: not-allowed; }
textarea:disabled, .row input:disabled, .row select:disabled { opacity: 0.45; cursor: not-allowed; }

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
.chips { display: flex; gap: 6px; flex-wrap: wrap; }
.chip {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  padding: 2px 8px;
  border-radius: 10px;
  background: #f1f5f9;
  color: #94a3b8;
  border: 1px solid #e2e8f0;
}
.chip.on { background: #dcfce7; color: #166534; border-color: #bbf7d0; }
.missing { margin-left: 4px; }

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
.banner-muted { background: #f9fafb; border: 1px solid #e5e7eb; color: #6b7280; }
</style>
