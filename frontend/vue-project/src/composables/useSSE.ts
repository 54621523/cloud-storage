import { ref, onBeforeUnmount } from 'vue'
import { fetchEventSource } from '@microsoft/fetch-event-source'
import { SSEEventBus } from '@/utils/SSEEventBus'

export interface UseSSEOptions {
    url: string
    method?: string
    headers?: Record<string, string>
    getBody?: () => any
    openWhenHidden?: boolean
    onOpen?: (response: Response) => void
    onClose?: () => void
    onError?: (error: Error) => void
}

/** 纯连接层：创建 SSE 客户端（无生命周期依赖，可在 store 中使用） */
export function createSSEClient(options: UseSSEOptions) {
    const sseBus = new SSEEventBus()
    const isConnected = ref(false)
    const isConnecting = ref(false)
    let abortController: AbortController | null = null

    async function connect(overrides?: Partial<UseSSEOptions>) {
        const merged = { ...options, ...overrides }

        if (abortController) abortController.abort()
        abortController = new AbortController()
        isConnecting.value = true

        try {
            await fetchEventSource(merged.url, {
                method: merged.method || 'POST',
                headers: merged.headers,
                body: merged.getBody ? JSON.stringify(merged.getBody()) : undefined,
                signal: abortController.signal,
                openWhenHidden: merged.openWhenHidden ?? true,

                onopen: async (response) => {
                    isConnecting.value = false
                    isConnected.value = true
                    if (!response.ok) {
                        const body = await response.text()
                        throw new Error(`HTTP ${response.status}: ${body || response.statusText}`)
                    }
                    sseBus.dispatchEvent('_connected', { status: response.status })
                    merged.onOpen?.(response)
                },

                onmessage: (event) => {
                    const eventName = event.event || 'message'
                    let parsedData = event.data
                    try {
                        if (event.data && typeof event.data === 'string') {
                            parsedData = JSON.parse(event.data)
                        }
                    } catch { /* 非 JSON 保持原样 */ }

                    if (sseBus.hasListener(eventName)) {
                        sseBus.dispatchEvent(eventName, parsedData)
                    } else {
                        sseBus.dispatchEvent('unknown', { event: eventName, data: parsedData })
                    }
                },

                onerror: (error) => {
                    if (abortController?.signal.aborted) return
                    const err = error instanceof Error ? error : new Error(String(error))
                    sseBus.dispatchEvent('_error', err)
                    merged.onError?.(err)
                    throw err // 终止自动重连
                },

                onclose: () => {
                    isConnected.value = false
                    sseBus.dispatchEvent('_closed', null)
                    merged.onClose?.()
                },
            })
        } catch (error: any) {
            if (error?.name === 'AbortError' || abortController?.signal.aborted) {
                sseBus.dispatchEvent('_aborted', null)
            } else {
                sseBus.dispatchEvent('_error', error)
            }
        } finally {
            isConnecting.value = false
            isConnected.value = false
            abortController = null
        }
    }

    function disconnect() {
        if (abortController) {
            abortController.abort()
            abortController = null
            isConnected.value = false
            isConnecting.value = false
        }
    }

    return { sseBus, isConnected, isConnecting, connect, disconnect }
}

/** 组件层：带生命周期自动清理（供组件使用） */
export function useSSE(options: UseSSEOptions) {
    const client = createSSEClient(options)
    onBeforeUnmount(() => {
        client.disconnect()
        client.sseBus.removeAllListeners()
    })
    return client
}