import { createRouter, createWebHashHistory } from 'vue-router';
import DashboardView from './views/DashboardView.vue';
import DescribeView from './views/DescribeView.vue';
import StatusLightView from './views/StatusLightView.vue';
import SettingsView from './views/SettingsView.vue';

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', name: 'dashboard', component: DashboardView },
    { path: '/describe', name: 'describe', component: DescribeView },
    { path: '/statuslight', name: 'statuslight', component: StatusLightView },
    { path: '/settings', name: 'settings', component: SettingsView },
  ],
});
