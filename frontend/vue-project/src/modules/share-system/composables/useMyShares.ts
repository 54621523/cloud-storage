// src/composables/useMyShares.ts

import { type Ref, ref, reactive, computed } from 'vue';
import { keepPreviousData, useQueryClient } from '@tanstack/vue-query';
import { ElMessage } from 'element-plus';
import type { ShareItemUI } from '@/modules/share-system/types/share';
import type { FileItemUI } from '@/modules/file-system/types/file';
import {
    useListSharedFile,
    useShareFile,
    useCancelSharedFile,
} from '@/api/分享模块';
import type { ListSharedFileParams } from '@/api/models';
import { getErrorMessage } from '@/utils/errorHandler';
import { useSelectionForList } from '@/composables/useSelectionForList';

export function useMyShares(options?: { enabled?: Ref<boolean> }) {
    const enabled = options?.enabled ?? ref(true); // 默认启用


    const queryClient = useQueryClient();
    const pageParams = reactive({ pageNum: 1, pageSize: 10 });
    // 列表查询
    const {
        data: rawResult,
        isLoading,
        refetch, // 保留，供"手动刷新按钮"使用
    } = useListSharedFile(
        computed<ListSharedFileParams>(() => ({
            pageNum: pageParams.pageNum,
            pageSize: pageParams.pageSize,
        })),
        {
            query: {
                enabled: enabled,
                select: (result) => {
                    if (result.code === 1) return result.data;
                    ElMessage.error(result.msg || '获取分享列表失败');

                    return { list: [], total: 0 };
                },
                placeholderData: keepPreviousData,
                staleTime: 5 * 60 * 1000,
            },
        }
    );

    const total = computed(() => rawResult.value?.total || 0);
    const shareList = computed<ShareItemUI[]>(() => {
        const raw = rawResult.value?.list || [];
        return raw.map(item => ({ ...item }));
    });

    const selection = useSelectionForList(shareList);

    // ---- 分页操作 ----
    function handlePageChange(page: number) {
        pageParams.pageNum = page;
        selection.clear();
    }

    function handleSizeChange(size: number) {
        pageParams.pageSize = size;
        pageParams.pageNum = 1;
        selection.clear();
    }

    // ---- 刷新（改为缓存过期模式） ----
    function refresh() {
        queryClient.invalidateQueries({ queryKey: ['api', 'shares', 'list'] });
        selection.clear(); // 清空选中
    }

    // ---- 创建分享 ----
    const shareFileMutation = useShareFile({
        mutation: {
            // 移除 onSuccess 中的 refresh，改由外部统一处理
            onError: (err) => ElMessage.error(getErrorMessage(err) || '创建分享失败'),
        },
    });

    async function createShare(
        files: FileItemUI | FileItemUI[],
        options?: { password?: string; expireTime?: string; displayName?: string }
    ) {
        if (!files) {
            ElMessage.warning('请选择要分享的项');
            return;
        }
        const fileArray = Array.isArray(files) ? files : [files];
        const items = fileArray.map((file) => ({
            targetId: file.id,
            targetType: file.type,
        }));
        await shareFileMutation.mutateAsync({ data: { items, ...options } });
        ElMessage.success('分享创建成功');
        refresh(); // 统一刷新
    }

    // ---- 取消分享 ----
    const cancelShareMutation = useCancelSharedFile({
        mutation: {
            // 移除 onSuccess 中的 refresh
            onError: (err) => ElMessage.error(getErrorMessage(err) || '取消分享失败'),
        },
    });

    async function cancelShare(shareId: number) {
        await cancelShareMutation.mutateAsync({ params: { shareId } });
        ElMessage.success('已取消分享');
        refresh(); // 统一刷新
    }

    // ---- 批量取消分享（新增） ----
    async function cancelShares(shareIds: number[]) {
        if (shareIds.length === 0) return;
        await Promise.all(shareIds.map(id => cancelShare(id)));
        // cancelShare 内部已调用 refresh，无需额外刷新
    }

    return {
        shareList,
        total,
        isLoading,
        pageParams,
        handlePageChange,
        handleSizeChange,
        refresh,
        updateSelect: selection.select,
        createShare,
        isCreating: shareFileMutation.isPending,
        cancelShare,
        cancelShares,
        isCancelling: cancelShareMutation.isPending,
    };
}