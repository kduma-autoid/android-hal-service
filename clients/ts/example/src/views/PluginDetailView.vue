<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue';
import { useRoute } from 'vue-router';
import type { PluginDescriptor } from '@kduma-autoid/hal-client-common';
import { allEvents } from '@kduma-autoid/hal-client-common';
import { useHalClient } from '../composables/useHalClient';
import { useToast } from '../composables/useToast';
import PluginCard from '../components/PluginCard.vue';

const route = useRoute();
const { client, isConnected } = useHalClient();
const toast = useToast();

const plugin = ref<PluginDescriptor | null>(null);
const loading = ref(false);
const error = ref<string | null>(null);

interface ReceivedEvent {
  timestamp: number;
  data: unknown;
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

async function subscribeToEvents() {
  await cleanupSubscriptions();

  if (!client.value || !plugin.value) return;

  const events = allEvents(plugin.value);
  for (const event of events) {
    if (!receivedEvents[event.name]) {
      receivedEvents[event.name] = [];
    }

    try {
      const off = await client.value.on(event.name, (_name: string, data: unknown) => {
        receivedEvents[event.name].push({ timestamp: Date.now(), data });
        toast.info(`Event: ${event.name}`);
      });

      unsubscribers.push(off);
    } catch {
      // event transport may not be available (HTTP mode)
    }
  }
}

async function cleanupSubscriptions() {
  for (const off of unsubscribers) {
    await off();
  }
  unsubscribers = [];
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

onUnmounted(async () => {
  await cleanupSubscriptions();
});

watch(isConnected, async (connected) => {
  if (connected) fetchPlugin();
  else {
    await cleanupSubscriptions();
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

      <PluginCard v-if="plugin" :plugin="plugin" :received-events="receivedEvents" />
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
