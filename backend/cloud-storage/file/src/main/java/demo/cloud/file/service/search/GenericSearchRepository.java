package demo.cloud.file.service.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meilisearch.sdk.model.SearchResultPaginated;
import demo.cloud.common.pojo.PageResult;
import demo.cloud.file.config.MeilisearchTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public abstract class GenericSearchRepository<T> {

    private final Class<T> documentClass;
    private final String indexUid;
    private final String primaryKey;
    private final ObjectMapper objectMapper;

    @Autowired
    private MeilisearchTemplate meilisearchTemplate;

    public GenericSearchRepository(Class<T> documentClass, ObjectMapper objectMapper) {
        this.documentClass = documentClass;
        this.objectMapper = objectMapper;
        MeiliIndexConfig config = MeiliSearchConfigHelper.extractConfig(documentClass);
        this.indexUid = config.getUid();
        this.primaryKey = config.getPrimaryKey();
    }

    public void initIndex() {
        meilisearchTemplate.createIndex(indexUid, primaryKey);
        MeiliIndexConfig config = MeiliSearchConfigHelper.extractConfig(documentClass);
        meilisearchTemplate.updateSettings(
                indexUid,
                config.getSearchableAttributes(),
                config.getFilterableAttributes(),
                config.getSortableAttributes()
        );
    }

    public void addDocument(T document) {
        meilisearchTemplate.addDocuments(indexUid, Collections.singletonList(document), primaryKey);
    }

    public void addDocuments(List<T> documents) {
        meilisearchTemplate.addDocuments(indexUid, documents, primaryKey);
    }

    public void updateDocument(T document) {
        meilisearchTemplate.updateDocuments(indexUid, Collections.singletonList(document), primaryKey);
    }

    public void deleteDocument(String id) {
        meilisearchTemplate.deleteDocument(indexUid, id);
    }

    /**
     * 原始搜索，返回 SDK 的原生结果
     */
    public SearchResultPaginated searchRaw(String keyword, List<String> filters, int page, int pageSize) {
        return meilisearchTemplate.search(indexUid, keyword, page, pageSize, filters.toArray(new String[0]));
    }

    /**
     * 便捷搜索：直接返回转换后的文档列表 + 分页信息（自定义包装）
     */
    public PageResult<T> search(String keyword, List<String> filters, int page, int pageSize) {
        SearchResultPaginated raw = searchRaw(keyword, filters, page, pageSize);
        List<T> documents = convertHits(raw.getHits(), documentClass);
        PageResult<T> pageResult = new PageResult<>();
        pageResult.setTotal((long) raw.getTotalHits());
        pageResult.setSize((long) raw.getHitsPerPage());
        pageResult.setPages((long) raw.getPage());
        pageResult.setList(documents);
        return pageResult;
    }


    /**
     * 将 hits (List<HashMap<String,Object>>) 转换为 List<T>
     */
    @SuppressWarnings("unchecked")
    protected List<T> convertHits(ArrayList<HashMap<String, Object>> hits, Class<T> clazz) {
        if (hits == null || hits.isEmpty()) {
            return Collections.emptyList();
        }
        return hits.stream()
                .map(map -> objectMapper.convertValue(map, clazz))
                .collect(Collectors.toList());
    }
}