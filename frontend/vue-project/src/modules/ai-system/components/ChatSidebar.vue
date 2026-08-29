<template>
  <div class="chat-sidebar">
    <div class="sidebar-header">
      <button class="new-chat-btn" @click="handleNewChat">
        <font-awesome-icon :icon="['fas', 'plus']" />
        新建对话
      </button>
    </div>

    <el-scrollbar ref="scrollbarRef" class="chat-history" :always="true" @end-reached="onEndReached">
      <div v-for="chat in sessions" :key="chat.sessionId" class="chat-item"
        :class="{ active: chat.sessionId === currentSessionId }" @click="handleSwitchChat(chat.sessionId!)">
        <font-awesome-icon :icon="['fas', 'comment']" class="chat-icon" />
        <div class="chat-info">
          <div class="chat-title">{{ chat.title || '新对话' }}</div>
        </div>
        <button class="delete-chat-btn" @click.stop="handleDeleteChat(chat.sessionId!)">
          <font-awesome-icon :icon="['fas', 'times']" />
        </button>
      </div>
    </el-scrollbar>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useChatSessionStore } from '@/modules/ai-system/stores/chatSessionStore';

const sessionStore = useChatSessionStore();

const sessions = computed(() => sessionStore.sessions);
const currentSessionId = computed(() => sessionStore.currentSessionId);
const hasMore = computed(() => sessionStore.hasNext);
const loading = ref(false);

const onEndReached = async (direction: string) => {
  if (direction === 'bottom') {
    await loadMore();
  }
};

const loadMore = async () => {
  if (loading.value || !hasMore.value) return;
  loading.value = true;
  try {
    await sessionStore.loadMoreSessions();
  } catch (error) {
    ElMessage.error('加载会话列表失败');
    console.error(error);
  } finally {
    loading.value = false;
  }
};

const handleNewChat = () => {
  sessionStore.newSession();
};

const handleSwitchChat = (sessionId: string) => {
  if (sessionId === currentSessionId.value) return;
  sessionStore.switchSession(sessionId);
};

const handleDeleteChat = async (sessionId: string) => {
  try {
    await ElMessageBox.confirm('确定要删除这个对话吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    });
    await sessionStore.deleteSession(sessionId);
    ElMessage.success('已删除');
  } catch {
    // 用户取消
  }
};

onMounted(() => {
  if (sessions.value.length === 0) {
    loadMore();
  }
});
</script>

<style scoped>
.chat-sidebar {
  width: 260px;
  background: #f8faff;
  border-right: 1px solid #edf1f9;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  height: 100%;
}

.sidebar-header {
  padding: 16px;
  border-bottom: 1px solid #edf1f9;
  flex-shrink: 0;
}

.new-chat-btn {
  width: 100%;
  padding: 10px 16px;
  background: #4f7cff;
  color: white;
  border: none;
  border-radius: 10px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 500;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  transition: all 0.2s;
}

.new-chat-btn:hover {
  background: #3a66e0;
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(79, 124, 255, 0.3);
}

.chat-history {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.chat-history::-webkit-scrollbar {
  width: 4px;
}

.chat-history::-webkit-scrollbar-track {
  background: transparent;
}

.chat-history::-webkit-scrollbar-thumb {
  background: #cdd9ec;
  border-radius: 10px;
}

.chat-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.2s;
  position: relative;
  margin-bottom: 2px;
}

.chat-item:hover {
  background: #edf4ff;
}

.chat-item.active {
  background: #e8eeff;
  border: 1px solid #cdd9ec;
}

.chat-item .chat-icon {
  color: #6b81a0;
  font-size: 14px;
  flex-shrink: 0;
}

.chat-item .chat-info {
  flex: 1;
  min-width: 0;
}

.chat-item .chat-title {
  font-size: 14px;
  color: #1a2332;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.chat-item .delete-chat-btn {
  background: none;
  border: none;
  color: #b2c3da;
  cursor: pointer;
  padding: 4px 6px;
  border-radius: 4px;
  opacity: 0;
  transition: all 0.2s;
}

.chat-item:hover .delete-chat-btn {
  opacity: 1;
}

.chat-item .delete-chat-btn:hover {
  background: #fee2e2;
  color: #e74c3c;
}

/* 响应式折叠 */
@media (max-width: 768px) {
  .chat-sidebar {
    width: 60px;
  }

  .chat-sidebar .chat-info,
  .chat-sidebar .delete-chat-btn {
    display: none;
  }

  .chat-item {
    justify-content: center;
    padding: 12px;
  }

  .new-chat-btn span {
    display: none;
  }
}
</style>