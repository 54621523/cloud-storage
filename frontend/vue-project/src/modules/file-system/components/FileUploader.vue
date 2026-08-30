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
</template>

<script setup lang="ts">
import { onMounted, ref, inject } from 'vue';
import SparkMD5 from 'spark-md5'
import { ElMessage } from 'element-plus'
const UPLOADER_NAME = 'myUploader'

import { FileUD, type Uploader, type UploadFile } from '@file-ud.js/core'
import { initUpload, completeMultipartUpload } from '@/api/分片上传'
import { tr } from 'element-plus/es/locales.mjs';
import { FILE_EXPLORER_KEY } from '@/constants/symbol';

const fileContext = inject(FILE_EXPLORER_KEY)
if (!fileContext) throw new Error('fileExplorer not provided');

const { refresh } = fileContext



let uploader: Uploader | null = null

const props = defineProps<{
  currentParentId: number
}>()

const folderInput = ref<HTMLInputElement | null>(null);
function triggerInput(type: 'file' | 'folder') {
  if (type === 'file') {
    uploader?.open();
  } else if (type === 'folder') {
    folderInput.value?.click();
  }
}

const handleFileChange = (event: Event) => {
  const target = event.target as HTMLInputElement;
  const files = target.files;
  console.log(`文件序列总观是 ${files}`)
  console.log(`文件序列的item0是 ${files![0]!.webkitRelativePath}`)
  console.log(`文件序列的item1是 ${files![1]!.webkitRelativePath}`)
  console.log(`文件序列的item2是 ${files![2]!.webkitRelativePath}`)
  console.log(`文件序列的item3是 ${files![3]!.webkitRelativePath}`)
  if (!files || files.length === 0) return;

  if (!uploader) {
    ElMessage.error('上传器未初始化');
    target.value = '';
    return;
  }

  // 使用 addFiles 批量添加所有文件
  uploader.addFiles(files)
    .then(() => {
      // 如果库需要手动启动，则调用 start（根据实际情况选择）
      if (typeof (uploader as any).start === 'function') {
        (uploader as any).start();
      }
    })
    .catch((err: Error) => {
      ElMessage.error('添加文件失败：' + err.message);
    });

  // 重置 input 以便重复选择同一文件夹
  target.value = '';
};


function calculateFileMD5(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const chunkSize = 5 * 1024 * 1024
    const chunks = Math.ceil(file.size / chunkSize)
    const spark = new SparkMD5.ArrayBuffer()
    const fileReader = new FileReader()
    let currentChunk = 0

    const loadNextChunk = (): void => {
      const start = currentChunk * chunkSize
      const end = Math.min(start + chunkSize, file.size)
      const blob = file.slice(start, end)
      fileReader.readAsArrayBuffer(blob)
    }

    fileReader.onload = (e: ProgressEvent<FileReader>) => {
      const result = e.target?.result
      if (result instanceof ArrayBuffer) {
        spark.append(result)
      }
      currentChunk++
      if (currentChunk < chunks) {
        loadNextChunk()
      } else {
        resolve(spark.end())
      }
    }

    fileReader.onerror = () => {
      reject(new Error('计算文件 MD5 失败'))
    }

    loadNextChunk()
  })
}


// 初始化上传会话
onMounted(() => {
  // 创建上传器
  uploader = FileUD.createUploader(UPLOADER_NAME, {
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
  uploader.onInitChunk = async (file: UploadFile, totalChunks: number) => {
    const rawFile = file.File
    const fileHash = await calculateFileMD5(rawFile)
    const relativePath = rawFile.webkitRelativePath || null

    const request = {
      fileName: file.fileName,
      fileSize: file.size,
      fileHash,
      parentId: props.currentParentId,
      relativePath: relativePath
    }

    try {
      const res = await initUpload(request)
      const data = res.data
      console.log(data)

      if (data.isComplete) {
        refresh()
        return {
          fileHash,
          isInstantUpload: true,
          chunks: data.uploadedChunks || [],
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
        fileHash,
        isInstantUpload: data.isComplete || false,
        chunks: data.uploadedChunks || [],
      }
    } catch (error) {
      const err = error as Error
      console.log(err)
      throw error
    }
  }

  // 合并分片
  uploader.onMergeChunk = async (chunkManager: any) => {
    const uploadFile = chunkManager.uploadFile as UploadFile
    const metadata = uploadFile.metadata
    const uploadId = metadata!.uploadId
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
      refresh()
      return response
    } catch (error) {
      const err = error as Error
      throw error
    }
  }

  uploader.onSuccess = (file: UploadFile) => {
    refresh()
  }

})


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
</style>