<!-- src/components/AITools/MessageItem.vue -->
<template>
  <div :class="['message', msg.role === 'user' ? 'user-message' : 'bot-message']">

    <!-- 头像区域 -->
    <div class="message-avatar">
      <font-awesome-icon :icon="msg.role === 'user' ? ['fas', 'user'] : ['fas', 'robot']" />
    </div>

    <!-- 消息主体区域 -->
    <div class="message-body">
      <!-- 渲染消息文本内容 -->
      <MessageContent :text="msg.content" :is-user="msg.role === 'user'" :msg-index="msgIndex"
        @cite-click="onCiteClick" />

      <!-- 预留：RAG 引用文档区域 (未来可在此引入 References 组件) -->
      <!-- <References 
        v-if="msg.sources && msg.sources.length > 0 && msg.role !== 'user'"
        ref="referencesRef"
        :chunks="msg.sources" 
        :msg-index="msgIndex" 
        @cite-click="(chunkIdx) => onCiteClick(msgIndex, chunkIdx)"
      /> -->

      <!-- 消息时间戳 -->
      <div class="message-time">{{ msg.time }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import MessageContent from '@/modules/ai-system/components/MessageContent.vue';
import References from '@/modules/ai-system/components/References.vue';

const props = defineProps<{
  msg: any; // 建议后续替换为具体的 Message 接口类型
  msgIndex: number;
}>();

const emit = defineEmits<{
  (e: 'cite-click', msgIndex: number, chunkIndex: number): void;
}>();

// 预留引用组件的 ref，供父组件调用
const referencesRef = ref<any>(null);

// 暴露给父组件的方法：用于点击引用时自动展开面板
const openReferences = () => {
  referencesRef.value?.openDetails();
};

defineExpose({
  openReferences
});

// 接收来自 References 的 chunkIndex，拼上 msgIndex 后透传给父组件
const onCiteClick = (msgIndex: number, chunkIndex: number) => {
  emit('cite-click', msgIndex, chunkIndex);
};
</script>

<style scoped>
.message {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
  animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(10px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* 用户消息靠右 */
.user-message {
  flex-direction: row-reverse;
}

.user-message .message-body {
  text-align: right;
}

.message-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 16px;
}

.bot-message .message-avatar {
  background: #e8eeff;
  color: #4f7cff;
}

.user-message .message-avatar {
  background: #1a2332;
  color: white;
}

.message-body {
  max-width: 80%;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.message-time {
  font-size: 11px;
  color: #9aabbf;
  margin-top: 4px;
}
</style>