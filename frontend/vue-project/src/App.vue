<!-- src/App.vue -->
<template>
  <div id="app">
    <router-view />
  </div>
</template>

<script setup>
import { RouterView } from 'vue-router';
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
  font-family: 'Segoe UI', Roboto, system-ui, -apple-system, sans-serif;
}

html, body, #app {
  height: 100%;
}

#app {
  display: flex;
  justify-content: center;
  align-items: center;
  background: #f0f4fc;
}
</style>