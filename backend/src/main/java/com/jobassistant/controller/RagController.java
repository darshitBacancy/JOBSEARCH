package com.jobassistant.controller;

import com.jobassistant.config.RagProperties;
import com.jobassistant.dto.IndexStatusDto;
import com.jobassistant.rag.IndexingService;
import com.jobassistant.rag.RagService;
import com.jobassistant.rag.RagTrace;
import com.jobassistant.rag.RagTraceStore;
import com.jobassistant.rag.Retriever;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Index management and RAG observability endpoints. */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;
    private final IndexingService indexingService;
    private final RagTraceStore traceStore;
    private final RagProperties props;

    public RagController(RagService ragService, IndexingService indexingService, RagTraceStore traceStore,
                         RagProperties props) {
        this.ragService = ragService;
        this.indexingService = indexingService;
        this.traceStore = traceStore;
        this.props = props;
    }

    /** Rebuild the vector index from the dataset (re-embeds every chunk). */
    @PostMapping("/reindex")
    public IndexStatusDto reindex() {
        return ragService.indexDocuments();
    }

    @GetMapping("/status")
    public IndexStatusDto status() {
        return indexingService.status();
    }

    /** Inspect raw vector retrieval for a query: which chunks come back and with what similarity. */
    @GetMapping("/retrieve")
    public ResponseEntity<?> retrieve(@RequestParam("q") String query, @RequestParam(defaultValue = "10") int topK) {
        if (!props.debugEnabled()) return disabled();
        if (query.isBlank() || query.length() > 2000) throw new IllegalArgumentException("q must be 1-2000 characters");
        Retriever.Result r = ragService.retrieveRelevantDocuments(query, Math.min(Math.max(topK, 1), 50), null);
        return ResponseEntity.ok(Map.of(
                "query", query,
                "mode", r.mode(),
                "embeddingProvider", String.valueOf(r.embeddingProvider()),
                "embeddingModel", String.valueOf(r.embeddingModel()),
                "note", String.valueOf(r.note()),
                "results", r.chunks().stream().map(c -> Map.of(
                        "chunkId", c.record().documentId(),
                        "jobId", c.record().jobId(),
                        "section", c.record().section(),
                        "similarity", Math.round(c.score() * 10000) / 10000.0,
                        "metadata", c.record().metadata(),
                        "text", c.record().chunkText())).toList()));
    }

    @GetMapping("/traces")
    public ResponseEntity<?> traces(@RequestParam(defaultValue = "20") int limit) {
        if (!props.debugEnabled()) return disabled();
        List<RagTrace> recent = traceStore.recent(Math.min(limit, 50));
        return ResponseEntity.ok(recent);
    }

    @GetMapping("/traces/{traceId}")
    public ResponseEntity<?> trace(@PathVariable String traceId) {
        if (!props.debugEnabled()) return disabled();
        return traceStore.find(traceId).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    private static ResponseEntity<?> disabled() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "RAG debugging is disabled (rag.debug-enabled=false)"));
    }
}
