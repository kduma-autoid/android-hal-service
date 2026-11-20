import { ref, watch, computed } from 'vue';
import type { StatusResponse } from '@kduma-autoid/hal-client-common';
import { useHalClient } from './useHalClient';
import { useSystemChanges } from './useSystemChanges';

const status = ref<StatusResponse | null>(null);
const loading = ref(false);
const fetchError = ref<string | null>(null);
let fetched = false;

/** [silent]: a background re-read keeps the current answer instead of going back to "checking". */
async function fetchStatus(silent = false): Promise<void> {
  const { client, isConnected } = useHalClient();
  if (!client.value || !isConnected.value) return;

  if (!silent) loading.value = true;
  fetchError.value = null;
  try {
    status.value = await client.value.getStatus();
    fetched = true;
  } catch (e) {
    fetchError.value = e instanceof Error ? e.message : String(e);
    if (!silent) status.value = null;
  } finally {
    loading.value = false;
  }
}

const { isConnected } = useHalClient();
watch(isConnected, (connected) => {
  if (connected) {
    fetched = false;
    fetchStatus();
  } else {
    status.value = null;
    fetchError.value = null;
    fetched = false;
  }
});

// App-wide, not per component: views gate on this, and a reader plugged in later must unlock them.
useSystemChanges(() => fetchStatus(true));

export interface PluginRequirement {
  pluginId?: string;
  capabilities?: string[];
}

export function usePluginAvailability(
  opts?: PluginRequirement | PluginRequirement[],
) {
  if (isConnected.value && !fetched && !loading.value) {
    fetchStatus();
  }

  const requirements = computed(() => {
    if (!opts) return [];
    return Array.isArray(opts) ? opts : [opts];
  });

  function checkRequirement(req: PluginRequirement): boolean | null {
    if (loading.value || !status.value) return null;

    const caps = req.capabilities;
    if (!caps || caps.length === 0) return true;

    return Object.values(status.value.plugins).some((plugin) =>
      caps.every((cap) => plugin.capabilities.includes(cap)),
    );
  }

  const plugins = computed(() =>
    requirements.value.map((req) => ({
      pluginId: req.pluginId ?? null,
      capabilities: req.capabilities ?? [],
      available: checkRequirement(req),
    })),
  );

  const isAvailable = computed<boolean | null>(() => {
    if (requirements.value.length === 0) return true;
    const results = plugins.value.map((p) => p.available);
    if (results.some((r) => r === null)) return null;
    return results.every((r) => r === true);
  });

  return {
    isAvailable,
    plugins,
    loading,
    fetchError,
    refreshStatus: () => fetchStatus(),
  };
}
