package demo.cloud.share.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
@Schema(description = "分享链接状态",enumAsRef = true)
public enum ShareStatus {



    NO_ACTIVE(0),
    ACTIVE(1);


    @EnumValue
    private final int code;

    ShareStatus(int code) {
        this.code = code;
    }

}
