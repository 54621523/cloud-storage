package demo.cloud.common.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CursorPageResult<T> {
    private List<T> list;
    private String nextCursor;
    private boolean hasNext;
    // 可选：上一页游标
    // private String previousCursor;
}