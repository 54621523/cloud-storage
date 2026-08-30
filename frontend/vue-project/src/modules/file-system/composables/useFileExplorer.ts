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
    useSearch  // 已导入
} from '@/api/文件管理';

import type { PageResultVirtualFileVO } from '@/api/models';
import { generateDownloadUrl } from '@/api/分片上传'
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

    // 排序状态
    const internalSortField = ref<SortField>('name');
    const internalSortOrder = ref<SortOrder>('asc');
    const sortField = options?.sortField ?? internalSortField;
    const sortOrder = options?.sortOrder ?? internalSortOrder;

    // 面包屑导航
    const breadcrumb = useBreadcrumb({
        id: initialRootId,
        name: initialRootName,
    });
    const pathStack = breadcrumb.pathStack;
    const parentId = breadcrumb.currentParentId;

    // ============================================================
    // 搜索状态
    // ============================================================
    const searchKeyword = ref<string>('');          // 当前搜索关键词
    const searchPage = ref<number>(1);
    const searchSize = ref<number>(20);
    const isSearchMode = computed(() => searchKeyword.value.trim().length > 0);
    console.log(isSearchMode.value)
    console.log(!isSearchMode.value)

    // ============================================================
    // 数据获取（浏览模式）
    // ============================================================
    const {
        data: rawBrowseResult,
        isLoading: isBrowseLoading,
        isError: isBrowseError,
        error: browseError,
        refetch: refetchBrowse,
    } = useListFiles<VirtualFileVO[], unknown>(
        computed(() => ({ parentId: parentId.value })),
        {
            query: {
                select: (result) => {
                    console.log('浏览数据更新')
                    if (result.code === 1) {
                        return result.data?.filter(item => item.id != null) || [];
                    } else {
                        ElMessage.error(result.msg || '获取文件列表失败');
                        return [];
                    }
                },
                staleTime: 0,
                enabled: (() => {
                    const enabled = !isSearchMode.value;
                    console.log('[useListFiles] enabled:', enabled, 'isSearchMode:', isSearchMode.value);
                    return enabled;
                })(),
            },
        }
    );

    // ============================================================
    // 数据获取（搜索模式）
    // ============================================================
    const {
        data: rawSearchResult,
        isLoading: isSearchLoading,
        isError: isSearchError,
        error: searchError,
        refetch: refetchSearch,
    } = useSearch<PageResultVirtualFileVO, unknown>(
        computed(() => ({
            keyword: searchKeyword.value,
            page: searchPage.value,
            size: searchSize.value,
        })),
        {
            query: {
                select: (result) => {
                    if (result.code === 1) {
                        // 假设 result.data 是 PageResult 结构，包含 list 和 total
                        return result.data || { list: [], total: 0, current: 1, size: 0, pages: 0 };
                    } else {
                        ElMessage.error(result.msg || '搜索失败');
                        return { list: [], total: 0, current: 1, size: 0, pages: 0 };
                    }
                },
                staleTime: 5 * 60 * 1000,
                enabled: isSearchMode, // 仅当搜索模式时启用
            },
        }
    );

    // ============================================================
    // 统一数据源
    // ============================================================
    // 原始数据列表（浏览或搜索）
    const rawData = computed<VirtualFileVO[]>(() => {
        if (isSearchMode.value) {
            return rawSearchResult.value?.list || [];
        } else {
            return rawBrowseResult.value || [];
        }
    });

    // 总条数（用于分页）
    const totalCount = computed<number>(() => {
        if (isSearchMode.value) {
            return rawSearchResult.value?.total || 0;
        } else {
            return rawBrowseResult.value?.length || 0;
        }
    });

    // 加载状态
    const isLoading = computed(() => {
        return isSearchMode.value ? isSearchLoading.value : isBrowseLoading.value;
    });
    const isError = computed(() => {
        return isSearchMode.value ? isSearchError.value : isBrowseError.value;
    });
    const error = computed(() => {
        return isSearchMode.value ? searchError.value : browseError.value;
    });

    // 排序（基于当前排序字段和顺序）
    const sortedRawFiles = computed<VirtualFileVO[]>(() => {
        const list = rawData.value || [];
        return sortFiles(list, sortField.value, sortOrder.value, true);
    });

    // 转换为 UI 模型（目前只是浅拷贝，可扩展）
    const fileItems = computed<FileItemUI[]>(() => {
        return sortedRawFiles.value.map((raw) => ({
            ...raw,
        }));
    });

    // ============================================================
    // 导航与路径操作（仅浏览模式有效）
    // ============================================================
    function navigateTo(file: FileItemUI) {
        if (file.type !== FileItemType.FOLDER || !file.id) return;
        // 如果处于搜索模式，进入文件夹时应清空搜索并跳转
        if (isSearchMode.value) {
            clearSearch();
        }
        clearSelection();
        breadcrumb.push(file.id, file.name || '未命名');
    }

    function goToBreadcrumb(index: number) {
        if (isSearchMode.value) {
            clearSearch();
        }
        clearSelection();
        breadcrumb.goTo(index);
    }

    // 刷新当前视图
    function refresh() {
        if (isSearchMode.value) {
            refetchSearch();
        } else {
            refetchBrowse();
            queryClient.invalidateQueries({ queryKey: ['api', 'files'] });
        }
    }

    // ============================================================
    // 搜索方法
    // ============================================================
    function search(keyword: string, page?: number, size?: number) {
        searchKeyword.value = keyword.trim();
        if (page !== undefined) searchPage.value = page;
        if (size !== undefined) searchSize.value = size;
        // 清空选中
        clearSelection();
        // 如果有关键词，自动触发搜索（由 enabled 控制）
        if (isSearchMode.value) {
            refetchSearch();
        } else {
            // 如果 keyword 为空，自动回到浏览模式
            refresh();
        }
    }

    function clearSearch() {
        searchKeyword.value = '';
        searchPage.value = 1;
        // 回到浏览模式，刷新列表
        refresh();
    }

    // 搜索分页变更
    function setSearchPage(page: number) {
        searchPage.value = page;
        if (isSearchMode.value) {
            refetchSearch();
        }
    }

    // ============================================================
    // 选择操作
    // ============================================================
    const selection = useSelectionForList(
        sortedRawFiles,
        (item) => item.type
    );

    function clearSelection() {
        selection.clear();
    }

    // ============================================================
    // 数据变更（创建文件夹、删除、下载、重命名）
    // ============================================================
    const createFolderMutation = useCreateFolder({
        mutation: {
            onError: (err: any) => {
                ElMessage.error(err?.message || '创建文件夹失败');
            },
        },
    });

    async function createFolder(name: string) {
        if (isSearchMode.value) {
            ElMessage.warning('在搜索模式下不能创建文件夹，请退出搜索');
            return;
        }
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
            id: item.id!,
            type: item.type!,
        }));

        await deleteMutation.mutateAsync({ data: { items: identities } });
        selection.clear();
        ElMessage.success(`成功删除 ${items.length} 项`);
        refresh();
        queryClient.invalidateQueries({ queryKey: QUERY_KEYS.recycle });
    }

    async function downloadFilesV2(ids: number[]) {
        if (!ids.length) return;
        for (const id of ids) {
            try {
                const request = { virtualFileId: id };
                const result = await generateDownloadUrl(request);
                if (result) {
                    window.open(result.data, '_blank');
                } else {
                    ElMessage.error(`下载文件 ${id} 失败: 未知错误`);
                }
            } catch (err) {
                ElMessage.error(`获取文件 ${id} 下载链接失败`);
            }
        }
    }

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
        };
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
        totalCount,          // 新增：总条数（用于分页）
        isSearchMode,        // 是否处于搜索模式

        // 选择相关
        selectedCount: selection.selectedCount,
        clearSelection,
        updateSelect: selection.select,
        selectedList: selection.selectedList,

        // 导航与排序
        navigateTo,
        goToBreadcrumb,
        refresh,

        // 搜索相关
        search,              // 执行搜索
        clearSearch,         // 清空搜索，返回浏览模式
        setSearchPage,       // 分页跳转
        searchKeyword,       // 当前关键词（只读）
        searchPage,          // 当前页码
        searchSize,          // 每页条数

        // 数据变更
        createFolder,
        deleteFiles,
        downloadFilesV2,
        renameFile
    };
}
export type UseFileExplorerReturn = ReturnType<typeof useFileExplorer>;