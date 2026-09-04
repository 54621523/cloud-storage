package demo.cloud.ai;

import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.lang.Math.log;
@Slf4j
public class RetrievalEvaluator {

    public static boolean isRelevant(String chunkText, List<String> goldFacts) {
        return goldFacts.stream().anyMatch(fact -> chunkText.contains(fact));
    }

    public static Map<String, Object> evaluate(
            List<String> retrievedTexts,    // 检索返回的文本块
            List<String> allChunkTexts,     // 整个向量库所有文本块
            List<String> goldFacts) {

        log.info("检索到的块:\n{}\n, 所有块:\n{}\n, 当前事实\n{}\n",retrievedTexts.get(0), allChunkTexts.get(0), goldFacts.get(0));
        int totalRel = (int) allChunkTexts.stream()
                .filter(c -> isRelevant(c, goldFacts)).count();
        if (totalRel == 0) {
            return Map.of("p@5", 0.0, "r@5", 0.0, "mrr", 0.0,
                    "ndcg@5", 0.0, "h@1", 0, "h@3", 0, "h@5", 0);
        }

        // precision
        long retRel = retrievedTexts.stream()
                .filter(c -> isRelevant(c, goldFacts)).count();
        double precision = (double) retRel / retrievedTexts.size();

        // recall
        Set<String> retSet = new HashSet<>(retrievedTexts);
        long recalled = allChunkTexts.stream()
                .filter(c -> isRelevant(c, goldFacts) && retSet.contains(c))
                .count();
        double recall = (double) recalled / totalRel;

        // MRR
        double rr = 0.0;
        for (int i = 0; i < retrievedTexts.size(); i++) {
            if (isRelevant(retrievedTexts.get(i), goldFacts)) {
                rr = 1.0 / (i + 1);
                break;
            }
        }

        // NDCG@5
        double dcg = 0.0;
        for (int i = 0; i < Math.min(5, retrievedTexts.size()); i++) {
            if (isRelevant(retrievedTexts.get(i), goldFacts)) {
                dcg += 1.0 / log(i + 2);
            }
        }
        int idealRel = Math.min(totalRel, 5);
        double idcg = 0.0;
        for (int i = 0; i < idealRel; i++) {
            idcg += 1.0 / log(i + 2);
        }
        double ndcg = idcg > 0 ? dcg / idcg : 0;

        return Map.of(
                "p@5", precision,
                "r@5", recall,
                "mrr", rr,
                "ndcg@5", ndcg,
                "h@1", rr >= 1.0 ? 1 : 0,
                "h@3", retrievedTexts.stream().limit(3)
                        .anyMatch(c -> isRelevant(c, goldFacts)) ? 1 : 0,
                "h@5", retrievedTexts.stream().limit(5)
                        .anyMatch(c -> isRelevant(c, goldFacts)) ? 1 : 0
        );
    }
}