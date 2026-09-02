package demo.cloud.file.pojo;


import demo.cloud.file.constant.FileItemType;
import demo.cloud.file.service.search.MSFiled;
import demo.cloud.file.service.search.MSIndex;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@MSIndex(uid = "user_file", primaryKey = "searchId")
public class FileDocument {

    @Schema(hidden = true)   // 前端不需要展示
    private String searchId;   // 建议在入库时生成 UUID.randomUUID().toString()

    @MSFiled(openSearch = true)
    private String namePure;   // 纯名称（不含后缀，便于模糊匹配）

    @MSFiled(openSearch = true)
    private String extension;      // 文件后缀（pdf, doc, mp4）

    @MSFiled(openFilter = true)
    private Integer status;        // 0-正常, 1-回收站, 2-冻结
    @MSFiled(openFilter = true)
    private Long userId;           // 文件所有者ID

    @MSFiled(openSearch = true)
    private String contentPreview; // 可选：txt/pdf 提取的文本内容（用于全文检索）


    @Schema(description = "文件/文件夹ID",
            example = "1001"
    )
    private Long id;

    @Schema(description = "文件/文件夹名称",
            example = "name"
    )
    @MSFiled(openSearch = true)
    private String name;

    @Schema(description = "文件大小（字节）",
            example = "1024"
    )
    @MSFiled(openFilter = true, openSort = true)
    private Long size;


    @Schema(description = "最后更新时间",
            example = "YYYY-MM-DD HH:MM:SS"
    )
    @MSFiled(openSort = true)
    private LocalDateTime updateTime;


    @Schema(description = "文件类型（文件/文件夹）",
            example = "FILE"
    )
    @MSFiled(openFilter = true)
    private FileItemType type;

    @Schema(description = "父目录ID",
            example = "1001"
    )
    @MSFiled(openFilter = true)
    private Long parentId;
}