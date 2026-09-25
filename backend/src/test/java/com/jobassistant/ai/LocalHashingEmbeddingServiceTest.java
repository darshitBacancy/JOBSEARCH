package com.jobassistant.ai;

import com.jobassistant.rag.VectorMath;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalHashingEmbeddingServiceTest {

    private final LocalHashingEmbeddingService embedder = new LocalHashingEmbeddingService();

    @Test
    void embeddingsAreDeterministicNormalisedAndFixedSize() {
        float[] a = embedder.embed("Java Spring Boot developer");
        float[] b = embedder.embed("Java Spring Boot developer");

        assertThat(a).hasSize(LocalHashingEmbeddingService.DIMENSIONS).containsExactly(b);
        assertThat(VectorMath.dot(a, a)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    void relatedTextsAreMoreSimilarThanUnrelatedTexts() {
        float[] query = embedder.embed("backend engineer with springboot and k8s");
        float[] related = embedder.embed("Spring Boot microservices deployed on Kubernetes");
        float[] unrelated = embedder.embed("Figma product designer for mobile apps");

        assertThat(VectorMath.cosine(query, related)).isGreaterThan(VectorMath.cosine(query, unrelated));
    }

    @Test
    void batchPreservesOrder() {
        List<float[]> out = embedder.embed(List.of("react", "python"));
        assertThat(out.get(0)).containsExactly(embedder.embed("react"));
        assertThat(out.get(1)).containsExactly(embedder.embed("python"));
    }
}
