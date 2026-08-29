<!-- BasePage.vue（作为模板，实际使用时复制重命名） -->
<template>
  <div class="page-container">
    <!-- 顶部栏：与内容区通过同一个 context 共享状态 -->
    <TopBarComponent />
    
    <!-- 主内容区 -->
    <ContentComponent />
  </div>
</template>

<script setup lang="ts">
import { provide, onBeforeUnmount } from 'vue';
import { useRoute } from 'vue-router';
import TopBarComponent from './components/TopBar.vue';
import ContentComponent from './components/Content.vue';

// ---------- 1. 调用该页面专属的组合函数 ----------
// 这里的 usePageLogic 需要替换为实际的组合函数
// 例如：useFileSystem、useShareDetail、useInbox 等
const pageContext = usePageLogic();

// ---------- 2. 通过 provide 下发给子组件 ----------
// 子组件通过 inject('pageContext') 获取
provide('pageContext', pageContext);

// ---------- 3. 可选：页面级别的初始化 ----------
const route = useRoute();
// 例如从路由参数中获取分享码、文件ID等
// const shareCode = route.params.shareCode;

// ---------- 4. 清理逻辑（可选） ----------
onBeforeUnmount(() => {
  // 页面离开时清空选中状态等
  pageContext.clearSelection?.();
});
</script>

<style scoped>
.page-container {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 20px;
}
</style>