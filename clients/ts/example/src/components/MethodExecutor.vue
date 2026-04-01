<script setup lang="ts">
import { ref } from 'vue';
import type { MethodDescriptor } from '@kduma-autoid/hal-client-common';
import { useHalClient } from '../composables/useHalClient';

const props = defineProps<{ method: MethodDescriptor }>();
const { client } = useHalClient();

function prettyJson(raw: string): string {
  if (!raw) return '';
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

const params = ref(prettyJson(props.method.exampleParameters));
const exampleOutput = prettyJson(props.method.exampleOutput);
const response = ref<string | null>(null);
const responseError = ref(false);
const executing = ref(false);

async function execute() {
  if (!client.value) return;
  executing.value = true;
  response.value = null;
  responseError.value = false;
  try {
    const parsed = params.value.trim() ? JSON.parse(params.value) : undefined;
    const result = await client.value.execute(props.method.name, parsed);
    response.value = JSON.stringify(result, null, 2);
    responseError.value = false;
  } catch (e) {
    response.value = e instanceof Error ? e.message : String(e);
    responseError.value = true;
  } finally {
    executing.value = false;
  }
}

function reset() {
  params.value = prettyJson(props.method.exampleParameters);
  response.value = null;
  responseError.value = false;
}
</script>

<template>
  <div class="method-executor" @click.stop>
    <div class="executor-section">
      <label class="executor-label">Parameters</label>
      <textarea
        v-model="params"
        class="executor-textarea"
        rows="6"
        spellcheck="false"
      />
    </div>
    <div class="executor-actions">
      <button class="btn btn-execute" @click="execute" :disabled="executing || !client">
        {{ executing ? 'Executing...' : 'Execute' }}
      </button>
      <button class="btn btn-reset" @click="reset">Reset</button>
    </div>
    <div v-if="response !== null" class="executor-section">
      <label class="executor-label">Response</label>
      <pre class="executor-response" :class="{ 'response-error': responseError, 'response-success': !responseError }">{{ response }}</pre>
    </div>
    <div v-else-if="exampleOutput" class="executor-section">
      <label class="executor-label">Example Output</label>
      <pre class="executor-response response-example">{{ exampleOutput }}</pre>
    </div>
  </div>
</template>

<style scoped>
.method-executor {
  padding: 12px 16px;
  background: #fafbfc;
  border-top: 1px solid #e5e7eb;
}
.executor-section {
  margin-bottom: 8px;
}
.executor-label {
  display: block;
  font-size: 12px;
  font-weight: 600;
  color: #555;
  margin-bottom: 4px;
}
.executor-textarea {
  width: 100%;
  font-family: monospace;
  font-size: 12px;
  padding: 8px;
  border: 1px solid #d1d5db;
  border-radius: 4px;
  background: #fff;
  resize: vertical;
  box-sizing: border-box;
}
.executor-textarea:focus {
  outline: none;
  border-color: #0066cc;
  box-shadow: 0 0 0 2px rgba(0, 102, 204, 0.15);
}
.executor-actions {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.btn {
  padding: 6px 16px;
  font-size: 13px;
  font-weight: 500;
  border: 1px solid #d1d5db;
  border-radius: 4px;
  cursor: pointer;
}
.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn-execute {
  background: #0066cc;
  color: #fff;
  border-color: #0066cc;
}
.btn-execute:hover:not(:disabled) {
  background: #0052a3;
}
.btn-reset {
  background: #fff;
  color: #333;
}
.btn-reset:hover {
  background: #f3f4f6;
}
.executor-response {
  margin: 0;
  padding: 8px 12px;
  font-family: monospace;
  font-size: 12px;
  border-radius: 4px;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
.response-success {
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  color: #166534;
}
.response-error {
  background: #fef2f2;
  border: 1px solid #fecaca;
  color: #dc2626;
}
.response-example {
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  color: #6b7280;
}
</style>
