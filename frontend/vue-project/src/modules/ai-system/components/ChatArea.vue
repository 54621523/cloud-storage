<template>
  <div class="chat-main">
    <!-- 消息滚动区域 -->
    <el-scrollbar ref="chatContainerRef" class="messages-container" :always="true" @end-reached="onEndReached">
      <!-- 消息列表 -->
      <MessageItem v-for="(msg, index) in currentMessages" :key="index" :msg="msg" :msg-index="index"
        :ref="(el) => { if (el) messageItemRefs[index] = el as any; }"
        @cite-click="(chunkIdx) => scrollToChunk(index, chunkIdx)" />
    </el-scrollbar>

    <!-- 底部输入区域 -->
    <ChatInput @send="handleSendMessage" :disabled="messageStore.isLoading" />
  </div>
</template>

<script setup lang="ts">
import { ref, watch, nextTick, onBeforeUpdate, onMounted, computed, } from 'vue';
import { ElMessage, ElScrollbar, type ScrollbarDirection } from 'element-plus';
import MessageItem from './MessageItem.vue';
import ChatInput from './ChatInput.vue';
import { useChatSessionStore } from '@/modules/ai-system/stores/chatSessionStore.ts';
import { useChatMessageStore } from '@/modules/ai-system/stores/chatHistoryStore.ts';

// --- Stores ---
const sessionStore = useChatSessionStore();
const messageStore = useChatMessageStore();

// 当前消息列表
const currentMessages = computed(() => messageStore.currentMessages);

// --- 历史消息分页状态 ---
const loadingHistory = ref<boolean>(false);
const hasMoreHistory = ref<boolean>(true);

// --- DOM 引用 ---
const chatContainerRef = ref<InstanceType<typeof ElScrollbar> | null>(null);
type MessageItemInstance = InstanceType<typeof MessageItem> & { openReferences?: () => void };
const messageItemRefs = ref<MessageItemInstance[]>([]);

onBeforeUpdate(() => {
  messageItemRefs.value = [];
});

// --- 滚动到底部 ---
const scrollToBottom = (): void => {
  if (chatContainerRef.value) {
    chatContainerRef.value.setScrollTop(999999);
  }
};

// 新消息到达时滚动到底部
watch(
  () => currentMessages.value.length,
  (newLen, oldLen) => {
    if (newLen > (oldLen || 0)) {
      nextTick(() => scrollToBottom());
    }
  }
);

// 初始化时滚动到底部
onMounted(() => {
  scrollToBottom();
  if (sessionStore.currentSessionId) {
    hasMoreHistory.value = true;
  }
});

// --- 加载更早历史消息 ---
const loadMoreHistory = async (): Promise<void> => {
  if (loadingHistory.value || !hasMoreHistory.value || !sessionStore.currentSessionId) {
    return;
  }
  loadingHistory.value = true;
  try {
    const result = await messageStore.loadMoreHistory();
    hasMoreHistory.value = result?.hasMore ?? false;
  } catch (error) {
    ElMessage.error('加载历史消息失败');
    console.error(error);
  } finally {
    loadingHistory.value = false;
  }
};

// 切换会话时重置分页并加载新会话消息
watch(
  () => sessionStore.currentSessionId,
  async (newId, oldId) => {
    if (!newId || newId === oldId) return;

    // 如果该会话已有缓存消息，直接展示，不加载历史
    const cached = messageStore.messagesMap[newId];
    if (cached && cached.length > 0) {
      // 重置分页状态（可选）
      messageStore.paginationMap[newId] = { cursor: undefined, hasMore: true, loading: false };
      return;
    }

    // 否则加载第一页历史
    await messageStore.fetchSessionHistory(newId);
    nextTick(() => scrollToBottom());
  }
);

// --- 引用跳转 ---
const scrollToChunk = async (msgIndex: number, chunkIndex: number): Promise<void> => {
  const msgItem = messageItemRefs.value[msgIndex];
  if (!msgItem) {
    console.warn(`未找到索引为 ${msgIndex} 的消息组件`);
    return;
  }
  if (typeof msgItem.openReferences === 'function') {
    msgItem.openReferences();
  }
  await nextTick();
  const chunkId = `ref-${msgIndex}-${chunkIndex + 1}`;
  const chunkEl = document.getElementById(chunkId);
  if (chunkEl) {
    chunkEl.scrollIntoView({ behavior: 'smooth', block: 'center' });
    chunkEl.classList.add('highlight-chunk');
    setTimeout(() => {
      chunkEl.classList.remove('highlight-chunk');
    }, 2000);
  } else {
    console.warn(`未找到 ID 为 ${chunkId} 的引用元素`);
  }
};

// --- 发送消息 ---
const handleSendMessage = (content: string): void => {
  if (!content || messageStore.isLoading) return;

  // ① 如果没有会话，先创建临时会话
  if (!sessionStore.currentSessionId) {
    sessionStore.createTemporarySession(); // 此方法会生成 ID 并设为当前
  }

  // ② 添加用户消息（此时 currentSessionId 已存在）
  messageStore.addUserMessage(content);

  // ③ 发起生成回复（generateResponse 不再需要创建会话）
  messageStore.generateResponse(content);
};

// --- 滚动到顶部时加载历史 ---
const onEndReached = async (direction: ScrollbarDirection) => {
  if (direction === 'top') {
    await loadMoreHistory();
  }
};
</script>

<style scoped>
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: white;
  min-width: 0;
  height: 100%;
}

.messages-container {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  background: #fafcff;
  position: relative;
}

.messages-container::-webkit-scrollbar {
  width: 5px;
}

.messages-container::-webkit-scrollbar-track {
  background: #f0f4fc;
}

.messages-container::-webkit-scrollbar-thumb {
  background: #cdd9ec;
  border-radius: 10px;
}
</style>