package demo.cloud.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Config;
import demo.cloud.ai.vector.MeilisearchVectorStore;
import lombok.Data;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "meilisearch")
public class MeilisearchConfig {

    private String hostUrl;
    private boolean enabled;

    @Bean
    public Client meilisearchClient() {
        Config config = new Config("http://localhost:7700");
        return new Client(config);
    }

    @Bean
    public VectorStore meilisearchVectorStore(Client meilisearchClient){
        return MeilisearchVectorStore.builder()
                .indexName("document_index")
                .meilisearchClient(meilisearchClient)
                .objectMapper(new ObjectMapper())
                .build();
    }

    @Bean
    public VectorStore experienceStore(Client meilisearchClient){
        return MeilisearchVectorStore.builder()
                .indexName("experience_index")
                .meilisearchClient(meilisearchClient)
                .objectMapper(new ObjectMapper().registerModule(new JavaTimeModule()))
                .build();
    }
}
