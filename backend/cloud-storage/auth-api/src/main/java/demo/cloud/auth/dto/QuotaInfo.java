package demo.cloud.auth.dto;


import lombok.Data;

@Data
public class QuotaInfo {

    Long userId;

    Long quotaTotal;

    Long quotaUsed;

    Long quotaAvailable;
}
