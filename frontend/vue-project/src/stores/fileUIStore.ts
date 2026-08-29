// stores/fileUIStore.ts
import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import type { ViewMode, SortField, SortOrder } from '@/modules/file-system/types/file';


export const useFileUIStore = defineStore('fileUI', () => {
    // ---------- State ----------
    const viewMode = ref<ViewMode>('list');
    const sortField = ref<SortField>('name');
    const sortOrder = ref<SortOrder>('asc');

    // ---------- Actions ----------

    function changeSort(field: SortField) {
        if (sortField.value === field) {
            sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc';
        } else {
            sortField.value = field;
            sortOrder.value = 'asc';
        }
    }


    function reset() {
        viewMode.value = 'list';
        sortField.value = 'name';
        sortOrder.value = 'asc';
    }

    return {
        // state
        viewMode,
        sortField,
        sortOrder,
        // actions
        changeSort,
        reset,
    };
});