<template>
  <!-- 只有当后端真的传了 chunks 且数组不为空时才渲染 -->
  <div 
    v-if="chunks?.length" 
    class="references-section"
  >
    <details class="references-details" ref="detailsRef">
      <summary class="references-title">
        <i class="fas fa-book-open"></i> 
        参考来源 ({{ chunks.length }})
      </summary>
      
      <ul class="sources-list">
        <li
          v-for="(chunk, idx) in chunks"
          :key="idx"
          class="source-item"
          :id="`ref-${msgIndex}-${idx + 1}`"
        >
          <!-- 1. 标题行：序号 + 文件名 + 页码 -->
          <div class="source-header">
            <span 
              class="ref-badge"
              @click.stop="onCiteClick(idx + 1)"
              title="点击在正文中定位"
            >
              [{{ idx + 1 }}]
            </span>
            <span class="source-filename">{{ chunk.filename || '未知文档' }}</span>
            <span v-if="chunk.page_number" class="source-page">
              · 第 {{ chunk.page_number }} 页
            </span>
          </div>

          <!-- 2. 内容摘要：后端传的 text 字段 -->
          <div v-if="chunk.text" class="source-excerpt">
            {{ chunk.text }}
          </div>
        </li>
      </ul>
    </details>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';

defineProps<{
  chunks: Array<{
    filename?: string;
    page_number?: number | string;
    text?: string;
  }>;
  msgIndex: number;
}>();

const emit = defineEmits<{
  (e: 'cite-click', chunkIndex: number): void;
}>();

const detailsRef = ref<HTMLDetailsElement | null>(null);

// 暴露给父组件：当用户点击正文里的 [1] 时，父组件调用此方法自动展开
const openDetails = () => {
  if (detailsRef.value && !detailsRef.value.open) {
    detailsRef.value.open = true;
  }
};

defineExpose({ openDetails });

const onCiteClick = (chunkIndex: number) => {
  emit('cite-click', chunkIndex);
};
</script>

<style scoped>
.references-section {
  margin-top: 16px;
  font-size: 14px;
}

.references-details {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
}

.references-title {
  cursor: pointer;
  padding: 10px 14px;
  background: #f9fafb;
  font-weight: 600;
  color: #374151;
  user-select: none;
  display: flex;
  align-items: center;
  gap: 8px;
  transition: background 0.2s;
}

.references-title:hover {
  background: #f3f4f6;
}

.sources-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.source-item {
  padding: 12px 14px;
  border-top: 1px solid #f3f4f6;
  transition: background 0.15s;
}

.source-item:hover {
  background: #fafafa;
}

.source-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}

.ref-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 24px;
  height: 20px;
  padding: 0 6px;
  background: #eff6ff;
  color: #2563eb;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.15s;
}

.ref-badge:hover {
  background: #2563eb;
  color: #fff;
}

.source-filename {
  font-weight: 600;
  color: #1f2937;
  word-break: break-all;
}

.source-page {
  color: #6b7280;
  font-size: 12px;
  white-space: nowrap;
}

.source-excerpt {
  color: #4b5563;
  line-height: 1.6;
  font-size: 13px;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  overflow: hidden;
  margin-left: 32px; /* 与标题文字对齐，避开 badge */
}
</style>