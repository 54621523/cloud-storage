// utils/shareToken.ts
const STORAGE_PREFIX = 'share_token_';

export function useShareToken() {
    // 获取某个分享码对应的 Token
    const getToken = (shareCode: string): string | null => {
        return sessionStorage.getItem(`${STORAGE_PREFIX}${shareCode}`);
    };

    // 存储某个分享码对应的 Token
    const setToken = (shareCode: string, token: string | null) => {
        if (token) {
            sessionStorage.setItem(`${STORAGE_PREFIX}${shareCode}`, token);
        } else {
            sessionStorage.removeItem(`${STORAGE_PREFIX}${shareCode}`);
        }
    };

    // 清除某个分享码的 Token
    const clearToken = (shareCode: string) => {
        sessionStorage.removeItem(`${STORAGE_PREFIX}${shareCode}`);
    };

    return {
        getToken,
        setToken,
        clearToken,
    };
}