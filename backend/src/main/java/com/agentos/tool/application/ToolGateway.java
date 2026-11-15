package com.agentos.tool.application;

public interface ToolGateway {
  /** Executes a tool action only after server-side authorization. */
  ToolResult execute(ToolActionRequest request);
}
