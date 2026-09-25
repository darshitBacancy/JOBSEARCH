package com.jobassistant.config;

import com.jobassistant.rag.IndexingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Loads the dataset and prepares the vector index once the application is up.
 * Runs in the background by default so the API is reachable while remote embeddings are generated
 * (searches fall back to keyword retrieval until the index is ready).
 */
@Component
public class IndexingStartupRunner {

    private static final Logger log = LoggerFactory.getLogger(IndexingStartupRunner.class);

    private final IndexingService indexingService;
    private final OpenRouterProperties openRouter;
    private final boolean async;

    public IndexingStartupRunner(IndexingService indexingService, OpenRouterProperties openRouter,
                                 @Value("${rag.index-async:true}") boolean async) {
        this.indexingService = indexingService;
        this.openRouter = openRouter;
        this.async = async;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("LLM provider: {}", openRouter);
        if (!openRouter.hasApiKey()) {
            log.warn("GEMINI_API_KEY is not set: running in search-only mode with local embeddings");
        }
        if (async) {
            Thread t = new Thread(indexingService::initialise, "rag-indexer");
            t.setDaemon(true);
            t.start();
        } else {
            indexingService.initialise();
        }
    }
}
