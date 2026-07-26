<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
import { useRoute } from 'vue-router';
import { useHalClient } from '../composables/useHalClient';

const route = useRoute();
const { isConnected, error, connect, disconnect } = useHalClient();
const menuOpen = ref(false);
const demosOpen = ref(false);
const isDescribeActive = computed(() => route.path.startsWith('/describe'));
const DEMO_PATHS = ['/statuslight', '/printer', '/barcode-scanner', '/screens'];
const isDemosActive = computed(() => DEMO_PATHS.includes(route.path));

function closeMenu() {
  menuOpen.value = false;
  demosOpen.value = false;
}

function toggleMenu() {
  menuOpen.value = !menuOpen.value;
  // The hamburger only shows on mobile, where Demos is an inline accordion — expand it by
  // default each time the menu opens. On desktop the menu toggle is hidden, so the floating
  // Demos dropdown stays closed on load.
  if (menuOpen.value) demosOpen.value = true;
}

// Close the (desktop) Demos dropdown when clicking anywhere outside of it.
const dropdownRef = ref<HTMLElement | null>(null);
function onDocumentClick(e: MouseEvent) {
  if (demosOpen.value && dropdownRef.value && !dropdownRef.value.contains(e.target as Node)) {
    demosOpen.value = false;
  }
}
onMounted(() => document.addEventListener('click', onDocumentClick));
onBeforeUnmount(() => document.removeEventListener('click', onDocumentClick));
</script>

<template>
  <nav class="navbar">
    <div class="navbar-inner">
      <div class="navbar-brand">
        <span class="navbar-title">HAL Client Demo</span>
        <div class="navbar-brand-right">
          <div class="navbar-right-mobile">
            <span class="status">
              <span class="status-dot" :class="isConnected ? 'connected' : 'disconnected'" />
              <span class="status-text">{{ isConnected ? 'Connected' : 'Disconnected' }}</span>
            </span>
            <button v-if="isConnected" class="btn btn-sm mobile-disconnect" @click="disconnect">Disconnect</button>
            <button v-else class="btn btn-sm btn-primary connect-btn" @click="connect" aria-label="Connect">
              <svg class="connect-icon" viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M18.36 6.64a9 9 0 1 1-12.73 0" />
                <line x1="12" y1="2" x2="12" y2="12" />
              </svg>
              <span class="connect-label">Connect</span>
            </button>
          </div>
          <button class="menu-toggle" @click="toggleMenu" aria-label="Toggle menu">
            <span class="hamburger" :class="{ open: menuOpen }" />
          </button>
        </div>
      </div>
      <div class="navbar-collapse" :class="{ open: menuOpen }">
        <div class="nav-links">
          <router-link to="/" class="nav-link" @click="closeMenu">Dashboard</router-link>
          <router-link to="/describe" class="nav-link" :class="{ 'router-link-active': isDescribeActive }" @click="closeMenu">API Explorer</router-link>
          <router-link to="/interfaces" class="nav-link" @click="closeMenu">Interfaces</router-link>
          <div ref="dropdownRef" class="nav-dropdown" :class="{ open: demosOpen }">
            <button class="nav-link nav-dropdown-toggle" :class="{ 'router-link-active': isDemosActive }" @click.stop="demosOpen = !demosOpen">
              Demos<span class="caret" />
            </button>
            <div class="nav-dropdown-menu">
              <router-link to="/statuslight" class="nav-link dropdown-link" @click="closeMenu">Status Light</router-link>
              <router-link to="/printer" class="nav-link dropdown-link" @click="closeMenu">Printer</router-link>
              <router-link to="/barcode-scanner" class="nav-link dropdown-link" @click="closeMenu">Barcode Scanner</router-link>
              <router-link to="/screens" class="nav-link dropdown-link" @click="closeMenu">Screens &amp; NFC</router-link>
            </div>
          </div>
          <router-link to="/log" class="nav-link" @click="closeMenu">Activity Log</router-link>
          <router-link to="/settings" class="nav-link" @click="closeMenu">Settings</router-link>
        </div>
        <div class="collapse-connection">
          <span class="status">
            <span class="status-dot" :class="isConnected ? 'connected' : 'disconnected'" />
            {{ isConnected ? 'Connected' : 'Disconnected' }}
          </span>
          <button v-if="isConnected" class="btn btn-sm" @click="disconnect">Disconnect</button>
          <button v-else class="btn btn-sm btn-primary" @click="connect">Connect</button>
        </div>
        <div class="navbar-right-desktop">
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
.navbar-right-desktop {
  display: flex;
  align-items: center;
  gap: 12px;
}
.navbar-brand-right {
  display: none;
  align-items: center;
  gap: 8px;
}
.navbar-right-mobile {
  display: flex;
  align-items: center;
  gap: 8px;
}
.collapse-connection {
  display: none;
}
.nav-link {
  color: #0066cc;
  text-decoration: none;
  font-size: 14px;
  white-space: nowrap;
}
.nav-link:hover { text-decoration: underline; }
.nav-link.router-link-active { font-weight: 600; }

/* Demos dropdown */
.nav-dropdown {
  position: relative;
  display: flex;
  align-items: center;
}
.nav-dropdown-toggle {
  background: none;
  border: none;
  padding: 0;
  margin: 0;
  font: inherit;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
}
.caret {
  display: inline-block;
  margin-left: 5px;
  border-left: 4px solid transparent;
  border-right: 4px solid transparent;
  border-top: 5px solid currentColor;
  transition: transform 0.15s;
}
.nav-dropdown.open .caret { transform: rotate(180deg); }
.nav-dropdown-menu {
  display: none;
  position: absolute;
  top: 100%;
  left: 0;
  margin-top: 8px;
  flex-direction: column;
  background: #fff;
  border: 1px solid #ddd;
  border-radius: 6px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  padding: 6px 0;
  min-width: 160px;
  z-index: 20;
}
.nav-dropdown.open .nav-dropdown-menu { display: flex; }
.dropdown-link {
  padding: 8px 14px;
}
.dropdown-link:hover {
  background: #f5f5f5;
  text-decoration: none;
}

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
.connect-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.connect-icon {
  display: block;
  flex-shrink: 0;
}
.navbar-error {
  max-width: 960px;
  margin: 0 auto;
  padding: 6px 16px 10px;
  color: #dc2626;
  font-size: 13px;
}

@media (max-width: 960px) {
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
  .navbar-right-desktop {
    display: none;
  }
  .navbar-brand-right {
    display: flex;
  }
  /* Demos dropdown becomes an inline accordion inside the hamburger menu. */
  .nav-dropdown {
    flex-direction: column;
    align-items: stretch;
  }
  .nav-dropdown-toggle {
    justify-content: space-between;
    width: 100%;
    padding: 8px 0;
    border-top: 1px solid #eee;
  }
  .nav-dropdown-menu {
    position: static;
    margin-top: 0;
    border: none;
    border-radius: 0;
    box-shadow: none;
    padding: 0;
    min-width: 0;
  }
  .dropdown-link {
    padding: 8px 0 8px 16px;
    border-top: 1px solid #eee;
  }
  /* Compact top bar on mobile: the Connect button is icon-only (labelled via aria-label). */
  .connect-label {
    display: none;
  }
}

/* Keep the connection controls in the top bar down to mid-size phones. */
@media (max-width: 420px) {
  .mobile-disconnect {
    display: none;
  }
  .collapse-connection {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding-top: 8px;
    border-top: 1px solid #eee;
  }
}

/* Smallest screens: drop the status word too (the dot stays). */
@media (max-width: 360px) {
  .status-text {
    display: none;
  }
}
</style>
