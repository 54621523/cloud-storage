// src/types/share.ts（仅保留 UI 相关类型）

import type { ShareLinkVO } from '@/api/models'
import { ShareStatus } from '@/api/models';
export type { ShareLinkVO };
export { ShareStatus }


export interface ShareItemUI extends ShareLinkVO {
}