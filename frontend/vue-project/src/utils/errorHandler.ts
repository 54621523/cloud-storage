// src/utils/errorHandler.ts
export function getErrorMessage(err: unknown): string {
    if (err instanceof Error) {
        return err.message;
    }
    if (typeof err === 'object' && err !== null && 'message' in err) {
        return String((err as any).message);
    }
    return '未知错误';
}