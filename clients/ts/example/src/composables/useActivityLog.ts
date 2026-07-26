import { ref } from 'vue';
import type { HalClient } from '@kduma-autoid/hal-client';
import type { EventMeta } from '@kduma-autoid/hal-client-common';
import { PROVIDER_PARAM_KEY } from '@kduma-autoid/hal-client-common';

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
  /** Provider pluginId: the handler for a method call, or the emitter (source) of an event. */
  provider?: string;
}

/** Splits the reserved `__provider` selector out of method params so it shows as the handler header
 * instead of leaking into the displayed request body. */
function stripProvider(params: unknown): { display: unknown; sent?: string } {
  if (
    params &&
    typeof params === 'object' &&
    !Array.isArray(params) &&
    PROVIDER_PARAM_KEY in (params as Record<string, unknown>)
  ) {
    const clone = { ...(params as Record<string, unknown>) };
    const sent = clone[PROVIDER_PARAM_KEY];
    delete clone[PROVIDER_PARAM_KEY];
    return { display: clone, sent: typeof sent === 'string' ? sent : undefined };
  }
  return { display: params };
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

  // Monkey-patch execute — observe the handling provider via the onMeta callback, and keep
  // `__provider` out of the displayed params (it becomes the handler header instead).
  const originalExecute = client.execute.bind(client);
  client.execute = async <T = unknown>(method: string, params?: unknown): Promise<T> => {
    const start = Date.now();
    const { display, sent } = stripProvider(params);
    let handler: string | undefined;
    try {
      const result = await originalExecute<T>(method, params, {
        onMeta: (meta) => { handler = meta.provider; },
      });
      addLog({
        type: 'method',
        timestamp: start,
        name: method,
        params: display,
        result,
        duration: Date.now() - start,
        provider: handler ?? sent,
      });
      return result;
    } catch (e) {
      addLog({
        type: 'method',
        timestamp: start,
        name: method,
        params: display,
        error: e instanceof Error ? e.message : String(e),
        duration: Date.now() - start,
        provider: sent,
      });
      throw e;
    }
  };

  // Subscribe to all events via IEventSubscriber (auto subscribe/unsubscribe)
  let offEvents: (() => Promise<void>) | null = null;
  client.on('*', (eventName: string, data: unknown, meta?: EventMeta) => {
    addLog({
      type: 'event',
      timestamp: Date.now(),
      name: eventName,
      data,
      provider: meta?.source,
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
