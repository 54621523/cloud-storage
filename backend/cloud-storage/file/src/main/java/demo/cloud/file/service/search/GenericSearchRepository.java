package demo.cloud.file.service.search;

import com.meilisearch.sdk.model.SearchResultPaginated;
import demo.cloud.file.config.MeilisearchTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;

@Slf4j
@Repository
public class GenericSearchRepository<T> {

    private final Class<T> documentClass;
    private final String indexUid;
    private final String primaryKey;

    @Autowired
    private MeilisearchTemplate meilisearchTemplate;

    public GenericSearchRepository(Class<T> documentClass) {
        this.documentClass = documentClass;
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
     * 通用搜索，过滤条件由调用方通过 FilterBuilder 构建
     */
    public SearchResultPaginated<T> search(
            String keyword,
            List<String> filters,
            int page, int pageSize) {

        return meilisearchTemplate.search(
                indexUid,
                keyword,
                page,
                pageSize,
                filters.toArray(new String[0])
        );
    }
}