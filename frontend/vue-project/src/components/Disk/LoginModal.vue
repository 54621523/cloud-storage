<template>
  <el-dialog v-model="dialogVisible" title="欢迎使用网盘" width="420px" :close-on-click-modal="false" @close="resetForm">
    <el-tabs v-model="activeTab" class="login-tabs">
      <!-- 登录 Tab -->
      <el-tab-pane label="登录" name="login">
        <el-form ref="loginFormRef" :model="loginForm" :rules="loginRules" label-width="0"
          @submit.prevent="handleLogin">
          <el-form-item prop="loginAccount">
            <el-input v-model="loginForm.loginAccount" placeholder="请输入用户名" prefix-icon="User" clearable />
          </el-form-item>
          <el-form-item prop="password">
            <el-input v-model="loginForm.password" type="password" placeholder="请输入密码" prefix-icon="Lock"
              show-password />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="loading" style="width:100%" @click="handleLogin">
              登 录
            </el-button>
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <!-- 注册 Tab -->
      <el-tab-pane label="注册" name="register">
        <el-form ref="registerFormRef" :model="registerForm" :rules="registerRules" label-width="0"
          @submit.prevent="handleRegister">
          <el-form-item prop="loginAccount">
            <el-input v-model="registerForm.loginAccount" placeholder="请设置用户名" prefix-icon="User" clearable />
          </el-form-item>
          <el-form-item prop="password">
            <el-input v-model="registerForm.password" type="password" placeholder="请设置密码（不少于6位）" prefix-icon="Lock"
              show-password />
          </el-form-item>
          <el-form-item prop="confirmPassword">
            <el-input v-model="registerForm.confirmPassword" type="password" placeholder="请再次输入密码" prefix-icon="Lock"
              show-password />
          </el-form-item>
          <el-form-item>
            <el-button type="success" :loading="loading" style="width:100%" @click="handleRegister">
              注 册
            </el-button>
          </el-form-item>
        </el-form>
      </el-tab-pane>
    </el-tabs>

    <!-- 底部额外信息（可选） -->
    <template #footer>
      <span class="dialog-footer">
        <el-text type="info" size="small">测试账号：admin / 123456</el-text>
      </span>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive, watch } from 'vue';
import { ElMessage } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
import { useUserStore } from '@/stores/userstore';

// ---------- 弹窗显示控制 ----------
const dialogVisible = defineModel<boolean>('visible', { default: false });

// ---------- 表单引用 ----------
const loginFormRef = ref<FormInstance>();
const registerFormRef = ref<FormInstance>();

// ---------- 登录表单 ----------
const loginForm = reactive({
  loginAccount: '',
  password: '',
});

const loginRules: FormRules = {
  loginAccount: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
};

// ---------- 注册表单 ----------
const registerForm = reactive({
  loginAccount: '',
  password: '',
  confirmPassword: '',
});

const validateConfirm = (rule: any, value: string, callback: (error?: Error) => void) => {
  if (value !== registerForm.password) {
    callback(new Error('两次输入的密码不一致'));
  } else {
    callback();
  }
};

const registerRules: FormRules = {
  loginAccount: [
    { required: true, message: '请设置用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '用户名长度 3-20 位', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请设置密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    { validator: validateConfirm, trigger: 'blur' },
  ],
};

// ---------- 状态 ----------
const loading = ref(false);
const activeTab = ref<'login' | 'register'>('login');

// ---------- Store 引用 ----------
const userStore = useUserStore();


// ---------- 登录逻辑 ----------
const handleLogin = async () => {
  if (!loginFormRef.value) return;
  await loginFormRef.value.validate(async (valid) => {
    if (!valid) return;
    loading.value = true;
    try {
      // 调用登录接口（假设返回 token、用户信息、rootFolderId 等）
      await userStore.login(loginForm);
      ElMessage.success('登录成功');
      dialogVisible.value = false;
    } catch (error: any) {
      ElMessage.error(error.message || '登录失败');
    } finally {
      loading.value = false;
    }
  });
};

// ---------- 注册逻辑 ----------
const handleRegister = async () => {
  if (!registerFormRef.value) return;
  await registerFormRef.value.validate(async (valid) => {
    if (!valid) return;
    loading.value = true;
    try {
      await userStore.register(registerForm.loginAccount, registerForm.password);
      ElMessage.success('注册成功，请登录');
      // 自动切换到登录选项卡，并填充用户名
      activeTab.value = 'login';
      loginForm.loginAccount = registerForm.loginAccount;
      registerForm.loginAccount = '';
      registerForm.password = '';
      registerForm.confirmPassword = '';
    } catch (error: any) {
      ElMessage.error(error.message || '注册失败');
    } finally {
      loading.value = false;
    }
  });
};

// ---------- 重置表单（弹窗关闭时） ----------
const resetForm = () => {
  loginFormRef.value?.resetFields();
  registerFormRef.value?.resetFields();
  loading.value = false;
  // 不重置 activeTab，保持用户习惯
};
</script>

<style scoped>
.login-tabs :deep(.el-tabs__header) {
  margin-bottom: 20px;
}

.login-tabs :deep(.el-tabs__item) {
  font-size: 16px;
  font-weight: 500;
}

.dialog-footer {
  display: flex;
  justify-content: center;
  font-size: 12px;
}
</style>