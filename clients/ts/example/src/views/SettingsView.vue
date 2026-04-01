<script setup lang="ts">
import { reactive } from 'vue';
import { useHalClient, hasBuiltinKey, type AppSettings } from '../composables/useHalClient';

const { settings, isConnected, error, saveSettings, connect } = useHalClient();

const form = reactive<AppSettings>({ ...settings });

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
      <span>Authentication</span>
      <select v-model="form.authMode">
        <option v-if="hasBuiltinKey" value="builtin">Built-in Service Key</option>
        <option value="service-key">Custom Service Key (JWT)</option>
        <option value="device-secret">Device Secret (HMAC)</option>
        <option value="none">Permission Dialog</option>
      </select>
    </label>

    <label v-if="form.authMode !== 'builtin'" class="field">
      <span>Client ID</span>
      <input v-model="form.clientId" type="text" placeholder="hal-example" />
    </label>

    <label v-if="form.authMode === 'service-key'" class="field">
      <span>Service Key</span>
      <input v-model="form.serviceKey" type="text" placeholder="Paste JWT token" />
    </label>

    <label v-if="form.authMode === 'device-secret'" class="field">
      <span>Device Secret</span>
      <input v-model="form.deviceSecret" type="text" placeholder="Base64URL-encoded HMAC key" />
      <span class="hint">Used to generate a device key JWT (HS256) on each connection.</span>
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
