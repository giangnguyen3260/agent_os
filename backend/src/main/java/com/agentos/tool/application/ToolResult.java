package com.agentos.tool.application;

import java.util.Map;

public record ToolResult(String status, Map<String, Object> output) {}
