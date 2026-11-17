package com.agentos.orchestration.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Stores text verified by the trusted reconciliation caller. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class ExecutionOutputContentStore {
  private static final int MAX_BYTES = 262144;

  private final JdbcTemplate jdbc;
  private final ExecutionClaimStore claims;

  ExecutionOutputContentStore(JdbcTemplate jdbc, ExecutionClaimStore claims) {
    this.jdbc = jdbc;
    this.claims = claims;
  }

  void recordVerified(UUID organization, UUID run, UUID attempt, String content) {
    String digest = digest(content);
    claims.lockRun(organization, run, false);
    var evidence =
        jdbc.queryForList(
            """
            select output_sha256 from execution_attempt_reconciliations
            where run_id=? and attempt_id=? and outcome='SUCCEEDED'
            """,
            String.class,
            run,
            attempt);
    if (evidence.isEmpty() || !digest.equals(evidence.getFirst())) throw unavailable();
    var existing =
        jdbc.queryForList(
            "select content from execution_verified_output_content where run_id=? and attempt_id=?",
            String.class,
            run,
            attempt);
    if (!existing.isEmpty()) {
      if (content.equals(existing.getFirst())) return;
      throw unavailable();
    }
    jdbc.update(
        """
        insert into execution_verified_output_content
          (run_id,attempt_id,output_sha256,content,stored_at)
        values (?,?,?,?,?)
        """,
        run,
        attempt,
        digest,
        content,
        claims.now());
  }

  String read(UUID run, UUID attempt, String digest) {
    var content =
        jdbc.queryForList(
            """
            select content from execution_verified_output_content
            where run_id=? and attempt_id=? and output_sha256=?
            """,
            String.class,
            run,
            attempt,
            digest);
    if (content.isEmpty())
      throw new jakarta.persistence.EntityNotFoundException("Review output not found");
    if (!digest.equals(digest(content.getFirst()))) throw unavailable();
    return content.getFirst();
  }

  private static String digest(String content) {
    if (content == null
        || content.isEmpty()
        || content.length() > MAX_BYTES
        || !StandardCharsets.UTF_8.newEncoder().canEncode(content)) throw unavailable();
    byte[] encoded = content.getBytes(StandardCharsets.UTF_8);
    if (encoded.length > MAX_BYTES) throw unavailable();
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private static ExecutionReviewService.Conflict unavailable() {
    return new ExecutionReviewService.Conflict();
  }
}
