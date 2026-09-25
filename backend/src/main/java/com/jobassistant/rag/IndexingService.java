package com.jobassistant.rag;

import com.jobassistant.ai.AiUnavailableException;
import com.jobassistant.ai.EmbeddingService;
import com.jobassistant.config.RagProperties;
import com.jobassistant.dto.IndexStatusDto;
import com.jobassistant.entity.Job;
import com.jobassistant.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Indexing pipeline: load jobs -> build documents -> chunk -> embed -> store vectors.
 * <p>
 * On start-up the persisted snapshot is reused when it was built from the same dataset with
 * the same embedding provider/model, so embeddings are not regenerated on every restart.
 * {@link #reindex()} (exposed as {@code POST /api/rag/reindex}) forces a rebuild.
 */
@Service
public class IndexingService {

    private static final Logger log = LoggerFactory.getLogger(IndexingService.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_RATE_LIMIT_WAITS = 20;
    private static final Pattern RETRY_IN = Pattern.compile("retry in ([0-9]+(?:\\.[0-9]+)?)s", Pattern.CASE_INSENSITIVE);

    public enum Status { NOT_INDEXED, INDEXING, READY, FAILED }

    private final JobDataLoader dataLoader;
    private final JobRepository jobRepository;
    private final JobDocumentBuilder documentBuilder;
    private final InMemoryVectorStore vectorStore;
    private final EmbeddingProviderSelector selector;
    private final RagProperties props;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile Status status = Status.NOT_INDEXED;
    private volatile String lastMessage = "Index not built yet";
    private volatile String datasetHash;

    public IndexingService(JobDataLoader dataLoader, JobRepository jobRepository, JobDocumentBuilder documentBuilder,
                           InMemoryVectorStore vectorStore, EmbeddingProviderSelector selector, RagProperties props) {
        this.dataLoader = dataLoader;
        this.jobRepository = jobRepository;
        this.documentBuilder = documentBuilder;
        this.vectorStore = vectorStore;
        this.selector = selector;
        this.props = props;
    }

    /**
     * Start-up path: sync jobs into the DB, then reuse the snapshot if it is still valid,
     * otherwise build the index.
     */
    public IndexStatusDto initialise() {
        lock.lock();
        try {
            JobDataLoader.LoadResult load = dataLoader.load();
            datasetHash = load.datasetHash();
            Optional<EmbeddingService> preferred = selector.preferred();
            if (!props.reindexOnStartup() && vectorStore.loadSnapshot()) {
                IndexInfo info = vectorStore.info().orElseThrow();
                boolean sameData = load.datasetHash().equals(info.datasetHash());
                boolean sameModel = preferred.map(p -> p.provider().equals(info.embeddingProvider())
                        && p.model().equals(info.embeddingModel())).orElse(false);
                if (sameData && sameModel) {
                    status = Status.READY;
                    lastMessage = "Loaded existing index from snapshot (no embeddings regenerated)";
                    log.info(lastMessage);
                    return status();
                }
                log.info("Snapshot is stale (sameData={}, sameModel={}); rebuilding index", sameData, sameModel);
            }
            return buildIndex();
        } catch (RuntimeException e) {
            status = Status.FAILED;
            lastMessage = "Indexing failed: " + e.getMessage();
            log.error(lastMessage, e);
            return status();
        } finally {
            lock.unlock();
        }
    }

    /** Force a full rebuild (reloads the dataset first). */
    public IndexStatusDto reindex() {
        lock.lock();
        try {
            datasetHash = dataLoader.load().datasetHash();
            return buildIndex();
        } catch (RuntimeException e) {
            status = Status.FAILED;
            lastMessage = "Re-indexing failed: " + e.getMessage();
            log.error(lastMessage, e);
            return status();
        } finally {
            lock.unlock();
        }
    }

    private IndexStatusDto buildIndex() {
        status = Status.INDEXING;
        long start = System.currentTimeMillis();
        List<Job> jobs = jobRepository.findAll();
        jobs.sort(Comparator.comparing(Job::getId));
        List<JobDocumentBuilder.Chunk> chunks = new ArrayList<>();
        for (Job job : jobs) chunks.addAll(documentBuilder.chunk(job));
        log.info("Built {} chunks from {} jobs", chunks.size(), jobs.size());

        List<String> errors = new ArrayList<>();
        for (EmbeddingService embedder : selector.indexingCandidates()) {
            try {
                List<float[]> vectors = embedAll(embedder, chunks);
                List<VectorRecord> records = new ArrayList<>(chunks.size());
                for (int i = 0; i < chunks.size(); i++) {
                    JobDocumentBuilder.Chunk c = chunks.get(i);
                    records.add(new VectorRecord(c.documentId(), c.jobId(), c.section().name(), c.index(), c.text(),
                            vectors.get(i), c.metadata()));
                }
                int dims = vectors.isEmpty() ? 0 : vectors.get(0).length;
                IndexInfo info = new IndexInfo(embedder.provider(), embedder.model(), dims, datasetHash, jobs.size(),
                        records.size(), Instant.now());
                vectorStore.replaceAll(records, info);
                vectorStore.saveSnapshot();
                long took = System.currentTimeMillis() - start;
                status = Status.READY;
                lastMessage = "Indexed " + records.size() + " chunks from " + jobs.size() + " jobs with "
                        + embedder.provider() + " (" + embedder.model() + ") in " + took + " ms"
                        + (errors.isEmpty() ? "" : "; fallback used because: " + String.join("; ", errors));
                log.info(lastMessage);
                return withDuration(status(), took);
            } catch (AiUnavailableException e) {
                String msg = embedder.provider() + " embeddings failed: " + e.getMessage();
                errors.add(msg);
                log.warn(msg);
            }
        }
        status = vectorStore.size() > 0 ? Status.READY : Status.FAILED;
        lastMessage = "No embedding provider could build the index: " + String.join("; ", errors);
        log.error(lastMessage);
        return status();
    }

    private List<float[]> embedAll(EmbeddingService embedder, List<JobDocumentBuilder.Chunk> chunks) {
        List<float[]> out = new ArrayList<>(chunks.size());
        int batch = props.embeddingBatchSize();
        for (int from = 0; from < chunks.size(); from += batch) {
            List<String> texts = chunks.subList(from, Math.min(from + batch, chunks.size())).stream()
                    .map(JobDocumentBuilder.Chunk::text).toList();
            out.addAll(embedWithRetry(embedder, texts));
            if (!"local".equals(embedder.provider())) {
                log.info("Embedded {}/{} chunks with {}", Math.min(from + batch, chunks.size()), chunks.size(),
                        embedder.model());
            }
        }
        return out;
    }

    private List<float[]> embedWithRetry(EmbeddingService embedder, List<String> texts) {
        AiUnavailableException last = null;
        int rateLimitWaits = 0;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return embedder.embed(texts);
            } catch (AiUnavailableException e) {
                last = e;
                if (!e.isRetryable()) throw e;
                long waitMs = 1000L * attempt * attempt;
                if (isRateLimit(e) && rateLimitWaits < MAX_RATE_LIMIT_WAITS) {
                    // Free-tier quotas are per minute: wait for the window to reset instead of giving up
                    rateLimitWaits++;
                    attempt--;
                    waitMs = retryDelayMs(e.getMessage());
                    log.info("Embedding quota reached; waiting {} s before continuing", waitMs / 1000);
                } else if (attempt >= MAX_ATTEMPTS) {
                    break;
                }
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    private static boolean isRateLimit(AiUnavailableException e) {
        String m = e.getMessage();
        return m != null && (m.contains("HTTP 429") || m.contains("RESOURCE_EXHAUSTED"));
    }

    /** Honours the provider's "retry in 11.2s" hint when present; otherwise waits a quota window. */
    static long retryDelayMs(String message) {
        if (message != null) {
            Matcher m = RETRY_IN.matcher(message);
            if (m.find()) return Math.min(90_000L, (long) (Double.parseDouble(m.group(1)) * 1000) + 1_000L);
        }
        return 30_000L;
    }

    public IndexStatusDto status() {
        Optional<IndexInfo> info = vectorStore.info();
        return new IndexStatusDto(status == Status.READY && vectorStore.size() > 0,
                info.map(IndexInfo::jobCount).orElse(0), vectorStore.size(),
                info.map(IndexInfo::embeddingProvider).orElse(null), info.map(IndexInfo::embeddingModel).orElse(null),
                info.map(IndexInfo::dimensions).orElse(0), info.map(i -> i.indexedAt().toString()).orElse(null),
                null, status.name() + ": " + lastMessage);
    }

    public Status currentStatus() {
        return status;
    }

    private static IndexStatusDto withDuration(IndexStatusDto s, long took) {
        return new IndexStatusDto(s.indexed(), s.jobCount(), s.chunkCount(), s.embeddingProvider(), s.embeddingModel(),
                s.dimensions(), s.indexedAt(), took, s.message());
    }
}
