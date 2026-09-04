<!-- FileUploader.vue -->
<template>
  <div class="uploader-wrapper">
    <!-- 悬浮菜单触发按钮 -->
    <div class="action-btn primary uploader-trigger">
      <font-awesome-icon :icon="['fas', 'upload']" />
      <span>上传</span>
    </div>

    <!-- 悬浮下拉菜单 -->
    <div class="dropdown-menu">
      <div class="dropdown-item" @click="triggerInput('file')">
        上传文件
      </div>
      <div class="dropdown-item" @click="triggerInput('folder')">
        上传文件夹
      </div>
    </div>

    <!-- 隐藏的文件夹选择器 -->
    <input ref="folderInput" type="file" webkitdirectory directory multiple style="display: none;"
      @change="handleFileChange" />
  </div>


  <!-- 浮动进度面板（使用 Teleport 挂载到 body） -->
  <Teleport to="body">
    <div v-if="showProgressPanel" class="progress-floating-panel">

      <!-- 面板主体 -->
      <div class="progress-card">
        <!-- 关闭按钮 -->
        <button class="close-btn" @click="closeProgressPanel">✕</button>

        <!-- 整体进度条 -->
        <div class="progress-row">
          <span class="progress-label">上传进度</span>
          <el-progress :percentage="totalProgress" :status="totalProgress === 100 ? 'success' : undefined"
            :stroke-width="8" />
        </div>

        <!-- 统计信息 -->
        <div class="stats-row">
          <span>文件：{{ completedFilesCount }} / {{ totalFilesCount }}</span>
          <span v-if="totalProgress === 100">✅ 完成</span>
          <span v-else>⏳ 上传中...</span>
        </div>

        <!-- 文件状态列表（滚动） -->
        <div class="file-status-list" v-if="fileStatuses.length">
          <div v-for="item in fileStatuses.slice(0, 8)" :key="item.name" class="file-status-item">
            <span class="file-name">{{ item.name }}</span>
            <span class="file-status" :class="item.status">
              {{ item.status === 'success' ? '✓' : item.status === 'error' ? '✗' : '⏳' }}
              {{ item.status === 'uploading' ? `${item.percent}%` : '' }}
            </span>
          </div>
          <div v-if="fileStatuses.length > 8" class="more-hint">
            还有 {{ fileStatuses.length - 8 }} 个文件...
          </div>
        </div>
      </div>
    </div>
  </Teleport>

</template>

<script setup lang="ts">
import { onMounted, ref, inject } from 'vue';
import SparkMD5 from 'spark-md5'
import { ElMessage } from 'element-plus'
const UPLOADER_NAME = 'myUploader'

import { FileUD, UploadFile, type Uploader } from '@file-ud.js/core'
import { initUpload, completeMultipartUpload } from '@/api/分片上传'
import { tr } from 'element-plus/es/locales.mjs';
import { FILE_EXPLORER_KEY } from '@/constants/symbol';
import { AsyncCompiler } from 'sass';

const fileContext = inject(FILE_EXPLORER_KEY)
if (!fileContext) throw new Error('fileExplorer not provided');

const { refresh } = fileContext



let uploader: Uploader | null = null

const props = defineProps<{
  currentParentId: number
}>()


// ============ 进度状态 ============
const totalProgress = ref(0)           // 全局百分比 0~100
const totalFilesCount = ref(0)         // 总文件数
const completedFilesCount = ref(0)     // 已完成文件数
const isUploading = ref(false)         // 是否正在上传  
const showProgressPanel = ref(false)
const fileStatuses = ref<Array<{ name: string; status: string; percent: number }>>([])
function updateFileStatus(fileName: string, status: string, percent: number = 0) {
  const existing = fileStatuses.value.find(f => f.name === fileName)
  if (existing) {
    existing.status = status
    existing.percent = percent
  } else {
    fileStatuses.value.push({ name: fileName, status, percent })
    // 限制列表长度，避免渲染过多
    if (fileStatuses.value.length > 20) {
      fileStatuses.value.shift()
    }
  }
}



const folderInput = ref<HTMLInputElement | null>(null);

