<!-- components/ChatTopBar.vue -->
<template>
  <div class="chat-topbar">
    <div class="topbar-left">
      <h3>{{ title }}</h3>
      <span class="chat-status" :class="statusClass">
        <span class="status-dot"></span>
        {{ connectionStatus }}
      </span>
    </div>
  </div>
</template>

<script setup>
import { computed, toRefs } from 'vue';

// Props
const props = defineProps({
  title: {
    type: String,
    default: '新对话'
  },
  connectionStatus: {
    type: String,
    default: '已连接'
  },
});

const {
  title,
  connectionStatus,
} = toRefs(props);


// 计算状态样式
const statusClass = computed(() => {
  return connectionStatus.value === '已连接' ? 'online' : 'offline';
});
</script>

<style scoped>
.chat-topbar {
  padding: 16px 24px;
  border-bottom: 1px solid #edf1f9;
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-shrink: 0;
  background: white;
  min-height: 68px;
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.topbar-left h3 {
  font-size: 16px;
  color: #1a2332;
  margin: 0;
  font-weight: 600;
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-status {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #6b81a0;
  padding: 4px 10px;
  border-radius: 12px;
  background: #f0f4fc;
  transition: all 0.3s;
}

.chat-status.online {
  background: #e8f5e9;
  color: #27ae60;
}

.chat-status.offline {
  background: #fde8e8;
  color: #e74c3c;
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  display: inline-block;
  animation: pulse 2s infinite;
}

.chat-status.online .status-dot {
  background: #27ae60;
}

.chat-status.offline .status-dot {
  background: #e74c3c;
}

@keyframes pulse {
  0% {
    opacity: 1;
    transform: scale(1);
  }

  50% {
    opacity: 0.5;
    transform: scale(0.8);
  }

  100% {
    opacity: 1;
    transform: scale(1);
  }
}

.topbar-right {
  display: flex;
  gap: 4px;
  align-items: center;
}

.topbar-btn {
  background: none;
  border: none;
  padding: 8px 12px;
  border-radius: 8px;
  cursor: pointer;
  color: #6b81a0;
  font-size: 16px;
  transition: all 0.2s;
  position: relative;
  display: flex;
  align-items: center;
  gap: 4px;
}

.topbar-btn:hover {
  background: #f0f4fc;
  color: #1a2332;
}

.topbar-btn.active {
  background: #e8eeff;
  color: #4f7cff;
}

.topbar-btn .badge {
  font-size: 9px;
  background: #4f7cff;
  color: white;
  padding: 1px 6px;
  border-radius: 10px;
  font-weight: 600;
  line-height: 1.4;
}

/* 按钮提示文字 */
.topbar-btn[title] {
  position: relative;
}

/* 当消息数量为0时，清空按钮不可用 */
.topbar-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
</style>