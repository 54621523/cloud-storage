// composables/useTrash.ts
import { computed, reactive } from 'vue';
import { keepPreviousData } from '@tanstack/vue-query';
import { useQueryClient } from '@tanstack/vue-query';
import { ElMessage } from 'element-plus';
import type { RecycleItemUI } from '@/modules/trash-system/types/recycle';
import { useSelectionForList } from '@/composables/useSelectionForList';
import {
    useListRecycleBinFiles,
    useRestore,
    useDeletePermanently,
} from '@/api/回收站管理';
import type { ListRecycleBinFilesParams } from '@/api/models';

export function useTrash() {
    const queryClient = useQueryClient();

    // ---------- 数据获取 ----------
    const pageParams = reactive({ pageNum: 1, pageSize: 10 });
    const {
        data: rawResult,
        isLoading,
        isError,
        error,
        refetch, // 保留，供手动刷新按钮使用（可选的）
    } = useListRecycleBinFiles(
        computed<ListRecycleBinFilesParams>(() => ({
            pageNum: pageParams.pageNum,
            pageSize: pageParams.pageSize,
        })),
        {
            query: {
                select: (result) => {
                    if (result.code === 1) return result.data;
                    ElMessage.error(result.msg || '获取回收站列表失败');
                    return { list: [], total: 0 };
                },
                placeholderData: keepPreviousData,
                staleTime: 5 * 60 * 1000,
            },
        }
    );

    const total = computed(() => rawResult.value?.total || 0);
    const recycleItems = computed<RecycleItemUI[]>(() => {
        const raw = rawResult.value?.list || [];
        return raw.map(item => ({ ...item }));
    });

    // ---------- 分页 ----------
    function handlePageChange(page: number) {
        pageParams.pageNum = page;
        selection.clear();
    }
    function handleSizeChange(size: number) {
        pageParams.pageSize = size;
        pageParams.pageNum = 1;
        selection.clear();
    }

    // ---------- 选择 ----------
    const selection = useSelectionForList(
        recycleItems,
        (item) => item.type
    );
    const selectedList = selection.selectedList;
    const selectedCount = selection.selectedCount;
    const selectedIds = computed(() => selection.selectedList.value.map(item => item.id));

    function toggleSelect(id: number) { selection.toggle(id); }
    function clearSelection() { selection.clear(); }
    function isSelected(id: number) { return selection.isSelected(id); }

    // ---------- 刷新（使用 invalidateQueries） ----------
    function refresh() {
        queryClient.invalidateQueries({ queryKey: ['api', 'recycle'] });
    }

    // ---------- 还原 ----------
    const restoreMutation = useRestore({
        mutation: {
            // 移除 onSuccess，错误处理保留
            onError: (err: any) => {
                ElMessage.error(err?.message || '还原失败');
            },
        },
    });

    async function restoreFiles(files?: RecycleItemUI | RecycleItemUI[]) {
        if (!files) {
            ElMessage.warning('请选择要还原的项');
            return;
        }
        const items = Array.isArray(files) ? files : [files];
        if (items.length === 0) {
            ElMessage.warning('请选择要还原的项');
            return;
        }
        const identities = items.map(item => ({ id: item.id, type: item.type }));
        await restoreMutation.mutateAsync({ data: { items: identities } });
        // 成功后统一处理
        selection.clear();
        ElMessage.success(`成功还原 ${items.length} 项`);
        refresh();
    }

    // ---------- 彻底删除 ----------
    const deletePermanentlyMutation = useDeletePermanently({
        mutation: {
            onError: (err: any) => {
                ElMessage.error(err?.message || '彻底删除失败');
            },
        },
    });

    async function deletePermanently(files?: RecycleItemUI | RecycleItemUI[]) {
        if (!files) {
            ElMessage.warning('请选择要彻底删除的项');
            return;
        }
        const items = Array.isArray(files) ? files : [files];
        if (items.length === 0) {
            ElMessage.warning('请选择要彻底删除的项');
            return;
        }
        const identities = items.map(item => ({ id: item.id, type: item.type }));
        await deletePermanentlyMutation.mutateAsync({ data: { items: identities } });
        selection.clear();
        ElMessage.success(`成功彻底删除 ${items.length} 项`);
        refresh();
    }

    // ---------- 暴露 ----------
    return {
        recycleItems,
        isLoading,
        isError,
        error,
        total,
        pageParams,
        handlePageChange,
        handleSizeChange,

        selectedCount,
        selectedList,
        selectedIds,
        toggleSelect,
        clearSelection,
        updateSelect: selection.select,
        isSelected,

        refresh,
        restoreFiles,
        deletePermanently,
    };
}
export type UseTrashReturn = ReturnType<typeof useTrash>;