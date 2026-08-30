type EventCallback<T = any> = (data: T) => void

export class SSEEventBus {
    private listeners = new Map<string, Set<EventCallback>>()

    /** 注册事件监听器，返回一个取消函数 */
    addEventListener<T = any>(event: string, callback: EventCallback<T>): () => void {
        if (!this.listeners.has(event)) {
            this.listeners.set(event, new Set())
        }
        this.listeners.get(event)!.add(callback as EventCallback)
        return () => this.removeEventListener(event, callback)
    }

    /** 移除指定事件的一个监听器 */
    removeEventListener<T = any>(event: string, callback: EventCallback<T>): void {
        this.listeners.get(event)?.delete(callback as EventCallback)
    }

    /** 分发事件 */
    dispatchEvent(event: string, data: any): void {
        this.listeners.get(event)?.forEach(cb => cb(data))
    }

    /** 检查某个事件是否有注册的监听器 */
    hasListener(event: string): boolean {
        return this.listeners.has(event) && this.listeners.get(event)!.size > 0
    }

    /** 移除某个事件的所有监听器，不传参则清空全部 */
    removeAllListeners(event?: string): void {
        if (event) {
            this.listeners.delete(event)
        } else {
            this.listeners.clear()
        }
    }
}

export const globalSSEBus = new SSEEventBus()