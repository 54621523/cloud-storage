<template>
  <div class="home-top-bar">

    <!-- 左侧区域：上传按钮 + 胶囊工具栏 -->
    <div class="left-actions">
      <FileUploader :current-parent-id="parentId" />

      <div class="capsule-toolbar">
        <div v-for="item in menuItems" :key="item.id" class="toolbar-item action-btn"
          @click="handleAction(item.action)">
          <font-awesome-icon :icon="item.icon" />
          <span>{{ item.label }}</span>
        </div>
      </div>
    </div>

    <!-- 右侧区域：搜索栏 -->
    <div class="right-search">
      <input type="text" class="search-input" placeholder="搜索文件或文件夹..." v-model="searchKeyword" />
      <!-- 可选：清空按钮 -->
      <span v-if="searchKeyword" class="clear-btn" @click="searchKeyword = ''">✕</span>
    </div>

  </div>
</template>

<script setup lang="ts">
import { ref, computed, inject, watch, onUnmounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { debounce } from 'lodash-es'; // 或自己实现防抖
import FileUploader from '@/modules/file-system/components/FileUploader.vue';
import { FILE_EXPLORER_KEY } from '@/constants/symbol';
import type { FileItemUI } from '@/modules/file-system/types/file';

const fileContext = inject(FILE_EXPLORER_KEY);
if (!fileContext) throw new Error('fileExplorer not provided');

const {
  selectedCount,
  selectedList,
  createFolder,
  deleteFiles,
  downloadFilesV2,
  parentId,
  search,
  clearSearch
} = fileContext;

const shareControl = inject<{ openShareDialog: (files?: FileItemUI[]) => void }>('shareControl');

// ---------- 搜索相关 ----------
const searchKeyword = ref('');

// 防抖搜索函数（用户停止输入 500ms 后执行）
const debouncedSearch = debounce((keyword: string) => {
  if (keyword.trim()) {
    search(keyword.trim());
  } else {
    clearSearch();
  }
}, 500);

// 监听输入变化
watch(searchKeyword, (newVal) => {
  debouncedSearch(newVal);
});

// 组件卸载时取消防抖，避免内存泄漏
onUnmounted(() => {
  debouncedSearch.cancel();
});

// ---------- 动态菜单 ----------
const menuItems = computed(() => {
  const items = [
    { id: 1, label: '新建文件夹', icon: 'folder-plus', action: 'createFolder' },
  ];
  if (selectedCount.value > 0) {
    items.push(
      { id: 2, label: '分享', icon: 'share-nodes', action: 'share' },
      { id: 3, label: '下载', icon: 'download', action: 'download' },
      { id: 4, label: '删除', icon: 'trash', action: 'delete' }
    );
  }
  return items;
});

// ---------- 操作处理 ----------
const handleAction = async (action: string) => {
  switch (action) {
    case 'createFolder':
      await handleCreateFolder();
      break;
    case 'share':
      shareControl?.openShareDialog();
      break;
    case 'download':
      await handleDownload();
      break;
    case 'delete':
      await handleDelete();
      break;
    default:
      console.warn('未知操作:', action);
  }
};

const handleCreateFolder = async () => {
  try {
    const { value: name } = await ElMessageBox.prompt('请输入文件夹名称', '新建文件夹', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空',
    });
    if (name) {
      await createFolder(name);
    }
  } catch (err: any) {
    if (err !== 'cancel') {
      ElMessage.error('创建失败：' + (err.message || '未知错误'));
    }
  }
};

const handleDownload = async () => {
  if (selectedList.value.length === 0) return;
  try {
    const ids = selectedList.value.map(item => item.id!);
    await downloadFilesV2(ids);
  } catch (err: any) {
    ElMessage.error('下载失败：' + (err.message || '未知错误'));
  }
};

const handleDelete = async () => {
  if (selectedList.value.length === 0) return;
  try {
    await ElMessageBox.confirm(
      `确定删除选中的 ${selectedList.value.length} 个项吗？`,
      '删除确认',
      {
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        type: 'warning',
      }
    );
    await deleteFiles(selectedList.value);
  } catch (err: any) {
    if (err !== 'cancel') {
      ElMessage.error('删除失败：' + (err.message || '未知错误'));
    }
  }
};
</script>

<style scoped>
@import '@/styles/buttons.css';

/* 1. 整体布局：两端对齐 */
.home-top-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
}

.left-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
  min-width: 0;
  width: 100%;
}

.capsule-toolbar {
  display: inline-flex;
  background-color: #eef2f6;
  border-radius: 24px;
  padding: 4px;
}

.capsule-toolbar .action-btn {
  border-radius: 0;
}

.capsule-toolbar .action-btn:first-child {
  border-radius: 20px 0 0 20px;
}

.capsule-toolbar .action-btn:last-child {
  border-radius: 0 20px 20px 0;
}

.capsule-toolbar .action-btn:only-child {
  border-radius: 20px;
}

.right-search {
  flex-shrink: 0;
  margin-left: 16px;
  position: relative;
}

.search-input {
  padding: 8px 32px 8px 16px;
  /* 右侧留出清空按钮空间 */
  border-radius: 20px;
  border: 1px solid #dce4ec;
  background-color: #f8fafc;
  font-size: 14px;
  outline: none;
  transition: all 0.2s;
  width: 240px;
}

.search-input:focus {
  border-color: #4f7cff;
  background-color: #fff;
  box-shadow: 0 0 0 3px rgba(79, 124, 255, 0.1);
}

.clear-btn {
  position: absolute;
  right: 10px;
  top: 50%;
  transform: translateY(-50%);
  cursor: pointer;
  color: #999;
  font-size: 14px;
  padding: 2px 6px;
  border-radius: 50%;
  transition: background 0.2s;
}

.clear-btn:hover {
  background: #e0e4ea;
  color: #333;
}
</style>