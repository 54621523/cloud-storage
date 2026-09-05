// src/composables/useSharedView.ts
import { ref, computed } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import type { FileItemUI } from '@/modules/file-system/types/file';
import { FileItemType } from '@/modules/file-system/types/file';
import { keepPreviousData } from '@tanstack/vue-query';
import {
    useVerifySharedFile,
    verifySharedFile,
    useGetShareInfo,
    useSaveSharedFile,
    downloadSharedFile,
} from '@/api/分享模块';
import type { ItemIdentity, ResultListVirtualFileVO, TransferRequest, VirtualFileVO } from '@/api/models';
import { useSelectionForList } from '@/composables/useSelectionForList';
import { useShareToken } from '@/utils/shareToken';
import { getErrorMessage } from '@/utils/errorHandler';

export function useSharedView() {
    const rootId = ref<number>(0);          // 0 表示分享根（初始视图）
    const currentParentId = ref<number>(0); // 当前浏览目录
    const pathStack = ref<{ id: number; name: string }[]>([
        { id: 0, name: '分享文件' }          // 虚拟根，用于返回初始视图
    ]);
    const route = useRoute();
    const { getToken, setToken, clearToken, tokenCache } = useShareToken();

    // ---------- 当前分享码 ----------
    const currentShareCode = computed(() => route.params.shareCode as string || '');

    // ---------- 验证状态（直接从存储中读取） ----------
    const isVerified = computed(() => {
        if (!currentShareCode.value) return false;
        // 直接从响应式 cache 中取，无需调用 getToken（但 getToken 内部也会读 cache）
        // 为了保持一致性，仍可调用 getToken，它会从 cache 读
        return !!tokenCache.get(currentShareCode.value);
    });


    // ---------- 验证密码 ----------
    const verifyPwdMutation = useVerifySharedFile({
        mutation: {
            onSuccess: (data) => {
                const token = data?.data;
                if (token) {
                    setToken(currentShareCode.value, token);
                    ElMessage.success('验证成功');
                } else {
                    // 无密码分享，可能不返回 token，但视为已验证
                    // 可以设置一个占位符表示已通过
                    setToken(currentShareCode.value, 'anonymous');
                }
            },
            onError: (err) => {
                ElMessage.error(getErrorMessage(err) || '提取码错误');
                clearToken(currentShareCode.value); // 清除无效 token
            },
        },
    });

    async function verifyPassword(pwd: string) {
        await verifyPwdMutation.mutateAsync({
            shareCode: currentShareCode.value,
            params: { password: pwd },
        });
    }

    // ---------- 请求参数（自动响应 rootId / currentParentId 变化） ----------
    const detailParams = computed(() => ({
        rootId: rootId.value,
        parentId: currentParentId.value,
    }));

    // ---------- 获取文件列表 ----------
    const { data: rawResult, isLoading, refetch } = useGetShareInfo<VirtualFileVO[]>(
        detailParams,
        {
            query: {
                enabled: isVerified, // 只有验证通过才请求
                select: (result) => {
                    if (result.code === 1) return result.data!;
                    ElMessage.error(result.msg || '获取分享详情失败');
                    return [];
                },
                staleTime: 5 * 60 * 1000,
                placeholderData: keepPreviousData,
            },
            request: {
                headers: {
                    'X-Share-Code': currentShareCode.value,
                },
            },
        }
    );

    const rawFileList = computed<VirtualFileVO[]>(() => {
        const data = rawResult.value;
        return Array.isArray(data) ? data : [];
    });

    // ---------- 选择管理 ----------
    const selection = useSelectionForList(rawFileList);

    const shareFileList = computed<FileItemUI[]>(() =>
        rawFileList.value.map(item => ({ ...item }))
    );

    const selectedList = selection.selectedList

    function clearSelection() {
        selection.clear();
    }

    // ---------- 面包屑导航 ----------


    /** 进入文件夹 */
    function navigateTo(file: FileItemUI) {
        if (file.type !== FileItemType.FOLDER || !file.id) return;
        clearSelection();

        if (rootId.value === 0 && currentParentId.value === 0) {
            // 从初始视图进入第一个文件夹 → 设为根
            rootId.value = file.id;
            currentParentId.value = file.id;
            pathStack.value = [
                { id: 0, name: '分享文件' },
                { id: file.id, name: file.name || '未命名' }
            ];
        } else {
            // 在已有根下进入子文件夹
            currentParentId.value = file.id;
            pathStack.value.push({ id: file.id, name: file.name || '未命名' });
        }
    }

    /** 面包屑跳转 */
    function goToBreadcrumb(index: number) {
        if (index < 0 || index >= pathStack.value.length) return;
        clearSelection();

        if (index === 0) {
            // 回到初始视图
            rootId.value = 0;
            currentParentId.value = 0;
            pathStack.value = [{ id: 0, name: '分享文件' }];
        } else {
            // 截断路径栈到目标层级
            pathStack.value = pathStack.value.slice(0, index + 1);
            const target = pathStack.value[index];
            // 防御性检查（尽管不会发生）
            if (!target) {
                // 异常降级
                rootId.value = 0;
                currentParentId.value = 0;
                pathStack.value = [{ id: 0, name: '分享文件' }];
                return;
            }
            currentParentId.value = target.id;
            // rootId 保持不变，因为根文件夹（pathStack[1]）没有改变
        }
    }

    // ---------- 下载 ----------
    async function downloadFile(fileId: number) {
        const downloadParams = {
            id: fileId,
            rootId: rootId.value
        };
        const result = await downloadSharedFile(
            downloadParams,
            {
                headers: {
                    'X-Share-Code': currentShareCode.value,
                },
            }
        );
        if (result?.data) {
            window.open(result.data, '_blank');
        }
    }

    // ---------- 转存 ----------
    const saveShareMutation = useSaveSharedFile({
        mutation: {
            onSuccess: () => {
                ElMessage.success('转存成功');
                selection.clear();
            },
            onError: (err) => ElMessage.error(getErrorMessage(err) || '转存失败'),
        },
        request: {
            headers: {
                'X-Share-Code': currentShareCode.value,
            },
        },
    });

    async function saveToMyDisk(files: FileItemUI | FileItemUI[], targetId: number) {
        if (!files) {
            ElMessage.warning('请选择要转存的项');
            return;
        }
        const items = Array.isArray(files) ? files : [files];
        const identities = items.map(f => ({ id: f.id!, type: f.type! }));
        const request = {
            targetFolderId: targetId,
            items: identities,
            rootId: rootId.value,
        };
        await saveShareMutation.mutateAsync({
            data: request
        });
    }

    // ---------- 导出 ----------
    return {
        shareFileList,
        isLoading,
        selectedCount: selection.selectedCount,
        isAllSelected: selection.isAllSelected,
        toggleSelect: selection.toggle,
        toggleSelectAll: selection.toggleAll,
        clearSelection: selection.clear,
        updateSelect: selection.select,
        selectedList,
        verifyPassword,
        isVerifying: verifyPwdMutation.isPending,
        isVerified,
        downloadFile,
        saveToMyDisk,
        isSaving: saveShareMutation.isPending,
        // 面包屑导航
        pathStack,
        navigateTo,
        goToBreadcrumb,
    };
}