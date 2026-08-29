<!-- src/views/Disk.vue -->
<template>
  <div class="disk-container">
    <Sidebar />
    <main class="main-content">
      <router-view />
      <LoginModal v-model:visible="userStore.showLoginModal" />
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import LoginModal from '@/components/Disk/LoginModal.vue';
import { useUserStore } from '@/stores/userstore';
import Sidebar from '@/components/Disk/Sidebar.vue';

const userStore = useUserStore();
</script>

<style scoped>
.disk-container {
  width: 1360px;
  max-width: 98vw;
  height: 920px;
  max-height: 98vh;
  background: white;
  border-radius: 28px;
  box-shadow: 0 20px 60px rgba(0, 20, 40, 0.12);
  display: flex;
  overflow: hidden;
  margin: 0 auto;
}

.main-content {
  flex: 1;
  background: #fafcff;
  display: flex;
  flex-direction: column;
  padding: 24px 28px 28px 28px;
  overflow: hidden;
}
</style>