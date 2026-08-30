// composables/useFileExplorer.ts
import { computed, ref, type Ref } from 'vue';
import { sortFiles } from '@/utils/sortFiles';
import { useFileUIStore } from '@/stores/fileUIStore';
import { useQueryClient } from '@tanstack/vue-query';
import { ElMessage } from 'element-plus';
import type { FileItemUI, SortField, SortOrder, VirtualFileVO, ItemIdentity } from '@/modules/file-system/types/file';
import { FileItemType } from '@/modules/file-system/types/file';
import { useSelectionForList } from '@/composables/useSelectionForList';
import { useBreadcrumb } from '@/composables/useBreadcrumb';
import {
    useListFiles,
    useCreateFolder,
    useMoveToRecycleBin,
    useRename,
} from '@/api/文件管理';
import { generateDownloadUrl } from '@/api/upload-v-2-controller'

import { QUERY_KEYS } from '@/constants/queryKeys'

// ============================================================
// UI 状态管理
// ============================================================
export function useFileExplorer(
    initialRootId: number,
    initialRootName: string = '我的文件',
    options?: {
        sortField?: Ref<SortField>;
        sortOrder?: Ref<SortOrder>;
    }
) {

    const queryClient = useQueryClient();

    const internalSortField = ref<SortField>('name');
    const internalSortOrder = ref<SortOrder>('asc');
    const sortField = options?.sortField ?? internalSortField;
    const sortOrder = options?.sortOrder ?? internalSortOrder;

    const breadcrumb = useBreadcrumb({
        id: initialRootId,
        name: initialRootName,
    });
    const pathStack = breadcrumb.pathStack;
    const parentId = breadcrumb.currentParentId;

    // ============================================================
    // 数据获取（列表查询 + 排序 + UI 模型转换）
    // ============================================================
    const {
        data: rawResult,
        isLoading,
        isError,
        error,
        refetch,
    } = useListFiles<VirtualFileVO[], unknown>(
        computed(() => ({ parentId: parentId.value })),
        {
            query: {
                select: (result) => {
                    if (result.code === 1) {
                        return result.data?.filter(item => item.id != null) || [];
                    } else {
                        ElMessage.error(result.msg || '获取文件列表失败');
                        return [];
                    }
                },
                staleTime: 5 * 60 * 1000,
            },
        }
    );

    // 排序（基于当前排序字段和顺序）
    // 内部默认排序类型
    const fileItems = computed<FileItemUI[]>(() => {

        return sortedRawFiles.value.map((raw) => ({
            ...raw,
        }));
    });

    const sortedRawFiles = computed<VirtualFileVO[]>(() => {
        const list = rawResult.value || [];
        return sortFiles(list, sortField.value, sortOrder.value, true); // 最后一个参数 true 表示文件夹优先
    });

    // ============================================================
    // 导航与路径操作
    // ============================================================
    function navigateTo(file: FileItemUI) {
        if (file.type !== FileItemType.FOLDER || !file.id) return;
        clearSelection();
        breadcrumb.push(file.id, file.name || '未命名');
    }

    function goToBreadcrumb(index: number) {
        clearSelection();
        breadcrumb.goTo(index);
    }

    // 手动刷新当前目录
    function refresh() {
        queryClient.invalidateQueries({ queryKey: ['api', 'files'] });
    }

    // ============================================================
    // 选择操作
    // ============================================================
    const selection = useSelectionForList(
        sortedRawFiles,
        (item) => item.type  // 用于构造 SelectedItem 的 type 字段
    );


    function clearSelection() {
        selection.clear();
    }

    // ============================================================
    // 模块六：数据变更（创建文件夹、删除、下载）
    // ============================================================
    const createFolderMutation = useCreateFolder({
        mutation: {
            onError: (err: any) => {
                ElMessage.error(err?.message || '创建文件夹失败');
            },
        },
    });

    async function createFolder(name: string) {
        await createFolderMutation.mutateAsync({
            data: {
                parentId: parentId.value,
                name,
            },
        });
        ElMessage.success('文件夹创建成功');
        refresh();
    }

    const deleteMutation = useMoveToRecycleBin({
        mutation: {
            onError: (err: any) => {
                ElMessage.error(err?.message || '删除失败');
            },
        },
    });

    async function deleteFiles(files: FileItemUI | FileItemUI[]) {
        if (!files) {
            ElMessage.warning('请选择要删除的项');
            return;
        }

        const items = Array.isArray(files) ? files : [files];
        if (items.length === 0) {
            ElMessage.warning('请选择要删除的项');
            return;
        }

        const identities = items.map(item => ({
            id: item.id,
            type: item.type,
        }));

        await deleteMutation.mutateAsync({ data: { items: identities } });
        selection.clear(); // 删除后清空选中状态
        ElMessage.success(`成功删除 ${items.length} 项`);
        refresh();
        queryClient.invalidateQueries({ queryKey: QUERY_KEYS.recycle });
    }

    async function downloadFilesV2(ids: number[]) {
        if (!ids.length) return;
        for (const id of ids) {
            try {
                let request = {
                    virtualFileId: id
                }
                const result = await generateDownloadUrl(request);
                if (result) {
                    window.open(result.data, '_blank');
                } else {
                    ElMessage.error(`下载文件 ${id} 失败: '未知错误'}`);
                }
            } catch (err) {
                ElMessage.error(`获取文件 ${id} 下载链接失败`);
            }
        }
    }

    // ---------- 重命名 ----------
    const renameMutation = useRename({
        mutation: {
            onError: (err: any) => {
                ElMessage.error(err?.message || '重命名失败');
            },
        },
    });

    async function renameFile(file: FileItemUI, newName: string) {
        const request = {
            id: file.id,
            type: file.type,
            newName: newName

        }
        await renameMutation.mutateAsync({ data: request });
        ElMessage.success('重命名成功');
        refresh();
    }

    // ============================================================
    // 暴露公共接口
    // ============================================================
    return {
        // 数据与状态
        fileItems,
        isLoading,
        isError,
        error,
        pathStack,
        parentId,
        // 选择相关
        selectedCount: selection.selectedCount,
        clearSelection,
        updateSelect: selection.select,
        selectedList: selection.selectedList,
        // 导航与排序
        navigateTo,
        goToBreadcrumb,
        refresh,
        // 数据变更
        createFolder,
        deleteFiles,
        downloadFilesV2,
        renameFile
    };
}
export type UseFileExplorerReturn = ReturnType<typeof useFileExplorer>;
