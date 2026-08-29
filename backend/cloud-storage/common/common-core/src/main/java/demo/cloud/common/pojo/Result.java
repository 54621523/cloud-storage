package demo.cloud.common.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import lombok.Data;

import java.io.Serializable;

/**
 * 后端统一返回结果
 */
@Data
@Schema(description = "通用响应对象")
public class Result<T> implements Serializable {

    @Schema(description = "响应状态码，1表示成功", example = "1")
    private Integer code; // 状态码：1成功，0和其它数字为失败
    @Schema(description = "响应消息", example = "操作成功")
    private String msg;   // 提示信息



    @Nullable
    @Schema(
            description = "响应数据",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private T data;       // 响应数据

    public Result() {}

    /**
     * 成功（无数据）
     */
    public static <T> Result<T> success() {
        Result<T> result = new Result<>();
        result.code = 1;
        result.msg = "操作成功";
        result.data = null;
        return result;
    }

    /**
     * 成功（带数据）
     */
    public static <T> Result<T> success(T object) {
        Result<T> result = new Result<>();
        result.code = ResultCode.SUCCESS.getCode();
        result.msg = ResultCode.SUCCESS.getMessage();
        result.data = object;
        return result;
    }

    /**
     * 失败（仅传消息，兼容你原先的写法）
     */
    public static <T> Result<T> error(String msg) {
        Result<T> result = new Result<>();
        result.code = 0;
        result.msg = msg;
        return result;
    }

    /**
     * 失败（传入自定义状态码和消息，配合 BusinessException 使用）
     */
    public static <T> Result<T> error(Integer code, String msg) {
        Result<T> result = new Result<>();
        result.code = code;
        result.msg = msg;
        return result;
    }

    /**
     * 失败（传入 ResultCode 枚举，最优雅的写法）
     */
    public static <T> Result<T> error(ResultCode resultCode) {
        Result<T> result = new Result<>();
        result.code = resultCode.getCode();
        result.msg = resultCode.getMessage();
        return result;
    }
}