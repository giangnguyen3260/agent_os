package com.agentos.providervault;

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
    ProviderPreparationJournal.Preparation preparation;
    try {
      preparation = journal.begin(scope, UUID.randomUUID().toString());
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
