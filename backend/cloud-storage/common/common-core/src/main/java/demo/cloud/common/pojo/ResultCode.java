package demo.cloud.common.pojo;

import lombok.Getter;

@Getter
public enum ResultCode {
    SUCCESS(1, "操作成功"),




    SHARE_NOT_FOUND(1001, "分享链接不存在或已失效"),
    SHARE_EXPIRED(1002, "分享链接已过期"),
    SHARE_PASSWORD_ERROR(1003, "提取码错误"),
    SHARE_NOT_PERMISSION(1004, "无权访问该文件夹"),
    SHARE_INVALID_ITEM(1005, "没有有效文件"),
    SHARE_PASSWORD_REQUIRED(400, "分享需要提取码"),
    SHARE_INVALID_REQUEST(400, "请求参数错误"),
    TOO_MANY_PASSWORD_ATTEMPTS(429, "密码尝试次数过多，请15分钟后重试"),


    UPLOAD_CANCELLED(2001, "上传已取消，无法合并"),
    UPLOAD_FAILED(2002, "上传已经失效，请重新上传"),






    ERROR_USER_PROFILE(-1, "错误的用户凭证"),
    UNKNOWN_ERROR(0, "未知错误");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}