package com.agentos.providervault;

import com.agentos.providercredentials.CredentialBoundary.CreateAttempt;
import com.agentos.providercredentials.CredentialBoundary.Failure;
import com.agentos.providercredentials.CredentialBoundary.Reference;
import com.agentos.providercredentials.CredentialBoundary.Scope;
import com.agentos.providercredentials.CredentialBoundary.Secrets;
import com.agentos.providerpreparation.ProviderPreparationJournal;
import java.util.UUID;

/** Explicit composition: durable preparation commit must succeed before any external write. */
public final class JournaledVaultSecrets implements Secrets {
  private final ProviderPreparationJournal journal;
  private final VaultCredentialSecrets vault;

  public JournaledVaultSecrets(ProviderPreparationJournal journal, VaultCredentialSecrets vault) {
    if (journal == null || vault == null) throw new Failure();
    this.journal = journal;
    this.vault = vault;
  }

  @Override
  public Reference store(Scope scope, byte[] borrowedCredential) {
    return store(scope, borrowedCredential, null);
  }

  @Override
  public Reference storeForCreate(Scope scope, byte[] borrowedCredential, CreateAttempt attempt) {
    if (attempt == null) throw new Failure();
    return store(scope, borrowedCredential, attempt);
  }

  private Reference store(Scope scope, byte[] borrowedCredential, CreateAttempt attempt) {
    ProviderPreparationJournal.Preparation preparation;
    try {
      String locator = UUID.randomUUID().toString();
      preparation =
          attempt == null
              ? journal.begin(scope, locator)
              : journal.beginCreate(scope, locator, attempt);
    } catch (RuntimeException failure) {
      // No network call is permitted after failed or uncertain journal begin.
      throw new Failure();
    }
    try {
      Reference reference = vault.storePrepared(preparation, borrowedCredential);
      journal.written(preparation);
      return reference;
    } catch (RuntimeException failure) {
      try {
        journal.unknown(preparation);
      } catch (RuntimeException journalUnavailable) {
        // The pre-existing PENDING row must remain pinned; never delete the Vault version.
      }
      throw new Failure();
    }
  }

  @Override
  public byte[] read(Reference reference) {
    return vault.read(reference);
  }

  @Override
  public void preparationFailed(Scope scope, Reference possibleReference) throws Exception {
    // CredentialBoundary may fail its HMAC step after store returned. The durable row is retained.
    // Invoke the explicitly configured reconciler; never infer orphanhood or issue a delete.
    vault.preparationFailed(scope, possibleReference);
  }

  @Override
  public String toString() {
    return "JournaledVaultSecrets[redacted]";
  }
}
