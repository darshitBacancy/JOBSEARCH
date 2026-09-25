package com.jobassistant.rag;

import com.jobassistant.ai.LocalHashingEmbeddingService;
import com.jobassistant.dto.IndexStatusDto;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "rag.vector-store-file=target/test-index/vector-store.bin")
class IndexingServiceTest {

    private static final Path SNAPSHOT = Path.of("target/test-index/vector-store.bin");

    @Autowired
    IndexingService indexingService;
    @Autowired
    InMemoryVectorStore vectorStore;
    @MockitoSpyBean
    LocalHashingEmbeddingService embedder;

    @BeforeAll
    static void cleanSnapshot() throws Exception {
        Files.deleteIfExists(SNAPSHOT);
    }

    @Test
    void startupIndexesEveryJobIntoSectionChunksAndPersistsASnapshot() {
        IndexStatusDto status = indexingService.status();

        assertThat(status.indexed()).isTrue();
        assertThat(status.jobCount()).isEqualTo(120);
        assertThat(status.chunkCount()).isEqualTo(600); // 5 sections per job
        assertThat(status.embeddingProvider()).isEqualTo("local");
        assertThat(status.dimensions()).isEqualTo(LocalHashingEmbeddingService.DIMENSIONS);
        assertThat(Files.exists(SNAPSHOT)).isTrue();

        // every job is represented, and every chunk links back to its job
        assertThat(vectorStore.findByJobId(109)).hasSize(5)
                .allSatisfy(r -> assertThat(r.metadata()).containsEntry("jobId", "109"));
        assertThat(vectorStore.findByJobId(109).stream().map(VectorRecord::section).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("OVERVIEW", "SKILLS", "REQUIREMENTS", "RESPONSIBILITIES", "BENEFITS");
    }

    @Test
    void restartReusesTheSnapshotWithoutRegeneratingEmbeddings_andReindexForcesARebuild() {
        clearInvocations(embedder);

        IndexStatusDto restarted = indexingService.initialise(); // what happens on the next application start
        assertThat(restarted.indexed()).isTrue();
        assertThat(restarted.message()).contains("snapshot");
        verify(embedder, never()).embed(anyList());

        IndexStatusDto reindexed = indexingService.reindex(); // POST /api/rag/reindex
        assertThat(reindexed.chunkCount()).isEqualTo(600);
        verify(embedder, atLeastOnce()).embed(anyList());
    }
}
