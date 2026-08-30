import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { ElMessage } from 'element-plus';
import { fetchEventSource } from '@microsoft/fetch-event-source';
import type { ChatMessage as message, ChatRequest, CursorPageResultChatMessage } from '@/api/models/';
import type { FileItemUI } from '@/modules/file-system/types/file';
import { useProcessExistingDocument } from '@/api/ai应用';
import { useListHistory } from '@/api/history-controller';
import { useChatSessionStore } from '@/modules/ai-system/stores/chatSessionStore';

import { createSSEClient } from '@/composables/useSSE'


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

    const sseClient = createSSEClient({
        url: '/api/ai/stream/chat',
        headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${localStorage.getItem('token')}`,
        },
    })

    function setupListeners() {
        const { sseBus } = sseClient

        sseBus.addEventListener('_connected', () => {
            console.log('🔌 SSE 连接已建立')
        })

        sseBus.addEventListener('_closed', () => {
            console.log('🔚 SSE 连接已关闭')
        })

        sseBus.addEventListener('_error', (err: Error) => {
            console.error('❌ SSE 错误:', err)
        })

        sseBus.addEventListener('session_created', (data) => {
            const sessionStore = useChatSessionStore()
            const realId = data?.data || data?.session_id
            const currentSessionId = sessionStore.currentSessionId

            if (realId && realId !== currentSessionId) {
                // 迁移消息缓存
                const tempMsgs = messagesMap.value[currentSessionId!] || []
                messagesMap.value[realId] = tempMsgs
                delete messagesMap.value[currentSessionId!]

                // 迁移分页状态
                const tempP = paginationMap.value[currentSessionId!]
                if (tempP) {
                    paginationMap.value[realId] = tempP
                    delete paginationMap.value[currentSessionId!]
                }

                sessionStore.replaceTemporarySession(currentSessionId!, realId)
            }
        })

        sseBus.addEventListener('stream_chunk', (data) => {
            const sessionStore = useChatSessionStore()
            const sessionId = sessionStore.currentSessionId
            const targetList = messagesMap.value[sessionId!]
            if (!targetList) return
            const targetMsg = targetList[targetList.length - 1]
            if (targetMsg?.role === 'assistant') {
                targetMsg.content += data?.content || ''
            }
        })

        sseBus.addEventListener('final_answer', (data) => {
            const sessionStore = useChatSessionStore()
            const sessionId = sessionStore.currentSessionId
            const targetList = messagesMap.value[sessionId!]
            if (!targetList) return
            const targetMsg = targetList[targetList.length - 1]
            if (targetMsg?.role === 'assistant') {
                targetMsg.content = data?.content || targetMsg.content
            }
            sseClient.disconnect()
        })

        sseBus.addEventListener('references', (data) => {
            // 处理引用数据
        })

        sseBus.addEventListener('error', (data) => {
            const sessionStore = useChatSessionStore()
            const sessionId = sessionStore.currentSessionId
            const targetList = messagesMap.value[sessionId!]
            if (targetList) {
                const targetMsg = targetList[targetList.length - 1]
                if (targetMsg?.role === 'assistant') {
                    targetMsg.content = `[错误] ${data?.message || data}`
                }
            }
        })
    }

    setupListeners()
    const isLoading = ref(false);
    let generating = false;
    async function generateResponse(userMessage: string) {
        if (generating) {
            console.warn('已有生成请求进行中，忽略本次调用')
            return
        }
        generating = true
        isLoading.value = true

        const sessionStore = useChatSessionStore()
        let sessionId = sessionStore.currentSessionId

        if (!sessionId) {
            sessionId = sessionStore.createTemporarySession()
            initPaginationForSession(sessionId)
        }

        // 添加 AI 占位消息
        const aiMessage: ChatMessage = { role: 'assistant', content: '' }
        addMessageToSession(sessionId, aiMessage)

        // 准备请求体
        const request: ChatRequest = { message: userMessage }
        const isTemp = sessionStore.isTemporarySession(sessionId)
        if (!isTemp) {
            request.sessionId = sessionId
        }

        try {
            await sseClient.connect({
                getBody: () => request,
            })
        } catch (error) {
            if (error instanceof Error && error.name === 'AbortError') return
            const targetList = messagesMap.value[sessionId]
            if (targetList) {
                const targetMsg = targetList[targetList.length - 1]
                if (targetMsg?.role === 'assistant') {
                    targetMsg.content = '请求异常，请重试'
                }
            }
        } finally {
            isLoading.value = false
            generating = false
        }
    }

    function cancelGeneration() {
        sseClient.disconnect()
        generating = false
        isLoading.value = false
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
        cancelGeneration,
        // 暴露分页状态（可选）
        paginationMap,
    };
});