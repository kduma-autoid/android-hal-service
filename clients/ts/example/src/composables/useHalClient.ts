import { ref, reactive, computed } from 'vue';
import type { AuthState, ConnectionState } from '@kduma-autoid/hal-client-common';
import type { HalClient } from '@kduma-autoid/hal-client';
import { LocalStorageTokenStore } from '@kduma-autoid/hal-client-token-store-browser';
import { useToast } from './useToast';
import { useActivityLog } from './useActivityLog';

const SETTINGS_KEY = 'hal_example_settings';
const BUILTIN_SERVICE_KEY = import.meta.env.VITE_SERVICE_KEY ?? '';

export type AuthMode = 'builtin' | 'service-key' | 'device-secret' | 'none';

export const hasBuiltinKey = !!BUILTIN_SERVICE_KEY;

function base64urlEncode(data: Uint8Array | string): string {
  const bytes = typeof data === 'string' ? new TextEncoder().encode(data) : data;
  let binary = '';
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function base64urlDecode(str: string): Uint8Array {
  const padded = str.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

async function generateDeviceKeyJwt(secret: string, clientId: string): Promise<string> {
  const keyBytes = base64urlDecode(secret);
  const cryptoKey = await crypto.subtle.importKey(
    'raw', keyBytes, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'],
  );

  const header = base64urlEncode(JSON.stringify({ alg: 'HS256', typ: 'hal-service-key+jwt' }));
  const now = Math.floor(Date.now() / 1000);
  const payload = base64urlEncode(JSON.stringify({
    iss: 'device-key',
    sub: clientId,
    iat: now,
    exp: now + 3600,
    client_type: 'web',
    permissions: ['*'],
  }));

  const sigInput = `${header}.${payload}`;
  const sig = await crypto.subtle.sign('HMAC', cryptoKey, new TextEncoder().encode(sigInput));
  return `${sigInput}.${base64urlEncode(new Uint8Array(sig))}`;
}

export interface AppSettings {
  host: string;
  port: number;
  secure: boolean;
  clientId: string;
  authMode: AuthMode;
  serviceKey: string;
  deviceSecret: string;
  transport: 'http' | 'ws';
}

const defaultSettings: AppSettings = {
  host: 'localhost',
  port: 8400,
  secure: false,
  clientId: 'hal-example',
  authMode: hasBuiltinKey ? 'builtin' : 'none',
  serviceKey: '',
  deviceSecret: '',
  transport: 'ws',
};

const settings = reactive<AppSettings>({ ...defaultSettings });
const client = ref<HalClient | null>(null);
/** The transport link as the library reports it (HTTP has no link to lose: `connected` once set up). */
const linkState = ref<ConnectionState>('disconnected');
const authState = ref<AuthState>('unauthenticated');
/**
 * Usable right now: linked and authorized. Drops while reconnecting or once the token is rejected,
 * and comes back by itself after the library restored the session — views watching it refetch.
 */
const isConnected = computed(
  // `refreshing` counts as usable: requests wait for the renewal, and views shouldn't tear down for it.
  () => linkState.value === 'connected' && (authState.value === 'authenticated' || authState.value === 'refreshing'),
);
/** What the navbar shows. */
const status = computed<{ text: string; tone: 'ok' | 'busy' | 'bad' | 'off' }>(() => {
  if (!client.value) return { text: 'Disconnected', tone: 'off' };
  if (linkState.value === 'reconnecting' || linkState.value === 'connecting') return { text: 'Reconnecting…', tone: 'busy' };
  if (linkState.value === 'disconnected') return { text: 'Connection lost', tone: 'bad' };
  switch (authState.value) {
    case 'authenticated':
      return { text: 'Connected', tone: 'ok' };
    case 'refreshing':
      return { text: 'Renewing token…', tone: 'busy' };
    case 'expired':
      return { text: 'Session expired', tone: 'bad' };
    case 'denied':
      return { text: 'Access denied', tone: 'bad' };
    default:
      return { text: 'Not authorized', tone: 'bad' };
  }
});
/** The token was rejected and can only be renewed by asking again (permission dialog / new key). */
const needsToken = computed(() => !!client.value && (authState.value === 'expired' || authState.value === 'denied'));
const error = ref<string | null>(null);
let unwatch: Array<() => void> = [];

function loadSettings(): void {
  try {
    const raw = localStorage.getItem(SETTINGS_KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      // Migrate old settings without authMode
      if (!parsed.authMode) {
        if (parsed.serviceKey) {
          parsed.authMode = 'service-key';
        } else if (parsed.deviceSecret) {
          parsed.authMode = 'device-secret';
        } else {
          parsed.authMode = defaultSettings.authMode;
        }
      }
      // If saved authMode is 'builtin' but no builtin key available, fall back
      if (parsed.authMode === 'builtin' && !hasBuiltinKey) {
        parsed.authMode = 'none';
      }
      Object.assign(settings, { ...defaultSettings, ...parsed });
    }
  } catch {
    // ignore corrupt settings
  }
}

function saveSettings(newSettings: AppSettings): void {
  Object.assign(settings, newSettings);
  // Don't persist the builtin key to localStorage
  const toSave = { ...settings, serviceKey: settings.authMode === 'builtin' ? '' : settings.serviceKey };
  localStorage.setItem(SETTINGS_KEY, JSON.stringify(toSave));
}

/**
 * The service key for the client: a fixed JWT, or — for the device secret — a function signing a
 * fresh short-lived JWT on every token request, so the library can renew tokens long after connecting.
 */
function serviceKeyOption(): string | (() => Promise<string>) | undefined {
  switch (settings.authMode) {
    case 'builtin':
      return BUILTIN_SERVICE_KEY || undefined;
    case 'service-key':
      return settings.serviceKey || undefined;
    case 'device-secret': {
      const { deviceSecret, clientId } = settings;
      return deviceSecret ? () => generateDeviceKeyJwt(deviceSecret, clientId) : undefined;
    }
    case 'none':
      return undefined;
  }
}

function stopWatching(): void {
  for (const off of unwatch) off();
  unwatch = [];
}

function watchClient(c: HalClient, transport: 'http' | 'ws'): void {
  stopWatching();
  const toast = useToast();
  authState.value = c.authState;
  unwatch.push(
    c.onAuthStateChange((state) => {
      authState.value = state;
      if (state === 'expired') toast.error('Session expired — request a new token');
    }),
  );
  if (transport === 'ws') {
    linkState.value = c.connectionState;
    unwatch.push(
      c.onConnectionStateChange((event) => {
        const was = linkState.value;
        linkState.value = event.state;
        if (event.state === 'reconnecting' && was === 'connected') {
          toast.info(event.reason === 'timeout' ? 'Connection silent — reconnecting…' : 'Connection lost — reconnecting…');
        } else if (event.state === 'connected' && was === 'reconnecting') {
          toast.success('Reconnected');
        }
      }),
    );
  } else {
    linkState.value = 'connected';
  }
}

async function connect(): Promise<void> {
  const toast = useToast();
  error.value = null;

  if (client.value) {
    stopWatching();
    client.value.dispose();
    client.value = null;
  }
  linkState.value = 'disconnected';
  authState.value = 'unauthenticated';

  try {
    const tokenStore = new LocalStorageTokenStore('hal_example_token');
    const serviceKey = serviceKeyOption();
    const options = {
      clientId: settings.authMode === 'builtin' ? 'hal-example' : settings.clientId,
      host: settings.host,
      port: settings.port,
      secure: settings.secure,
      serviceKey,
      tokenStore,
    };

    let newClient: HalClient;

    if (settings.transport === 'ws') {
      const { createWsHalClient } = await import('@kduma-autoid/hal-client/ws');
      newClient = createWsHalClient(options);
      await newClient.connect();
    } else {
      const { createHttpHalClient } = await import('@kduma-autoid/hal-client/http');
      newClient = createHttpHalClient(options);
    }

    watchClient(newClient, settings.transport);
    client.value = newClient;
    await newClient.requestToken();
    const { startLogging } = useActivityLog();
    startLogging(newClient);
    toast.success(`Connected via ${settings.transport.toUpperCase()}`);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    error.value = msg;
    stopWatching();
    client.value?.dispose();
    client.value = null;
    linkState.value = 'disconnected';
    authState.value = 'unauthenticated';
    toast.error(`Connection failed: ${msg}`);
  }
}

/** After `expired` / `denied`: ask for a new token (with no service key this shows the permission dialog). */
async function requestNewToken(): Promise<void> {
  const toast = useToast();
  if (!client.value) return;
  try {
    await client.value.requestToken();
    toast.success('Token renewed');
  } catch (e) {
    toast.error(`Token request failed: ${e instanceof Error ? e.message : String(e)}`);
  }
}

function disconnect(): void {
  const toast = useToast();
  const { stopLogging } = useActivityLog();
  stopLogging();
  stopWatching();
  if (client.value) {
    client.value.dispose();
    client.value = null;
  }
  linkState.value = 'disconnected';
  authState.value = 'unauthenticated';
  error.value = null;
  toast.info('Disconnected');
}

// Load settings on first import
loadSettings();

export function useHalClient() {
  return {
    settings,
    client,
    isConnected,
    linkState,
    authState,
    status,
    needsToken,
    requestNewToken,
    error,
    loadSettings,
    saveSettings,
    connect,
    disconnect,
  };
}
