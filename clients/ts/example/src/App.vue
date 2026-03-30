<script setup lang="ts">
import NavBar from './components/NavBar.vue';
import ToastContainer from './components/ToastContainer.vue';
</script>

<template>
  <NavBar />
  <main class="container">
    <router-view />
  </main>
  <ToastContainer />
</template>

<style scoped>
.container {
  max-width: 960px;
  margin: 0 auto;
  padding: 24px 16px;
}
</style>
