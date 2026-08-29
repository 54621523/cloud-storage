// src/utils/request.ts
import axios from 'axios';
import type {
  AxiosInstance,
  AxiosRequestConfig,
  AxiosResponse,
  InternalAxiosRequestConfig
} from 'axios';

// ---------- 1. 定义后端统一响应结构（Java 格式） ----------
export interface ApiResponse<T = any> {
  code: number;      // 1 表示成功，其他表示失败
  message?: string;
  msg?: string;      // 兼容字段
  data: T;
}

// ---------- 2. 创建 axios 实例 ----------
const service: AxiosInstance = axios.create({
  baseURL: '',
  timeout: 30000
});

// ---------- 3. 请求拦截器 ----------
service.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    config.headers['X-User-Id'] = '1';
    // const token = localStorage.getItem('token');
    // if (token) {
    //   config.headers.Authorization = `Bearer ${token}`;
    // }
    return config;
  },
  (error) => Promise.reject(error)
);

// ---------- 4. 响应拦截器 ----------
service.interceptors.response.use(
  (response: AxiosResponse): any => {
    const res = response.data;

    // 判断是否为 Java 统一响应结构
    const isJavaFormat = res && typeof res === 'object' && 'code' in res;

    if (isJavaFormat) {
      // 强制类型转换为 ApiResponse（实际运行时就是）
      const apiRes = res as ApiResponse;
      if (apiRes.code !== 1) {
        const errMsg = apiRes.message || apiRes.msg || '未知错误';
        console.error('Java接口报错:', errMsg);
        return Promise.reject(new Error(errMsg));
      }
      // 成功：返回 data 字段
      return apiRes.data;
    }

    // 非 Java 结构（如 Python 直出），直接返回 res
    return res;
  },
  (error) => Promise.reject(error)
);

// ---------- 5. 导出增强的请求方法（泛型支持） ----------
// 由于拦截器已经解包，我们需要重新声明方法，让 TS 知道返回的是 Promise<T> 而不是 Promise<AxiosResponse<T>>。
// 方案：用一个自定义对象包裹 service，重新定义 get/post/put/delete 方法。
// 但更好的方式是扩展 service 本身的类型，可以通过接口合并，不过更简单的是写一个包装函数。

// 我们直接导出 service，但为了类型正确，我们额外导出一个类型安全的 request 对象。
// 或者，可以直接在使用时断言，但为了全局规范，我们推荐创建一个 request 工具对象。

// 这里我们选择：重新导出一个自定义的 request 对象，它内部调用 service，但返回类型为 Promise<T>
// 这样所有调用地方都使用 request.get<T>，而不是 service.get。

// 注意：我们依然保留默认导出 service（可兼容旧代码），但建议新代码使用新的 request 对象。

// 定义包装后的请求方法类型
interface RequestInstance {
  get<T = any>(url: string, config?: AxiosRequestConfig): Promise<T>;
  post<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T>;
  put<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T>;
  delete<T = any>(url: string, config?: AxiosRequestConfig): Promise<T>;
  // 可以继续添加 patch 等
}

// 创建包装对象
const request: RequestInstance = {
  get: (url, config) => service.get(url, config) as Promise<any>,
  post: (url, data, config) => service.post(url, data, config) as Promise<any>,
  put: (url, data, config) => service.put(url, data, config) as Promise<any>,
  delete: (url, config) => service.delete(url, config) as Promise<any>,
};

// 默认导出 service（兼容旧代码）
export default service;
// 导出类型安全的 request 对象（推荐使用）
export { request };