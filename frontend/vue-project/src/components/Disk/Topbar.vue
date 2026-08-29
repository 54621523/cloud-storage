<template>
  <div class="top-bar">
    
    <!-- 按钮区域：动态渲染对应的子 TopBar -->
    <div class="action-buttons">
      <component :is="currentTopBarComponent" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent } from 'vue';
import type { Component } from 'vue';

// ---------- Props ----------
const props = defineProps<{
  currentPage: 'home' | 'share' | 'inbox';
}>();

// ---------- 子组件映射表（策略模式） ----------
const topBarMap: Record<string, Component> = {
  home: defineAsyncComponent(() => import('@/modules/file-system/components/HomeTopBar.vue')),
  share: defineAsyncComponent(() => import('@/modules/share-system/components/ShareTopBar.vue')),
  inbox: defineAsyncComponent(() => import('@/modules/inbox-system/InboxTopBar.vue')),
};

// 动态计算当前应该渲染哪个子组件
const currentTopBarComponent = computed(() => {
  return topBarMap[props.currentPage] || null;
});
</script>

<style scoped>
/* 协调层的样式只负责布局，不涉及按钮细节 */
.top-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 20px;
  flex-shrink: 0;
}

.page-title {
  font-size: 22px;
  font-weight: 600;
  color: #1a2332;
}

.page-title svg {
  color: #4f7cff;
  margin-right: 10px;
}

.action-buttons {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: auto
}
</style>