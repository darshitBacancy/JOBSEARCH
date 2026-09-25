package com.jobassistant.ai;

import java.util.List;

/**
 * Turns text into dense vectors. Documents and queries must be embedded by the same
 * implementation/model, which is why the vector store records which provider built it.
 */
public interface EmbeddingService {

    /** Short provider id, e.g. "openrouter" or "local". */
    String provider();

    /** Model identifier recorded alongside the vectors. */
    String model();

    /** True when the service can be called (e.g. required configuration is present). */
    boolean isAvailable();

    /**
     * Embed a batch of texts. The result has the same size and order as the input.
     *
     * @throws AiUnavailableException when the provider fails
     */
    List<float[]> embed(List<String> texts);

    default float[] embed(String text) {
        return embed(List.of(text)).get(0);
    }
}
