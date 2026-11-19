<script setup lang="ts">
import { ref, shallowRef, computed, watch, onUnmounted, reactive } from 'vue';
import { useHalClient } from '../composables/useHalClient';
import { useToast } from '../composables/useToast';
import { usePluginAvailability } from '../composables/usePluginAvailability';
import { bindBackend, enabledBackends, serialized } from '../composables/backendBinding';
import { MagneticCardReaderClient } from '@kduma-autoid/hal-client-plugin-magnetic-card-reader-facade';
import type { InterfaceProvider, MagneticCardTracks } from '@kduma-autoid/hal-client-common';
import { Msr605xClient, type Msr605xCoercivity, type Msr605xInfo, type Msr605xLed, type Msr605xTrack } from '@kduma-autoid/hal-client-plugin-msr605x';
import { SunmiCardClient, type SunmiCardInfo, type SunmiCardSwipeEvent } from '@kduma-autoid/hal-client-plugin-sunmi-card';
import {
  buildPaymentTracks,
  groupDigits,
  paymentCardToInput,
  parsePaymentCard,
  validateTrack,
  type PaymentCardInput,
  type TrackNumber,
} from '../utils/magneticCard';

const { client, isConnected } = useHalClient();
const toast = useToast();

const MAX_READS = 20;
/** Swipe waits must finish before the transport's 30 s request timeout. */
const MAX_TIMEOUT_S = 25;
const TRACKS: TrackNumber[] = [1, 2, 3];
const LEDS: Msr605xLed[] = ['off', 'green', 'yellow', 'red', 'all'];

function trackOf(tracks: MagneticCardTracks, n: TrackNumber): string | null {
  return n === 1 ? tracks.track1 : n === 2 ? tracks.track2 : tracks.track3;
}

function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

// ---------------------------------------------------------------------------------------------
// Backend binding (magneticCardReader interface)
// ---------------------------------------------------------------------------------------------

const reader = shallowRef<MagneticCardReaderClient | null>(null);
const backends = ref<InterfaceProvider[]>([]);
const selectedBackend = ref('');
const detecting = ref(false);
const bindError = ref('');
// Subscribing can fail on its own (the HTTP transport has no event channel) while binding worked.
const eventError = ref('');
const usable = computed(() => enabledBackends(backends.value));

let unsubscribeSwipe: (() => Promise<void>) | null = null;
let unsubscribeChanges: (() => Promise<void>) | null = null;

async function stopSwipeSubscription() {
  if (!unsubscribeSwipe) return;
  try {
    await unsubscribeSwipe();
  } catch {
    /* ignore */
  }
  unsubscribeSwipe = null;
}

const bind = serialized((pluginId?: string) => bindOnce(pluginId));

async function bindOnce(pluginId?: string) {
  if (!client.value || !isConnected.value) {
    await stopSwipeSubscription();
    reader.value = null;
    backends.value = [];
    return;
  }
  const c = client.value;
  detecting.value = true;
  try {
    backends.value = await MagneticCardReaderClient.listBackends(c);
    const { bound, pinFailed } = await bindBackend(
      pluginId,
      (id) => MagneticCardReaderClient.forBackend(c, id),
      () => MagneticCardReaderClient.create(c),
    );
    reader.value = bound;
    selectedBackend.value = bound.backend;
    bindError.value = '';
    if (pinFailed) toast.info(`${pinFailed} is not available — using ${bound.backend}`);
  } catch (e) {
    await stopSwipeSubscription();
    reader.value = null;
    selectedBackend.value = '';
    bindError.value = message(e);
    return;
  } finally {
    detecting.value = false;
    reading.value = false;
    writing.value = false;
  }

  try {
    await stopSwipeSubscription();
    unsubscribeSwipe = await reader.value.onSwipe((tracks) => addRead(tracks, 'event'));
    eventError.value = '';
  } catch (e) {
    await stopSwipeSubscription();
    eventError.value = message(e);
  }
}

function onBackendChange(e: Event) {
  bind((e.target as HTMLSelectElement).value);
}

