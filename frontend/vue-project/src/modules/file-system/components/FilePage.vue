<!-- HomePage.vue -->
<template>
  <el-config-provider :locale="zhCn">
    <div class="home-page">
      <HomeTopBar />
      <FilePanel />
    </div>
    <ShareDialog v-model:visible="shareVisible" :files="shareFiles" />
  </el-config-provider>
</template>

<script setup lang="ts">
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import { provide, onBeforeUnmount, ref } from 'vue';
import { useFileExplorer } from '@/modules/file-system/composables/useFileExplorer';
import HomeTopBar from '@/modules/file-system/components/HomeTopBar.vue';
import FilePanel from '@/modules/file-system/components/FilePanel.vue';
import { FILE_EXPLORER_KEY } from '@/constants/symbol';
import type { FileItemUI } from '@/modules/file-system/types/file';
import ShareDialog from '@/modules/file-system/components/ShareDialog.vue';
import { ElMessage } from 'element-plus';


const rootId = Number(localStorage.getItem('file_root_id')) || 0;
const rootName = localStorage.getItem('file_root_name') || '我的文件';
const fileContext = useFileExplorer(rootId, rootName);
provide(FILE_EXPLORER_KEY, fileContext);


const shareVisible = ref(false);
const shareFiles = ref<FileItemUI[]>([]);


const openShareDialog = (files?: FileItemUI[]) => {
  // 如果没传文件列表，默认用当前所有选中的文件
  const target = files ?? fileContext.selectedList.value;
  if (target.length === 0) {
    ElMessage.warning('请先选择要分享的文件');
    return;
  }
  shareFiles.value = target;
  shareVisible.value = true;
};

provide('shareControl', {
  openShareDialog,
});

// 页面离开时清空选中
onBeforeUnmount(() => {
  fileContext.clearSelection();
});
</script>

<style scoped>
.home-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 20px;
}
</style>