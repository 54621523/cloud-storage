package demo.cloud.file.service.search;

import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class MeiliSearchConfigHelper {

    /**
     * 从实体类中提取完整的索引配置
     */
    public static MeiliIndexConfig extractConfig(Class<?> clazz) {
        // 1. 读取 @MSIndex
        MSIndex indexAnno = clazz.getAnnotation(MSIndex.class);
        if (indexAnno == null) {
            throw new IllegalArgumentException("Class " + clazz.getName() + " must be annotated with @MSIndex");
        }
        String uid = StringUtils.isEmpty(indexAnno.uid()) ? clazz.getSimpleName().toLowerCase() : indexAnno.uid();
        String primaryKey = StringUtils.isEmpty(indexAnno.primaryKey()) ? "id" : indexAnno.primaryKey();
        int maxTotalHits = indexAnno.maxTotalHits();
        int maxValuesPerFacet = indexAnno.maxValuesPerFacet();

        // 2. 读取字段上的 @MSFiled
        List<String> searchable = new ArrayList<>();
        List<String> filterable = new ArrayList<>();
        List<String> sortable = new ArrayList<>();
        List<String> displayed = new ArrayList<>();

        Field[] fields = clazz.getDeclaredFields();
        for (Field field : fields) {
            MSFiled fieldAnno = field.getAnnotation(MSFiled.class);
            if (fieldAnno == null) continue;

            // key 若未指定则使用字段名
            String key = StringUtils.isEmpty(fieldAnno.key()) ? field.getName() : fieldAnno.key();

            if (fieldAnno.openSearch()) {
                searchable.add(key);
            }
            if (fieldAnno.openFilter()) {
                filterable.add(key);
            }
            if (fieldAnno.openSort()) {
                sortable.add(key);
            }
            if (fieldAnno.noDisplayed()) {
                displayed.add(key);
            }
        }

        // 如果没标记任何 searchable，可以设置一个默认（比如所有 String 字段），但不是必须
        // 这里保持原样，由用户显式标记

        return new MeiliIndexConfig(
                uid,
                primaryKey,
                maxTotalHits,
                maxValuesPerFacet,
                searchable.toArray(new String[0]),
                filterable.toArray(new String[0]),
                sortable.toArray(new String[0]),
                displayed.toArray(new String[0])
        );
    }
}