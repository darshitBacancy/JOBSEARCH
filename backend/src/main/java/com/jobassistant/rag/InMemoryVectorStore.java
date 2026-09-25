package com.jobassistant.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobassistant.ai.LocalHashingEmbeddingService;
import com.jobassistant.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * In-memory vector database.
 * <ul>
 *   <li>All vectors live in memory; search is an exact (brute-force) cosine similarity scan,
 *       which for a few thousand chunks takes well under a millisecond.</li>
 *   <li>Structured pre-filtering: callers pass the set of job ids allowed by the SQL filters.</li>
 *   <li>Persistence: the index is snapshotted to a binary file and reloaded on start-up, so
 *       embeddings are not regenerated on every restart.</li>
 * </ul>
 * The index is swapped atomically (copy-on-write), so searches never see a half-built index.
 */
@Component
public class InMemoryVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryVectorStore.class);
    private static final int MAGIC = 0x4A534156; // "JSAV"
    private static final int FORMAT_VERSION = 1;
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper;
    private final Path snapshotPath;

    private volatile Snapshot current = new Snapshot(List.of(), null);

    private record Snapshot(List<VectorRecord> records, IndexInfo info) {
    }

    public InMemoryVectorStore(ObjectMapper mapper, RagProperties props) {
        this.mapper = mapper;
        this.snapshotPath = props.vectorStoreFile() == null || props.vectorStoreFile().isBlank()
                ? null : Path.of(props.vectorStoreFile());
    }

    @Override
    public void replaceAll(Collection<VectorRecord> records, IndexInfo info) {
        List<VectorRecord> normalised = new ArrayList<>(records.size());
        for (VectorRecord r : records) {
            normalised.add(new VectorRecord(r.documentId(), r.jobId(), r.section(), r.chunkIndex(), r.chunkText(),
                    VectorMath.normalise(r.embedding()), Map.copyOf(r.metadata())));
        }
        current = new Snapshot(List.copyOf(normalised), info);
    }

    @Override
    public List<ScoredChunk> similaritySearch(float[] queryEmbedding, int topK, Set<Long> allowedJobIds) {
        Snapshot snap = current;
        if (snap.records.isEmpty() || topK <= 0) return List.of();
        float[] q = VectorMath.normalise(queryEmbedding);
        PriorityQueue<ScoredChunk> heap = new PriorityQueue<>(Comparator.comparingDouble(ScoredChunk::score));
        for (VectorRecord r : snap.records) {
            if (allowedJobIds != null && !allowedJobIds.contains(r.jobId())) continue;
            if (r.embedding().length != q.length) continue; // guards against a mismatched query model
            double score = VectorMath.dot(q, r.embedding()); // both normalised -> cosine similarity
            if (heap.size() < topK) {
                heap.add(new ScoredChunk(r, score));
            } else if (score > heap.peek().score()) {
                heap.poll();
                heap.add(new ScoredChunk(r, score));
            }
        }
        List<ScoredChunk> out = new ArrayList<>(heap);
        out.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return out;
    }

    @Override
    public List<ScoredChunk> keywordSearch(String query, int topK, Set<Long> allowedJobIds) {
        Snapshot snap = current;
        Set<String> terms = new HashSet<>(LocalHashingEmbeddingService.tokenize(query));
        if (snap.records.isEmpty() || terms.isEmpty()) return List.of();
        List<ScoredChunk> scored = new ArrayList<>();
        for (VectorRecord r : snap.records) {
            if (allowedJobIds != null && !allowedJobIds.contains(r.jobId())) continue;
            List<String> tokens = LocalHashingEmbeddingService.tokenize(r.chunkText());
            if (tokens.isEmpty()) continue;
            Set<String> unique = new HashSet<>(tokens);
            long hits = terms.stream().filter(unique::contains).count();
            if (hits == 0) continue;
            double score = (double) hits / terms.size() / (1 + Math.log(1 + tokens.size() / 50.0));
            scored.add(new ScoredChunk(r, score));
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return scored.size() > topK ? new ArrayList<>(scored.subList(0, topK)) : scored;
    }

    @Override
    public List<VectorRecord> findByJobId(long jobId) {
        return current.records.stream().filter(r -> r.jobId() == jobId).toList();
    }

    @Override
    public int size() {
        return current.records.size();
    }

    @Override
    public Optional<IndexInfo> info() {
        return Optional.ofNullable(current.info);
    }

    // ------------------------------------------------------------------ persistence

    public boolean hasSnapshotFile() {
        return snapshotPath != null && Files.isRegularFile(snapshotPath);
    }

    /** Write the current index to disk (atomic rename). */
    public void saveSnapshot() {
        if (snapshotPath == null) return;
        Snapshot snap = current;
        if (snap.info == null) return;
        try {
            Path parent = snapshotPath.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = snapshotPath.resolveSibling(snapshotPath.getFileName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(MAGIC);
                out.writeInt(FORMAT_VERSION);
                IndexInfo i = snap.info;
                Map<String, String> header = new LinkedHashMap<>();
                header.put("embeddingProvider", i.embeddingProvider());
                header.put("embeddingModel", i.embeddingModel());
                header.put("dimensions", String.valueOf(i.dimensions()));
                header.put("datasetHash", i.datasetHash());
                header.put("jobCount", String.valueOf(i.jobCount()));
                header.put("chunkCount", String.valueOf(i.chunkCount()));
                header.put("indexedAt", i.indexedAt().toString());
                out.writeUTF(mapper.writeValueAsString(header));
                out.writeInt(snap.records.size());
                for (VectorRecord r : snap.records) {
                    out.writeUTF(r.documentId());
                    out.writeLong(r.jobId());
                    out.writeUTF(r.section());
                    out.writeInt(r.chunkIndex());
                    writeLongString(out, r.chunkText());
                    writeLongString(out, mapper.writeValueAsString(r.metadata()));
                    out.writeInt(r.embedding().length);
                    for (float f : r.embedding()) out.writeFloat(f);
                }
            }
            Files.move(tmp, snapshotPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("Vector store snapshot saved to {} ({} chunks)", snapshotPath.toAbsolutePath(), snap.records.size());
        } catch (IOException e) {
            log.warn("Could not save vector store snapshot to {}: {}", snapshotPath, e.getMessage());
        }
    }

    /** Load the snapshot file into memory. Returns false when missing or unreadable. */
    public boolean loadSnapshot() {
        if (!hasSnapshotFile()) return false;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(snapshotPath)))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT_VERSION) {
                log.warn("Ignoring vector store snapshot with unknown format: {}", snapshotPath);
                return false;
            }
            Map<String, String> h = mapper.readValue(in.readUTF(), MAP_TYPE);
            IndexInfo info = new IndexInfo(h.get("embeddingProvider"), h.get("embeddingModel"),
                    Integer.parseInt(h.get("dimensions")), h.get("datasetHash"), Integer.parseInt(h.get("jobCount")),
                    Integer.parseInt(h.get("chunkCount")), Instant.parse(h.get("indexedAt")));
            int count = in.readInt();
            List<VectorRecord> records = new ArrayList<>(count);
            for (int n = 0; n < count; n++) {
                String id = in.readUTF();
                long jobId = in.readLong();
                String section = in.readUTF();
                int chunkIndex = in.readInt();
                String text = readLongString(in);
                Map<String, String> meta = mapper.readValue(readLongString(in), MAP_TYPE);
                float[] v = new float[in.readInt()];
                for (int d = 0; d < v.length; d++) v[d] = in.readFloat();
                records.add(new VectorRecord(id, jobId, section, chunkIndex, text, v, meta));
            }
            current = new Snapshot(List.copyOf(records), info);
            log.info("Loaded vector store snapshot: {} chunks, provider={}, model={}", count,
                    info.embeddingProvider(), info.embeddingModel());
            return true;
        } catch (Exception e) {
            log.warn("Could not load vector store snapshot {}: {}", snapshotPath, e.getMessage());
            return false;
        }
    }

    private static void writeLongString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readLongString(DataInputStream in) throws IOException {
        byte[] bytes = new byte[in.readInt()];
        in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
