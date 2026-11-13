package com.agentos.providertransport;

import com.agentos.providercommands.CommandBoundary.Operation;
import com.agentos.providercompletion.ProviderCompletionBoundary.LookupOutcome;
import com.agentos.providercompletion.ProviderCompletionBoundary.WriteOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Projects confirmed historical Rotate receipts and safe errors. */
public final class ProviderRotateResponses {
  private ProviderRotateResponses() {}

  public static void writeOutcome(
      ObjectMapper json, HttpServletResponse response, WriteOutcome outcome) throws IOException {
    ProviderConnectionChangeResponses.writeOutcome(json, response, outcome, Operation.ROTATE);
  }

  public static void writeLookup(
      ObjectMapper json, HttpServletResponse response, LookupOutcome outcome) throws IOException {
    ProviderConnectionChangeResponses.writeLookup(json, response, outcome, Operation.ROTATE);
  }
}
