package com.jobassistant.rag;

import com.jobassistant.ai.EmbeddingService;
import com.jobassistant.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Decides which {@link EmbeddingService} builds the index and embeds queries.
 * <ul>
 *   <li>{@code openrouter}: OpenRouter only</li>
 *   <li>{@code local}: the offline hashing embedder only</li>
 *   <li>{@code auto} (default): OpenRouter when a key is configured, local as fallback</li>
 * </ul>
 * Queries are always embedded by the provider that built the current index.
 */
@Component
public class EmbeddingProviderSelector {

    private final List<EmbeddingService> services;
    private final String mode;

    public EmbeddingProviderSelector(List<EmbeddingService> services, RagProperties props) {
        this.services = services;
        this.mode = props.embeddingProvider().toLowerCase(Locale.ROOT);
    }

    /** Providers to try for indexing, in order of preference, that are currently usable. */
    public List<EmbeddingService> indexingCandidates() {
        List<EmbeddingService> out = new ArrayList<>();
        switch (mode) {
            case "openrouter" -> byProvider("openrouter").filter(EmbeddingService::isAvailable).ifPresent(out::add);
            case "local" -> byProvider("local").ifPresent(out::add);
            default -> {
                byProvider("openrouter").filter(EmbeddingService::isAvailable).ifPresent(out::add);
                byProvider("local").ifPresent(out::add);
            }
        }
        return out;
    }

    public Optional<EmbeddingService> preferred() {
        return indexingCandidates().stream().findFirst();
    }

    public Optional<EmbeddingService> byProvider(String provider) {
        return services.stream().filter(s -> s.provider().equalsIgnoreCase(provider)).findFirst();
    }

    public String mode() {
        return mode;
    }
}
