package demo.cloud.outer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@Builder
@ToString
public class IngestRequest {


    private String bucket;
    @JsonProperty("oss_key")
    private String ossKey;


    private DocumentMetadata metadata;
}