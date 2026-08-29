// src/composables/useSelectionForList.ts
import { computed, ref, type ComputedRef, type Ref } from 'vue';
import type { FileItemType } from '@/modules/file-system/types/file';

// 内部使用的选中项类型（可独立定义，也可复用原来的类型）
export interface SelectedItem {
    id: number;
    type?: FileItemType;
}

/**
 * 为列表创建选中状态管理，兼容 id 为 undefined 的情况
 * @param list - 响应式列表，元素需包含 id（可为 undefined）
 * @param getType - 可选，从元素提取 type 字段
 */
export function useSelectionForList<T extends { id?: number }>(
    list: Ref<T[]> | ComputedRef<T[]>,
    getType?: (item: T) => any
) {
    // ---------- 内联的 useSelection 逻辑 ----------
    const selectedMap = ref<Map<number, SelectedItem>>(new Map());

    // 判断是否选中（基础方法）
    const isSelected = (id: number): boolean => selectedMap.value.has(id);

    // 切换单个选中（基础方法）
    const toggleBase = (item: SelectedItem) => {
        const map = selectedMap.value;
        if (map.has(item.id)) {
            map.delete(item.id);
        } else {
            map.set(item.id, item);
        }
        selectedMap.value = new Map(map); // 触发响应式更新
    };

    // 全量替换选中（基础方法）
    const setSelected = (items: SelectedItem[]) => {
        const newMap = new Map<number, SelectedItem>();
        items.forEach(item => newMap.set(item.id, item));
        selectedMap.value = newMap;
    };

    // 清空选中（基础方法）
    const clearBase = () => {
        selectedMap.value = new Map();
    };

    // 全选（基础方法，和 setSelected 一样）
    const selectAllBase = (items: SelectedItem[]) => {
        setSelected(items);
    };
    // ---------- 内联结束 ----------

    // 过滤出有效 id 的项（类型收窄为 id: number）
    const validItems = computed(() =>
        list.value.filter((item): item is T & { id: number } => item.id != null)
    );

    // 当前列表中实际被选中的项（返回完整 T[]）
    const selectedList = computed<T[]>(() =>
        validItems.value.filter(item => isSelected(item.id))
    );

    // 选中数量（仅当前列表的有效项）
    const selectedCount = computed(() => selectedList.value.length);

    // 是否全选（基于有效项）
    const isAllSelected = computed(() => {
        const items = validItems.value;
        return items.length > 0 && items.every(item => isSelected(item.id));
    });

    // 切换单个选中（若 id 无效则忽略）
    function toggle(id: number | undefined) {
        if (id == null) return;
        const item = validItems.value.find(i => i.id === id);
        if (!item) return;
        const selectedItem: SelectedItem = { id: item.id };
        if (getType) selectedItem.type = getType(item);
        toggleBase(selectedItem);
    }

    // 全选（基于当前列表的有效项）
    function selectAll() {
        const items = validItems.value.map(item => {
            const si: SelectedItem = { id: item.id };
            if (getType) si.type = getType(item);
            return si;
        });
        selectAllBase(items);
    }

    // 清空选中
    function clear() {
        clearBase();
    }

    // 切换全选/取消全选
    function toggleAll() {
        if (isAllSelected.value) {
            clear();
        } else {
            selectAll();
        }
    }

    // 手动选中指定 id 列表（仅存在于当前列表中的项才被选中）
    function select(ids?: number[]) {
        // 无参数或空数组时清空
        if (!ids || ids.length === 0) {
            clear();
            return;
        }
        const items = validItems.value
            .filter(item => ids.includes(item.id))
            .map(item => {
                const si: SelectedItem = { id: item.id };
                if (getType) si.type = getType(item);
                return si;
            });
        setSelected(items);
    }

    // 返回所有公开 API
    return {
        selectedList,
        selectedCount,
        isAllSelected,
        isSelected,
        toggle,
        toggleAll,
        selectAll,
        clear,
        select,
    };
}