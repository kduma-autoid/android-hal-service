<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue';
import { useRoute } from 'vue-router';
import type { PluginDescriptor, InterfaceDescriptor, EventMeta } from '@kduma-autoid/hal-client-common';
import { allEvents } from '@kduma-autoid/hal-client-common';
import { useHalClient } from '../composables/useHalClient';
import { useToast } from '../composables/useToast';
import PluginCard from '../components/PluginCard.vue';

const route = useRoute();
const { client, isConnected } = useHalClient();
const toast = useToast();

const plugin = ref<PluginDescriptor | null>(null);
const interfaces = ref<InterfaceDescriptor[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);

interface ReceivedEvent {
  timestamp: number;
  data: unknown;
  source?: string;
}

const receivedEvents = reactive<Record<string, ReceivedEvent[]>>({});

const pluginId = computed(() => route.params.pluginId as string);
const withSuper = computed(() => route.query.withSuper === 'true');
const withExperimental = computed(() => route.query.withExperimental === 'true');

const backLink = computed(() => {
  const query: Record<string, string> = {};
  if (withSuper.value) query.withSuper = 'true';
  if (withExperimental.value) query.withExperimental = 'true';
  return { name: 'describe', query };
});

let unsubscribers: (() => Promise<void>)[] = [];
let isUnmounted = false;

async function subscribeToEvents() {
  cleanupSubscriptions();

  if (!client.value || !plugin.value) return;

  // Native events plus the events of any interface this plugin provides.
  const eventNames = new Set<string>(allEvents(plugin.value).map((e) => e.name));
  for (const id of plugin.value.providesInterfaces ?? []) {
    interfaces.value.find((i) => i.interfaceId === id)?.events.forEach((e) => eventNames.add(e.name));
  }

  const promises: Promise<void>[] = [];
  for (const name of eventNames) {
    if (!receivedEvents[name]) receivedEvents[name] = [];
    promises.push(
      client.value
        .on(name, (_n: string, data: unknown, meta?: EventMeta) => {
          if (isUnmounted) return;
          receivedEvents[name].push({ timestamp: Date.now(), data, source: meta?.source });
          toast.info(`Event: ${name}`);
        })
        .then((off) => {
          unsubscribers.push(off);
        })
        .catch(() => {
          // event transport may not be available (HTTP mode)
        }),
    );
  }

  await Promise.all(promises);
}

function cleanupSubscriptions() {
  const toCleanup = unsubscribers;
  unsubscribers = [];
  for (const off of toCleanup) {
    off().catch(() => { /* ignore */ });
  }
}

async function fetchPlugin() {
  if (!client.value || !isConnected.value) return;

  loading.value = true;
  error.value = null;
  try {
    const result = await client.value.getDescribe({
      withSuper: withSuper.value,
      withExperimental: withExperimental.value,
    });
    const found = result.plugins.find(p => p.pluginId === pluginId.value);
    if (found) {
      plugin.value = found;
      interfaces.value = result.interfaces ?? [];
      await subscribeToEvents();
    } else {
      error.value = `Plugin not found: ${pluginId.value}`;
      plugin.value = null;
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
    plugin.value = null;
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  if (isConnected.value) fetchPlugin();
});

onUnmounted(() => {
  isUnmounted = true;
  cleanupSubscriptions();
});

watch(isConnected, (connected) => {
  if (connected) fetchPlugin();
  else {
    cleanupSubscriptions();
    plugin.value = null;
  }
});

watch(pluginId, () => {
  Object.keys(receivedEvents).forEach(k => delete receivedEvents[k]);
  fetchPlugin();
});
</script>

<template>
  <div>
    <router-link :to="backLink" class="back-link">&larr; Back to API Explorer</router-link>

    <div v-if="!isConnected" class="not-connected">
      Not connected.
      <router-link to="/settings">Go to Settings</router-link>
      to configure and connect.
    </div>

    <template v-else>
      <div v-if="error" class="error">{{ error }}</div>

      <div v-if="loading && !plugin" class="loading">Loading plugin details...</div>

      <PluginCard v-if="plugin" :plugin="plugin" :interfaces="interfaces" :received-events="receivedEvents" />
    </template>
  </div>
</template>

<style scoped>
.back-link {
  display: inline-block;
  margin-bottom: 16px;
  color: #0066cc;
  font-size: 14px;
  text-decoration: none;
}
.back-link:hover { text-decoration: underline; }
.not-connected {
  padding: 24px;
  text-align: center;
  color: #666;
  font-size: 15px;
}
.not-connected a { color: #0066cc; }
.error {
  padding: 10px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 6px;
  color: #dc2626;
  font-size: 13px;
  margin-bottom: 12px;
}
.loading {
  text-align: center;
  padding: 24px;
  color: #888;
}
</style>
