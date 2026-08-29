// stores/shareUIStore.ts
import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import type { PathNode } from '@/modules/file-system/types/file';

export const useShareUIStore = defineStore('shareUI', () => {
    const shareCode = ref<string>('');
    const shareToken = ref<string | null>(null);
    const currentPath = ref<PathNode[]>([{ id: 0, name: '根目录' }]);
    const needPassword = ref<boolean>(false);

    const currentParentId = computed(() =>
        currentPath.value[currentPath.value.length - 1]?.id ?? 0
    );

    function setShareCode(code: string) {
        shareCode.value = code;
    }

    function setShareToken(token: string) {
        shareToken.value = token;
        localStorage.setItem(`share_token_${shareCode.value}`, token);
    }

    function clearShareToken() {
        shareToken.value = null;
        localStorage.removeItem(`share_token_${shareCode.value}`);
    }

    function navigateTo(id: number, name: string) {
        const idx = currentPath.value.findIndex(item => item.id === id);
        if (idx !== -1) {
            currentPath.value = currentPath.value.slice(0, idx + 1);
        } else {
            currentPath.value.push({ id, name });
        }
    }

    function goToBreadcrumb(index: number) {
        if (index >= currentPath.value.length - 1) return;
        currentPath.value = currentPath.value.slice(0, index + 1);
    }

    function reset() {
        shareCode.value = '';
        shareToken.value = null;
        currentPath.value = [{ id: 0, name: '根目录' }];
        needPassword.value = false;
        localStorage.removeItem(`share_token_${shareCode.value}`);
    }

    return {
        shareCode,
        shareToken,
        currentPath,
        needPassword,
        currentParentId,
        setShareCode,
        setShareToken,
        clearShareToken,
        navigateTo,
        goToBreadcrumb,
        reset,
    };
});