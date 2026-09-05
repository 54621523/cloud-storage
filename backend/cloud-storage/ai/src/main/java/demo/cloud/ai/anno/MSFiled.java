package demo.cloud.ai.anno;

import java.lang.annotation.*;


@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MSFiled {

    /**
     * 是否开启过滤
     */
    boolean openFilter() default false;

    /**
     * 是否不展示
     */
    boolean noDisplayed() default false;

    /**
     * 是否开启排序
     */
    boolean openSort() default false;

    /**
     * 是否可搜索（全文检索）
     */
    boolean openSearch() default false;

    /**
     * 处理的字段名（若为空，则使用字段名本身）
     */
    String key() default "";
}

