  <!-- HomeTopBar.vue -->
  <template>
    <div class="home-top-bar">

      <!-- 左侧区域：上传按钮 + 胶囊工具栏 -->
      <div class="left-actions">
        <FileUploader :current-parent-id="currentParentId" />

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
      </div>

    </div>
  </template>

<script setup lang="ts">
import { ref, computed, inject } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import FileUploader from '@/modules/file-system/components/FileUploader.vue';
import { FILE_EXPLORER_KEY } from '@/symbol';
import type { FileItemUI } from '@/modules/file-system/types/file';

const fileContext = inject(FILE_EXPLORER_KEY)
if (!fileContext) throw new Error('fileExplorer not provided');


const { selectedCount, selectedList, createFolder, deleteFiles, downloadFilesV2, parentId } = fileContext;
let currentParentId = parentId.value


const shareControl = inject<{ openShareDialog: (files?: FileItemUI[]) => void }>('shareControl');

const searchKeyword = ref('');

// 动态菜单项
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

// 统一操作入口
const handleAction = async (action: string) => {
  switch (action) {
    case 'createFolder':
      await handleCreateFolder();
      break;
    case 'share':
      shareControl?.openShareDialog(); // 不传参，默认分享所有选中的文件
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

// 新建文件夹
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

// 下载选中的文件
const handleDownload = async () => {
  if (selectedList.value.length === 0) return;
  try {
    const ids = selectedList.value.map(item => item.id!);
    await downloadFilesV2(ids);
  } catch (err: any) {
    ElMessage.error('下载失败：' + (err.message || '未知错误'));
  }
};

// 删除选中的文件/文件夹
const handleDelete = async () => {
  if (selectedList.value.length === 0) return;
  try {
    await ElMessageBox.confirm(
      `确定删除选中的 ${selectedList.value.length} 个项吗？此操作不可恢复！`,
      '删除确认',
      {
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        type: 'warning',
      }
    );
    // 不传 ids，默认删除所有选中的项
    await deleteFiles(selectedList.value);
    // 删除成功后的消息由组合式函数内部处理
  } catch (err: any) {
    if (err !== 'cancel') {
      ElMessage.error('删除失败：' + (err.message || '未知错误'));
    }
  }
};

// 搜索处理（仅为 UI 演示，实际搜索可交由父组件或服务端）
const handleSearch = () => {
  // 若需要前端过滤，可在此触发自定义事件，由父组件处理
  // 或扩展 useFileExplorer 提供 search 方法
  console.log('搜索关键词:', searchKeyword.value);
  // 这里可以触发一个事件，例如：emit('search', searchKeyword.value)
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

/* 2. 左侧区域：弹性布局，防止胶囊过长挤压搜索栏 */
.left-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  /* 上传按钮和胶囊之间的间距 */
  flex: 1;
  /* 占据剩余空间 */
  min-width: 0;
  /* 允许内部元素在必要时收缩 */
  width: 100%;
}

/* 3. 胶囊工具栏样式 */
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

/* 4. 右侧搜索栏样式 */
.right-search {
  flex-shrink: 0;
  /* 防止搜索栏被压缩 */
  margin-left: 16px;
}

.search-input {
  padding: 8px 16px;
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
</style>