package com.jobassistant.search;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Recognises and canonicalises locations ("Bengaluru" -> "Bangalore", "Gurugram" -> "Gurgaon"). */
@Component
public class LocationNormalizer {

    private static final Map<String, List<String>> LOCATIONS = new LinkedHashMap<>();

    static {
        LOCATIONS.put("Bangalore", List.of("bangalore", "bengaluru", "blr"));
        LOCATIONS.put("Hyderabad", List.of("hyderabad", "hyd"));
        LOCATIONS.put("Pune", List.of("pune"));
        LOCATIONS.put("Chennai", List.of("chennai", "madras"));
        LOCATIONS.put("Mumbai", List.of("mumbai", "bombay"));
        LOCATIONS.put("Gurgaon", List.of("gurgaon", "gurugram"));
        LOCATIONS.put("Noida", List.of("noida"));
        LOCATIONS.put("Ahmedabad", List.of("ahmedabad"));
        LOCATIONS.put("Kochi", List.of("kochi", "cochin"));
        LOCATIONS.put("Kolkata", List.of("kolkata", "calcutta"));
        LOCATIONS.put("India", List.of("india"));
    }

    /** First recognised location in the text; cities take precedence over the country. */
    public Optional<String> detect(String text) {
        if (text == null) return Optional.empty();
        String lower = text.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> e : LOCATIONS.entrySet()) {
            if (e.getKey().equals("India")) continue;
            for (String alias : e.getValue()) {
                if (Pattern.compile("\\b" + Pattern.quote(alias) + "\\b").matcher(lower).find()) {
                    return Optional.of(e.getKey());
                }
            }
        }
        return Pattern.compile("\\bindia\\b").matcher(lower).find() ? Optional.of("India") : Optional.empty();
    }

    /** Canonical name for a location string from the LLM or UI (unknown values are kept as-is). */
    public String canonicalise(String location) {
        if (location == null || location.isBlank()) return null;
        String trimmed = location.trim();
        if (trimmed.equalsIgnoreCase("remote") || trimmed.equalsIgnoreCase("anywhere")) return null;
        return detect(trimmed).orElse(trimmed);
    }

    public boolean isCountry(String location) {
        return location != null && location.equalsIgnoreCase("India");
    }

    public boolean matches(String jobLocation, String requested) {
        if (requested == null) return true;
        if (jobLocation == null) return false;
        return jobLocation.toLowerCase(Locale.ROOT).contains(requested.toLowerCase(Locale.ROOT));
    }
}
