<script setup lang="ts">
import { computed } from 'vue';
import type { EventDescriptor } from '@kduma-autoid/hal-client-common';

interface ReceivedEvent {
  timestamp: number;
  data: unknown;
}

const props = defineProps<{
  event: EventDescriptor;
  receivedEvents: ReceivedEvent[];
}>();

function prettyJson(raw: string | unknown): string {
  if (!raw) return '';
  try {
    if (typeof raw === 'string') {
      return JSON.stringify(JSON.parse(raw), null, 2);
    }
    return JSON.stringify(raw, null, 2);
  } catch {
    return String(raw);
  }
}

const exampleEvent = computed(() => prettyJson(props.event.exampleEvent));

function formatTime(ts: number): string {
  return new Date(ts).toLocaleTimeString();
}
</script>

<template>
  <div class="event-monitor" @click.stop>
    <div v-if="exampleEvent" class="monitor-section">
      <label class="monitor-label">Example Event</label>
      <pre class="monitor-pre pre-example">{{ exampleEvent }}</pre>
    </div>

    <div class="monitor-section">
      <label class="monitor-label">
        Received Events
        <span v-if="receivedEvents.length" class="event-count">({{ receivedEvents.length }})</span>
      </label>
      <div v-if="receivedEvents.length === 0" class="no-events">No events received yet</div>
      <div v-else class="event-log">
        <div v-for="(evt, i) in receivedEvents" :key="i" class="event-entry">
          <span class="event-time">{{ formatTime(evt.timestamp) }}</span>
          <pre class="monitor-pre pre-received">{{ prettyJson(evt.data) }}</pre>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.event-monitor {
  padding: 12px 16px;
  background: #fafbfc;
  border-top: 1px solid #e5e7eb;
}
.monitor-section {
  margin-bottom: 8px;
}
.monitor-section:last-child {
  margin-bottom: 0;
}
.monitor-label {
  display: block;
  font-size: 12px;
  font-weight: 600;
  color: #555;
  margin-bottom: 4px;
}
.event-count {
  font-weight: 400;
  color: #888;
}
.monitor-pre {
  margin: 0;
  padding: 8px 12px;
  font-family: monospace;
  font-size: 12px;
  border-radius: 4px;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
.pre-example {
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  color: #6b7280;
}
.pre-received {
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  color: #1e40af;
}
.no-events {
  font-size: 12px;
  color: #999;
  font-style: italic;
}
.event-log {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 300px;
  overflow-y: auto;
}
.event-entry {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.event-time {
  font-size: 11px;
  color: #888;
  font-family: monospace;
}
</style>
