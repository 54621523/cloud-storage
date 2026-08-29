<template>
  <el-dialog v-model="visible" title="分享文件" width="500px" :close-on-click-modal="false" @close="handleClose">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
      <!-- 显示选中的文件数量 -->
      <el-form-item label="分享内容">
        <span>已选择 <strong>{{ fileCount }}</strong> 个文件/文件夹</span>
      </el-form-item>

      <!-- 提取码（可选） -->
      <el-form-item label="提取码" prop="password">
        <el-input v-model="form.password" placeholder="留空则不设置提取码" maxlength="6" show-word-limit />
      </el-form-item>
      <el-form-item label="分享名称" prop="shareName">
        <el-input v-model="form.shareName" placeholder="留空则自动生成" maxlength="50" show-word-limit />
      </el-form-item>

      <!-- 过期时间（预设选项） -->
      <el-form-item label="过期时间" prop="expiryPreset">
        <el-radio-group v-model="form.expiryPreset">
          <el-radio-button label="1天" value="1d" />
          <el-radio-button label="7天" value="7d" />
          <el-radio-button label="30天" value="30d" />
          <el-radio-button label="365天" value="365d" />
          <el-radio-button label="永不过期" value="never" />
        </el-radio-group>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="loading" @click="handleSubmit">
        创建分享
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue';
import { ElMessage, timeUnits } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
import { useMyShares } from '@/modules/share-system/composables/useMyShares';
import type { FileItemUI } from '@/modules/file-system/types/file';
import { dayjs } from 'element-plus';

// Props
const props = defineProps<{
  files: FileItemUI | FileItemUI[];
}>();

// 对话框显示状态
const visible = defineModel<boolean>('visible', { default: false });

// 计算文件数量
const fileCount = computed(() => {
  const arr = Array.isArray(props.files) ? props.files : [props.files];
  return arr.length;
});

// 表单数据
interface FormData {
  password: string;
  expiryPreset: string;  // 预设值，如 '1d', 'never'
  shareName: string;
}
const form = reactive<FormData>({
  password: '',
  expiryPreset: '7d', // 默认选中7天
  shareName: '分享'
});

// 表单校验规则（只校验密码长度，过期时间由预设决定，无需额外校验）
const rules: FormRules = {
  password: [
    { min: 0, max: 6, message: '提取码长度不能超过6位', trigger: 'blur' },
  ],
};

const formRef = ref<FormInstance>();

// 加载状态
const loading = ref(false);

// 使用分享组合式函数
const { createShare } = useMyShares({ enabled: ref(false) });


const getExpireTime = (preset: string): string | undefined => {
  if (preset === 'never') return undefined;
  const days = parseInt(preset, 10);
  return dayjs().add(days, 'day').format('YYYY-MM-DDTHH:mm:ss');
};

// 提交表单
const handleSubmit = async () => {
  if (!formRef.value) return;
  await formRef.value.validate(async (valid) => {
    if (!valid) return;
    loading.value = true;
    try {
      const expireTime = getExpireTime(form.expiryPreset);
      await createShare(props.files, {
        password: form.password || undefined,
        expireTime, // 可能为 undefined
        displayName: form.shareName
      });
      visible.value = false;
      // 重置表单
      form.password = '';
      form.expiryPreset = '7d';
      form.shareName = '分享'
    } catch (error) {
    } finally {
      loading.value = false;
    }
  });
};

// 关闭对话框时的清理
const handleClose = () => {
  formRef.value?.resetFields();
  form.password = '';
  form.expiryPreset = '7d';
  form.shareName = '分享'
};
</script>

<style scoped>
:deep(.el-radio-button) {
  margin-right: 6px;
}

:deep(.el-radio-button:last-child) {
  margin-right: 0;
}
</style>