// src/api/mutator/custom-instance.ts
import Axios, { type AxiosRequestConfig } from 'axios';
import { useShareToken } from '@/utils/shareToken';
import { ElMessage } from 'element-plus';
import router from '@/router';

// 创建 Axios 实例
export const AXIOS_INSTANCE = Axios.create();

// 请求拦截器：统一添加 Header
AXIOS_INSTANCE.interceptors.request.use((config) => {
    // 1. 用户认证 Token
    const userToken = localStorage.getItem('token');
    if (userToken) {
        config.headers['Authorization'] = 'Bearer ' + userToken;
    }

    // 2. 分享 Token（从自定义请求头中读取 shareCode）
    const shareCode = config.headers['X-Share-Code'];
    if (shareCode) {
        const { getToken } = useShareToken();
        const token = getToken(shareCode);
        if (token) {
            config.headers['Share-Token'] = token;
        }
        // 删除自定义头，避免发送给后端（后端不认识该字段）
        delete config.headers['X-Share-Code'];
    }

    return config;
});

// 响应拦截器（可选）
AXIOS_INSTANCE.interceptors.response.use(
    (response) => response,
    (error) => {
        if (error.response?.status === 401) {
            const config = error.config;
            // 从失败的请求中提取 shareCode，单独清除它的 Token
            let shareCode = config?.params?.shareCode || config?.data?.shareCode;
            if (shareCode) {
                const { clearToken } = useShareToken();
                clearToken(shareCode);
                ElMessage.warning(`分享 "${shareCode}" 验证已过期，请重新输入提取码`);
                // 可选：跳转到验证页
                if (router.currentRoute.value.params.shareCode === shareCode) {
                    // 停留在当前页，让用户重新验证
                }
            } else {
                ElMessage.error('认证失败，请重新登录');
            }
        }
        return Promise.reject(error);
    }
);

// 包装函数，供 Orval 使用
export const customInstance = <T>(
    config: AxiosRequestConfig,
    options?: AxiosRequestConfig
): Promise<T> => {

    if (config.data instanceof FormData) {
        const requestVal = config.data.get('request');

        // 如果 request 字段存在，且被 Orval 错误地序列化成了纯字符串
        if (typeof requestVal === 'string') {
            // 1. 删除旧的纯字符串
            config.data.delete('request');

            // 2. 重新包装为带有 application/json 类型的 Blob 对象
            const jsonBlob = new Blob([requestVal], { type: 'application/json' });

            // 3. 重新追加到 FormData 中
            config.data.append('request', jsonBlob);
        }
    }

    return AXIOS_INSTANCE({
        ...config,
        ...options,
    }).then(({ data }) => data);
};