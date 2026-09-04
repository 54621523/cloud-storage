// utils/shareToken.ts
import { reactive } from 'vue';

const STORAGE_PREFIX = 'share_token_';

// 响应式缓存，存储所有 shareCode 对应的 token
const tokenCache = reactive<Map<string, string>>(new Map());

export function useShareToken() {
    // 获取某个分享码对应的 Token（优先从响应式 cache 读）
    const getToken = (shareCode: string): string | null => {
        // 1. 先从响应式 cache 取
        if (tokenCache.has(shareCode)) {
            return tokenCache.get(shareCode)!;
        }
        // 2. cache 中没有，尝试从 sessionStorage 恢复
        const stored = sessionStorage.getItem(`${STORAGE_PREFIX}${shareCode}`);
        if (stored) {
            tokenCache.set(shareCode, stored); // 写入响应式 cache
            return stored;
        }
        return null;
    };

    // 存储某个分享码对应的 Token
    const setToken = (shareCode: string, token: string | null) => {
        if (token) {
            // 同时写入 sessionStorage 和响应式 cache
            sessionStorage.setItem(`${STORAGE_PREFIX}${shareCode}`, token);
            tokenCache.set(shareCode, token);
        } else {
            // 删除
            sessionStorage.removeItem(`${STORAGE_PREFIX}${shareCode}`);
            tokenCache.delete(shareCode);
        }
    };

    // 清除某个分享码的 Token
    const clearToken = (shareCode: string) => {
        sessionStorage.removeItem(`${STORAGE_PREFIX}${shareCode}`);
        tokenCache.delete(shareCode);
    };

    // 可选：获取当前分享码的验证状态（直接由 computed 使用）
    // 但可以返回响应式缓存本身，让外部 computed 依赖
    return {
        getToken,
        setToken,
        clearToken,
        tokenCache, // 暴露响应式缓存，供 computed 依赖
    };
}