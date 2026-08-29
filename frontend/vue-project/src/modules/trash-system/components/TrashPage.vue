<template>
    <el-config-provider :locale="zhCn">
        <div class="page-container">
            <!-- 主内容区 -->
            <TrashPanel />
        </div>
    </el-config-provider>
</template>

<script setup lang="ts">
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import { provide, onBeforeUnmount } from 'vue';
import { useRoute } from 'vue-router';
import TrashPanel from './TrashPanel.vue';

</script>

<style scoped>
.page-container {
    display: flex;
    flex-direction: column;
    height: 100%;
    padding: 20px;
}
</style>