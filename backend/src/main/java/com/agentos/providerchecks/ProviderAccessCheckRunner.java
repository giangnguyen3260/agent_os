package com.agentos.providerchecks;

import com.agentos.auth.HumanIdentity;
import com.agentos.providerchecks.ProviderAccessCheckDispatch.Result;
import com.agentos.providerconnections.ProviderConnectionFailure;
import com.agentos.providerconnections.ProviderConnectionService;
import com.agentos.providercredentials.CredentialBoundary;
import com.agentos.providercredentials.CredentialBoundary.Provider;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.aop.support.AopUtils;

/** Processes an explicitly selected outbox item. Provider transport is supplied by the caller. */
public final class ProviderAccessCheckRunner {
  @FunctionalInterface
  public interface Transport {
    /** Make one bounded provider request using the borrowed key without retaining it. */
    Result request(Provider provider, String workspace, byte[] key, OffsetDateTime deadline)
        throws Exception;
  }

  private final ProviderAccessCheckDispatch dispatch;
  private final ProviderConnectionService connections;
  private final CredentialBoundary credentials;
  private final Transport transport;
  private final Clock clock;

  public ProviderAccessCheckRunner(
      ProviderAccessCheckDispatch dispatch,
      ProviderConnectionService connections,
      CredentialBoundary credentials,
      Transport transport,
      Clock clock) {
    this.dispatch = Objects.requireNonNull(dispatch);
    this.connections = Objects.requireNonNull(connections);
    if (!AopUtils.isAopProxy(connections))
      throw new IllegalArgumentException("Transactional connection reader required");
    this.credentials = Objects.requireNonNull(credentials);
    this.transport = Objects.requireNonNull(transport);
    this.clock = Objects.requireNonNull(clock);
  }

  public void run(UUID organization, UUID check, UUID worker) {
    var claim = dispatch.claim(organization, check, worker);
    if (claim == null) return;
    var target = dispatch.target(claim);
    if (target == null) return;

    ProviderConnectionService.Connection connection;
    try {
      var actor = new HumanIdentity(target.actor(), "", "", target.actorVersion());
      connection = connections.get(actor, organization, target.connection());
    } catch (ProviderConnectionFailure failure) {
      dispatch.finish(claim, Result.CREDENTIAL_UNAVAILABLE);
      return;
    }
    if (connection.revision() != target.revision()
        || connection.credentialRevision() != target.credentialRevision()
        || !"ENABLED".equals(connection.state())) {
      dispatch.finish(claim, Result.CREDENTIAL_UNAVAILABLE);
      return;
    }
    var scope =
        new CredentialBoundary.Scope(
            organization, target.connection(), connection.provider(), target.credentialRevision());
    var request = new RequestAttempt();
    try {
      credentials.use(
          scope,
          connection.metadata(),
          key -> {
            request.verified = true;
            var permission = dispatch.authorize(claim);
            if (permission == null) return;
            request.authorized = true;
            request.result =
                permission.requestDeadline().toInstant().isAfter(clock.instant())
                    ? transport.request(
                        connection.provider(),
                        connection.workspace(),
                        key,
                        permission.requestDeadline())
                    : Result.UNKNOWN;
          });
    } catch (CredentialBoundary.Failure failure) {
      if (request.verified && !request.authorized) return;
      request.result = request.authorized ? Result.UNKNOWN : Result.CREDENTIAL_UNAVAILABLE;
    }
    if (request.result != null) dispatch.finish(claim, request.result);
    else if (request.authorized) dispatch.finish(claim, Result.UNKNOWN);
  }

  private static final class RequestAttempt {
    boolean verified;
    boolean authorized;
    Result result;
  }
}
