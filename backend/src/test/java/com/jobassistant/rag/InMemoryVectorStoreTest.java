package com.jobassistant.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobassistant.ai.LocalHashingEmbeddingService;
import com.jobassistant.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryVectorStoreTest {

    private final LocalHashingEmbeddingService embedder = new LocalHashingEmbeddingService();

    private InMemoryVectorStore store(String file) {
        return new InMemoryVectorStore(new ObjectMapper(),
                new RagProperties(null, null, file, null, null, null, null, null, null, null, null, null));
    }

    private VectorRecord record(String id, long jobId, String text) {
        return new VectorRecord(id, jobId, "SKILLS", 0, text, embedder.embed(text), Map.of("jobId", String.valueOf(jobId)));
    }

    private InMemoryVectorStore populated(String file) {
        InMemoryVectorStore s = store(file);
        List<VectorRecord> records = List.of(
                record("a", 1, "Java Spring Boot microservices on AWS"),
                record("b", 2, "React TypeScript frontend engineer building UI"),
                record("c", 3, "Kubernetes Terraform DevOps infrastructure on AWS"),
                record("d", 4, "Python machine learning with PyTorch and NLP"));
        s.replaceAll(records, new IndexInfo("local", embedder.model(), LocalHashingEmbeddingService.DIMENSIONS,
                "hash", 4, records.size(), Instant.now()));
        return s;
    }

    @Test
    void similaritySearchRanksTheMostSimilarChunkFirst() {
        InMemoryVectorStore s = populated("");
        List<ScoredChunk> results = s.similaritySearch(embedder.embed("frontend React developer"), 4, null);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).record().jobId()).isEqualTo(2);
        for (int i = 1; i < results.size(); i++) {
            assertThat(results.get(i - 1).score()).isGreaterThanOrEqualTo(results.get(i).score());
        }
    }

    @Test
    void similaritySearchHonoursTopKAndTheStructuredPreFilter() {
        InMemoryVectorStore s = populated("");
        assertThat(s.similaritySearch(embedder.embed("AWS"), 1, null)).hasSize(1);

        List<ScoredChunk> filtered = s.similaritySearch(embedder.embed("AWS cloud"), 10, Set.of(3L, 4L));
        assertThat(filtered).extracting(c -> c.record().jobId()).containsOnly(3L, 4L);
        assertThat(filtered.get(0).record().jobId()).isEqualTo(3L);
    }

    @Test
    void emptyStoreAndEmptyFilterReturnNothing() {
        assertThat(store("").similaritySearch(embedder.embed("java"), 5, null)).isEmpty();
        assertThat(populated("").similaritySearch(embedder.embed("java"), 5, Set.of())).isEmpty();
    }

    @Test
    void keywordSearchIsAvailableAsFallback() {
        List<ScoredChunk> results = populated("").keywordSearch("pytorch nlp", 5, null);
        assertThat(results).extracting(c -> c.record().jobId()).containsExactly(4L);
    }

    @Test
    void snapshotRoundTripRestoresVectorsWithoutReembedding(@TempDir Path dir) {
        String file = dir.resolve("vectors.bin").toString();
        InMemoryVectorStore original = populated(file);
        original.saveSnapshot();

        InMemoryVectorStore restored = store(file);
        assertThat(restored.loadSnapshot()).isTrue();
        assertThat(restored.size()).isEqualTo(4);
        assertThat(restored.info()).get().extracting(IndexInfo::embeddingProvider, IndexInfo::datasetHash)
                .containsExactly("local", "hash");
        List<ScoredChunk> results = restored.similaritySearch(embedder.embed("Kubernetes infrastructure"), 1, null);
        assertThat(results.get(0).record().jobId()).isEqualTo(3L);
        assertThat(results.get(0).record().metadata()).containsEntry("jobId", "3");
    }
}
