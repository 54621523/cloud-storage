package demo.cloud.file.service.search;

import java.lang.annotation.*;


@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MSIndex {

    /**
     * 索引
     */
    String uid() default "";

    /**
     * 主键
     */
    String primaryKey() default "";

    /**
     * 分类最大数量
     */
    int maxValuesPerFacet() default 100;

    /**
     *  单次查询最大数量
     */
    int maxTotalHits() default 1000;
}
