<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue';
import QRCode from 'qrcode';
import { buildFrames, formatFrame, hasNonAscii, randomSequenceId } from '../utils/rollingQr';

const DEFAULT_PAYLOAD =
  'Rolling QR demo. Each frame carries one fragment of this message; the reader puts them back ' +
  'together in any order. Zażółć gęślą jaźń 🙂 0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ abcdefghijklmnopqrstuvwxyz.';

const payload = ref(DEFAULT_PAYLOAD);
const chunkLength = ref(40);
const frameMs = ref(300);
/** Auto: Base64 only when the payload has non-ASCII characters (scanners may not pass them through). */
const encodingChoice = ref<'auto' | 'text' | 'base64'>('auto');
const sequenceId = ref(randomSequenceId());
const playing = ref(true);
const current = ref(0);
const canvas = ref<HTMLCanvasElement | null>(null);
const stage = ref<HTMLElement | null>(null);
const renderError = ref('');

const safeChunk = computed(() => Math.min(Math.max(1, Math.trunc(Number(chunkLength.value) || 1)), 500));
const safeFrameMs = computed(() => Math.min(Math.max(50, Math.trunc(Number(frameMs.value) || 300)), 5000));
const encoding = computed(() =>
  encodingChoice.value === 'auto' ? (hasNonAscii(payload.value) ? 'base64' : 'text') : encodingChoice.value,
);
const frames = computed(() => buildFrames(payload.value, safeChunk.value, sequenceId.value, encoding.value));
const frameText = computed(() => formatFrame(frames.value[Math.min(current.value, frames.value.length - 1)]));

let timer: ReturnType<typeof setInterval> | null = null;

function schedule() {
  if (timer) clearInterval(timer);
  timer = null;
  if (playing.value && frames.value.length > 1) {
    timer = setInterval(() => {
      current.value = (current.value + 1) % frames.value.length;
    }, safeFrameMs.value);
  }
}

async function draw() {
  await nextTick();
  if (!canvas.value) return;
  try {
    // White quiet zone and a fixed size: scanners read best from a large, high-contrast code.
    await QRCode.toCanvas(canvas.value, frameText.value, { errorCorrectionLevel: 'M', margin: 2, width: 360 });
    renderError.value = '';
  } catch (e) {
    renderError.value = e instanceof Error ? e.message : String(e);
  }
}

function step(delta: number) {
  playing.value = false;
  const n = frames.value.length;
  current.value = (current.value + delta + n) % n;
}

function jump(i: number) {
  playing.value = false;
  current.value = i;
}

function newSequence() {
  sequenceId.value = randomSequenceId();
  current.value = 0;
}

function fullscreen() {
  stage.value?.requestFullscreen?.().catch(() => { /* not allowed here */ });
}

watch([playing, safeFrameMs, () => frames.value.length], schedule);
watch(frames, () => {
  if (current.value >= frames.value.length) current.value = 0;
});
watch(frameText, draw);
onMounted(() => {
  schedule();
  draw();
});
onUnmounted(() => {
  if (timer) clearInterval(timer);
});
</script>

