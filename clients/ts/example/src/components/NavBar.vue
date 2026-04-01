<script setup lang="ts">
import { ref } from 'vue';
import { useHalClient } from '../composables/useHalClient';

const { isConnected, error, connect, disconnect } = useHalClient();
const menuOpen = ref(false);
</script>

<template>
  <nav class="navbar">
    <div class="navbar-inner">
      <div class="navbar-brand">
        <span class="navbar-title">HAL Client Demo</span>
        <button class="menu-toggle" @click="menuOpen = !menuOpen" aria-label="Toggle menu">
          <span class="hamburger" :class="{ open: menuOpen }" />
        </button>
      </div>
      <div class="navbar-collapse" :class="{ open: menuOpen }">
        <div class="nav-links">
          <router-link to="/" class="nav-link" @click="menuOpen = false">Dashboard</router-link>
          <router-link to="/describe" class="nav-link" @click="menuOpen = false">API Explorer</router-link>
          <router-link to="/statuslight" class="nav-link" @click="menuOpen = false">Status Light</router-link>
          <router-link to="/screens" class="nav-link" @click="menuOpen = false">Screens & NFC</router-link>
          <router-link to="/settings" class="nav-link" @click="menuOpen = false">Settings</router-link>
        </div>
        <div class="navbar-right">
          <span class="status">
            <span class="status-dot" :class="isConnected ? 'connected' : 'disconnected'" />
            {{ isConnected ? 'Connected' : 'Disconnected' }}
          </span>
          <button v-if="isConnected" class="btn btn-sm" @click="disconnect">Disconnect</button>
          <button v-else class="btn btn-sm btn-primary" @click="connect">Connect</button>
        </div>
      </div>
    </div>
    <div v-if="error" class="navbar-error">{{ error }}</div>
  </nav>
</template>

<style scoped>
.navbar {
  background: #fff;
  border-bottom: 1px solid #ddd;
  position: sticky;
  top: 0;
  z-index: 10;
}
.navbar-inner {
  max-width: 960px;
  margin: 0 auto;
  padding: 12px 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
}
.navbar-brand {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: auto;
}
.navbar-title {
  font-weight: 700;
  font-size: 16px;
  white-space: nowrap;
}
.menu-toggle {
  display: none;
  background: none;
  border: 1px solid #ccc;
  border-radius: 4px;
  width: 36px;
  height: 36px;
  cursor: pointer;
  margin-left: 12px;
  position: relative;
}
.hamburger {
  display: block;
  width: 18px;
  height: 2px;
  background: #333;
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  transition: background 0.2s;
}
.hamburger::before,
.hamburger::after {
  content: '';
  display: block;
  width: 18px;
  height: 2px;
  background: #333;
  position: absolute;
  left: 0;
  transition: transform 0.2s, top 0.2s, bottom 0.2s;
}
.hamburger::before { top: -6px; }
.hamburger::after { top: 6px; }
.hamburger.open { background: transparent; }
.hamburger.open::before { top: 0; transform: rotate(45deg); }
.hamburger.open::after { top: 0; transform: rotate(-45deg); }
.navbar-collapse {
  display: flex;
  align-items: center;
  gap: 16px;
  flex: 1;
  justify-content: space-between;
}
.nav-links {
  display: flex;
  align-items: center;
  gap: 16px;
}
.navbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.nav-link {
  color: #0066cc;
  text-decoration: none;
  font-size: 14px;
  white-space: nowrap;
}
.nav-link:hover { text-decoration: underline; }
.nav-link.router-link-active { font-weight: 600; }
.status {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #666;
  white-space: nowrap;
}
.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
  flex-shrink: 0;
}
.status-dot.connected { background: #22c55e; }
.status-dot.disconnected { background: #ef4444; }
.btn {
  padding: 6px 14px;
  border: 1px solid #ccc;
  border-radius: 6px;
  background: #fff;
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
}
.btn:hover { background: #f5f5f5; }
.btn-primary {
  background: #0066cc;
  color: #fff;
  border-color: #0066cc;
}
.btn-primary:hover { background: #0055aa; }
.btn-sm { padding: 4px 10px; }
.navbar-error {
  max-width: 960px;
  margin: 0 auto;
  padding: 6px 16px 10px;
  color: #dc2626;
  font-size: 13px;
}

@media (max-width: 640px) {
  .navbar-brand {
    width: 100%;
  }
  .menu-toggle {
    display: block;
  }
  .navbar-collapse {
    display: none;
    flex-direction: column;
    align-items: stretch;
    width: 100%;
    gap: 12px;
    padding-top: 8px;
  }
  .navbar-collapse.open {
    display: flex;
  }
  .nav-links {
    flex-direction: column;
    align-items: stretch;
    gap: 0;
  }
  .nav-link {
    padding: 8px 0;
    border-top: 1px solid #eee;
  }
  .navbar-right {
    padding-top: 8px;
    border-top: 1px solid #eee;
    justify-content: space-between;
  }
}
</style>
