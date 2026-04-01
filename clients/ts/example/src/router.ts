import { createRouter, createWebHashHistory } from 'vue-router';
import DashboardView from './views/DashboardView.vue';
import DescribeView from './views/DescribeView.vue';
import PluginDetailView from './views/PluginDetailView.vue';
import StatusLightView from './views/StatusLightView.vue';
import ScreensNfcView from './views/ScreensNfcView.vue';
import SettingsView from './views/SettingsView.vue';

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', name: 'dashboard', component: DashboardView },
    { path: '/describe', name: 'describe', component: DescribeView },
    { path: '/describe/:pluginId', name: 'describe-detail', component: PluginDetailView },
    { path: '/statuslight', name: 'statuslight', component: StatusLightView },
    { path: '/screens', name: 'screens', component: ScreensNfcView },
    { path: '/settings', name: 'settings', component: SettingsView },
  ],
});
