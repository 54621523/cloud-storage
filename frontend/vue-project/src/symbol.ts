import type { InjectionKey } from 'vue';
import type { UseFileExplorerReturn } from '@/modules/file-system/composables/useFileExplorer';

export const FILE_EXPLORER_KEY: InjectionKey<UseFileExplorerReturn> = Symbol('fileExplorer');


export const STORAGE_KEYS = {
    ROOT_ID: 'file_root_id',
    ROOT_NAME: 'file_root_name',
};