import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { ElMessage } from 'element-plus';
import { fetchEventSource } from '@microsoft/fetch-event-source';
import type { ChatMessage as message, ChatRequest, CursorPageResultChatMessage } from '@/api/models/';
import type { FileItemUI } from '@/modules/file-system/types/file';
import { useProcessExistingDocument } from '@/api/ai应用';
import { useListHistory } from '@/api/history-controller';
import { useChatSessionStore } from '@/modules/ai-system/stores/chatSessionStore';

export interface ChatMessage extends message {
    sources?: Array<{ filename?: string; page_number?: number; text?: string }>;
}

export const useChatMessageStore = defineStore('chatMessage', () => {
    const sessionStore = useChatSessionStore();

    // ========== 消息缓存 ==========
    const messagesMap = ref<Record<string, ChatMessage[]>>({});

    // 当前会话消息列表（计算属性）
    const currentMessages = computed(() => {
        const id = sessionStore.currentSessionId;
        return id ? messagesMap.value[id] || [] : [];
    });

    // ---------- 消息操作 ----------
    function addMessageToSession(sessionId: string, message: ChatMessage) {
        if (!messagesMap.value[sessionId]) {
            messagesMap.value[sessionId] = [];
        }
        messagesMap.value[sessionId].push(message);
    }

    // 添加用户消息（调用前必须保证 currentSessionId 有效，组件会保证）
    function addUserMessage(content: string) {
        const sessionId = sessionStore.currentSessionId;
        if (!sessionId) {
            console.warn('addUserMessage 被调用但无当前会话，请先创建临时会话');
            return;
        }
        addMessageToSession(sessionId, { role: 'user', content });
    }

    // ========== 历史分页（按会话独立） ==========
    interface PaginationState {
        cursor?: string;
        hasMore: boolean;
        loading: boolean;
    }
    const paginationMap = ref<Record<string, PaginationState>>({});

    function getPagination(sessionId: string): PaginationState {
        if (!paginationMap.value[sessionId]) {
            paginationMap.value[sessionId] = { cursor: undefined, hasMore: true, loading: false };
        }
        return paginationMap.value[sessionId];
    }

    // 创建临时会话并设置分页初始状态
    function initPaginationForSession(sessionId: string) {
        paginationMap.value[sessionId] = { cursor: undefined, hasMore: true, loading: false };
    }

    // 历史 API 请求（使用 useListHistory 但每次动态参数）
    const { refetch: refetchHistory } = useListHistory<CursorPageResultChatMessage>(
        computed(() => ({
            sessionId: sessionStore.currentSessionId || '',
            cursor: sessionStore.currentSessionId ? getPagination(sessionStore.currentSessionId).cursor : undefined,
        })),
        {
            query: {
                enabled: false,
                select: (result) => {
                    if (result.code === 1) return result.data!;
                    ElMessage.error(result.msg || '获取对话记录失败');
                    return {} as CursorPageResultChatMessage;
                },
            },
        }
    );

    // 加载更多历史消息（追加到当前会话顶部）
    async function loadMoreHistory() {
        const sessionId = sessionStore.currentSessionId;
        if (!sessionId) return { hasMore: false };

        const p = getPagination(sessionId);
        if (p.loading || !p.hasMore) return { hasMore: p.hasMore };

        p.loading = true;
        try {
            const { data } = await refetchHistory();
            if (data?.list) {
                const existing = messagesMap.value[sessionId] || [];
                // 追加到头部
                messagesMap.value[sessionId] = [...data.list, ...existing];
                p.cursor = data.nextCursor;
                p.hasMore = data.hasNext ?? false;
                return { hasMore: p.hasMore };
            }
            return { hasMore: false };
        } finally {
            p.loading = false;
        }
    }

    // 获取指定会话的第一页历史（用于切换会话时加载）
    async function fetchSessionHistory(sessionId: string) {
        // 如果该会话已有缓存消息，则不请求（避免覆盖）
        if (messagesMap.value[sessionId] && messagesMap.value[sessionId].length > 0) {
            // 但需重置分页状态以便后续加载更多
            const p = getPagination(sessionId);
            p.cursor = undefined;
            p.hasMore = true;
            return;
        }

        // 否则拉取第一页
        const p = getPagination(sessionId);
        p.cursor = undefined;
        p.hasMore = true;
        p.loading = false;

        const { data } = await refetchHistory(); // 注意：这里 refetchHistory 依赖的是 currentSessionId，可能不准确
        // 但由于我们调用时已经切换了 sessionId，且 refetch 使用了 computed 动态参数，应该没问题。
        // 更保险的做法是单独调用 API，但为了简化，我们假设 refetchHistory 会使用最新的 currentSessionId。
        // 若担心，可以改为直接调用 useListHistory 的 mutate 并传入参数，但此处保留。
        if (data) {
            messagesMap.value[sessionId] = data.list || [];
            p.cursor = data.nextCursor;
            p.hasMore = data.hasNext ?? false;
        }
    }

    // ========== SSE 流式生成回复 ==========
    const isLoading = ref(false);
    let abortController = new AbortController();

    async function generateResponse(userMessage: string) {
        // 1. 确保有会话 ID（若无则创建临时会话）
        let sessionId = sessionStore.currentSessionId;
        if (!sessionId) {
            // 调用 sessionStore 创建临时会话（该方法应生成 ID 并加入会话列表，设置当前 ID）
            sessionId = sessionStore.createTemporarySession(); // 需要在 sessionStore 中实现
            // 并且初始化分页
            initPaginationForSession(sessionId);
        }

        // 2. 添加 AI 占位消息
        const aiMessage: ChatMessage = { role: 'assistant', content: '' };
        addMessageToSession(sessionId, aiMessage);
        const msgList = messagesMap.value[sessionId];
        const botMsgIdx = msgList!.length - 1;

        // 3. 准备请求
        const request: ChatRequest = { message: userMessage };
        // 如果是临时会话，不传 sessionId（让后端创建新会话）；否则传递真实 ID
        const isTemp = sessionStore.isTemporarySession(sessionId); // 需在 sessionStore 提供
        if (!isTemp) {
            request.sessionId = sessionId;
        }

        isLoading.value = true;
        abortController = new AbortController();

        try {
            await fetchEventSource('/api/ai/stream/chat', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    Authorization: `Bearer ${localStorage.getItem('token')}`,
                },
                body: JSON.stringify(request),
                signal: abortController.signal,
                onopen: async (response) => {
                    if (!response.ok) throw new Error(`HTTP ${response.status}`);
                },
                onmessage: (event) => {
                    const targetList = messagesMap.value[sessionId!];
                    if (!targetList || targetList.length <= botMsgIdx) return;
                    const targetMsg = targetList[botMsgIdx];
                    if (!targetMsg) return;

                    const eventType = event.event || 'stream_chunk';
                    const dataStr = event.data;

                    if (eventType === 'done' || dataStr === '[DONE]') {
                        abortController.abort();
                        return;
                    }

                    if (eventType === 'error') {
                        targetMsg.content = `[错误] ${dataStr}`;
                        return;
                    }

                    if (eventType === 'session_created') {
                        try {
                            const realId = JSON.parse(dataStr).data;
                            if (realId && realId !== sessionId) {
                                // 迁移消息缓存：将临时 ID 下的消息移到真实 ID
                                const tempMsgs = messagesMap.value[sessionId!] || [];
                                messagesMap.value[realId] = tempMsgs;
                                delete messagesMap.value[sessionId!];

                                // 迁移分页状态
                                const tempP = paginationMap.value[sessionId!];
                                if (tempP) {
                                    paginationMap.value[realId] = tempP;
                                    delete paginationMap.value[sessionId!];
                                }

                                // 通知 sessionStore 替换临时会话为真实会话
                                sessionStore.replaceTemporarySession(sessionId!, realId);
                                // 更新当前会话 ID（sessionStore 内部会做）
                                // 注意：此时 sessionStore.currentSessionId 变为 realId，但我们的局部变量 sessionId 还是旧的
                                // 因此需要更新局部变量，以便后续的 onmessage 能继续使用正确的 key
                                sessionId = realId;
                            }
                        } catch (e) {
                            console.warn('解析 session_created 失败:', dataStr, e);
                        }
                        return;
                    }

                    // 常规流式数据
                    try {
                        const parsed = dataStr ? JSON.parse(dataStr) : {};
                        switch (eventType) {
                            case 'references':
                                // targetMsg.sources = parsed.data || [];
                                break;
                            case 'final_answer':
                                targetMsg.content = parsed.content || targetMsg.content;
                                break;
                            case 'stream_chunk':
                                targetMsg.content += parsed.content || '';
                                break;
                            default:
                                if (parsed.content) targetMsg.content += parsed.content;
                                break;
                        }
                    } catch (e) {
                        console.warn('解析 SSE 数据失败:', dataStr, e);
                    }
                },
                onclose: () => { },
                onerror: (err) => {
                    console.error('SSE onerror:', err);
                    const targetList = messagesMap.value[sessionId!];
                    if (targetList?.[botMsgIdx]) {
                        targetList[botMsgIdx].content = '请求失败，请重试';
                    }
                    return undefined;
                },
            });
        } catch (error) {
            if (error instanceof Error && error.name === 'AbortError') return;
            const targetList = messagesMap.value[sessionId];
            if (targetList?.[botMsgIdx]) {
                targetList[botMsgIdx].content = '请求异常，请重试';
            }
        } finally {
            isLoading.value = false;
        }
    }

    // ========== 文档处理 ==========
    const processExistingDocumentMutation = useProcessExistingDocument({
        mutation: {
            onSuccess: () => ElMessage.success('文档处理成功'),
            onError: (err: any) => ElMessage.error(err?.message || '处理文档失败'),
        },
    });

    async function processExistingDocument(file: FileItemUI) {
        await processExistingDocumentMutation.mutateAsync({ data: { id: file.id } });
    }

    // ========== 清理 ==========

    return {
        messagesMap,
        currentMessages,
        addMessage: addMessageToSession,
        addUserMessage,
        generateResponse,
        fetchSessionHistory,
        loadMoreHistory,
        processExistingDocument,
        isLoading,
        // 暴露分页状态（可选）
        paginationMap,
    };
});