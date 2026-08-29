<!-- SharePage.vue（用户自己的分享列表管理） -->
<template>
  <el-config-provider :locale="zhCn">
    <div class="share-page">
      <ShareTopBar />
      <ShareManager />
    </div>
  </el-config-provider>
</template>

<script setup lang="ts">
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import { provide, onBeforeUnmount } from 'vue';
import { useMyShares } from '@/modules/share-system/composables/useMyShares';
import ShareTopBar from '@/modules/share-system/components/ShareTopBar.vue';
import ShareManager from '@/modules/share-system/components/ShareManager.vue';


const shareContext = useMyShares();

provide('shareContext', shareContext);
</script>

<style scoped>
.share-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 20px;
}
</style>