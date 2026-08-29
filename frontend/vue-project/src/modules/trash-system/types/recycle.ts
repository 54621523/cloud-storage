// src/types/file.ts（仅保留 UI 相关类型）
import type { RecycleFileVO, ItemIdentity } from '@/api/models';
import { FileItemType } from '@/api/models'; // 从生成模型导入

// 直接导出生成的类型
export type { RecycleFileVO, ItemIdentity };
export { FileItemType }

// 前端 UI 扩展类型（继承 VirtualFileVO）
export interface RecycleItemUI extends RecycleFileVO {
}