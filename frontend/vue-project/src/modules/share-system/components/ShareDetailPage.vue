<template>
  <div class="share-detail-page">
    <div class="share-detail-container">

      <!-- 状态1：需要输入提取码 -->
      <ShareVerifyPanel v-if="!shareContext.isVerified.value" />

      <!-- 状态2：验证成功，展示文件列表 -->
      <template v-else>
        <!-- 面包屑导航（用于浏览分享内容） -->
        <div class="path-bar">
          <div class="path">
            <el-breadcrumb separator="/" class="custom-breadcrumb">
              <el-breadcrumb-item v-for="(item, index) in pathStack" :key="item.id">
                <span v-if="index === pathStack.length - 1" class="current-path">
                  {{ item.name }}
                </span>
                <a v-else href="javascript:void(0)" @click.prevent="handlePathClick(index)" class="history-path">
                  {{ item.name }}
                </a>
              </el-breadcrumb-item>
            </el-breadcrumb>
          </div>
        </div>

        <!-- 文件列表表格 -->
        <el-table ref="tableRef" v-loading="isLoading" :data="shareFileList" row-key="id" class="file-table"
          @row-click="handleRowClick" @selection-change="handleSelectionChange" :row-class-name="tableRowClassName">
          <el-table-column type="selection" width="55" align="center" :reserve-selection="true" />
          <el-table-column prop="name" label="文件名" min-width="200">
            <template #default="{ row }">
              <div class="name-cell">
                <FileIcon :file="row" />
                <span class="file-name">{{ row.name }}</span>
                <!-- 悬浮操作组 -->
                <div class="hover-actions" @click.stop>
                  <div class="action-btn" title="下载" @click="handleDownload(row)">
                    <font-awesome-icon :icon="['fas', 'download']" />
                  </div>
                  <div class="action-btn" title="转存" @click="handleTransfer(row)">
                    <font-awesome-icon :icon="['fas', 'inbox']" />
                  </div>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="size" label="大小" width="120">
            <template #default="{ row }">{{ formatFileSize(row.size) }}</template>
          </el-table-column>
          <el-table-column prop="updateTime" label="修改时间" width="180">
            <template #default="{ row }">{{ formatDate(row.updateTime) }}</template>
          </el-table-column>
        </el-table>


        <div class="action-wrapper">
          <div class="save-target-bar">
            <span class="save-label">保存到：</span>
            <el-breadcrumb separator="/" class="save-breadcrumb">
              <el-breadcrumb-item v-for="(item, index) in currentPath" :key="index">
                {{ item }}
              </el-breadcrumb-item>
            </el-breadcrumb>
            <div class="icon-wrapper" @click.stop="dialogVisible = true">
              <font-awesome-icon :icon="['fas', 'folder']" />
            </div>
          </div>

          <!-- 右侧：独立于 bar 的按钮区域 -->
          <div class="action-btn-group">
            <el-button type="primary" class="btn-save">保存到网盘</el-button>
            <el-button class="btn-download">下载</el-button>
          </div>
        </div>


      </template>
    </div>
  </div>
  <FolderTreeSelector v-model="dialogVisible" @select="onSelect" @confirm="onConfirm" @cancel="onCancel" />
</template>

<script setup lang="ts">
import { ref, onBeforeUnmount, provide, watch } from 'vue';
import { useRoute } from 'vue-router';
import type { FileItemUI } from '@/modules/file-system/types/file';
import { FileItemType } from '@/modules/file-system/types/file';
import { ElMessage } from 'element-plus';
import { formatDate, formatFileSize } from '@/utils/format';
import { useSharedView } from '@/modules/share-system/composables/useSharedView';
import ShareVerifyPanel from '@/modules/share-system/components/ShareVerifyPanel.vue';
import FileIcon from '@/modules/file-system/components/FileIcon.vue';
import FolderTreeSelector from '@/modules/share-system/components/FolderTreeSelector.vue';

// 获取分享上下文
const shareContext = useSharedView();
provide('shareContext', shareContext);

const {
  shareFileList,
  isLoading,
  pathStack,
  navigateTo,
  goToBreadcrumb,
  selectedCount,
  clearSelection,
  updateSelect,

  downloadFile,
  saveToMyDisk
} = shareContext;

// ---------- 保存目标相关 ----------
const rootId = Number(localStorage.getItem('file_root_id'));
const rootName = localStorage.getItem('file_root_name') || '我的文件';
const targetId = ref<number>(rootId);
const currentPath = ref<string[]>([rootName]); // 默认显示根

// ---------- 表格相关 ----------
const handleSelectionChange = (selection: any[]) => {
  const ids = selection.map(item => item.id).filter(id => id != null);
  updateSelect(ids);
};

