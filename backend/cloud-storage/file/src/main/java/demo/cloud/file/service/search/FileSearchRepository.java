package demo.cloud.file.service.search;

import com.meilisearch.sdk.model.SearchResultPaginated;
import demo.cloud.file.config.MeilisearchTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Repository
public class DishSearchRepository {

    private static final String INDEX_UID = "dish";
    private static final String PRIMARY_KEY = "id";

    @Autowired
    private MeilisearchTemplate meilisearchTemplate;

    public void initIndex() {
        meilisearchTemplate.createIndex(INDEX_UID, PRIMARY_KEY);
        String[] searchableAttributes = {"name", "description", "categoryName", "flavors.value", "flavors.name"};
        String[] filterableAttributes = {"categoryId", "categoryName", "status", "price"};
        String[] sortableAttributes = {"price", "updateTime"};
        meilisearchTemplate.updateSettings(INDEX_UID, searchableAttributes, filterableAttributes, sortableAttributes);
    }

    public void addDish(DishDocument dishDocument) {
        List<DishDocument> documents = new ArrayList<>();
        documents.add(dishDocument);
        meilisearchTemplate.addDocuments(INDEX_UID, documents, PRIMARY_KEY);
    }

    public void addDishes(List<DishDocument> dishDocuments) {
        meilisearchTemplate.addDocuments(INDEX_UID, dishDocuments, PRIMARY_KEY);
    }

    public void updateDish(DishDocument dishDocument) {
        List<DishDocument> documents = new ArrayList<>();
        documents.add(dishDocument);
        meilisearchTemplate.updateDocuments(INDEX_UID, documents, PRIMARY_KEY);
    }

    public void deleteDish(Long dishId) {
        meilisearchTemplate.deleteDocument(INDEX_UID, String.valueOf(dishId));
    }

    public SearchResultPaginated searchDishes(String name, int page, int pageSize, Long categoryId, Integer status) {
        List<String> filters = new ArrayList<>();

        if (categoryId != null) {
            filters.add("categoryId=" + categoryId);
        }

        if (status != null) {
            filters.add("status=" + status);
        }

        String[] filterArray = filters.toArray(new String[0]);

        return meilisearchTemplate.search(INDEX_UID, name, page, pageSize, filterArray);
    }

    public SearchResultPaginated smartSearch(String keyword, int page, int pageSize, Long categoryId, Integer status,
                                             BigDecimal minPrice, BigDecimal maxPrice) {
        List<String> filters = new ArrayList<>();

        if (categoryId != null && categoryId > 0) {
            filters.add("categoryId=" + categoryId);
        }
        if (status != null) {
            filters.add("status=" + status);
        }
        if (minPrice != null && minPrice.compareTo(BigDecimal.ZERO) > 0) {
            filters.add("price >= " + minPrice);
        }
        if (maxPrice != null && maxPrice.compareTo(BigDecimal.ZERO) > 0) {
            filters.add("price <= " + maxPrice);
        }

        String[] filterArray = filters.toArray(new String[0]);
        return meilisearchTemplate.search(INDEX_UID, keyword, page, pageSize, filterArray);
    }
}
