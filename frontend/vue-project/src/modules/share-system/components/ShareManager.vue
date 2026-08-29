<template>
  <div class="share-panel">
    <!-- 标题栏 -->
    <div class="share-header">
      <div class="share-title">
        <font-awesome-icon :icon="['fas', 'share-alt']" class="title-icon" />
        <span>我的分享</span>
      </div>
    </div>

    <el-table :data="shareList" v-loading="isLoading" @selection-change="handleSelectionChange" row-key="id"
      class="share-table" style="width: 100%">
      <!-- 多选列 -->
      <el-table-column type="selection" width="40" align="center" />

      <!-- 文件名 -->
      <el-table-column prop="displayName" label="文件名" min-width="200">
        <template #default="{ row }">
          <div class="name-cell">
            <font-awesome-icon :icon="['fas', 'folder']" class="file-icon" style="color: #4f7cff;" />
            <span class="file-name">{{ row.displayName || '未命名分享' }}</span>
            <!-- 悬浮操作组 -->
            <div class="hover-actions" @click.stop>
              <div class="action-btn" title="复制链接" @click="copyLink(row)">
                <font-awesome-icon :icon="['fas', 'copy']" />
              </div>
              <div class="action-btn" title="删除分享" @click="handleDeleteShare(row.id)">
                <font-awesome-icon :icon="['fas', 'trash-can']" />
              </div>
            </div>
          </div>
        </template>
      </el-table-column>

      <!-- 提取码 -->
      <el-table-column prop="password" label="提取码" width="120">
        <template #default="{ row }">
          {{ row.password || '无' }}
        </template>
      </el-table-column>

      <!-- 有效期 -->
      <el-table-column label="有效期" width="100">
        <template #default="{ row }">
          {{ getValidityText(row) }}
        </template>
      </el-table-column>

      <!-- 到期时间 -->
      <el-table-column prop="expireTime" label="到期时间" width="160">
        <template #default="{ row }">
          {{ formatDate(row.expireTime) }}
        </template>
      </el-table-column>

      <!-- 分享时间 -->
      <el-table-column prop="createTime" label="分享时间" width="160">
        <template #default="{ row }">
          {{ formatDate(row.createTime) }}
        </template>
      </el-table-column>
    </el-table>



    <!-- 分页 -->
    <div class="share-pagination">
      <el-pagination background layout="total, sizes, prev, pager, next, jumper" :total="total"
        :page-sizes="[10, 20, 50, 100]" v-model:current-page="pageParams.pageNum"
        v-model:page-size="pageParams.pageSize" @size-change="handleSizeChange" @current-change="handlePageChange" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import { useMyShares } from '@/modules/share-system/composables/useMyShares';
import { formatDate } from '@/utils/format';
import { useRouter } from 'vue-router';


const {
  shareList,
  total,
  isLoading,

  updateSelect,

  pageParams,
  handlePageChange,
  handleSizeChange,

  refresh,

  cancelShare,
} = useMyShares();

const router = useRouter();

// 计算有效期文本
const getValidityText = (share: any) => {
  if (!share.expireTime) return '永久有效';
  const diff = new Date(share.expireTime).getTime() - new Date(share.createTime).getTime();
  const days = Math.ceil(diff / (1000 * 60 * 60 * 24));
  return days > 0 ? `${days}天` : '已过期';
};


const handleSelectionChange = (selection: any[]) => {
  const ids = selection
    .map(item => item.id)
    .filter((id): id is number => id != null);
  updateSelect(ids);

};

// 复制分享链接
const copyLink = async (share: any) => {
  const route = router.resolve({
    name: 'ShareView',
    params: { shareCode: share.shareCode }
  });
  const fullUrl = window.location.origin + route.href;
  try {
    await navigator.clipboard.writeText(fullUrl);
    ElMessage.success('链接已复制');
  } catch (err) {
    console.error('复制失败', err);
  }
};

// 删除分享
const handleDeleteShare = async (shareId: number) => {
  try {
    await cancelShare(shareId);
    ElMessage.success('已取消分享');
    refresh(); // 刷新列表
  } catch (err: any) {
    ElMessage.error(err?.message || '取消分享失败');
  }
};


// 组件挂载时加载数据
onMounted(() => {
  refresh();
});
</script>

<style scoped lang="scss">
@use '@/styles/panel-common.scss' as panel;

.share-panel {
  @extend .base-panel;
}

.share-table {
  @extend .base-table;
}

.name-cell {
  @extend .base-name-cell;
}

/* 标题栏 */
.share-header {
  padding: 0 20px 16px 20px;
  border-bottom: 1px solid #f0f4fc;
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-shrink: 0;
}

.share-title {
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

/* 分页  */
.share-pagination {
  flex-shrink: 0;
  padding: 16px 20px;
  border-top: 1px solid #f0f4fc;
  display: flex;
  justify-content: flex-end;
  align-items: center;
  background-color: #fff;
}
</style>