const handleRowClick = (row: FileItemUI) => {
  if (row.type === FileItemType.FOLDER) {
    clearSelection();
    navigateTo(row);
  } else {
    console.log('点击文件', row);
  }
};

const handlePathClick = (index: number) => {
  goToBreadcrumb(index);
};

const tableRowClassName = ({ row }: { row: FileItemUI }) => {
  return row.type === FileItemType.FOLDER ? 'folder-row' : '';
};

// 下载
const handleDownload = (row: FileItemUI) => {
  ElMessage.info(`准备下载: ${row.name}`);
  if (!row.id) {
    return
  }
  downloadFile(row.id)
};

const handleTransfer = (row: any) => {
  // 转存时使用 currentSaveTargetId
  ElMessage.info(`准备将 "${row.name}" 转存到目标文件夹 ID: ${targetId.value}`);
  saveToMyDisk(row, targetId.value)
};



const dialogVisible = ref(false);
const onSelect = (payload: { id: number; path: string[] }) => {
  targetId.value = payload.id;
  currentPath.value = payload.path;
};

// 确认保存时使用当前的 targetId 和路径
const onConfirm = (payload: { targetId: number; path: string[] }) => {
  // 可再次使用 payload 确保一致性
  targetId.value = payload.targetId;
  currentPath.value = payload.path;
  // 执行保存请求，使用 targetId.value 作为目标文件夹 ID
  console.log('保存到文件夹 ID:', targetId.value);
  // 关闭对话框（由组件内部关闭，无需重复操作）
};

const onCancel = () => {
  // 可选：取消时的处理
};


onBeforeUnmount(() => {
  shareContext.clearSelection();
});
</script>

<style scoped lang="scss">
@use '@/styles/panel-common.scss' as panel;

.share-detail-page {
  width: 96vw;
  max-width: 98vw;
  height: 96vh;
  max-height: 98vh;
  background: white;
  border-radius: 28px;
  box-shadow: 0 20px 60px rgba(0, 20, 40, 0.12);
  display: flex;
  overflow: hidden;
  margin: 0 auto;
}

.share-detail-container {
  flex: 1;
  background: #fafcff;
  display: flex;
  flex-direction: column;
  padding: 24px 28px 28px 28px;
  overflow: hidden;
}

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

  &:hover {
    color: #1a5cff;
  }
}

.current-path {
  color: #1a5cff;
  font-weight: 600;
}

/* 表格 */
.file-table {
  @extend .base-table;
  flex: 1;
}

.file-table :deep(.folder-row) {
  cursor: pointer;
}

.name-cell {
  @extend .base-name-cell;
}

/* 1. 外层容器：Flex 布局，让 Bar 和按钮组分开 */
.action-wrapper {
  display: flex;
  align-items: center;
  gap: 16px;
  /* 控制灰色 bar 和 右侧按钮组之间的距离 */
  width: 100%;
}

/* 2. Bar 区域：只装路径和图标 */
.save-target-bar {
  display: flex;
  align-items: center;
  flex: 1;
  /* 灰条占据左侧剩余所有空间 */
  background-color: #f5f6f8;
  border-radius: 8px;
  padding: 0 16px;
  height: 48px;
  /* 控制灰条高度 */
  box-sizing: border-box;
}

.save-label {
  color: #969799;
  font-size: 14px;
  white-space: nowrap;
  margin-right: 4px;
}

.save-breadcrumb {
  font-size: 14px;
  color: #333;
  flex: 1;
  /* 让文字撑满灰条中间区域 */
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  /* 防止路径太长溢出 */
}


/* 文件夹图标 */
.icon-wrapper {
  display: flex;
  align-items: center;
  padding-left: 12px;
  /* 左侧留出间距 */
  color: #666;
  cursor: pointer;
  border-left: 1px solid #e0e0e0;
  /* 可选：给图标左边加个极淡的分隔线，区分路径和图标 */
  transition: color 0.2s;
}

.icon-wrapper:hover {
  color: #409EFF;
}

.folder-icon {
  font-size: 20px;
}

/* 3. 独立按钮组：放在灰条外面 */
.action-btn-group {
  display: flex;
  align-items: center;
  gap: 12px;
  /* 两个按钮之间的间距 */
}

/* 保存按钮 */
.btn-save {
  background-color: #007bff;
  border-color: #007bff;
  padding: 8px 16px;
}

.btn-save:hover {
  background-color: #0062cc;
  border-color: #0062cc;
}

/* 下载按钮 */
.btn-download {
  background-color: #e6f7ff;
  color: #1890ff;
  border: none;
  padding: 8px 16px;
}

.btn-download:hover {
  background-color: #bae7ff;
  color: #1890ff;
}
</style>