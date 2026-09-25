package com.jobassistant.ai;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Offline embedding model used when OpenRouter is not configured or unreachable.
 * <p>
 * It is a feature-hashing embedder: tokens and bigrams (after synonym normalisation and a
 * small concept expansion, e.g. "cloud" also activates aws/azure/gcp) are hashed with a
 * signed hash into a fixed-size vector, weighted with sublinear term frequency and L2
 * normalised. Cosine similarity between these vectors behaves like a soft lexical match.
 * It keeps the RAG pipeline fully functional offline; neural embeddings from OpenRouter
 * give better semantic recall.
 */
@Service
public class LocalHashingEmbeddingService implements EmbeddingService {

    public static final String PROVIDER = "local";
    public static final int DIMENSIONS = 1024;

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9+#]+");

    private static final Map<String, String> PHRASES = Map.ofEntries(
            Map.entry("spring boot", "springboot"),
            Map.entry("spring cloud", "springcloud"),
            Map.entry("node.js", "nodejs"),
            Map.entry("node js", "nodejs"),
            Map.entry("react.js", "react"),
            Map.entry("reactjs", "react"),
            Map.entry("react native", "reactnative"),
            Map.entry("next.js", "nextjs"),
            Map.entry("vue.js", "vuejs"),
            Map.entry(".net", "dotnet"),
            Map.entry("c#", "csharp"),
            Map.entry("machine learning", "machinelearning"),
            Map.entry("deep learning", "deeplearning"),
            Map.entry("rest api", "restapi"),
            Map.entry("rest apis", "restapi"),
            Map.entry("github actions", "githubactions"),
            Map.entry("ci/cd", "cicd"),
            Map.entry("power bi", "powerbi"),
            Map.entry("full stack", "fullstack"),
            Map.entry("full-stack", "fullstack"),
            Map.entry("front end", "frontend"),
            Map.entry("front-end", "frontend"),
            Map.entry("back end", "backend"),
            Map.entry("back-end", "backend"),
            Map.entry("tailwind css", "tailwind"));

