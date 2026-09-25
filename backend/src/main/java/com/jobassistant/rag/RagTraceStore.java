package com.jobassistant.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/** Keeps the most recent RAG traces in memory (and logs them at DEBUG level). */
@Component
public class RagTraceStore {

    private static final Logger log = LoggerFactory.getLogger(RagTraceStore.class);
    private static final int CAPACITY = 50;

    private final Deque<RagTrace> traces = new ArrayDeque<>();
    private final ObjectMapper mapper;

    public RagTraceStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public synchronized void add(RagTrace trace) {
        traces.addFirst(trace);
        while (traces.size() > CAPACITY) traces.removeLast();
        if (log.isDebugEnabled()) {
            try {
                log.debug("RAG trace:\n{}", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(trace));
            } catch (Exception ignored) {
                // logging only
            }
        }
    }

    public synchronized List<RagTrace> recent(int limit) {
        return new ArrayList<>(traces).subList(0, Math.min(Math.max(limit, 0), traces.size()));
    }

    public synchronized Optional<RagTrace> find(String traceId) {
        return traces.stream().filter(t -> t.getTraceId().equals(traceId)).findFirst();
    }
}
