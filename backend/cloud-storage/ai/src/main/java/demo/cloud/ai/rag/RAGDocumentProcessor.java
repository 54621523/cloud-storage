package demo.cloud.ai.rag;

import com.alibaba.cloud.ai.parser.tika.TikaDocumentParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.InputStream;
import java.util.List;

@Slf4j
@Component
public class RAGDocumentProcessor {

    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;
    private final TikaDocumentParser parser;
    private final S3Client s3Client;

    public RAGDocumentProcessor(VectorStore vectorStore, TokenTextSplitter textSplitter, S3Client s3Client) {
        this.vectorStore = vectorStore;
        this.textSplitter = textSplitter;
        this.s3Client = s3Client;
        this.parser = new TikaDocumentParser();
    }

    public void processDocumentForRAG(InputStream inputStream) {
// 1. 解析文档
        List<Document> documents = parser.parse(inputStream);

// 2. 文本分割（将大文档分割成小块）
        List<Document> splitDocuments = textSplitter.transform(documents);

// 3. 存储到向量数据库
        vectorStore.write(splitDocuments);

        log.info("成功加载 {} 个文档块到向量数据库", splitDocuments.size());
    }

    public List<Document> searchSimilarDocuments(String query) {
// 从向量数据库检索相似文档
        return vectorStore.similaritySearch(query);
    }

    public void processDocumentFromS3(String bucketName, String objectKey){
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .build();
        try {
            ResponseInputStream<GetObjectResponse> object = s3Client.getObject(getObjectRequest);
            this.processDocumentForRAG(object);

        } catch (S3Exception e) {
            System.err.println("OSS 错误: " + e.awsErrorDetails().errorMessage());
            throw new RuntimeException("获取 OSS 对象失败", e);
        }
    }
}