<script setup lang="ts">
import { reactive } from 'vue';
import { useHalClient, type AppSettings } from '../composables/useHalClient';

const { settings, isConnected, error, saveSettings, connect } = useHalClient();

const form = reactive<AppSettings>({ ...settings });

const defaultServiceKey = import.meta.env.VITE_SERVICE_KEY ?? '';

function resetServiceKey() {
  form.serviceKey = defaultServiceKey;
  form.deviceSecret = '';
}

async function onSubmit() {
  saveSettings({ ...form });
  await connect();
}
</script>

<template>
  <h2>Settings</h2>

  <form class="form" @submit.prevent="onSubmit">
    <label class="field">
      <span>Base URL</span>
      <input v-model="form.baseUrl" type="text" placeholder="http://localhost:8400" />
    </label>

    <label class="field">
      <span>Client ID</span>
      <input v-model="form.clientId" type="text" placeholder="hal-example" />
    </label>

    <label class="field">
      <span>Service Key</span>
      <div class="input-with-action">
        <input v-model="form.serviceKey" type="text" placeholder="Optional JWT" :disabled="!!form.deviceSecret" />
        <button
          v-if="defaultServiceKey && form.serviceKey !== defaultServiceKey"
          type="button"
          class="btn btn-reset"
          :disabled="!!form.deviceSecret"
          @click="resetServiceKey"
        >Reset</button>
      </div>
    </label>

    <label class="field">
      <span>Device Secret</span>
      <input v-model="form.deviceSecret" type="text" placeholder="Base64URL-encoded HMAC key" :disabled="!!form.serviceKey" />
      <span class="hint">Used to generate a device key JWT (HS256) when no service key is set.</span>
    </label>

    <label class="field">
      <span>Transport</span>
      <select v-model="form.transport">
        <option value="http">HTTP</option>
        <option value="ws">WebSocket</option>
      </select>
    </label>

    <button type="submit" class="btn btn-primary">Save &amp; Connect</button>
  </form>

  <div v-if="error" class="error">{{ error }}</div>

  <div class="status-info">
    <strong>Status:</strong> {{ isConnected ? 'Connected' : 'Disconnected' }}
    <template v-if="isConnected"> via {{ settings.transport.toUpperCase() }} to {{ settings.baseUrl }}</template>
  </div>
</template>

<style scoped>
h2 { margin-top: 0; }
.form {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 480px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.field span {
  font-size: 13px;
  font-weight: 600;
  color: #444;
}
.field .hint {
  font-size: 11px;
  font-weight: 400;
  color: #888;
}
.input-with-action {
  display: flex;
  gap: 8px;
}
.input-with-action input {
  flex: 1;
  min-width: 0;
}
.btn-reset {
  padding: 8px 12px;
  border: 1px solid #ccc;
  border-radius: 6px;
  background: #f5f5f5;
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
}
.btn-reset:hover { background: #e5e5e5; }
.field input:disabled {
  background: #f5f5f5;
  color: #999;
  cursor: not-allowed;
}
.field input,
.field select {
  padding: 8px 10px;
  border: 1px solid #ccc;
  border-radius: 6px;
  font-size: 14px;
}
.btn {
  padding: 8px 20px;
  border: none;
  border-radius: 6px;
  font-size: 14px;
  cursor: pointer;
  align-self: flex-start;
}
.btn-primary {
  background: #0066cc;
  color: #fff;
}
.btn-primary:hover { background: #0055aa; }
.error {
  margin-top: 12px;
  padding: 10px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 6px;
  color: #dc2626;
  font-size: 13px;
}
.status-info {
  margin-top: 16px;
  font-size: 14px;
  color: #555;
}
</style>
