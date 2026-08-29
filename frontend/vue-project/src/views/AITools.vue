<template>
  <div class="ai-tools-container">
    <div class="chat-wrapper">
      <!-- 左侧：历史会话列表 -->
      <ChatSidebar />

      <!-- 右侧：聊天区域 -->
      <ChatArea />
    </div>
  </div>
</template>

<script setup lang="ts">
import ChatSidebar from '@/modules/ai-system/components/ChatSidebar.vue';
import ChatArea from '@/modules/ai-system/components/ChatArea.vue';
</script>

<style scoped>
/* ===== 全局重置：铺满全屏 ===== */
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html,
body {
  width: 100%;
  height: 100%;
  overflow: hidden;
}

.ai-tools-container {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  width: 100vw;
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fafcff;
  border-radius: 0;
  padding: 0;
  margin: 0;
}

.chat-wrapper {
  display: flex;
  flex: 1;
  overflow: hidden;
  background: white;
  border-radius: 0;
  border: none;
  width: 100%;
  height: 100%;
}
</style>