import { ref, reactive } from 'vue';
import type { HalClient } from '@kduma-autoid/hal-client';
import { LocalStorageTokenStore } from '@kduma-autoid/hal-client-token-store-browser';
import { useToast } from './useToast';

const SETTINGS_KEY = 'hal_example_settings';

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
  baseUrl: string;
  clientId: string;
  serviceKey: string;
  deviceSecret: string;
  transport: 'http' | 'ws';
}

const defaultSettings: AppSettings = {
  baseUrl: 'http://localhost:8400',
  clientId: 'hal-example',
  serviceKey: import.meta.env.VITE_SERVICE_KEY ?? '',
  deviceSecret: '',
  transport: 'http',
};

const settings = reactive<AppSettings>({ ...defaultSettings });
const client = ref<HalClient | null>(null);
const isConnected = ref(false);
const error = ref<string | null>(null);

function loadSettings(): void {
  try {
    const raw = localStorage.getItem(SETTINGS_KEY);
    if (raw) {
      Object.assign(settings, { ...defaultSettings, ...JSON.parse(raw) });
    }
  } catch {
    // ignore corrupt settings
  }
}

function saveSettings(newSettings: AppSettings): void {
  Object.assign(settings, newSettings);
  localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings));
}

async function connect(): Promise<void> {
  const toast = useToast();
  error.value = null;

  if (client.value) {
    client.value.dispose();
    client.value = null;
    isConnected.value = false;
  }

  try {
    const tokenStore = new LocalStorageTokenStore('hal_example_token');
    let serviceKey = settings.serviceKey || undefined;
    if (!serviceKey && settings.deviceSecret) {
      serviceKey = await generateDeviceKeyJwt(settings.deviceSecret, settings.clientId);
    }
    const options = {
      clientId: settings.clientId,
      baseUrl: settings.baseUrl,
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

    await newClient.requestToken();
    client.value = newClient;
    isConnected.value = true;
    toast.success(`Connected via ${settings.transport.toUpperCase()}`);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    error.value = msg;
    isConnected.value = false;
    toast.error(`Connection failed: ${msg}`);
  }
}

function disconnect(): void {
  const toast = useToast();
  if (client.value) {
    client.value.dispose();
    client.value = null;
  }
  isConnected.value = false;
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
    error,
    loadSettings,
    saveSettings,
    connect,
    disconnect,
  };
}
