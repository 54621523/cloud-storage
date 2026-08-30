package demo.cloud.file.service.search;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MeiliIndexConfig {
    private String uid;
    private String primaryKey;
    private int maxTotalHits;
    private int maxValuesPerFacet;
    private String[] searchableAttributes;
    private String[] filterableAttributes;
    private String[] sortableAttributes;
    private String[] displayedAttributes;
}