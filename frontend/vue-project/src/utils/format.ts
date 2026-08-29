import { dayjs } from 'element-plus'

export function formatFileSize(bytes: number): string {
    if (!bytes || bytes === 0) return '—';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
};

/**
 * 格式化日期，若值为 null/undefined/空 则返回空字符串
 * @param dateValue - 后端返回的日期 (如 "2026-08-03T17:13:32" 或 Date 对象)
 * @param formatStr - 目标格式，默认 'YYYY-MM-DD HH:mm'
 * @returns 格式化后的字符串或 ''
 */
export function formatDate(
    dateValue: string | Date | null | undefined,
    formatStr: string = 'YYYY-MM-DD HH:mm'
): string {
    // 1. 判空：若为 null/undefined/空字符串，直接返回 ''
    if (dateValue === null || dateValue === undefined || dateValue === '') {
        return '';
    }

    // 2. 使用 dayjs 解析
    const d = dayjs(dateValue);

    // 3. 校验是否有效
    if (!d.isValid()) {
        return ''; // 无效日期也不显示
    }

    // 4. 返回格式化结果
    return d.format(formatStr);
}
