package com.agentos.invitation;

import java.net.URI;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
class InvitationSettings {
  final boolean enabled;
  final String frontend;
  final String from;
  final int retrySeconds;

  InvitationSettings(Environment env) {
    enabled = env.getProperty("agentos.invitations.enabled", Boolean.class, false);
    frontend = env.getProperty("agentos.invitations.frontend-base-url", "");
    String configuredFrom = env.getProperty("agentos.invitations.from", "");
    from = enabled ? InvitationWire.email(configuredFrom) : "";
    retrySeconds = env.getProperty("agentos.invitations.retry-delay-seconds", Integer.class, 60);
    int delay = env.getProperty("agentos.invitations.dispatch-delay-ms", Integer.class, 1000);
    if (retrySeconds < 1 || retrySeconds > 3600 || delay < 100 || delay > 60000)
      throw new IllegalArgumentException("Invalid invitation timing configuration");
    if (enabled) {
      // Bound each shared JavaMailSender blocking phase within the 30-second worker transaction.
      for (String timeout : new String[] {"connectiontimeout", "timeout", "writetimeout"}) {
        int millis =
            Integer.parseInt(
                env.getProperty("spring.mail.properties.mail.smtp." + timeout, "5000"));
        if (millis < 100 || millis > 5000)
          throw new IllegalArgumentException("Invitation SMTP timeouts must be 100..5000 ms");
      }
      URI u = URI.create(frontend);
      if (u.getHost() == null
          || u.getUserInfo() != null
          || u.getQuery() != null
          || u.getFragment() != null
          || !("https".equals(u.getScheme())
              || "http".equals(u.getScheme())
                  && java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(u.getHost())))
        throw new IllegalArgumentException("Invitations require trusted frontend URL");
    }
  }

  void require() {
    if (!enabled) throw new InvitationFailure(503, "INVITATIONS_UNAVAILABLE");
  }
}
