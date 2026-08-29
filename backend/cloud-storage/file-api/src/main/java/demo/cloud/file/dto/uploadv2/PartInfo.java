    package demo.cloud.file.dto.uploadv2;

    import lombok.AllArgsConstructor;
    import lombok.Data;
    import lombok.NoArgsConstructor;


    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    public class PartInfo {
        private Integer partNumber;
        private String eTag;
    }