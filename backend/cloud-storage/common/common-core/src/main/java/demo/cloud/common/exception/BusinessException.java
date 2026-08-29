package demo.cloud.common.exception;

import demo.cloud.common.pojo.ResultCode;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final int code;

    // 构造方法1：传入错误码枚举
    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    // 构造方法2：传入错误码枚举和自定义消息（覆盖枚举中的默认消息）
    public BusinessException(ResultCode resultCode, String customMessage) {
        super(customMessage);
        this.code = resultCode.getCode();
    }

    // 构造方法3：直接传入 code 和 message（适用于临时动态错误）
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}