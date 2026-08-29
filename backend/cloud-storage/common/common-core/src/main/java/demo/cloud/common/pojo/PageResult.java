package demo.cloud.common.pojo;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {
    private Long total;      // 总记录数
    private Long pages;      // 总页数
    private Long current;    // 当前页
    private Long size;       // 每页条数
    private List<T> list;    // 当前页数据列表

    // 提供一个静态方法，方便从 IPage 快速转换
    public static <T> PageResult<T> of(IPage<T> page) {
        PageResult<T> result = new PageResult<>();
        result.setTotal(page.getTotal());
        result.setPages(page.getPages());
        result.setCurrent(page.getCurrent());
        result.setSize(page.getSize());
        result.setList(page.getRecords());
        return result;
    }
}