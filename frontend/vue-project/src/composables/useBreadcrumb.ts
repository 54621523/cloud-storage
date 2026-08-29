// composables/useBreadcrumb.ts
import { ref, computed, readonly } from 'vue';

export interface BreadcrumbNode {
    id: number;
    name: string;
    meta?: Record<string, any>;
}

export function useBreadcrumb(initialNode: BreadcrumbNode) {
    const stack = ref<BreadcrumbNode[]>([initialNode]);

    const current = computed(() => stack.value.at(-1));
    const currentParentId = computed(() => current.value?.id ?? 0);

    function push(id: number, name: string, meta?: Record<string, any>) {
        const existIndex = stack.value.findIndex(item => item.id === id);
        if (existIndex !== -1) {
            stack.value = stack.value.slice(0, existIndex + 1);
        } else {
            stack.value.push({ id, name, meta });
        }
    }

    function goTo(index: number) {
        if (index < 0 || index >= stack.value.length - 1) return;
        stack.value = stack.value.slice(0, index + 1);
    }

    function reset(node: BreadcrumbNode) {
        stack.value = [node];
    }

    function updateCurrentName(newName: string) {
        const last = stack.value.at(-1);
        if (last) {
            last.name = newName;
        }
    }

    return {
        pathStack: readonly(stack),
        current: readonly(current),
        currentParentId: readonly(currentParentId),
        push,
        goTo,
        reset,
        updateCurrentName,
    };
}