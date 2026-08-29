<!-- src/components/AITools/MessageContent.vue -->
<template>
  <div 
    class="message-content" 
    v-html="parsedHtml" 
    @click="onContentClick"
  ></div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { parseMarkdown, escapeHtml } from '@/utils/markdown';

const props = defineProps<{
  text: string;
  isUser: boolean;
  msgIndex?: number | null;
}>();

const emit = defineEmits<{
  (e: 'cite-click', msgIndex: number, chunkIndex: number): void;
}>();

// 核心：格式化消息内容
const parsedHtml = computed(() => {
  if (!props.text) return '';
  if (props.isUser) {
    // 用户消息进行 HTML 转义，防止 XSS 攻击
    return escapeHtml(props.text);
  }
  // AI 消息解析 Markdown 并注入引用点击属性
  return parseMarkdown(props.text, props.msgIndex);
});

// 事件委托：处理 v-html 内部元素的点击
const onContentClick = (e: MouseEvent) => {
  const citeRef = (e.target as HTMLElement).closest('.cite-ref');
  if (!citeRef) return;
  
  const msgIndexStr = citeRef.getAttribute('data-msg-index');
  const chunkIndexStr = citeRef.getAttribute('data-chunk-index');
  
  if (msgIndexStr !== null && chunkIndexStr !== null) {
    emit('cite-click', Number(msgIndexStr), Number(chunkIndexStr));
  }
};
</script>

<style scoped>
.message-content {
  font-size: 14px;
  line-height: 1.6;
  word-wrap: break-word;
}
/* 可以在这里补充 Markdown 渲染后的样式，如代码块、列表等 */
.message-content :deep(pre) {
  background: #1a2332;
  color: #e8eeff;
  padding: 12px;
  border-radius: 8px;
  overflow-x: auto;
  margin: 8px 0;
}
.message-content :deep(code) {
  font-family: 'Courier New', monospace;
}
</style>