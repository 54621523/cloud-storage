<template>
    <div class="trash-panel">
        <!-- 标题栏（固定高度） -->
        <div class="trash-header">
            <div class="trash-title">
                <font-awesome-icon :icon="['fas', 'trash']" class="title-icon" />
                <span>回收站</span>
            </div>
            <!-- 胶囊工具栏：仅当有选中项时显示 -->
            <div v-if="selectedRows.length > 0" class="capsule-toolbar">
                <div class="action-btn" @click="handleBatchRestore">
                    <font-awesome-icon :icon="['fas', 'undo-alt']" />
                    <span>还原</span>
                </div>
                <div class="action-btn" @click="handleBatchDelete">
                    <font-awesome-icon :icon="['fas', 'trash-can']" style="color: #e74c3c;" />
                    <span>彻底删除</span>
                </div>
            </div>
        </div>

        <!-- 表格 -->
        <el-table ref="tableRef" :data="recycleItems" v-loading="isLoading" @selection-change="handleSelectionChange"
            row-key="id" class="trash-table" style="width: 100%">
            <!-- 多选列 -->
            <el-table-column type="selection" width="40" align="center" />

            <!-- 文件名 -->
            <el-table-column prop="name" label="文件名" min-width="200">
                <template #default="{ row }">
                    <div class="name-cell">
                        <FileIcon :file="row" />
                        <span class="file-name">{{ row.name }}</span>
                        <div class="hover-actions" @click.stop>
                            <div class="action-btn" title="还原" @click="handleRestore(row)">
                                <font-awesome-icon :icon="['fas', 'undo-alt']" />
                            </div>
                            <div class="action-btn" title="彻底删除" @click="handlePermanentDelete(row)">
                                <font-awesome-icon :icon="['fas', 'trash-can']" style="color: #e74c3c;" />
                            </div>
                        </div>
                    </div>
                </template>
            </el-table-column>

            <!-- 删除日期 -->
            <el-table-column prop="deleteTime" label="删除日期" width="180">
                <template #default="{ row }">
                    {{ formatDate(row.deletedAt) }}
                </template>
            </el-table-column>
        </el-table>

        <!-- 分页 -->
        <div class="trash-pagination">
            <el-pagination background layout="total, sizes, prev, pager, next, jumper" :total="total"
                :page-sizes="[10, 20, 50, 100]" v-model:current-page="pageParams.pageNum"
                v-model:page-size="pageParams.pageSize" @size-change="handleSizeChange"
                @current-change="handlePageChange" />
        </div>
    </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { formatDate } from '@/utils/format';
import { type RecycleItemUI, FileItemType } from '@/modules/trash-system/types/recycle';
import { useTrash } from '@/modules/trash-system/composables/useTrash';
import FileIcon from '@/modules/file-system/components/FileIcon.vue';

const {
    recycleItems,
    total,
    isLoading,
    handlePageChange,
    handleSizeChange,
    pageParams,
    updateSelect,
    restoreFiles,
    deletePermanently,
} = useTrash();

// ---- 本地选中状态 ----
const selectedRows = ref<RecycleItemUI[]>([]);

// ---- 表格多选事件 ----
const handleSelectionChange = (selection: RecycleItemUI[]) => {
    selectedRows.value = selection;
    const ids = selection
        .map(item => item.id)
        .filter((id): id is number => id != null);
    updateSelect(ids);
};

// ---- 还原单个 ----
const handleRestore = async (row: RecycleItemUI) => {
    await restoreFiles(row);
};

// ---- 彻底删除单个 ----
const handlePermanentDelete = async (row: RecycleItemUI) => {
    await deletePermanently(row);
};

// ---- 批量还原 ----
const handleBatchRestore = async () => {
    if (selectedRows.value.length === 0) return;
    try {
        await ElMessageBox.confirm(
            `确定还原选中的 ${selectedRows.value.length} 个文件/文件夹吗？`,
            '批量还原确认',
            { confirmButtonText: '确定', cancelButtonText: '取消', type: 'info' }
        );
        // 执行还原
        restoreFiles(selectedRows.value)
        selectedRows.value = [];
        // 无需手动刷新，restoreFiles 内部会刷新列表
    } catch (err: any) {
        if (err !== 'cancel') {
            ElMessage.error(err?.message || '批量还原失败');
        }
    }
};

// ---- 批量彻底删除 ----
const handleBatchDelete = async () => {
    if (selectedRows.value.length === 0) return;
    try {
        await ElMessageBox.confirm(
            `确定彻底删除选中的 ${selectedRows.value.length} 个文件/文件夹吗？此操作不可恢复！`,
            '批量彻底删除确认',
            { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' }
        );
        deletePermanently(selectedRows.value)
        ElMessage.success(`成功彻底删除 ${selectedRows.value.length} 项`);
        selectedRows.value = [];
    } catch (err: any) {
        if (err !== 'cancel') {
            ElMessage.error(err?.message || '批量删除失败');
        }
    }
};
</script>

<style scoped lang="scss">
@use '@/styles/panel-common.scss' as panel;

.trash-panel {
    @extend .base-panel;
}

.trash-table {
    @extend .base-table;
}

.name-cell {
    @extend .base-name-cell;
}

.trash-pagination {
    flex-shrink: 0;
    padding: 16px 20px;
    border-top: 1px solid #f0f4fc;
    display: flex;
    justify-content: flex-end;
    align-items: center;
    background-color: #fff;
}

/* ---- 标题栏（固定高度，防止跳动） ---- */
.trash-header {
    padding: 0 20px 16px 20px;
    border-bottom: 1px solid #f0f4fc;
    display: flex;
    justify-content: space-between;
    align-items: center;
    flex-shrink: 0;
    height: 56px;
    /* 固定高度，确保页面不跳动 */
}

.trash-title {
    display: flex;
    align-items: center;
    gap: 10px;
    font-size: 16px;
    font-weight: 600;
    color: #1a2332;
}

.title-icon {
    color: #4f7cff;
    font-size: 18px;
}

/* ---- 胶囊工具栏（与 HomeTopBar 一致） ---- */
.capsule-toolbar {
    display: inline-flex;
    background-color: #eef2f6;
    border-radius: 24px;
    padding: 4px;
}

.capsule-toolbar .action-btn {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 6px 16px;
    border-radius: 20px;
    font-size: 13px;
    color: #2a405c;
    cursor: pointer;
    transition: all 0.2s;
    background: transparent;
    border: none;
    user-select: none;
}

.capsule-toolbar .action-btn:hover {
    background-color: #dce4ec;
}

/* 首尾圆角处理（只有一个按钮时） */
.capsule-toolbar .action-btn:first-child {
    border-radius: 20px 0 0 20px;
}

.capsule-toolbar .action-btn:last-child {
    border-radius: 0 20px 20px 0;
}

.capsule-toolbar .action-btn:only-child {
    border-radius: 20px;
}
</style>