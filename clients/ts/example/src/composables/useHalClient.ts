import { ref, reactive } from 'vue';
import type { HalClient } from '@kduma-autoid/hal-client';
import { LocalStorageTokenStore } from '@kduma-autoid/hal-client-token-store-browser';
import { useToast } from './useToast';

const SETTINGS_KEY = 'hal_example_settings';

export interface AppSettings {
  baseUrl: string;
  clientId: string;
  developerKey: string;
  transport: 'http' | 'ws';
}

const defaultSettings: AppSettings = {
  baseUrl: 'http://localhost:8400',
  clientId: 'hal-example',
  developerKey: '',
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
    const options = {
      clientId: settings.clientId,
      baseUrl: settings.baseUrl,
      developerKey: settings.developerKey || undefined,
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
