<template>
    <div class="test-page">
        <!-- 操作面板 -->
        <el-card class="panel">
            <template #header>
                <div class="panel-header">
                    <span>操作面板</span>
                </div>
            </template>

            <el-form label-position="top">
                <el-form-item label="上传地址">
                    <el-input v-model="uploadUrl" placeholder="例如：/api/upload" />
                </el-form-item>

                <el-form-item>
                    <el-button type="primary" @click="selectFiles">
                        选择文件并上传
                    </el-button>
                </el-form-item>

                <el-form-item>
                    <el-button type="primary" @click="downloadAAA">
                        测试下载
                    </el-button>
                </el-form-item>

                <!-- 进度条 -->
                <el-form-item v-if="loading || percent > 0">
                    <el-progress :percentage="percent" />
                </el-form-item>

                <el-form-item v-if="fileCount > 0">
                    <span>队列中文件数：{{ fileCount }}</span>
                </el-form-item>
            </el-form>
        </el-card>


        <el-card class="panel">
            <template #header>
                <div class="panel-header">
                    <span>日志面板</span>
                    <el-button plain @click="clearLogs">清空日志</el-button>
                </div>
            </template>
            <div ref="logContainerRef" class="log-container">
                <div v-for="(log, index) in logs" :key="index" class="log-line">
                    <span class="log-time">{{ log.time }}</span>
                    <span :class="['log-level', `log-level-${log.level}`]">
                        {{ log.level.toUpperCase() }}
                    </span>
                    <span class="log-message">{{ log.message }}</span>
                </div>
                <div v-if="logs.length === 0" class="log-empty">暂无日志</div>
            </div>
        </el-card>
    </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { FileUD, type Uploader, type Downloader, type UploadFile } from '@file-ud.js/core'


import { initUpload, completeMultipartUpload, generateDownloadUrl } from '@/api/upload-v-2-controller'
import SparkMD5 from 'spark-md5'

// ---------- 类型定义 ----------
interface LogEntry {
    time: string
    level: 'info' | 'success' | 'warn' | 'error'
    message: string
}

// 扩展 UploadFile 的 metadata 类型（可选）
interface ExtendedMetadata {
    uploadId?: string
    uploadedChunks?: Set<number>
    chunkUrlMap?: Map<number, string>
    parts?: { partNumber: number; etag: string }[]
}

// ---------- 常量 ----------
const UPLOADER_NAME = 'myUploader'
const DOWNLOADER_NAME = 'myDownloader'

// ---------- 响应式状态 ----------
const uploadUrl = ref<string>('/api/storage/upload/init')
const logs = ref<LogEntry[]>([])
const logContainerRef = ref<HTMLElement | null>(null)
const loading = ref<boolean>(false)
const percent = ref<number>(0)
const fileCount = ref<number>(0)

// ---------- 上传器实例 ----------
let uploader: Uploader | null = null
let downloader: Downloader | null = null

