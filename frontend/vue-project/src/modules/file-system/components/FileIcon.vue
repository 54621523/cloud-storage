<script setup lang="ts">
import { computed } from 'vue';
import { FileItemType, type FileItemUI } from '@/modules/file-system/types/file'; // 请根据实际路径调整

// 定义 Props
const props = defineProps<{
    file: FileItemUI;
}>();

const iconMap: Record<string, string> = {
    folder: 'folder',
    pdf: 'file-pdf',
    doc: 'file-word',
    docx: 'file-word',
    xls: 'file-excel',
    xlsx: 'file-excel',
    png: 'file-image',
    jpg: 'file-image',
    jpeg: 'file-image',
    zip: 'file-archive',
    rar: 'file-archive',
};

const colorMap: Record<string, string> = {
    folder: '#f5b342',
    pdf: '#e74c3c',
    doc: '#2b7aff',
    docx: '#2b7aff',
    xls: '#27ae60',
    xlsx: '#27ae60',
    png: '#2ecc71',
    jpg: '#2ecc71',
    jpeg: '#2ecc71',
    zip: '#f39c12',
    rar: '#f39c12',
};

const icon = computed(() => {
    if (props.file.type === FileItemType.FOLDER) return ['fas', 'folder'];
    if (!props.file.name) return ['fas', 'file'];
    const ext = props.file.name.split('.').pop()?.toLowerCase() || '';
    return ['fas', iconMap[ext] || 'file'];
});

const color = computed(() => {
    if (props.file.type === FileItemType.FOLDER) return colorMap.folder;
    if (!props.file.name) return '#4a7fd4';
    const ext = props.file.name.split('.').pop()?.toLowerCase() || '';
    return colorMap[ext] || '#4a7fd4';
});
</script>

<template>
    <font-awesome-icon :icon="icon" :style="{ color }" class="file-icon" />
</template>