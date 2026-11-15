package com.agentos.providerchecks;

import com.agentos.providerchecks.ProviderAccessCheckDispatch.Catalogue;
import com.agentos.providerchecks.ProviderAccessCheckDispatch.Claim;
import com.agentos.providerchecks.ProviderAccessCheckDispatch.Result;
import com.agentos.providerchecks.ProviderModelsTransport.Model;
import com.agentos.providerchecks.ProviderModelsTransport.Page;
import com.agentos.providerconnections.ProviderConnectionService;
import com.agentos.providercredentials.CredentialBoundary;
import com.agentos.providercredentials.CredentialBoundary.Provider;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.aop.support.AopUtils;

/** Fetches explicitly authorized pages and publishes a whole, ordered catalogue. */
public final class ProviderModelDiscoveryRunner {
  private static final int MAX_PAGES = 10;
  private static final int MAX_MODELS = 1000;
  private static final int MAX_BYTES = 4 * 1024 * 1024;

  @FunctionalInterface
  public interface Transport {
    Page requestPage(
        Provider provider,
        String workspace,
        byte[] key,
        OffsetDateTime deadline,
        String cursor,
        int remainingBytes)
        throws Exception;
  }

  private final ProviderAccessCheckDispatch dispatch;
  private final ProviderConnectionService connections;
  private final CredentialBoundary credentials;
  private final Transport transport;
  private final Clock clock;

  public ProviderModelDiscoveryRunner(
      ProviderAccessCheckDispatch dispatch,
      ProviderConnectionService connections,
      CredentialBoundary credentials,
      Transport transport,
      Clock clock) {
    this.dispatch = Objects.requireNonNull(dispatch);
    if (!"MODEL_DISCOVERY".equals(dispatch.checkKind()) || !AopUtils.isAopProxy(connections))
      throw new IllegalArgumentException("Discovery dispatch and transactional reader required");
    this.connections = connections;
    this.credentials = Objects.requireNonNull(credentials);
    this.transport = Objects.requireNonNull(transport);
    this.clock = Objects.requireNonNull(clock);
  }

  public void run(UUID organization, UUID check, UUID worker) {
    var claim = dispatch.claim(organization, check, worker);
    if (claim == null) return;
    var target = dispatch.target(claim);
    if (target == null) return;
    var connection = ProviderAccessCheckRunner.loadConnection(connections, organization, target);
    if (connection == null) {
      dispatch.finish(claim, Result.CREDENTIAL_UNAVAILABLE);
      return;
    }
    var scope =
        new CredentialBoundary.Scope(
            organization, target.connection(), connection.provider(), target.credentialRevision());
    var attempt = new Attempt();
    try {
      credentials.use(
          scope,
          connection.metadata(),
          key -> {
            attempt.verified = true;
            attempt.completion = fetch(claim, connection, key, attempt);
          });
    } catch (CredentialBoundary.Failure failure) {
      if (!attempt.verified) attempt.completion = Completion.failed(Result.CREDENTIAL_UNAVAILABLE);
      else if (attempt.authorized) attempt.completion = Completion.failed(Result.UNKNOWN);
    }
    if (attempt.completion != null)
      dispatch.finish(claim, attempt.completion.result(), attempt.completion.catalogue());
  }

  private Completion fetch(
      Claim claim, ProviderConnectionService.Connection connection, byte[] key, Attempt attempt)
      throws Exception {
    var models = new TreeMap<String, Model>();
    var seenCursors = new HashSet<String>();
    String cursor = null;
    int remainingBytes = MAX_BYTES;
    for (int ordinal = 1; ordinal <= MAX_PAGES; ordinal++) {
      var permission = dispatch.authorizePage(claim, ordinal);
      if (permission == null) return null;
      attempt.authorized = true;
      if (!permission.requestDeadline().toInstant().isAfter(clock.instant()))
        return Completion.failed(Result.UNKNOWN);
      var page =
          transport.requestPage(
              connection.provider(),
              connection.workspace(),
              key,
              permission.requestDeadline(),
              cursor,
              remainingBytes);
      if (page.result() == Result.RESPONSE_LIMIT) return cutoff(models, ordinal);
      if (page.result() != Result.ACCESSIBLE) return Completion.failed(page.result());
      if (page.bodyBytes() <= 0 || page.bodyBytes() > remainingBytes)
        return Completion.failed(Result.PROTOCOL_ERROR);

      cursor = page.nextCursor();
      if (cursor != null && !seenCursors.add(cursor))
        return Completion.failed(Result.PROTOCOL_ERROR);
      var additions = new HashMap<String, Model>();
      for (var model : page.models()) {
        var existing = models.get(model.id());
        if (existing == null) existing = additions.putIfAbsent(model.id(), model);
        if (existing != null && !existing.equals(model))
          return Completion.failed(Result.PROTOCOL_ERROR);
        if (models.size() + additions.size() > MAX_MODELS) return cutoff(models, ordinal);
      }
      models.putAll(additions);
      remainingBytes -= page.bodyBytes();
      if (cursor == null) return Completion.catalogue(models, false);
      if (remainingBytes == 0 || models.size() == MAX_MODELS || ordinal == MAX_PAGES)
        return Completion.catalogue(models, true);
    }
    throw new IllegalStateException("Discovery page limit");
  }

  private static Completion cutoff(TreeMap<String, Model> models, int ordinal) {
    return ordinal == 1
        ? Completion.failed(Result.RESPONSE_LIMIT)
        : Completion.catalogue(models, true);
  }

  private record Completion(Result result, Catalogue catalogue) {
    static Completion failed(Result result) {
      return new Completion(result, null);
    }

    static Completion catalogue(TreeMap<String, Model> models, boolean truncated) {
      return new Completion(
          Result.ACCESSIBLE, new Catalogue(List.copyOf(models.values()), truncated));
    }
  }

  private static final class Attempt {
    boolean verified;
    boolean authorized;
    Completion completion;
  }
}
