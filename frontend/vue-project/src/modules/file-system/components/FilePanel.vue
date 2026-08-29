<template>
  <div class="file-panel">
    <!-- 路径导航 -->
    <div class="path-bar">
      <div class="path">
        <el-breadcrumb separator="/" class="custom-breadcrumb">
          <el-breadcrumb-item v-for="(item, index) in pathStack" :key="item.id">
            <!-- 最后一级（当前目录）不可点击 -->
            <span v-if="index === pathStack.length - 1" class="current-path">
              {{ item.name }}
            </span>
            <!-- 历史级可点击，点击后跳转 -->
            <a v-else href="javascript:void(0)" @click.prevent="handlePathClick(index)" class="history-path">
              {{ item.name }}
            </a>
          </el-breadcrumb-item>
        </el-breadcrumb>
      </div>
    </div>

    <el-table ref="tableRef" :data="fileItems" v-loading="isLoading" @selection-change="handleSelectionChange"
      row-key="id" class="file-table" style="width: 100%" @row-click="handleRowClick"
      :row-class-name="tableRowClassName">
      <!-- 多选列 -->
      <el-table-column type="selection" width="40" align="center" />

      <!-- 文件名（含图标和悬浮操作） -->
      <el-table-column prop="name" label="文件名" min-width="200">
        <template #default="{ row }">
          <div class="name-cell">
            <FileIcon :file="row" />
            <span class="file-name">{{ row.name }}</span>
            <!-- 悬浮操作组 -->
            <div class="hover-actions" @click.stop>
              <div class="action-btn" title="分享" @click="handleShare(row)">
                <font-awesome-icon :icon="['fas', 'share-nodes']" />
              </div>
              <div class="action-btn" title="下载" @click="handleSingleDownload(row)">
                <font-awesome-icon :icon="['fas', 'download']" />
              </div>
              <div class="action-btn" title="删除" @click="handleDelete(row)">
                <font-awesome-icon :icon="['fas', 'trash-can']" />
              </div>
              <div class="action-btn" title="重命名" @click="handleRename(row)">
                <font-awesome-icon :icon="['fas', 'pen']" />
              </div>
            </div>
          </div>
        </template>
      </el-table-column>

      <!-- 大小 -->
      <el-table-column prop="size" label="大小" width="180">
        <template #default="{ row }">
          {{ formatFileSize(row.size) }}
        </template>
      </el-table-column>

      <!-- 修改时间 -->
      <el-table-column prop="updateTime" label="修改时间" width="180">
        <template #default="{ row }">
          {{ formatDate(row.updateTime) }}
        </template>
      </el-table-column>
    </el-table>

    <!-- 底部状态栏 -->
    <div class="file-footer">
      <span>
        <font-awesome-icon :icon="['far', 'file']" />
        共 {{ fileItems.length }} 项
        <span v-if="selectedCount > 0" class="selected-info">
          ，已选 {{ selectedCount }} 项
        </span>
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">

import { ref, inject } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { FileItemType, type FileItemUI } from '@/modules/file-system/types/file';
import { formatFileSize, formatDate } from '@/utils/format';
import { FILE_EXPLORER_KEY } from '@/symbol';
import FileIcon from '@/modules/file-system/components/FileIcon.vue';




// ---- 组合式 API ----
const fileContext = inject(FILE_EXPLORER_KEY)
if (!fileContext) throw new Error('fileExplorer not provided');


const shareControl = inject<{ openShareDialog: (files?: FileItemUI[]) => void }>('shareControl');
const {
  fileItems,
  isLoading,

  selectedCount,
  clearSelection,
  updateSelect,

  pathStack,
  navigateTo,
  goToBreadcrumb,

  renameFile,
  deleteFiles,
  downloadFilesV2,
} = fileContext;


// ---- 表格选中同步 ----
const handleSelectionChange = async (selection: FileItemUI[]) => {
  const ids = selection
    .map(item => item.id)
    .filter((id): id is number => id != null);
  updateSelect(ids)
};

// ---- 事件处理 ----

// 面包屑点击
const handlePathClick = (index: number) => {
  goToBreadcrumb(index);
};

// 分享
const handleShare = (file: FileItemUI) => {
  shareControl?.openShareDialog([file]); // 传入当前行对应的单个文件
};

// 重命名
const handleRename = (file: FileItemUI) => {
  ElMessageBox.prompt(`请输入 "${file.name}" 的新名称`, '重命名', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    inputValue: file.name,
    inputValidator: (value) => {
      if (!value || value.trim() === '') return '名称不能为空';
      if (value === file.name) return '名称未改变';
      return true;
    }
  })
    .then(async ({ value }) => {
      await renameFile(file, value);
    })
    .catch(() => { }); // 用户取消或校验失败不处理
};

// 删除
const handleDelete = async (file: FileItemUI) => {
  try {
    await ElMessageBox.confirm(`确定删除 "${file.name}" 吗？`, '提示', { type: 'warning' });
    await deleteFiles(file)
  } catch (error) {
    if (error !== 'cancel') {
    }
  }
};

// 下载
const handleSingleDownload = async (file: FileItemUI) => {
  downloadFilesV2([file.id!])
};

// ---- 表格行类名 ----
const tableRowClassName = ({ row }: { row: FileItemUI }) => {
  return row.type === FileItemType.FOLDER ? 'folder-row' : '';
};

// ---- 行单击事件 ----
const handleRowClick = (row: FileItemUI) => {
  if (row.type === FileItemType.FOLDER) {
    clearSelection();
    navigateTo(row);
  }
};
</script>

<style scoped lang="scss">
@use '@/styles/panel-common.scss' as panel;


.path-bar {
  padding: 0 20px 16px 20px;
  border-bottom: 1px solid #f0f4fc;
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-shrink: 0;
}

.path {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #2a405c;
  font-size: 14px;
  font-weight: 500;
}

.history-path {
  cursor: pointer;
  transition: color 0.2s;
}

.history-path:hover {
  color: #1a5cff;
}

.current-path {
  color: #1a5cff;
  font-weight: 600;
}

.separator {
  color: #b2c3da;
}

.file-panel {
  @extend .base-panel;
}

.file-table {
  @extend .base-table;
}

.file-table :deep(.folder-row) {
  cursor: pointer;
}

.name-cell {
  @extend .base-name-cell;
}

.file-icon {
  font-size: 18px;
}

.file-footer {
  flex-shrink: 0;
  padding: 16px 20px;
  border-top: 1px solid #f0f4fc;
  font-size: 13px;
  color: #5f7898;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.file-footer i {
  margin-right: 6px;
  color: #7892b2;
}

.selected-info {
  color: #1a5cff;
  font-weight: 500;
}

.file-table :deep(.folder-row) {
  cursor: pointer;
}
</style>