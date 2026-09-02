package demo.cloud.file.service.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import demo.cloud.common.pojo.PageResult;
import demo.cloud.file.pojo.FileDocument;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Repository
public class FileSearchRepository extends GenericSearchRepository<FileDocument> {

    public FileSearchRepository(ObjectMapper objectMapper) {
        super(FileDocument.class, objectMapper);
    }

    @PostConstruct
    public void initialize() {
        initIndex();  // 父类方法
    }

    public PageResult<FileDocument> searchFile(String keyword,          // 模糊关键词
                                       Long userId,      // 当前用户ID
                                       Long parentId,           // 可选：限定某个文件夹内搜索
                                       String extension,        // 可选：限定后缀
                                       Long minSize, Long maxSize,
                                       int page, int pageSize) {
        List<String> filters = new ArrayList<>();

        // ----- 权限过滤-----
        // 规则：用户能看到 自己拥有的
        String permissionFilter = "(userId=" + userId + ")";
        filters.add(permissionFilter);

        // ----- 状态过滤（默认排除回收站）-----
        filters.add("status=0");

        // ----- 业务过滤条件 -----
        if (parentId != null && parentId > 0) {
            filters.add("parentId=" + parentId); // 当前文件夹内搜索
        }
        if (StringUtils.hasText(extension)) {
            filters.add("extension=" + extension);
        }
        if (minSize != null) filters.add("fileSize >= " + minSize);
        if (maxSize != null) filters.add("fileSize <= " + maxSize);

        return super.search(keyword, filters, page, pageSize);
    }


}
