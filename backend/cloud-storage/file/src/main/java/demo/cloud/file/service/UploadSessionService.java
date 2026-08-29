package demo.cloud.file.service;

import demo.cloud.file.dto.upload.UploadProgress;
import demo.cloud.file.pojo.UploadSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class UploadSessionService {
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private static final String UPLOAD_SESSION_KEY = "upload:session:";
    private static final String UPLOADED_CHUNKS_KEY = "upload:chunks:";
    private static final String UPLOAD_PROGRESS_KEY = "upload:progress:";
    private static final long SESSION_TTL = 24; // 小时

    public UploadSessionService(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void saveSession(UploadSession session) {
        redisTemplate.opsForValue().set(UPLOAD_SESSION_KEY + session.getSessionId(), session, SESSION_TTL, TimeUnit.HOURS);
    }

    public UploadSession getSession(String sessionId) {
        Object sessionObj = redisTemplate.opsForValue().get(UPLOAD_SESSION_KEY + sessionId);
        if (sessionObj == null) {
            return null;
        }
        return objectMapper.convertValue(sessionObj, UploadSession.class);
    }


    public void markChunkUploaded(String sessionId, int chunkIndex) {
        String chunkKey = UPLOADED_CHUNKS_KEY + sessionId;
        redisTemplate.opsForSet().add(chunkKey, chunkIndex);
        redisTemplate.expire(chunkKey, SESSION_TTL, TimeUnit.HOURS);
    }

    public Set<Object> getUploadedChunks(String sessionId) {
        String chunkKey = UPLOADED_CHUNKS_KEY + sessionId;
        return redisTemplate.opsForSet().members(chunkKey);
    }

    public boolean isChunkUploaded(String sessionId, int chunkIndex) {
        String chunkKey = UPLOADED_CHUNKS_KEY + sessionId;
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(chunkKey, chunkIndex));
    }

    public int getUploadedChunkCount(String sessionId) {
        String chunkKey = UPLOADED_CHUNKS_KEY + sessionId;
        Long size = redisTemplate.opsForSet().size(chunkKey);
        return size != null ? size.intValue() : 0;
    }

    public void deleteChunkRecords(String sessionId) {
        String chunkKey = UPLOADED_CHUNKS_KEY + sessionId;
        redisTemplate.delete(chunkKey);
        redisTemplate.delete(UPLOAD_PROGRESS_KEY + sessionId);
    }



    /**
     * 根据上传会话创建进度对象
     */
    public UploadProgress create(UploadSession session) {
        if (session == null) {
            return null;
        }

        int uploadedCount = this.getUploadedChunkCount(session.getSessionId());
        double progress = session.getTotalChunks() > 0 ? (double) uploadedCount / session.getTotalChunks() * 100 : 0;

        UploadProgress uploadProgress = new UploadProgress(
                session.getSessionId(),
                session.getFileName(),
                session.getStatus(),
                uploadedCount,
                session.getTotalChunks(),
                progress
        );

        if ("COMPLETED".equals(session.getStatus())) {
            uploadProgress.setOssKey(session.getOssKey());
        }
        return uploadProgress;
    }
}