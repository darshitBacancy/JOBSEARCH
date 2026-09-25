package com.jobassistant.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits text into chunks of at most {@code maxChars}, preferring sentence/line boundaries
 * and carrying one sentence of overlap between consecutive chunks so context isn't lost.
 */
public final class TextChunker {

    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?])\\s+|\\n+");

    private TextChunker() {
    }

    public static List<String> split(String text, int maxChars) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;
        String trimmed = text.trim();
        if (trimmed.length() <= maxChars) {
            chunks.add(trimmed);
            return chunks;
        }
        String[] sentences = SENTENCE_BOUNDARY.split(trimmed);
        StringBuilder current = new StringBuilder();
        String previousSentence = null;
        for (String raw : sentences) {
            String sentence = raw.trim();
            if (sentence.isEmpty()) continue;
            // a single sentence longer than the limit is hard-wrapped on whitespace
            if (sentence.length() > maxChars) {
                if (!current.isEmpty()) {
                    chunks.add(current.toString().trim());
                    current.setLength(0);
                }
                chunks.addAll(hardWrap(sentence, maxChars));
                previousSentence = null;
                continue;
            }
            if (current.length() + sentence.length() + 1 > maxChars && !current.isEmpty()) {
                chunks.add(current.toString().trim());
                current.setLength(0);
                if (previousSentence != null && previousSentence.length() + sentence.length() + 1 <= maxChars) {
                    current.append(previousSentence).append(' '); // overlap
                }
            }
            current.append(sentence).append(' ');
            previousSentence = sentence;
        }
        if (!current.isEmpty()) chunks.add(current.toString().trim());
        return chunks;
    }

    private static List<String> hardWrap(String sentence, int maxChars) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (String word : sentence.split("\\s+")) {
            if (sb.length() + word.length() + 1 > maxChars && !sb.isEmpty()) {
                out.add(sb.toString().trim());
                sb.setLength(0);
            }
            sb.append(word).append(' ');
        }
        if (!sb.isEmpty()) out.add(sb.toString().trim());
        return out;
    }
}