// ---------- 日志工具 ----------
function formatTime(): string {
    const now = new Date()
    const pad = (v: number): string => String(v).padStart(2, '0')
    return `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
}

function addLog(message: string, level: LogEntry['level'] = 'info'): void {
    logs.value.push({ time: formatTime(), level, message })
    nextTick(() => {
        if (logContainerRef.value) {
            logContainerRef.value.scrollTop = logContainerRef.value.scrollHeight
        }
    })
}

// ---------- 计算 MD5 ----------
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

function getExpiryFromUrl(urlString: string) {
    const url = new URL(urlString);
    const expires = url.searchParams.get('X-Amz-Expires');
    const date = url.searchParams.get('X-Amz-Date');
    if (expires && date) {
        const year = Number(date.slice(0, 4));
        const month = Number(date.slice(4, 6));
        const day = Number(date.slice(6, 8));
        const hour = Number(date.slice(9, 11)) + 8;
        const minute = Number(date.slice(11, 13));
        const second = Number(date.slice(13, 15));
        const createTime = new Date(Date.UTC(year, Number(month) - 1, day, hour, minute, second));
        return createTime.getTime() + parseInt(expires, 10) * 1000;
    }
    return Date.now() + 3600 * 1000; // 解析失败则默认 1 小时
}

// ---------- 生命周期 ----------
onMounted(() => {
    // 创建上传器
    uploader = FileUD.createUploader(UPLOADER_NAME, {
        action: async (formData: FormData, uploadFile: UploadFile) => {
            try {
                const metadata = (uploadFile.metadata || {}) as ExtendedMetadata
                const chunkIndex = parseInt(formData.get('chunkIndex') as string, 10)
                const partNumber = chunkIndex + 1
                const chunkUrlMap = metadata.chunkUrlMap || new Map<number, string>()
                const uploadedChunks = metadata.uploadedChunks || new Set<number>()
                const parts = metadata.parts || []

                if (uploadedChunks.has(partNumber)) {
                    addLog(`分片 ${partNumber} 已上传，跳过`, 'info')
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

                addLog(`正在直传分片 ${partNumber} 到 S3`)

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

                addLog(`分片 ${partNumber} 上传成功`, 'success')
                return {
                    data: await response.text(),
                    chunkSize: chunk.size,
                }
            } catch (error) {
                const err = error as Error
                addLog(`[ACTION 错误] ${err.message}`, 'error')
                if (err.stack) addLog(err.stack, 'error')
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
    })

    if (!uploader) {
        addLog('上传器创建失败', 'error')
        return
    }

    // 初始化分片
    uploader.onInitChunk = async (file: UploadFile, totalChunks: number) => {
        const rawFile = file.File
        const fileHash = await calculateFileMD5(rawFile)
        addLog(`文件的 MD5 为 ${fileHash}`, 'info')

        const request = {
            fileName: file.fileName,
            fileSize: file.size,
            fileHash,
        }

        try {
            const res = await initUpload(request)
            const data = res as any // 根据实际 API 响应定义更具体的类型

            if (data.isComplete) {
                addLog('文件秒传成功', 'success')
                return {
                    fileHash,
                    isInstantUpload: true,
                    chunks: data.uploadedChunks || [],
                }
            }

            // 扩展 metadata
            const metadata = (file.metadata || {}) as ExtendedMetadata
            metadata.uploadId = data.uploadId
            metadata.uploadedChunks = new Set(data.uploadedChunks || [])
            const allChunks = Array.from({ length: totalChunks }, (_, i) => i + 1)
            const missingChunks = allChunks.filter((n) => !metadata.uploadedChunks!.has(n))

            if (missingChunks.length !== data.presignedUrls.length) {
                throw new Error('预签名 URL 数量与未上传分片数量不匹配')
            }

            const chunkUrlMap = new Map<number, string>()
            data.presignedUrls.forEach((url: string, idx: number) => {
                chunkUrlMap.set(missingChunks[idx]!, url)
            })
            metadata.chunkUrlMap = chunkUrlMap

            const parts: { partNumber: number; etag: string }[] = []
            metadata.parts = parts

            // 将 metadata 写回 file
            file.metadata = metadata

            addLog(`总分片数 ${totalChunks}，已上传 ${metadata.uploadedChunks.size} 个`)
            addLog(`上传 ID: ${metadata.uploadId}`)

            return {
                fileHash,
                isInstantUpload: data.isComplete || false,
                chunks: data.uploadedChunks || [],
            }
        } catch (error) {
            const err = error as Error
            addLog(`初始化分片失败: ${err.message}`, 'error')
            throw error
        }
    }

    // 合并分片
    uploader.onMergeChunk = async (chunkManager: any) => {
        const uploadFile = chunkManager.uploadFile as UploadFile
        const metadata = uploadFile.metadata as ExtendedMetadata
        const uploadId = metadata.uploadId
        const parts = metadata.parts || []

        if (!uploadId) {
            throw new Error('缺少 uploadId，无法合并')
        }

        addLog(`正在合并文件，uploadId: ${uploadId}`)

        const request = {
            uploadId,
            parts,
        }

        try {
            const response = await completeMultipartUpload(request)
            addLog('文件合并成功', 'success')
            return response
        } catch (error) {
            const err = error as Error
            addLog(`合并失败: ${err.message}`, 'error')
            throw error
        }
    }

    // 进度监听
    uploader.onUpdate = () => {
        loading.value = Boolean(uploader?.loading)
        percent.value = uploader?.totalPercent || 0
    }

    uploader.onSuccess = (file: UploadFile) => {
        addLog(`文件 ${file.File.name} 上传成功`, 'success')
    }

    addLog('上传器已初始化（预签名直传模式）', 'info')

    downloader = FileUD.createDownloader(DOWNLOADER_NAME, {
        action: async (downloadFile) => {
            downloadFile.metadata = downloadFile.metadata || {}
            addLog('下载文件被调用', 'info')
            const cacheKey = downloadFile.fileId
            addLog(`url是 ${downloadFile.url}`)
            addLog(`文件的id是 ${downloadFile.metadata.id}`)
            const cached = urlCacheMap.get(cacheKey);
            if (cached && cached.expiresAt > Date.now() + 1 * 60 * 1000) {
                return cached.url;
            }
            const generateDownloadUrlParams = {
                virtualFileId: Number(downloadFile.url)
            }
            const response = generateDownloadUrl(generateDownloadUrlParams)
            const url = await response;

            urlCacheMap.set(cacheKey, {
                url: url,
                expiresAt: getExpiryFromUrl(url.data)
            });
            return url;
        },
        chunkOptions: {
            chunkSize: 2 * 1024 * 1024,    // 2MB 分片
            maxConcurrent: 3,               // 最多 3 个并发
            retries: 3,                     // 失败重试 3 次
            timeout: 10000,                 // 单分片超时
            enableFileCache: true,          // 启用 IndexedDB 缓存
        },
    })

    addLog('下载器已初始化（预签名直传模式）', 'info')
})
const urlCacheMap = new Map();

onBeforeUnmount(() => {
    if (uploader) {
        FileUD.destroyUploaders(UPLOADER_NAME)
        uploader = null
    }
})

// ---------- UI 操作 ----------
function selectFiles(): void {
    if (!uploader) {
        ElMessage.error('上传器未初始化')
        return
    }
    uploader.open()
    addLog('已打开文件选择器')
}


function clearLogs(): void {
    logs.value = []
}

function downloadAAA() {
    if (!downloader) {
        ElMessage.error('下载器未初始化')
        return
    }
    const fileObj =
        downloader.downloadFile({
            fileName: "11118",
            url: "1",
            size: 13
        })
    fileObj.metadata = {}
    fileObj.metadata.id = 111
    fileObj.metadata.name = "123"
}
</script>

<style scoped>
/* 样式保持不变，与之前相同 */
.test-page {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 16px;
    padding: 16px;
    height: 100%;
    box-sizing: border-box;
}

.panel {
    height: 100%;
    display: flex;
    flex-direction: column;
}

.panel-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
}

.log-container {
    flex: 1;
    min-height: 320px;
    max-height: 520px;
    overflow-y: auto;
    background-color: #1e1e1e;
    color: #d4d4d4;
    font-family: Consolas, Monaco, monospace;
    font-size: 13px;
    padding: 12px;
    border-radius: 4px;
}

.log-line {
    display: flex;
    gap: 8px;
    padding: 2px 0;
    line-height: 1.6;
}

.log-time {
    color: #9cdcfe;
    white-space: nowrap;
}

.log-level {
    min-width: 52px;
    text-align: center;
    font-weight: bold;
}

.log-level-info {
    color: #4fc3f7;
}

.log-level-success {
    color: #81c784;
}

.log-level-warn {
    color: #ffb74d;
}

.log-level-error {
    color: #e57373;
}

.log-message {
    color: #d4d4d4;
    word-break: break-all;
}

.log-empty {
    color: #888;
    text-align: center;
    padding: 20px 0;
}

@media (max-width: 900px) {
    .test-page {
        grid-template-columns: 1fr;
    }
}
</style>