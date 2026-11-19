package com.agentos.orchestration.application;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal trusted reconciliation boundary. Not a public provider-result ingress. */
@Service
class ExecutionResultPublicationService {
  private final ExecutionAttemptReconciliationStore reconciliations;
  private final ExecutionOutputContentStore outputs;
  private final ExecutionRunReviewStore reviews;
  private final TransactionTemplate transaction;

  ExecutionResultPublicationService(
      PlatformTransactionManager manager,
      ExecutionAttemptReconciliationStore reconciliations,
      ExecutionOutputContentStore outputs,
      ExecutionRunReviewStore reviews) {
    this.reconciliations = reconciliations;
    this.outputs = outputs;
    this.reviews = reviews;
    this.transaction = new TransactionTemplate(manager);
    // The worker invokes this after verifying evidence and settling usage, outside business locks.
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  void publishVerified(
      UUID organization,
      UUID run,
      UUID attempt,
      long revision,
      String evidenceSha256,
      String outputSha256,
      String content) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Result publication requires an independent worker transaction.");
    }
    transaction.executeWithoutResult(
        status -> {
          reconciliations.recordVerified(
              organization,
              run,
              attempt,
              ExecutionAttemptReconciliationStore.Outcome.SUCCEEDED,
              evidenceSha256,
              outputSha256);
          outputs.recordVerified(organization, run, attempt, content);
          reviews.submitReconciled(organization, run, attempt, revision, outputSha256);
        });
  }
}