const isReady = computed(() => isConnected.value && reader.value !== null);
const canRead = computed(() => isReady.value && !!reader.value?.capabilities.read);
const canWrite = computed(() => isReady.value && !!reader.value?.capabilities.write);
const subscription = computed(() =>
  selectedBackend.value ? `magneticCardReader.onSwipe@${selectedBackend.value}` : 'magneticCardReader.onSwipe',
);

// ---------------------------------------------------------------------------------------------
// Reads
// ---------------------------------------------------------------------------------------------

interface ReadEntry {
  at: Date;
  via: 'read' | 'event';
  tracks: MagneticCardTracks;
}

const reads = ref<ReadEntry[]>([]);
const selected = ref(0);
const reading = ref(false);
const timeoutS = ref(15);
const showPan = ref(false);

const swipeOptions = computed(() => ({
  timeoutMs: Math.min(Math.max(1, Number(timeoutS.value) || 15), MAX_TIMEOUT_S) * 1000,
}));

const sameTracks = (a: MagneticCardTracks, b: MagneticCardTracks) =>
  a.track1 === b.track1 && a.track2 === b.track2 && a.track3 === b.track3;

function addRead(tracks: MagneticCardTracks, via: ReadEntry['via']) {
  // An active read also emits onSwipe: keep one entry when the event already delivered it.
  const last = reads.value[0];
  if (last && Date.now() - last.at.getTime() < 3000 && sameTracks(last.tracks, tracks)) {
    selected.value = 0;
    return;
  }
  reads.value.unshift({ at: new Date(), via, tracks });
  if (reads.value.length > MAX_READS) reads.value.length = MAX_READS;
  selected.value = 0;
  showPan.value = false;
}

const current = computed<ReadEntry | null>(() => reads.value[selected.value] ?? null);
const payment = computed(() => (current.value ? parsePaymentCard(current.value.tracks) : null));

async function readCard() {
  const read = reader.value?.read;
  if (!read) return;
  reading.value = true;
  try {
    addRead(await read(swipeOptions.value), 'read');
  } catch (e) {
    toast.error(message(e));
  } finally {
    reading.value = false;
  }
}

async function cancel() {
  try {
    await reader.value?.cancel?.();
  } catch (e) {
    toast.error(message(e));
  }
}

function trackLabel(value: string | null): { text: string; cls: string } {
  if (value === null) return { text: 'read error', cls: 'state-error' };
  if (value === '') return { text: 'empty', cls: 'state-empty' };
  return { text: value, cls: '' };
}

function time(d: Date): string {
  return d.toLocaleTimeString(undefined, { hour12: false });
}

// ---------------------------------------------------------------------------------------------
// Write
// ---------------------------------------------------------------------------------------------

const writeMode = ref<'payment' | 'raw'>('payment');
const writing = ref(false);
const rawWrite = reactive<Record<TrackNumber, string>>({ 1: '', 2: '', 3: '' });
const paymentInput = reactive<PaymentCardInput>({
  pan: '4111 1111 1111 1111',
  cardholder: 'DOE/JOHN',
  expiry: '3012',
  serviceCode: '201',
  discretionary: '',
});

const rawErrors = computed(() =>
  TRACKS.map((n) => (rawWrite[n] ? validateTrack(n, rawWrite[n]) : null)),
);
const builtPayment = computed(() => buildPaymentTracks(paymentInput));

const pendingWrite = computed<{ track1?: string; track2?: string; track3?: string } | null>(() => {
  if (writeMode.value === 'payment') {
    if (builtPayment.value.errors.length) return null;
    return { track1: builtPayment.value.track1, track2: builtPayment.value.track2 };
  }
  if (rawErrors.value.some(Boolean)) return null;
  const tracks: { track1?: string; track2?: string; track3?: string } = {};
  if (rawWrite[1]) tracks.track1 = rawWrite[1];
  if (rawWrite[2]) tracks.track2 = rawWrite[2];
  if (rawWrite[3]) tracks.track3 = rawWrite[3];
  return Object.keys(tracks).length ? tracks : null;
});

