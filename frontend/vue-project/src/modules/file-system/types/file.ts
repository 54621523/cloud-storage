// src/types/file.ts（仅保留 UI 相关类型）
import type { VirtualFileVO, ItemIdentity } from '@/api/models';
import { FileItemType } from '@/api/models'; // 从生成模型导入

// 直接导出生成的类型
export type { VirtualFileVO, ItemIdentity };
export { FileItemType }

// 前端 UI 扩展类型（继承 VirtualFileVO）
export interface FileItemUI extends VirtualFileVO {
}


export interface PathNode {
  id: number;
  name: string;
}

export type ViewMode = 'list' | 'grid';
export type SortField = 'name' | 'size' | 'updateTime';
export type SortOrder = 'asc' | 'desc';


export type FileExt = 'pdf' | 'doc' | 'docx' | 'xls' | 'xlsx' | 'png' | 'jpg' | 'jpeg' | 'zip' | 'rar' | 'folder';