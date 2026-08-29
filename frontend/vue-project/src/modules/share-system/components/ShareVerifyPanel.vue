<template>
    <div class="verify-card">
        <h2 class="verify-title">该分享需要提取码</h2>
        <p class="verify-desc">请输入提取码以查看分享内容</p>

        <el-form @submit.prevent="handleSubmit">
            <el-input v-model="pwdInput" placeholder="请输入提取码" size="large" maxlength="6" show-word-limit clearable
                :disabled="shareContext.isVerifying.value" @keyup.enter="handleSubmit" />
            <div v-if="verifyError" class="verify-error">{{ verifyError }}</div>

            <el-button type="primary" size="large" :loading="shareContext.isVerifying.value" class="verify-btn"
                @click="handleSubmit">
                验证并查看
            </el-button>
        </el-form>
    </div>
</template>

<script setup lang="ts">
import { ref, inject, onMounted } from 'vue';
import { useRoute } from 'vue-router';

// 直接注入父组件提供的上下文
const shareContext = inject<any>('shareContext');
const route = useRoute();

const pwdInput = ref('');
const verifyError = ref('');


const performVerification = async (password: string, silent: boolean = false) => {
    // 静默模式下不显示之前的错误
    if (silent) {
        verifyError.value = '';
    }

    try {
        const shareCode = route.params.shareCode as string;
        await shareContext.verifyPassword(shareCode, password.trim());
        await shareContext.fetchDetail(0, 0);
    } catch (err: any) {
        // 仅在非静默模式下显示错误
        if (!silent) {
            verifyError.value = err?.message || '提取码错误，请重试';
        }
        // 静默模式下：不设置错误信息，只让输入框显示（因为 isVerified 为 false）
        // 不清空 pwdInput，保留用户可能输入的内容
    }
};

const handleSubmit = async () => {
    if (!pwdInput.value.trim()) {
        verifyError.value = '提取码不能为空';
        return;
    }
    await performVerification(pwdInput.value, false);
};

// 自动验证逻辑（URL带pwd参数时）
onMounted(() => {
    const pwdFromUrl = route.query.pwd as string;
    const password = (pwdFromUrl !== undefined) ? pwdFromUrl : '';
    performVerification(password, true);
});
</script>

<style scoped lang="scss">
.verify-card {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 40px;
}

.verify-title {
    font-size: 24px;
    color: #303133;
    margin-bottom: 10px;
}

.verify-desc {
    font-size: 14px;
    color: #909399;
    margin-bottom: 30px;
}

.verify-card .el-form {
    display: flex;
    flex-direction: column;
    align-items: center;
    width: 100%;
}

.verify-card .el-form .el-input {
    width: 300px;
}

.verify-card .el-form .verify-btn {
    width: 300px;
    margin-top: 20px;
}

.verify-card .el-form .verify-error {
    width: 300px;
    color: #f56c6c;
    font-size: 12px;
    margin-top: 8px;
    text-align: left;
}
</style>