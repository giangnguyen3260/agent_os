package com.agentos.governance.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApprovalInformationResponse(
    UUID approvalId,
    long revision,
    boolean canRequest,
    boolean canReply,
    List<Entry> entries,
    UUID latestQuestionId) {
  public record Entry(
      UUID id,
      String kind,
      UUID actorId,
      String actorName,
      String text,
      String evidence,
      Instant createdAt) {}

  public record Mutation(UUID approvalId, long revision, String status, UUID entryId) {}

  public record Result(Mutation mutation, boolean replay) {}

  @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
      using = ApprovalInformationCommandDeserializer.class)
  public record Command(
      Long revision,
      String question,
      UUID questionId,
      String answer,
      String evidence,
      boolean reply) {}
}
