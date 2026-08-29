import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import request from '@/utils/request';
import { ElMessage } from 'element-plus';
import { STORAGE_KEYS } from '@/symbol';


import { useLogin, useRegister } from '@/api/认证模块'

import type { LoginRequest } from '@/api/models'


export interface LoginResponse {
    token: string;
    nickname: string;
    username: string;
    phone: string;
    email: string;
    userId: string;
    expiresIn: number;
    rootFolderId: number;
    rootFolderName: string;
}

export const useUserStore = defineStore('user', () => {
    // ---------- State ----------
    const token = ref<string | null>(localStorage.getItem('token'));
    const userInfo = ref<{
        name: string;
        rootFolderId: number;
        // usedSpace: number;    // 已使用空间 (MB)
        // totalSpace: number;   // 总空间 (MB)
    } | null>(
        (() => {
            const stored = localStorage.getItem('userInfo');
            if (!stored) return null;
            try {
                return JSON.parse(stored);
            } catch {
                return null; // 解析失败则置空
            }
        })()  // 立即执行
    );

    // 登录弹窗显示状态（全局控制）
    const showLoginModal = ref(false);

    // ---------- Getters ----------
    const isLoggedIn = computed(() => !!token.value && !!userInfo.value);

    // ---------- Actions ----------
    function toggleLoginModal(show?: boolean) {
        showLoginModal.value = show !== undefined ? show : !showLoginModal.value;
    }

    const { mutate, isPending, isError, error } = useLogin()

    const login = (formData: LoginRequest) => {
        mutate({ data: formData }, {
            onSuccess: (response) => {
                // 登录成功
                const token = response.data?.token
                const rootFolderId = response.data?.rootFolderId
                const rootFolderName = response.data?.rootFolderName
                const nickname = response.data?.nickname

                userInfo.value = {
                    name: nickname!,
                    rootFolderId: rootFolderId!
                }

                localStorage.setItem('token', token!)
                localStorage.setItem(STORAGE_KEYS.ROOT_ID, String(rootFolderId));
                localStorage.setItem(STORAGE_KEYS.ROOT_NAME, rootFolderName || '我的文件');


                toggleLoginModal(false);
                ElMessage.success(`欢迎回来，${nickname}`);
            },
            onError: (err) => {
                // 错误已自动捕获，可通过 error 响应式变量访问
                console.error('登录失败', err)
            }
        })
    }

    async function register(username: string, password: string) {
        await request.post('/api/auth/register', { username, password });
        ElMessage.success('注册成功，请登录');
        // 可选：自动填充登录表单，由 LoginModal 内部处理
    }

    function logout() {
        token.value = null;
        userInfo.value = null;
        localStorage.removeItem('token');
        localStorage.removeItem('userInfo');
        localStorage.removeItem('file_root_id');
        localStorage.removeItem('file_root_name');
        ElMessage.success('已退出登录');
        // 重定向到首页或刷新页面
    }

    return {
        token,
        userInfo,
        showLoginModal,
        isLoggedIn,
        toggleLoginModal,
        login,
        register,
        logout,
    };
});