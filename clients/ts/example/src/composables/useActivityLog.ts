import { ref } from 'vue';
import type { HalClient } from '@kduma-autoid/hal-client';

export type LogEntryType = 'method' | 'event';

export interface ActivityLogEntry {
  id: number;
  type: LogEntryType;
  timestamp: number;
  name: string;
  params?: unknown;
  result?: unknown;
  error?: string;
  duration?: number;
  data?: unknown;
}

const MAX_ENTRIES = 100;
let nextId = 0;
const logs = ref<ActivityLogEntry[]>([]);
let cleanup: (() => void) | null = null;

function addLog(entry: Omit<ActivityLogEntry, 'id'>) {
  logs.value.unshift({ ...entry, id: ++nextId });
  if (logs.value.length > MAX_ENTRIES) {
    logs.value.length = MAX_ENTRIES;
  }
}

function clearLogs() {
  logs.value = [];
}

function startLogging(client: HalClient) {
  stopLogging();

  // Monkey-patch execute
  const originalExecute = client.execute.bind(client);
  client.execute = async <T = unknown>(method: string, params?: unknown): Promise<T> => {
    const start = Date.now();
    try {
      const result = await originalExecute<T>(method, params);
      addLog({
        type: 'method',
        timestamp: start,
        name: method,
        params,
        result,
        duration: Date.now() - start,
      });
      return result;
    } catch (e) {
      addLog({
        type: 'method',
        timestamp: start,
        name: method,
        params,
        error: e instanceof Error ? e.message : String(e),
        duration: Date.now() - start,
      });
      throw e;
    }
  };

  // Subscribe to all events via IEventSubscriber (auto subscribe/unsubscribe)
  let offEvents: (() => Promise<void>) | null = null;
  client.on('*', (eventName: string, data: unknown) => {
    addLog({
      type: 'event',
      timestamp: Date.now(),
      name: eventName,
      data,
    });
  }).then(off => {
    offEvents = off;
  }).catch(() => {
    // event transport may not be available (HTTP mode)
  });

  cleanup = () => {
    client.execute = originalExecute;
    offEvents?.().catch(() => { /* ignore */ });
  };
}

function stopLogging() {
  if (cleanup) {
    cleanup();
    cleanup = null;
  }
}

export function useActivityLog() {
  return { logs, clearLogs, startLogging, stopLogging };
}
