package demo.cloud.file.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.SearchRequest;
import com.meilisearch.sdk.model.SearchResultPaginated;
import com.meilisearch.sdk.model.Settings;
import com.meilisearch.sdk.model.TaskInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class MeilisearchTemplate {

    @Autowired
    private Client meilisearchClient;

    private final ObjectMapper objectMapper;

    public MeilisearchTemplate() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public Index getIndex(String indexUid) {
        return meilisearchClient.getIndex(indexUid);
    }

    public void createIndex(String indexUid, String primaryKey) {
        try {
            Index index = getIndex(indexUid);
            if(index != null) {
                log.info("索引已存在: {}", indexUid);
                return;
            }
            TaskInfo taskInfo = meilisearchClient.createIndex(indexUid, primaryKey);
            log.info("创建索引成功: {}, taskId: {}", indexUid, taskInfo.getTaskUid());
        } catch (Exception e) {
            log.error("创建索引失败: {}", indexUid, e);
            throw new RuntimeException("创建索引失败", e);
        }
    }

    public void updateSettings(String indexUid, String[] filterableAttributes, String[] sortableAttributes) {
        try {
            Index index = getIndex(indexUid);
            Settings settings = new Settings();
            settings.setFilterableAttributes(filterableAttributes);
            settings.setSortableAttributes(sortableAttributes);
            TaskInfo taskInfo = index.updateSettings(settings);
            log.info("更新索引设置成功: {}, taskId: {}", indexUid, taskInfo.getTaskUid());
        } catch (Exception e) {
            log.error("更新索引设置失败: {}", indexUid, e);
            throw new RuntimeException("更新索引设置失败", e);
        }
    }

    public void updateSettings(String indexUid, String[] searchableAttributes,
                               String[] filterableAttributes, String[] sortableAttributes) {
        try {
            Index index = getIndex(indexUid);
            Settings settings = new Settings();
            if (searchableAttributes != null) {
                settings.setSearchableAttributes(searchableAttributes);
            }
            settings.setFilterableAttributes(filterableAttributes);
            settings.setSortableAttributes(sortableAttributes);
            TaskInfo taskInfo = index.updateSettings(settings);
            log.info("更新索引完整设置成功: {}, taskId: {}", indexUid, taskInfo.getTaskUid());
        } catch (Exception e) {
            log.error("更新索引完整设置失败: {}", indexUid, e);
            throw new RuntimeException("更新索引完整设置失败", e);
        }
    }

    public <T> void addDocuments(String indexUid, List<T> documents, String primaryKey) {
        try {
            Index index = getIndex(indexUid);
            String jsonStr = objectMapper.writeValueAsString(documents);
            TaskInfo taskInfo = index.addDocuments(jsonStr, primaryKey);
            log.info("添加文档成功: {}, 数量: {}, taskId: {}", indexUid, documents.size(), taskInfo.getTaskUid());
        } catch (Exception e) {
            log.error("添加文档失败: {}", indexUid, e);
            throw new RuntimeException("添加文档失败", e);
        }
    }

    public <T> void updateDocuments(String indexUid, List<T> documents, String primaryKey) {
        try {
            Index index = getIndex(indexUid);
            String jsonStr = objectMapper.writeValueAsString(documents);
            TaskInfo taskInfo = index.updateDocuments(jsonStr, primaryKey);
            log.info("更新文档成功: {}, 数量: {}, taskId: {}", indexUid, documents.size(), taskInfo.getTaskUid());
        } catch (Exception e) {
            log.error("更新文档失败: {}", indexUid, e);
            throw new RuntimeException("更新文档失败", e);
        }
    }

    public void deleteDocument(String indexUid, String documentId) {
        try {
            Index index = getIndex(indexUid);
            TaskInfo taskInfo = index.deleteDocument(documentId);
            log.info("删除文档成功: {}, documentId: {}, taskId: {}", indexUid, documentId, taskInfo.getTaskUid());
        } catch (Exception e) {
            log.error("删除文档失败: {}, documentId: {}", indexUid, documentId, e);
            throw new RuntimeException("删除文档失败", e);
        }
    }

    public SearchResultPaginated search(String indexUid, String query, int page, int hitsPerPage, String[] filter) {
        try {
            Index index = getIndex(indexUid);
            return (SearchResultPaginated) index.search(
                    SearchRequest.builder()
                            .q(query)
                            .page(page)
                            .hitsPerPage(hitsPerPage)
                            .filter(filter)
                            .build()
            );
        } catch (Exception e) {
            log.error("搜索失败: {}", indexUid, e);
            throw new RuntimeException("搜索失败", e);
        }
    }
}