async function writeCard() {
  const write = reader.value?.write;
  if (!write || !pendingWrite.value) return;
  writing.value = true;
  try {
    await write(pendingWrite.value, swipeOptions.value);
    toast.success('Card written');
  } catch (e) {
    toast.error(message(e));
  } finally {
    writing.value = false;
  }
}

function copyToWriter() {
  if (!current.value) return;
  if (payment.value) {
    Object.assign(paymentInput, paymentCardToInput(payment.value));
    writeMode.value = 'payment';
  } else {
    TRACKS.forEach((n) => {
      rawWrite[n] = trackOf(current.value!.tracks, n) ?? '';
    });
    writeMode.value = 'raw';
  }
  toast.info('Copied to the writer — swipe a blank card to write it');
}

// ---------------------------------------------------------------------------------------------
// Native plugin panels
// ---------------------------------------------------------------------------------------------

const { isAvailable: hasMsr605x } = usePluginAvailability({ pluginId: 'msr605x', capabilities: ['msr605x'] });
const { isAvailable: hasSunmiCard } = usePluginAvailability({ pluginId: 'sunmi.card', capabilities: ['sunmi.card'] });

const msr = computed(() => (client.value && hasMsr605x.value ? new Msr605xClient(client.value) : null));
const msrInfo = ref<Msr605xInfo | null>(null);
const msrCoercivity = ref<Msr605xCoercivity | null>(null);
const msrBusy = ref(false);
const eraseTracks = reactive<Record<Msr605xTrack, boolean>>({ 1: true, 2: true, 3: true });
const erasing = ref(false);

async function msrCall<T>(fn: (m: Msr605xClient) => Promise<T>, done?: string): Promise<T | undefined> {
  if (!msr.value) return undefined;
  msrBusy.value = true;
  try {
    const result = await fn(msr.value);
    if (done) toast.success(done);
    return result;
  } catch (e) {
    toast.error(message(e));
    return undefined;
  } finally {
    msrBusy.value = false;
  }
}

async function refreshMsr() {
  msrInfo.value = (await msrCall((m) => m.getInfo())) ?? null;
  msrCoercivity.value = (await msrCall((m) => m.getCoercivity())) ?? null;
}

async function setCoercivity(mode: Msr605xCoercivity) {
  await msrCall((m) => m.setCoercivity(mode), `Coercivity set to ${mode === 'hi' ? 'Hi-Co' : 'Lo-Co'}`);
  msrCoercivity.value = (await msrCall((m) => m.getCoercivity())) ?? msrCoercivity.value;
}

async function setListening(enabled: boolean) {
  await msrCall((m) => m.setListening(enabled), `Listening ${enabled ? 'on' : 'off'}`);
  msrInfo.value = (await msrCall((m) => m.getInfo())) ?? msrInfo.value;
}

function setLed(led: Msr605xLed) {
  msrCall((m) => m.setLed(led));
}

async function erase() {
  const tracks = TRACKS.filter((n) => eraseTracks[n]);
  if (!tracks.length || !msr.value) return;
  erasing.value = true;
  try {
    await msr.value.erase(tracks, swipeOptions.value);
    toast.success(`Erased track${tracks.length > 1 ? 's' : ''} ${tracks.join(', ')}`);
  } catch (e) {
    toast.error(message(e));
  } finally {
    erasing.value = false;
  }
}

async function cancelErase() {
  await msrCall((m) => m.cancel());
}

const sunmi = computed(() => (client.value && hasSunmiCard.value ? new SunmiCardClient(client.value) : null));
const sunmiInfo = ref<SunmiCardInfo | null>(null);
const sunmiLastSwipe = ref<SunmiCardSwipeEvent | null>(null);
let unsubscribeSunmi: (() => Promise<void>) | null = null;

async function refreshSunmi() {
  if (!sunmi.value) return;
  try {
    sunmiInfo.value = await sunmi.value.getInfo();
  } catch (e) {
    toast.error(message(e));
  }
}

