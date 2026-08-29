import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { ElMessage } from 'element-plus';
import { useListSessions, deleteSession as deleteSessionApi } from '@/api/history-controller';
import type { ChatSession as session, CursorPageResultChatSession } from '@/api/models/';


export interface ChatSession extends session {
    isTemp?: boolean;
}

export const useChatSessionStore = defineStore('chatSession', () => {
    // ===== State =====
    const sessions = ref<ChatSession[]>([]);
    const currentSessionId = ref<string | null>(null);
    const cursor = ref<string | undefined>(undefined);
    const hasNext = ref(true);
    const loadingNext = ref(false);

    // ===== API 配置 =====
    const sessionsParams = computed(() => ({ cursor: cursor.value }));
    const { refetch: refetchSessions } = useListSessions<CursorPageResultChatSession, undefined>(
        sessionsParams,
        {
            query: {
                enabled: false,
                select: (result) => {
                    if (result.code === 1) return result.data!;
                    ElMessage.error(result.msg || '获取会话列表失败');
                    return { list: [], cursor: undefined, hasNext: false } as CursorPageResultChatSession;
                },
            },
        }
    );

    // ===== Actions =====
    // 加载更多会话（分页）
    const loadMoreSessions = async () => {
        if (loadingNext.value || !hasNext.value) return;
        loadingNext.value = true;
        try {
            const { data } = await refetchSessions();
            if (data) {
                sessions.value = [...sessions.value, ...(data.list || [])];
                hasNext.value = data.hasNext ?? false;
                cursor.value = data.nextCursor;
            }
        } catch (error) {
            ElMessage.error('加载会话列表失败');
            console.error(error);
        } finally {
            loadingNext.value = false;
        }
    };

    // 切换会话
    const switchSession = (sessionId: string) => {
        if (sessionId === currentSessionId.value) return;
        currentSessionId.value = sessionId;
    };

    // 新建会话（仅清空当前选中）
    const newSession = async () => {
        currentSessionId.value = null;
    };

    function createTemporarySession(): string {
        const tempId = `temp-${Date.now()}`;
        sessions.value.unshift({ sessionId: tempId, title: '新对话', isTemp: true });
        currentSessionId.value = tempId;
        return tempId;
    }
    function replaceTemporarySession(tempId: string, realId: string) {
        const idx = sessions.value.findIndex(s => s.sessionId === tempId);
        if (idx !== -1) {
            sessions.value[idx] = { sessionId: realId, title: sessions.value[idx]!.title, isTemp: false };
        } else {
            // 如果找不到（极端情况），添加新会话
            sessions.value.unshift({ sessionId: realId, title: '新对话', isTemp: false });
        }
        if (currentSessionId.value === tempId) {
            currentSessionId.value = realId;
        }
    }

    function isTemporarySession(id: string): boolean {
        return sessions.value.some(s => s.sessionId === id && s.isTemp);
    }

    // 删除会话（模拟，实际需调用删除 API）
    const deleteSession = async (sessionId: string) => {
        deleteSessionApi({ sessionId })
        // 调用删除 API 后更新列表
        sessions.value = sessions.value.filter((s) => s.sessionId !== sessionId);
        if (currentSessionId.value === sessionId) {
            const first = sessions.value[0];
            currentSessionId.value = first ? first.sessionId! : null;
        }
    };

    // 供消息 store 调用的方法：添加新会话到列表（用于 SSE 创建会话时）
    const addSession = (session: ChatSession) => {
        if (!sessions.value.some((s) => s.sessionId === session.sessionId)) {
            sessions.value = [session, ...sessions.value];
        }
    };

    return {
        sessions,
        currentSessionId,
        hasNext,
        loadingNext,
        loadMoreSessions,
        switchSession,
        newSession,
        deleteSession,
        addSession,


        isTemporarySession,
        replaceTemporarySession,
        createTemporarySession
    };
});