let pendingAdd = false;
function triggerInput(type: 'file' | 'folder') {
  if (type === 'file') {
    pendingAdd = true;
    uploader?.open();
  } else if (type === 'folder') {
    folderInput.value?.click();
  }
}
const hashCache = new WeakMap<File, string>()   // 缓存文件 MD5
const BATCH_SIZE = 10                           // 每批文件数

const handleFileChange = async (event: Event) => {
  const target = event.target as HTMLInputElement
  const files = target.files
  if (!files || files.length === 0) return

  if (!uploader) {
    ElMessage.error('上传器未初始化')
    target.value = ''
    return
  }

  const fileList = Array.from(files)
  const total = fileList.length
  pendingAdd = true;


  totalFilesCount.value = total
  completedFilesCount.value = 0
  fileStatuses.value = []
  isUploading.value = true
  showProgressPanel.value = true

  // 分批处理
  for (let i = 0; i < fileList.length; i += BATCH_SIZE) {
    const batch = fileList.slice(i, i + BATCH_SIZE)

    // 并行计算本批文件的 MD5
    await Promise.all(
      batch.map(async (file) => {
        const md5 = await calculateFileMD5(file)
        hashCache.set(file, md5)
      })
    )

    // 添加本批文件到上传器
    uploader.addFiles(batch)

  }

  // 重置 input 以便重复选择
  target.value = ''
}


const SAMPLE_SIZE = 1 * 1024 * 1024 // 1MB

function calculateFileMD5(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    // 只取文件头部指定大小的数据
    const blob = file.slice(0, SAMPLE_SIZE)
    const fileReader = new FileReader()
    fileReader.onload = (e) => {
      const result = e.target?.result
      if (result instanceof ArrayBuffer) {
        const spark = new SparkMD5.ArrayBuffer()
        spark.append(result)
        resolve(spark.end())
      } else {
        reject(new Error('读取文件数据失败'))
      }
    }
    fileReader.onerror = () => reject(new Error('读取文件失败'))
    fileReader.readAsArrayBuffer(blob)
  })
}