async function stopSunmiSubscription() {
  if (!unsubscribeSunmi) return;
  try {
    await unsubscribeSunmi();
  } catch {
    /* ignore */
  }
  unsubscribeSunmi = null;
}

watch(sunmi, async (s) => {
  await stopSunmiSubscription();
  sunmiInfo.value = null;
  if (!s) return;
  try {
    unsubscribeSunmi = await s.onSwipe((data) => {
      sunmiLastSwipe.value = data;
    });
  } catch {
    /* no event channel (HTTP) — the info button still works */
  }
});

watch(msr, (m) => {
  msrInfo.value = null;
  msrCoercivity.value = null;
  if (m) refreshMsr();
});

// ---------------------------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------------------------

async function teardown() {
  await stopSwipeSubscription();
  await stopSunmiSubscription();
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
        // Hot-plug (the MSR605X is USB) / interface reorder: re-resolve, keeping the pinned backend.
        // Without an event channel (HTTP transport) there is nothing to follow; eventError says so.
        try {
          unsubscribeChanges = await MagneticCardReaderClient.onChanged(client.value, () => {
            bind(selectedBackend.value || undefined);
          });
        } catch {
          /* no event channel */
        }
      }
    } else {
      await teardown();
      reader.value = null;
      backends.value = [];
    }
  },
  { immediate: true },
);
</script>

