<script setup lang="ts">
import { ref, computed } from 'vue';
import { useActivityLog } from '../composables/useActivityLog';
import type { LogEntryType } from '../composables/useActivityLog';

const { logs, clearLogs } = useActivityLog();

const filter = ref<'all' | LogEntryType>('all');

const filteredLogs = computed(() => {
  if (filter.value === 'all') return logs.value;
  return logs.value.filter(l => l.type === filter.value);
});

const expandedEntries = ref<Set<number>>(new Set());

function toggleEntry(id: number) {
  if (expandedEntries.value.has(id)) {
    expandedEntries.value.delete(id);
  } else {
    expandedEntries.value.add(id);
  }
}

function formatTime(ts: number): string {
  const d = new Date(ts);
  return d.toLocaleTimeString(undefined, { hour12: false }) + '.' + String(d.getMilliseconds()).padStart(3, '0');
}

function prettyJson(value: unknown): string {
  if (value === undefined || value === null) return '';
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

const methodCount = computed(() => logs.value.filter(l => l.type === 'method').length);
const eventCount = computed(() => logs.value.filter(l => l.type === 'event').length);
</script>

<template>
  <div>
    <div class="header">
      <h2>Activity Log</h2>
      <span class="count">{{ logs.length }} entries ({{ methodCount }} methods, {{ eventCount }} events)</span>
    </div>

    <div class="toolbar">
      <div class="filters">
        <button class="btn" :class="{ active: filter === 'all' }" @click="filter = 'all'">All</button>
        <button class="btn" :class="{ active: filter === 'method' }" @click="filter = 'method'">Methods</button>
        <button class="btn" :class="{ active: filter === 'event' }" @click="filter = 'event'">Events</button>
      </div>
      <button class="btn btn-clear" @click="clearLogs" :disabled="logs.length === 0">Clear</button>
    </div>

    <div v-if="filteredLogs.length === 0" class="empty">
      <template v-if="logs.length === 0">No activity recorded yet. Connect and use the service to see logs here.</template>
      <template v-else>No {{ filter }} entries.</template>
    </div>

    <div v-else class="log-list">
      <div
        v-for="entry in filteredLogs"
        :key="entry.id"
        class="log-entry"
        :class="{ 'has-error': entry.error, expanded: expandedEntries.has(entry.id) }"
        @click="toggleEntry(entry.id)"
      >
        <div class="entry-header">
          <span class="entry-time">{{ formatTime(entry.timestamp) }}</span>
          <span class="badge" :class="entry.type === 'method' ? 'badge-method' : 'badge-event'">
            {{ entry.type === 'method' ? 'METHOD' : 'EVENT' }}
          </span>
          <code class="entry-name">{{ entry.name }}</code>
          <span v-if="entry.duration !== undefined" class="badge badge-duration">{{ entry.duration }}ms</span>
          <span v-if="entry.error" class="badge badge-error">ERROR</span>
        </div>

        <div v-if="expandedEntries.has(entry.id)" class="entry-details" @click.stop>
          <template v-if="entry.type === 'method'">
            <div v-if="entry.params !== undefined" class="detail-section">
              <label>Parameters</label>
              <pre>{{ prettyJson(entry.params) }}</pre>
            </div>
            <div v-if="entry.result !== undefined" class="detail-section">
              <label>Response</label>
              <pre class="pre-success">{{ prettyJson(entry.result) }}</pre>
            </div>
            <div v-if="entry.error" class="detail-section">
              <label>Error</label>
              <pre class="pre-error">{{ entry.error }}</pre>
            </div>
          </template>
          <template v-else>
            <div v-if="entry.data !== undefined" class="detail-section">
              <label>Event Data</label>
              <pre>{{ prettyJson(entry.data) }}</pre>
            </div>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
h2 { margin-top: 0; }
.header {
  display: flex;
  align-items: baseline;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.count {
  font-size: 13px;
  color: #888;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.filters {
  display: flex;
  gap: 4px;
}
.btn {
  padding: 5px 12px;
  border: 1px solid #d1d5db;
  border-radius: 4px;
  background: #fff;
  font-size: 13px;
  cursor: pointer;
}
.btn:hover { background: #f3f4f6; }
.btn.active {
  background: #0066cc;
  color: #fff;
  border-color: #0066cc;
}
.btn-clear { color: #dc2626; border-color: #fca5a5; }
.btn-clear:hover:not(:disabled) { background: #fef2f2; }
.btn:disabled { opacity: 0.4; cursor: default; }
.empty {
  text-align: center;
  padding: 32px;
  color: #999;
  font-size: 14px;
}
.log-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.log-entry {
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  background: #fff;
  cursor: pointer;
  transition: border-color 0.1s;
}
.log-entry:hover {
  border-color: #b0c4de;
}
.log-entry.has-error {
  border-left: 3px solid #ef4444;
}
.log-entry.expanded {
  border-color: #93c5fd;
}
.entry-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  flex-wrap: wrap;
}
.entry-time {
  font-family: monospace;
  font-size: 12px;
  color: #888;
  flex-shrink: 0;
}
.entry-name {
  font-size: 13px;
  background: #f3f4f6;
  padding: 1px 6px;
  border-radius: 3px;
  word-break: break-all;
}
.badge {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 8px;
  font-size: 10px;
  font-weight: 700;
  flex-shrink: 0;
}
.badge-method { background: #dbeafe; color: #1e40af; }
.badge-event { background: #fce7f3; color: #9d174d; }
.badge-duration { background: #f3f4f6; color: #666; font-weight: 400; }
.badge-error { background: #fef2f2; color: #dc2626; }
.entry-details {
  padding: 0 12px 10px;
  cursor: default;
}
.detail-section {
  margin-top: 6px;
}
.detail-section label {
  display: block;
  font-size: 11px;
  font-weight: 600;
  color: #666;
  margin-bottom: 2px;
}
.detail-section pre {
  margin: 0;
  padding: 8px 10px;
  font-family: monospace;
  font-size: 12px;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 4px;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
.pre-success {
  background: #f0fdf4;
  border-color: #bbf7d0;
  color: #166534;
}
.pre-error {
  background: #fef2f2;
  border-color: #fecaca;
  color: #dc2626;
}
</style>
