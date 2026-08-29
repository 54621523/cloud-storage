<!-- src/components/Disk/MainArea.vue -->
<template>
  <div class="main-area">
    <keep-alive>
      <component :is="currentPageComponent" v-bind="pageProps" />
    </keep-alive>
  </div>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, type Component } from 'vue';

// ---------- Props ----------
const props = defineProps<{
  currentPage: 'home' | 'share' | 'inbox';
}>();


// ---------- 页面组件映射表（策略模式） ----------
const pageComponentMap: Record<string, Component> = {
  home: defineAsyncComponent(() => import('@/modules/file-system/components/FilePanel.vue')),
  share: defineAsyncComponent(() => import('@/modules/share-system/components/ShareManager.vue')),
  inbox: defineAsyncComponent(() => import('@/modules/inbox-system/InboxManager.vue')),
};

// 计算当前应该渲染哪个组件
const currentPageComponent = computed(() => {
  return pageComponentMap[props.currentPage] || null;
});

// ---------- 为特定页面传递 Props（可选） ----------
const pageProps = computed(() => {
  // 不同页面可能需要不同的 Props
  const propsMap: Record<string, any> = {
    home: {},
    share: {},
    inbox: {},
    ai: {},
  };
  return propsMap[props.currentPage] || {};
});
</script>

<style scoped>
.main-area {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
</style>