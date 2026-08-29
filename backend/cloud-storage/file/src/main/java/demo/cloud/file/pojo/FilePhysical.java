package demo.cloud.file.pojo;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 物理文件实体
 * 对应表: file_physical
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FilePhysical implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 文件MD5值（唯一索引）
     */
    private String md5;

    /**
     * 文件SHA-256值（可选，增强校验）
     */
    private String sha256;

    /**
     * 文件大小（字节）
     */
    private Long size;

    /**
     * MinIO存储路径
     */
    private String ossKey;

    /**
     * 引用计数（被多少用户拥有）
     */
    private Integer refCount;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 增加引用计数
     */
    public void incrementRefCount() {
        if (this.refCount == null) {
            this.refCount = 1;
        } else {
            this.refCount++;
        }
    }

    /**
     * 减少引用计数
     */
    public void decrementRefCount() {
        if (this.refCount != null && this.refCount > 0) {
            this.refCount--;
        }
    }

    /**
     * 判断是否可以被物理删除（无任何用户引用）
     */
    public boolean canDelete() {
        return this.refCount == null || this.refCount <= 0;
    }
}