<template>
  <h2>Magnetic Card</h2>

  <div v-if="!isConnected" class="banner banner-info">
    Not connected.
    <router-link to="/settings">Go to Settings</router-link>
    to configure and connect.
  </div>
  <div v-else-if="detecting && !reader" class="banner banner-info">Resolving card reader backend...</div>
  <div v-else-if="!reader && usable.length" class="banner banner-warning">
    Could not bind a card reader backend: {{ bindError }}
    <router-link to="/interfaces">Go to Interfaces</router-link> to inspect the registry.
  </div>
  <div v-else-if="!reader && backends.length" class="banner banner-warning">
    Every provider of the <code>magneticCardReader</code> interface is disabled.
    <router-link to="/interfaces">Go to Interfaces</router-link> to enable one.
  </div>
  <div v-else-if="!reader" class="banner banner-warning">
    No magnetic card reader is available — the <code>magneticCardReader</code> interface has no provider.
    Plug in an MSR605X (and accept the USB permission prompt), or enable the experimental plugins in the
    service settings.
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
        <span class="badge" :class="{ off: !reader.capabilities.read }">read</span>
        <span class="badge" :class="{ off: !reader.capabilities.write }">write</span>
      </span>
      <span class="sub">listening on <code>{{ subscription }}</code></span>
    </div>
    <div v-if="eventError" class="banner banner-warning">
      Live swipes are unavailable: {{ eventError }}
      <template v-if="canRead">On-demand reads still work.</template>
      Switch to the WebSocket transport in <router-link to="/settings">Settings</router-link> to receive
      <code>onSwipe</code>.
    </div>
  </template>

  <!-- Read -->
  <div class="card">
    <h3>Read card</h3>
    <div v-if="canRead" class="row">
      <button class="btn btn-primary" :disabled="reading || writing" @click="readCard">Read card</button>
      <button class="btn" :disabled="!reading" @click="cancel">Cancel</button>
      <label class="inline">
        Swipe timeout
        <input v-model.number="timeoutS" type="number" min="1" :max="MAX_TIMEOUT_S" class="num" /> s
      </label>
      <span v-if="reading" class="waiting"><span class="pulse" /> swipe a card…</span>
    </div>
    <p v-else-if="isReady" class="hint">
      <code>{{ selectedBackend }}</code> is a passive reader — just swipe a card; reads appear below as they happen.
    </p>
    <p v-else class="hint">Bind a card reader to read cards.</p>
  </div>

  <div class="card">
    <div class="card-head">
      <h3>Reads <span class="count">{{ reads.length }}</span></h3>
      <button class="btn btn-sm" :disabled="!reads.length" @click="reads = []">Clear</button>
    </div>

    <p v-if="!reads.length" class="empty">No cards read yet.</p>
    <template v-else>
      <div class="read-list">
        <button
          v-for="(r, i) in reads"
          :key="`${r.at.getTime()}-${i}`"
          class="read-chip"
          :class="{ active: i === selected }"
          @click="selected = i; showPan = false"
        >
          <span class="mono">{{ time(r.at) }}</span>
          <span class="via">{{ r.via }}</span>
        </button>
      </div>

      <div v-if="current" class="tracks">
        <div v-for="n in TRACKS" :key="n" class="track">
          <span class="track-label">Track {{ n }}</span>
          <span class="mono track-value" :class="trackLabel(trackOf(current.tracks, n)).cls">
            {{ trackLabel(trackOf(current.tracks, n)).text }}
          </span>
        </div>
      </div>

      <div v-if="payment" class="payment">
        <div class="payment-head">
          <h4>Payment card <span class="brand">{{ payment.brand }}</span></h4>
          <span class="sub">from {{ payment.sources.join(' + ') }}</span>
        </div>
        <dl>
          <dt>Card number</dt>
          <dd>
            <span class="mono pan">{{ showPan ? groupDigits(payment.pan) : payment.maskedPan }}</span>
            <button class="btn btn-sm" @click="showPan = !showPan">{{ showPan ? 'Hide' : 'Show' }}</button>
            <span class="pill" :class="payment.luhnValid ? 'ok' : 'bad'">
              {{ payment.luhnValid ? 'Luhn OK' : 'Luhn check failed' }}
            </span>
          </dd>
          <dt>Cardholder</dt>
          <dd>
            <template v-if="payment.cardholder">
              {{ payment.cardholder.first }} {{ payment.cardholder.last }}
              <span class="mono sub">({{ payment.cardholder.raw.trim() }})</span>
            </template>
            <span v-else class="sub">not on track 2 — track 1 missing</span>
          </dd>
          <dt>Expires</dt>
          <dd>
            <template v-if="payment.expiry">
              {{ String(payment.expiry.month).padStart(2, '0') }}/{{ payment.expiry.year }}
              <span v-if="payment.expiry.expired" class="pill bad">expired</span>
              <span v-else class="pill ok">valid</span>
            </template>
            <span v-else class="sub">invalid date</span>
          </dd>
          <dt>Service code</dt>
          <dd v-if="payment.serviceCode">
            <span class="mono">{{ payment.serviceCode.raw }}</span>
            <ul class="svc">
              <li>{{ payment.serviceCode.interchange }}</li>
              <li>{{ payment.serviceCode.authorization }}</li>
              <li>{{ payment.serviceCode.allowedServices }}</li>
            </ul>
          </dd>
          <dt>Discretionary data</dt>
          <dd class="mono">{{ payment.discretionary || '—' }}</dd>
        </dl>
      </div>
      <p v-else-if="current" class="hint">These tracks are not in payment card (ISO/IEC 7813) format.</p>

      <div v-if="canWrite && current" class="row top-gap">
        <button class="btn btn-sm" @click="copyToWriter">Copy to writer</button>
      </div>
    </template>
  </div>

  <!-- Write -->
  <div v-if="canWrite" class="card">
    <div class="card-head">
      <h3>Write card</h3>
      <div class="tabs">
        <button class="tab" :class="{ active: writeMode === 'payment' }" @click="writeMode = 'payment'">Payment card</button>
        <button class="tab" :class="{ active: writeMode === 'raw' }" @click="writeMode = 'raw'">Raw tracks</button>
      </div>
    </div>

    <template v-if="writeMode === 'payment'">
      <div class="form-grid">
        <label>Card number <input v-model="paymentInput.pan" class="mono" placeholder="4111 1111 1111 1111" /></label>
        <label>Cardholder (SURNAME/FIRST) <input v-model="paymentInput.cardholder" placeholder="DOE/JOHN" /></label>
        <label>Expiry (YYMM) <input v-model="paymentInput.expiry" class="mono" maxlength="4" placeholder="3012" /></label>
        <label>Service code <input v-model="paymentInput.serviceCode" class="mono" maxlength="3" placeholder="201" /></label>
        <label class="wide">Discretionary data <input v-model="paymentInput.discretionary" class="mono" /></label>
      </div>
      <div class="preview">
        <div class="track"><span class="track-label">Track 1</span><span class="mono track-value">{{ builtPayment.track1 }}</span></div>
        <div class="track"><span class="track-label">Track 2</span><span class="mono track-value">{{ builtPayment.track2 }}</span></div>
      </div>
      <ul v-if="builtPayment.errors.length" class="errors">
        <li v-for="err in builtPayment.errors" :key="err">{{ err }}</li>
      </ul>
      <p class="hint">Use test card numbers only. Sentinels (%, ;, ?) are added by the device.</p>
    </template>

    <template v-else>
      <div v-for="(n, i) in TRACKS" :key="n" class="raw-row">
        <label>
          Track {{ n }}
          <input v-model="rawWrite[n]" class="mono" :placeholder="n === 1 ? 'ABC 123' : '1234=5678'" />
        </label>
        <span v-if="rawErrors[i]" class="error">{{ rawErrors[i] }}</span>
      </div>
      <p class="hint">Data without sentinels. Empty tracks are left untouched on the card.</p>
    </template>

    <div class="row top-gap">
      <button class="btn btn-primary" :disabled="!pendingWrite || writing || reading" @click="writeCard">
        Write (swipe card)
      </button>
      <button class="btn" :disabled="!writing" @click="cancel">Cancel</button>
      <span v-if="writing" class="waiting"><span class="pulse" /> swipe the card to write…</span>
    </div>
  </div>

  <!-- MSR605X native -->
  <div v-if="msr" class="card">
    <div class="card-head">
      <h3>MSR605X device <code class="plugin">msr605x</code></h3>
      <button class="btn btn-sm" :disabled="msrBusy" @click="refreshMsr">Refresh</button>
    </div>
    <dl v-if="msrInfo" class="compact">
      <dt>Connected</dt><dd>{{ msrInfo.connected ? 'yes' : 'no' }}</dd>
      <dt>USB permission</dt><dd>{{ msrInfo.permission ? 'granted' : 'not granted' }}</dd>
      <dt>Model</dt><dd>{{ msrInfo.model ?? '—' }}</dd>
      <dt>Firmware</dt><dd class="mono">{{ msrInfo.firmware ?? '—' }}</dd>
    </dl>

    <div class="row">
      <span class="label">Listening</span>
      <button class="btn btn-sm" :class="{ selected: msrInfo?.listening === true }" :disabled="msrBusy" @click="setListening(true)">On</button>
      <button class="btn btn-sm" :class="{ selected: msrInfo?.listening === false }" :disabled="msrBusy" @click="setListening(false)">Off</button>
      <span class="hint">every swipe arrives as an event; LEDs return to the reader's own state</span>
    </div>
    <div class="row">
      <span class="label">Coercivity</span>
      <button class="btn btn-sm" :class="{ selected: msrCoercivity === 'hi' }" :disabled="msrBusy" @click="setCoercivity('hi')">Hi-Co</button>
      <button class="btn btn-sm" :class="{ selected: msrCoercivity === 'lo' }" :disabled="msrBusy" @click="setCoercivity('lo')">Lo-Co</button>
    </div>
    <div class="row">
      <span class="label">LEDs</span>
      <button v-for="led in LEDS" :key="led" class="btn btn-sm" :disabled="msrBusy" @click="setLed(led)">
        {{ led }}
      </button>
    </div>
    <div class="row">
      <span class="label">Erase</span>
      <label v-for="n in TRACKS" :key="n" class="inline"><input v-model="eraseTracks[n]" type="checkbox" /> track {{ n }}</label>
      <button class="btn btn-sm danger" :disabled="erasing || !TRACKS.some((n) => eraseTracks[n])" @click="erase">Erase (swipe card)</button>
      <button class="btn btn-sm" :disabled="!erasing" @click="cancelErase">Cancel</button>
      <span v-if="erasing" class="waiting"><span class="pulse" /> swipe the card to erase…</span>
    </div>
    <div class="row">
      <span class="label">Reset</span>
      <button class="btn btn-sm" :disabled="msrBusy" @click="msrCall((m) => m.reset(), 'Reset sent')">Soft</button>
      <button class="btn btn-sm" :disabled="msrBusy" @click="msrCall((m) => m.reset({ hard: true }), 'Hard reset sent')">Hard</button>
    </div>
  </div>

  <!-- Sunmi card native -->
  <div v-if="sunmi" class="card">
    <div class="card-head">
      <h3>Sunmi card reader <code class="plugin">sunmi.card</code></h3>
      <button class="btn btn-sm" @click="refreshSunmi">Get info</button>
    </div>
    <dl v-if="sunmiInfo" class="compact">
      <dt>Name</dt><dd>{{ sunmiInfo.name }}</dd>
      <dt>Version</dt><dd>{{ sunmiInfo.version }}</dd>
      <dt>Serial</dt><dd class="mono">{{ sunmiInfo.sn }}</dd>
      <dt>Connected</dt><dd>{{ sunmiInfo.connected ? 'yes' : 'no' }}</dd>
    </dl>
    <p class="hint">Last native <code>sunmi.card.swipe</code> (raw reader output, with sentinels):</p>
    <div class="mono raw-swipe">{{ sunmiLastSwipe?.raw ?? '—' }}</div>
  </div>
