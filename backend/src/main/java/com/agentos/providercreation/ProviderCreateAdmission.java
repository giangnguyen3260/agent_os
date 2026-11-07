package com.agentos.providercreation;

import com.agentos.auth.HumanIdentity;
import com.agentos.provideradmission.ProviderWriteAdmission;
import com.agentos.providercommands.CommandBoundary.Provider;
import java.util.UUID;
import javax.sql.DataSource;

/** Compatibility delegate for Create callers using the shared durable write admission. */
public final class ProviderCreateAdmission {
  private final ProviderWriteAdmission shared;

  public ProviderCreateAdmission(DataSource datasource) {
    this(new ProviderWriteAdmission(datasource));
  }

  public ProviderCreateAdmission(ProviderWriteAdmission shared) {
    if (shared == null) throw new IllegalArgumentException("INVALID_CONFIGURATION");
    this.shared = shared;
  }

  /** False means a concurrent own receipt exists; re-probe it before preparing credentials. */
  public boolean acquire(HumanIdentity actor, UUID org, UUID key, Provider provider) {
    return shared.acquireCreate(actor, org, key, provider);
  }
}