// 初始化上传会话
onMounted(() => {
  // 创建上传器
  uploader = FileUD.createUploader(UPLOADER_NAME, {
    maxFileConcurrent: 10,
    action: async (formData: FormData, uploadFile: UploadFile) => {
      try {
        const metadata = (uploadFile.metadata || {})
        const chunkIndex = parseInt(formData.get('chunkIndex') as string, 10)
        const partNumber = chunkIndex + 1
        const chunkUrlMap = metadata.chunkUrlMap || new Map<number, string>()
        const uploadedChunks = metadata.uploadedChunks || new Set<number>()
        const parts = metadata.parts || []

        if (uploadedChunks.has(partNumber)) {
          return { success: true }
        }

        const url = chunkUrlMap.get(partNumber)
        if (!url) {
          throw new Error(`未找到分片 ${partNumber} 的预签名 URL`)
        }

        const chunk = formData.get('file')
        if (!(chunk instanceof Blob)) {
          throw new Error('分片数据无效')
        }

        const response = await fetch(url, {
          method: 'PUT',
          body: chunk,
          headers: { 'Content-Type': 'application/octet-stream' },
        })

        if (!response.ok) {
          throw new Error(`上传失败 (${response.status}) ${response.statusText}`)
        }

        const etag = response.headers.get('ETag')!
        if (!etag) {
          throw new Error(`分片 ${partNumber} 上传后未返回 ETag`);
        }
        parts.push({
          partNumber,
          etag: etag as string,
        })

        return {
          data: await response.text(),
          chunkSize: chunk.size,
        }
      } catch (error) {
        const err = error as Error
        throw error
      }
    },
    file: (options: any) => {
      options.formData.append('file', options.data)
      options.formData.append('chunkIndex', options.chunkIndex)
    },
    chunkOptions: {
      chunkSize: 5 * 1024 * 1024,
      maxConcurrent: 3,
      retries: 3,
      timeout: 10000,
      enableFileCache: true,
    },
    multiple: true
  })

  if (!uploader) {
    return
  }

  // 初始化分片
  uploader.onInitChunk = async (file: UploadFile, totalChunks: number, fileHash: string) => {
    const rawFile = file.File
    let md5 = hashCache.get(rawFile)
    if (!md5) {
      md5 = await calculateFileMD5(rawFile)
    }
    const relativePath = rawFile.webkitRelativePath || null

    const request = {
      fileName: file.fileName,
      fileSize: file.size,
      fileHash: md5,
      parentId: props.currentParentId,
      relativePath: relativePath
    }

    try {
      const res = await initUpload(request)
      const data = res.data
      if (data.isComplete) {
        return {
          fileHash: md5,
          isInstantUpload: true,
          uploadId: 'instant-upload-' + Date.now(),
        }
      }

      const metadata = (file.metadata || {})
      metadata.uploadId = data.uploadId
      metadata.uploadedChunks = new Set(data.uploadedChunks || [])
      const allChunks = Array.from({ length: totalChunks }, (_, i) => i + 1)
      const missingChunks = allChunks.filter((n) => !metadata.uploadedChunks!.has(n))

      if (missingChunks.length !== data.presignedUrls!.length) {
        throw new Error('预签名 URL 数量与未上传分片数量不匹配')
      }

      const chunkUrlMap = new Map<number, string>()
      data.presignedUrls!.forEach((url: string, idx: number) => {
        chunkUrlMap.set(missingChunks[idx]!, url)
      })
      metadata.chunkUrlMap = chunkUrlMap

      const parts: { partNumber: number; etag: string }[] = []
      metadata.parts = parts

      file.metadata = metadata


      return {
        fileHash: md5,
        isInstantUpload: data.isComplete || false,
        chunks: data.uploadedChunks || [],
      }
    } catch (error) {
      const err = error as Error
      console.log(`错误信息：${err}`)
      throw error
    }
  }

  // 合并分片
  uploader.onMergeChunk = async (chunkManager: any) => {
    if (chunkManager.isInstantTransfer) {
      return { success: true, instant: true };
    }
    const uploadFile = chunkManager.uploadFile as UploadFile
    const metadata = uploadFile.metadata
    const uploadId = metadata?.uploadId || []
    const parts = metadata!.parts || []

    if (!uploadId) {
      throw new Error('缺少 uploadId，无法合并')
    }

    const request = {
      uploadId,
      parts,
    }

    try {
      const response = await completeMultipartUpload(request)
      return response
    } catch (error) {
      const err = error as Error
      throw error
    }
  }

  uploader.onUpdate = async (files: UploadFile[]) => {
    // 如果正处于添加新文件的准备阶段，则初始化或更新计数
    if (pendingAdd) {
      // 仅当没有正在上传且面板未打开时，才重置所有状态（全新上传）
      if (!isUploading.value && !showProgressPanel.value) {
        // 全新上传，重置所有计数
        totalFilesCount.value = files.length;
        completedFilesCount.value = 0;
        fileStatuses.value = [];
        totalProgress.value = 0;
        showProgressPanel.value = true;
        isUploading.value = true;
      } else {
        // 上传中又添加了文件，只更新总文件数
        totalFilesCount.value = files.length;
        // 已完成数不变，面板已打开
      }
      pendingAdd = false; // 消费标记
    } else {
      // 非添加引起的 update（比如文件状态变化），只更新总文件数（防止漏计）
      totalFilesCount.value = files.length;
    }

    // 如果文件列表为空，关闭面板（可选）
    if (files.length === 0) {
      showProgressPanel.value = false;
      isUploading.value = false;
    }
  }

  uploader.onSuccess = async (Response, file) => {
    completedFilesCount.value++;
    updateFileStatus(file.fileName, 'success', 100);

    // 全部上传完成
    if (completedFilesCount.value === totalFilesCount.value && totalFilesCount.value > 0) {
      totalProgress.value = 100;
      isUploading.value = false;
      // 刷新文件列表
      refresh();
      ElMessage.success('所有文件上传完成');
    }
  };


  uploader.on('progress', (percent: number) => {
    totalProgress.value = percent
  })

  uploader.on('chunk-success', ({ file, percent }: any) => {
    if (file) {
      updateFileStatus(file.fileName, '上传中', percent || 0)
    }
  })

  setInterval(() => {
    console.log('activeFiles:', uploader!.activeFiles.length);
    console.log('files status:', uploader!.files.map(f => f.status));
  }, 5000);


})

function closeProgressPanel() {
  showProgressPanel.value = false
}


</script>

<style scoped>
.uploader-wrapper {
  position: relative;
  display: inline-block;
}