</template>

<style scoped>
h2 { margin-top: 0; }
h3 { margin: 0 0 12px; font-size: 14px; color: #444; text-transform: uppercase; letter-spacing: 1px; }
h4 { margin: 0; font-size: 14px; }

.card { border: 1px solid #ddd; border-radius: 8px; padding: 16px; margin-bottom: 16px; background: #fff; }
.card-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; margin-bottom: 12px; }
.card-head h3 { margin-bottom: 0; }
.count { font-size: 11px; background: #eef2ff; color: #4338ca; border-radius: 10px; padding: 1px 7px; margin-left: 6px; }
.plugin { font-size: 11px; text-transform: none; letter-spacing: 0; background: #f1f5f9; padding: 1px 5px; border-radius: 3px; }

.row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 8px; }
.top-gap { margin-top: 12px; }
.label { font-size: 12px; color: #666; width: 80px; }
.inline { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: #444; }
.num { width: 56px; padding: 4px 6px; border: 1px solid #ccc; border-radius: 4px; }
.hint { font-size: 12px; color: #888; margin: 8px 0 0; }
.empty { color: #9ca3af; font-size: 13px; margin: 0; }
.sub { font-size: 12px; color: #6b7280; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }

.btn { padding: 6px 14px; border: 1px solid #ccc; border-radius: 6px; background: #fff; font-size: 13px; font-weight: 600; cursor: pointer; }
.btn:hover { background: #f5f5f5; }
.btn-primary { background: #0066cc; color: #fff; border-color: #0066cc; }
.btn-primary:hover { background: #0055aa; }
.btn-sm { padding: 4px 10px; font-weight: 500; }
.btn.selected { border-color: #0066cc; color: #0066cc; background: #eff6ff; }
.btn.danger { color: #b91c1c; border-color: #fca5a5; }
.btn:disabled { opacity: 0.45; cursor: not-allowed; }

.waiting { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: #16a34a; }
.pulse { width: 8px; height: 8px; border-radius: 50%; background: #16a34a; animation: pulse 1s ease-in-out infinite; }
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.25; } }

.badge { font-size: 11px; background: #dcfce7; border: 1px solid #bbf7d0; color: #166534; border-radius: 4px; padding: 1px 6px; margin-left: 6px; }
.badge.off { background: #f3f4f6; border-color: #e5e7eb; color: #9ca3af; text-decoration: line-through; }

.read-list { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 12px; }
.read-chip { display: inline-flex; gap: 6px; align-items: center; border: 1px solid #e5e7eb; background: #f9fafb; border-radius: 14px; padding: 3px 10px; font-size: 12px; cursor: pointer; }
.read-chip.active { border-color: #0066cc; background: #eff6ff; }
.via { color: #9ca3af; font-size: 11px; }

.tracks, .preview { display: flex; flex-direction: column; gap: 6px; }
.track { display: flex; gap: 10px; align-items: baseline; }
.track-label { font-size: 11px; text-transform: uppercase; letter-spacing: 0.5px; color: #9ca3af; width: 60px; flex-shrink: 0; }
.track-value { word-break: break-all; font-size: 13px; }
.state-empty { color: #9ca3af; font-style: italic; font-family: inherit; }
.state-error { color: #b91c1c; font-style: italic; font-family: inherit; }

.payment { margin-top: 16px; border: 1px solid #e0e7ff; background: #f8faff; border-radius: 8px; padding: 12px 14px; }
.payment-head { display: flex; justify-content: space-between; align-items: baseline; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.brand { font-size: 12px; background: #1e3a8a; color: #fff; border-radius: 4px; padding: 1px 7px; margin-left: 6px; }
dl { display: grid; grid-template-columns: 140px 1fr; gap: 6px 12px; margin: 0; font-size: 13px; }
dl.compact { grid-template-columns: 120px 1fr; margin-bottom: 12px; }
dt { color: #6b7280; }
dd { margin: 0; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.pan { font-size: 15px; letter-spacing: 1px; }
.pill { font-size: 11px; border-radius: 10px; padding: 1px 8px; }
.pill.ok { background: #dcfce7; color: #166534; }
.pill.bad { background: #fee2e2; color: #991b1b; }
.svc { margin: 4px 0 0; padding-left: 18px; color: #374151; width: 100%; }

.tabs { display: flex; gap: 4px; }
.tab { border: 1px solid #ddd; background: #fff; border-radius: 6px; padding: 4px 10px; font-size: 12px; cursor: pointer; }
.tab.active { border-color: #0066cc; color: #0066cc; background: #eff6ff; font-weight: 600; }

.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 14px; margin-bottom: 12px; }
.form-grid label, .raw-row label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: #555; }
.form-grid .wide { grid-column: 1 / -1; }
.form-grid input, .raw-row input { padding: 6px 8px; border: 1px solid #ccc; border-radius: 6px; font-size: 13px; }
.raw-row { margin-bottom: 10px; }
.error { display: block; color: #b91c1c; font-size: 12px; margin-top: 3px; }
.errors { color: #b91c1c; font-size: 12px; margin: 8px 0 0; padding-left: 18px; }
.preview { background: #f9fafb; border: 1px dashed #d1d5db; border-radius: 6px; padding: 8px 10px; }
.raw-swipe { background: #f9fafb; border: 1px solid #eee; border-radius: 6px; padding: 8px; font-size: 12px; word-break: break-all; margin-top: 6px; }

.backend-bar { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.backend-bar select { padding: 4px 8px; border: 1px solid #bfdbfe; border-radius: 6px; font-size: 13px; }

.banner { padding: 12px 16px; border-radius: 6px; font-size: 14px; margin-bottom: 16px; }
.banner a { color: #0066cc; }
.banner code { font-size: 12px; background: rgba(0,0,0,0.06); padding: 1px 4px; border-radius: 3px; }
.banner-info { background: #eff6ff; border: 1px solid #bfdbfe; color: #1e40af; }
.banner-warning { background: #fffbeb; border: 1px solid #fde68a; color: #92400e; }

@media (max-width: 600px) {
  .form-grid { grid-template-columns: 1fr; }
  dl, dl.compact { grid-template-columns: 1fr; }
  dt { margin-top: 6px; }
}
</style>
