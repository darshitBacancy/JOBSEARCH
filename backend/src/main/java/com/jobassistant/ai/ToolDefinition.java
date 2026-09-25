package com.jobassistant.ai;

import java.util.Map;

/**
 * A backend function the model may call.
 *
 * @param parameters JSON Schema of the arguments object
 */
public record ToolDefinition(String name, String description, Map<String, Object> parameters) {
}