<template>
  <div class="card">
    <h3>Generator</h3>
    <label class="field">
      <span>Payload</span>
      <textarea v-model="payload" rows="3" spellcheck="false" />
    </label>
    <div class="row">
      <label class="inline">Fragment length <input v-model.number="chunkLength" type="number" min="1" max="500" /></label>
      <label class="inline">Frame time [ms] <input v-model.number="frameMs" type="number" min="50" max="5000" step="50" /></label>
      <label class="inline">
        Encoding
        <select v-model="encodingChoice">
          <option value="auto">Auto ({{ hasNonAscii(payload) ? 'Base64' : 'text' }})</option>
          <option value="text">Text (AQR)</option>
          <option value="base64">Base64 (AQRB)</option>
        </select>
      </label>
      <span class="hint">{{ Array.from(payload).length }} characters → {{ frames.length }} frame(s), sequence <code>{{ sequenceId }}</code></span>
    </div>
    <div v-if="encoding === 'text' && hasNonAscii(payload)" class="warn">
      Non-ASCII text as plain frames: some scanners mangle it (the SS2222 converts to GB2312 and drops what
      it lacks, e.g. ż, ł, emoji). Use Base64 to carry any text intact.
    </div>
    <div class="row controls">
      <button class="btn btn-primary" @click="playing = !playing">{{ playing ? 'Pause' : 'Play' }}</button>
      <button class="btn" :disabled="frames.length < 2" @click="step(-1)">‹ Prev</button>
      <button class="btn" :disabled="frames.length < 2" @click="step(1)">Next ›</button>
      <button class="btn" @click="newSequence">New sequence</button>
      <button class="btn" @click="fullscreen">Fullscreen</button>
    </div>
  </div>

  <div ref="stage" class="stage">
    <div class="code">
      <canvas ref="canvas" />
    </div>
    <div class="label mono">{{ sequenceId }} {{ current + 1 }}/{{ frames.length }}</div>
    <div class="dots">
      <button
        v-for="(f, i) in frames"
        :key="f.index"
        class="dot"
        :class="{ on: i === current }"
        :title="`Frame ${f.index}`"
        @click="jump(i)"
      />
    </div>
    <div class="frame mono">{{ frameText }}</div>
    <div v-if="renderError" class="error">Cannot render this frame: {{ renderError }}</div>
  </div>
</template>

<style scoped>
h3 { margin: 0 0 12px; font-size: 14px; color: #444; text-transform: uppercase; letter-spacing: 1px; }
.card { border: 1px solid #ddd; border-radius: 8px; padding: 16px; margin-bottom: 16px; background: #fff; }
.row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.controls { margin-top: 12px; }
.field { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: #555; margin-bottom: 10px; }
.field textarea {
  width: 100%; box-sizing: border-box; font: 13px ui-monospace, SFMono-Regular, Menlo, monospace;
  padding: 8px; border: 1px solid #ccc; border-radius: 6px; resize: vertical;
}
.inline { font-size: 13px; color: #555; display: inline-flex; align-items: center; gap: 6px; }
.inline input, .inline select { padding: 4px 6px; border: 1px solid #ccc; border-radius: 6px; font-size: 13px; }
.inline input { width: 80px; }
.warn { font-size: 12px; color: #92400e; background: #fffbeb; border: 1px solid #fde68a; border-radius: 6px; padding: 6px 10px; margin-top: 10px; }
.hint { font-size: 12px; color: #888; }
.hint code { font-size: 12px; background: #f1f5f9; padding: 1px 4px; border-radius: 3px; }
.btn {
  padding: 6px 14px; border: 1px solid #ccc; border-radius: 6px; background: #fff;
  font-size: 13px; font-weight: 600; cursor: pointer;
}
.btn:hover { background: #f5f5f5; }
.btn-primary { background: #0066cc; color: #fff; border-color: #0066cc; }
.btn-primary:hover { background: #0055aa; }
.btn:disabled { opacity: 0.45; cursor: not-allowed; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }

/* The stage stays white in fullscreen too: the code needs its quiet zone against any page theme. */
.stage { display: flex; flex-direction: column; align-items: center; gap: 8px; background: #fff; padding: 12px; }
.stage:fullscreen { justify-content: center; }
.code { background: #fff; padding: 8px; }
.code canvas { display: block; width: min(80vw, 60vh, 420px) !important; height: auto !important; image-rendering: pixelated; }
.label { font-size: 22px; font-weight: 600; }
.dots { display: flex; flex-wrap: wrap; gap: 4px; justify-content: center; max-width: 480px; }
.dot { width: 12px; height: 12px; border-radius: 50%; border: none; background: #d1d5db; padding: 0; cursor: pointer; }
.dot.on { background: #0066cc; }
.frame { font-size: 12px; color: #666; word-break: break-all; text-align: center; max-width: 480px; }
.error { color: #b91c1c; font-size: 13px; }
</style>