    private static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("js", "javascript"),
            Map.entry("ts", "typescript"),
            Map.entry("k8s", "kubernetes"),
            Map.entry("postgres", "postgresql"),
            Map.entry("golang", "go"),
            Map.entry("ml", "machinelearning"),
            Map.entry("node", "nodejs"),
            Map.entry("bengaluru", "bangalore"),
            Map.entry("gurugram", "gurgaon"),
            Map.entry("bombay", "mumbai"),
            Map.entry("wfh", "remote"),
            Map.entry("developer", "engineer"),
            Map.entry("dev", "engineer"),
            Map.entry("programmer", "engineer"),
            Map.entry("devs", "engineer"),
            Map.entry("engineers", "engineer"),
            Map.entry("developers", "engineer"),
            Map.entry("jobs", "job"),
            Map.entry("roles", "role"),
            Map.entry("microservice", "microservices"),
            Map.entry("apis", "api"));

    /** Light concept expansion so that broad phrases reach concrete skills. */
    private static final Map<String, List<String>> CONCEPTS = Map.ofEntries(
            Map.entry("cloud", List.of("aws", "azure", "gcp")),
            Map.entry("backend", List.of("api", "server", "microservices")),
            Map.entry("frontend", List.of("ui", "react", "javascript")),
            Map.entry("devops", List.of("cicd", "kubernetes", "docker")),
            Map.entry("data", List.of("sql", "pipeline")),
            Map.entry("ai", List.of("machinelearning", "llms")),
            Map.entry("mobile", List.of("android", "ios")),
            Map.entry("testing", List.of("qa", "automation")),
            Map.entry("fullstack", List.of("frontend", "backend")));

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "of", "to", "in", "on", "for", "with", "at", "by", "from", "as", "is",
            "are", "be", "this", "that", "it", "its", "we", "you", "our", "your", "i", "im", "me", "my", "will",
            "have", "has", "who", "what", "which", "into", "across", "such", "etc", "can", "do", "does", "find",
            "show", "want", "looking", "need", "please", "any", "some", "all", "am", "also", "about", "using");

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String model() {
        return "local-hashing-bow-" + DIMENSIONS;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String t : texts) out.add(embedOne(t));
        return out;
    }

    private float[] embedOne(String text) {
        List<String> tokens = tokenize(text);
        Map<String, Double> features = new HashMap<>();
        for (int i = 0; i < tokens.size(); i++) {
            String tok = tokens.get(i);
            features.merge(tok, 1.0, Double::sum);
            for (String c : CONCEPTS.getOrDefault(tok, List.of())) features.merge(c, 0.35, Double::sum);
            if (i + 1 < tokens.size()) features.merge(tok + "_" + tokens.get(i + 1), 0.5, Double::sum);
        }
        float[] v = new float[DIMENSIONS];
        for (Map.Entry<String, Double> e : features.entrySet()) {
            int h = murmur(e.getKey());
            int idx = Math.floorMod(h, DIMENSIONS);
            float sign = ((h >>> 31) == 0) ? 1f : -1f;
            double tf = e.getValue();
            double weight = tf < 1.0 ? tf : 1.0 + Math.log(tf); // sublinear TF; partial features keep their weight
            v[idx] += sign * (float) weight;
        }
        normalise(v);
        return v;
    }

    /** A token plus the concrete terms its concept expands to ("cloud" -> cloud, aws, azure, gcp). */
    public static List<String> withConcepts(String token) {
        List<String> out = new ArrayList<>();
        out.add(token);
        out.addAll(CONCEPTS.getOrDefault(token, List.of()));
        return out;
    }

    /** Normalised tokens (lower-cased, synonyms mapped, stop words removed). Also used for keyword search. */
    public static List<String> tokenize(String text) {
        String s = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> p : PHRASES.entrySet()) s = s.replace(p.getKey(), " " + p.getValue() + " ");
        List<String> out = new ArrayList<>();
        for (String raw : TOKEN_SPLIT.split(s)) {
            if (raw.isEmpty() || STOPWORDS.contains(raw)) continue;
            String tok = SYNONYMS.getOrDefault(raw, raw);
            if (tok.length() > 4 && tok.endsWith("s") && !tok.endsWith("ss")) tok = tok.substring(0, tok.length() - 1);
            out.add(tok);
        }
        return out;
    }

    private static void normalise(float[] v) {
        double norm = 0;
        for (float x : v) norm += x * x;
        norm = Math.sqrt(norm);
        if (norm == 0) return;
        for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / norm);
    }

    /** 32-bit MurmurHash3 of the UTF-8 bytes - stable across JVMs (unlike String.hashCode semantics guarantees). */
    private static int murmur(String key) {
        byte[] data = key.getBytes(StandardCharsets.UTF_8);
        int h = 0x9747b28c;
        int len = data.length;
        int i = 0;
        while (i + 4 <= len) {
            int k = (data[i] & 0xff) | ((data[i + 1] & 0xff) << 8) | ((data[i + 2] & 0xff) << 16) | ((data[i + 3] & 0xff) << 24);
            k *= 0xcc9e2d51;
            k = Integer.rotateLeft(k, 15);
            k *= 0x1b873593;
            h ^= k;
            h = Integer.rotateLeft(h, 13);
            h = h * 5 + 0xe6546b64;
            i += 4;
        }
        int k = 0;
        switch (len - i) {
            case 3: k ^= (data[i + 2] & 0xff) << 16;
            case 2: k ^= (data[i + 1] & 0xff) << 8;
            case 1:
                k ^= (data[i] & 0xff);
                k *= 0xcc9e2d51;
                k = Integer.rotateLeft(k, 15);
                k *= 0x1b873593;
                h ^= k;
            default:
        }
        h ^= len;
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }
}
