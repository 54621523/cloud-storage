<template>
    <el-dialog v-model="internalVisible" title="选择文件夹" width="500px" :close-on-click-modal="false" @close="handleClose">
        <div class="tree-wrapper">
            <el-tree ref="treeRef" :data="treeData" lazy :load="loadNode" node-key="id" highlight-current
                :default-expanded-keys="[]" :expand-on-click-node="false" @node-click="handleNodeClick">
                <template #default="{ node, data }">
                    <span class="custom-tree-node">
                        <font-awesome-icon :icon="['fas', 'folder']" :style="{ color: '#f5b342' }" class="file-icon" />
                        <span>{{ data.label }}</span>
                    </span>
                </template>
            </el-tree>
        </div>

        <template #footer>
            <span class="dialog-footer">
                <el-button @click="handleCancel">取消</el-button>
                <el-button type="primary" @click="handleConfirm">确认</el-button>
            </span>
        </template>
    </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch, computed } from 'vue';
import { listFolderOnly } from '@/api/文件管理';
import { ElTree } from 'element-plus';

const props = defineProps<{
    modelValue: boolean;
    rootId?: number;
    rootName?: string;
}>();

const emit = defineEmits<{
    (e: 'update:modelValue', value: boolean): void;
    (e: 'select', payload: { id: number; path: string[] }): void;
    (e: 'confirm', payload: { targetId: number; path: string[] }): void;
    (e: 'cancel'): void;
}>();

// ---------- 内部状态 ----------
const internalVisible = ref(props.modelValue);
const treeRef = ref<InstanceType<typeof ElTree>>();
const selectedId = ref<number | null>(null);
const currentPath = ref<string[]>([]);

// 根节点信息
const rootId = computed(() => {
    const stored = localStorage.getItem('file_root_id');
    return props.rootId ?? (stored != null ? Number(stored) : undefined) ?? 0;
});
const rootName = computed(() => {
    const stored = localStorage.getItem('file_root_name');
    return props.rootName ?? stored ?? '我的文件';
});

const treeData = ref<{ id: number; label: string }[]>([]);

// ---------- 同步父组件 modelValue ----------
watch(
    () => props.modelValue,
    (val) => { internalVisible.value = val; }
);
watch(internalVisible, (val) => {
    if (val !== props.modelValue) {
        emit('update:modelValue', val);
    }
});

// ---------- 懒加载 ----------
const loadNode = (node: any, resolve: (data: any[]) => void) => {
    if (node.level === 0) {
        listFolderOnly({ parentId: rootId.value })
            .then((res: any) => {
                const children = res.data.map((item: any) => ({
                    id: item.id,
                    label: item.name,
                }));
                resolve(children);
            })
            .catch(() => resolve([]));
        return;
    }
    const parentId = node.data.id;
    listFolderOnly({ parentId })
        .then((res: any) => {
            const children = res.data.map((item: any) => ({
                id: item.id,
                label: item.name,
            }));
            resolve(children);
        })
        .catch(() => resolve([]));
};

// ---------- 节点点击 ----------
const handleNodeClick = (data: any, node: any) => {
    selectedId.value = data.id;
    treeRef.value?.setCurrentKey(data.id);

    // 构建完整路径（从根节点名称开始）
    const pathLabels: string[] = [];
    let currentNode = node;
    while (currentNode && currentNode.level > 0) {
        pathLabels.unshift(currentNode.label);
        currentNode = currentNode.parent;
    }
    const fullPath = [rootName.value, ...pathLabels];
    currentPath.value = fullPath;

    // 通知父组件当前选中的节点
    emit('select', { id: data.id, path: fullPath });
};

// ---------- 确认 ----------
const handleConfirm = () => {
    const targetId = selectedId.value !== null ? selectedId.value : rootId.value;
    const path = selectedId.value !== null ? currentPath.value : [rootName.value];
    emit('confirm', { targetId, path });
    internalVisible.value = false;
};

// ---------- 取消 ----------
const handleCancel = () => {
    emit('cancel');
    internalVisible.value = false;
};

// ---------- 右上角关闭 ----------
const handleClose = () => {
    if (internalVisible.value === false) return;
    internalVisible.value = false;
    emit('cancel');
};

// ---------- 对话框打开时重置并发送默认根路径 ----------
watch(internalVisible, (val) => {
    if (val) {
        selectedId.value = null;
        currentPath.value = [rootName.value];  // 默认只显示根
        treeRef.value?.setCurrentKey(null);
        // 发送默认根选择，让父组件面包屑显示根
        emit('select', { id: rootId.value, path: [rootName.value] });
    }
});
</script>