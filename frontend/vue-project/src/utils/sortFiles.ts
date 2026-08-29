// utils/sortFiles.ts
import type { VirtualFileVO, SortField, SortOrder } from '@/modules/file-system/types/file';
import { FileItemType } from '@/modules/file-system/types/file';

/**
 * 对文件列表进行排序（纯函数）
 * @param list 原始文件列表（不会修改原数组）
 * @param field 排序字段
 * @param order 排序顺序
 * @param foldersFirst 是否将文件夹始终置顶（默认 true）
 * @returns 排序后的新数组
 */
export function sortFiles(
    list: VirtualFileVO[],
    field: SortField,
    order: SortOrder,
    foldersFirst: boolean = true
): VirtualFileVO[] {
    if (!list.length) return [];

    // 防御性：确保 field 有效，若无效默认按 name 排序
    const validFields: SortField[] = ['name', 'size', 'updateTime'];
    const sortField = validFields.includes(field) ? field : 'name';

    // 复制数组，避免修改原数组
    const sorted = [...list];

    sorted.sort((a, b) => {
        // 文件夹优先（如果启用）
        if (foldersFirst) {
            const aIsFolder = a.type === FileItemType.FOLDER;
            const bIsFolder = b.type === FileItemType.FOLDER;
            if (aIsFolder && !bIsFolder) return -1;
            if (!aIsFolder && bIsFolder) return 1;
        }

        // 获取比较值
        let valA = a[sortField] ?? '';
        let valB = b[sortField] ?? '';

        // 统一转为字符串或数字比较
        if (typeof valA === 'string' && typeof valB === 'string') {
            // 不区分大小写的中文友好比较
            const compare = valA.localeCompare(valB, undefined, { sensitivity: 'base' });
            return order === 'asc' ? compare : -compare;
        } else {
            // 数字比较
            const numA = Number(valA);
            const numB = Number(valB);
            if (isNaN(numA) || isNaN(numB)) {
                // 如果无法转为数字，按字符串处理
                const strA = String(valA);
                const strB = String(valB);
                const compare = strA.localeCompare(strB);
                return order === 'asc' ? compare : -compare;
            }
            return order === 'asc' ? numA - numB : numB - numA;
        }
    });

    return sorted;
}