.action-btn {
  background: white;
  border: 1px solid #e4e9f2;
  padding: 8px 16px;
  border-radius: 30px;
  font-size: 13px;
  font-weight: 500;
  color: #2c3e50;
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  transition: all 0.2s ease;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.02);
  user-select: none;
}

.action-btn i {
  color: #56759e;
  font-size: 14px;
}

.action-btn.primary {
  background: #2b7aff;
  border-color: #2b7aff;
  color: white;
}

.action-btn.primary i {
  color: white;
}

.action-btn.primary:hover {
  background: #1a5cdf;
  border-color: #1a5cdf;
}

.action-btn.primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.dropdown-menu {
  position: absolute;
  top: 100%;
  left: 0;
  margin-top: 8px;
  background: white;
  border: 1px solid #e4e9f2;
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  min-width: 140px;
  z-index: 10;
  opacity: 0;
  visibility: hidden;
  transform: translateY(-5px);
  transition: all 0.2s ease;
}

.uploader-wrapper:hover .dropdown-menu {
  opacity: 1;
  visibility: visible;
  transform: translateY(0);
}

.dropdown-item {
  padding: 10px 16px;
  font-size: 13px;
  color: #2c3e50;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
  transition: background 0.15s;
}

.dropdown-item:first-child {
  border-radius: 8px 8px 0 0;
}

.dropdown-item:last-child {
  border-radius: 0 0 8px 8px;
}

.dropdown-item:hover {
  background: #f0f5ff;
  color: #2b7aff;
}

.upload-progress {
  margin-top: 12px;
  padding: 12px 16px;
  background: #f8fafc;
  border-radius: 8px;
  border: 1px solid #e4e9f2;
  min-width: 280px;
}

.progress-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.progress-label {
  font-size: 13px;
  color: #2c3e50;
  white-space: nowrap;
}

.stats-row {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: #555;
  margin-top: 4px;
}

.file-status-list {
  max-height: 150px;
  overflow-y: auto;
  margin-top: 8px;
  font-size: 12px;
  border-top: 1px solid #e4e9f2;
  padding-top: 6px;
}

.file-status-item {
  display: flex;
  justify-content: space-between;
  padding: 2px 0;
  border-bottom: 1px solid #f0f2f5;
}

.file-status-item .file-name {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-status-item .file-status.success {
  color: #52c41a;
}

.file-status-item .file-status.error {
  color: #f5222d;
}

.file-status-item .file-status.uploading {
  color: #1890ff;
}

.more-hint {
  color: #999;
  font-size: 12px;
  padding: 4px 0;
}
</style>

<style>
/* 浮层遮罩 */
.progress-backdrop {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.3);
  z-index: 999;
}

/* 进度卡片 */
.progress-card {
  position: fixed;
  bottom: 40px;
  right: 40px;
  width: 380px;
  max-height: 360px;
  background: #ffffff;
  border-radius: 12px;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.2);
  padding: 20px 24px;
  z-index: 1000;
  overflow-y: auto;
  border: 1px solid #e4e9f2;
}

/* 关闭按钮 */
.close-btn {
  position: absolute;
  top: 8px;
  right: 12px;
  background: none;
  border: none;
  font-size: 18px;
  color: #999;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
}

.close-btn:hover {
  background: #f0f0f0;
  color: #333;
}

/* 卡片内部元素复用之前的样式 */
.progress-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 6px;
}

.progress-label {
  font-size: 14px;
  font-weight: 500;
  color: #2c3e50;
  white-space: nowrap;
}

.stats-row {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: #555;
  margin-bottom: 8px;
}

.file-status-list {
  max-height: 180px;
  overflow-y: auto;
  border-top: 1px solid #e4e9f2;
  padding-top: 8px;
  margin-top: 4px;
}

.file-status-item {
  display: flex;
  justify-content: space-between;
  padding: 3px 0;
  font-size: 13px;
  border-bottom: 1px solid #f0f2f5;
}

.file-status-item .file-name {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-status-item .file-status.success {
  color: #52c41a;
}

.file-status-item .file-status.error {
  color: #f5222d;
}

.file-status-item .file-status.uploading {
  color: #1890ff;
}

.more-hint {
  color: #999;
  font-size: 12px;
  padding: 4px 0;
}
</style>