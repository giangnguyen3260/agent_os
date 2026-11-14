package com.agentos.providerchecks;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.providerchecks.ProviderAccessCheckDispatch.Result;
import com.agentos.providercredentials.CredentialBoundary.Provider;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ProviderModelsTransportTest {
  @Test
  void acceptsEachProviderShapeAndRejectsMalformedOrConflictingModels() {
    assertThat(body(Provider.OPENAI, "{\"data\":[{\"id\":\"model-a\"}]}"))
        .isEqualTo(Result.ACCESSIBLE);
    assertThat(
            body(
                Provider.ANTHROPIC,
                """
                {"data":[{"id":"model-a","display_name":"A"}],"has_more":true,"last_id":"model-a"}
                """))
        .isEqualTo(Result.ACCESSIBLE);
    assertThat(
            body(
                Provider.GOOGLE_GEMINI,
                """
                {"models":[{"name":"models/model-a"}],"nextPageToken":"page-two"}
                """))
        .isEqualTo(Result.ACCESSIBLE);
    assertThat(body(Provider.OPENAI, "{\"data\":[]}")).isEqualTo(Result.ACCESSIBLE);
    assertThat(body(Provider.OPENAI, "{\"data\":[]} {}")).isEqualTo(Result.PROTOCOL_ERROR);
    assertThat(body(Provider.OPENAI, "{\"data\":[],\"data\":[]}")).isEqualTo(Result.PROTOCOL_ERROR);
    assertThat(
            body(
                Provider.GOOGLE_GEMINI,
                """
{"models":[{"name":"models/a","displayName":"A"},{"name":"models/a","displayName":"B"}]}
"""))
        .isEqualTo(Result.PROTOCOL_ERROR);
    assertThat(
            body(
                Provider.ANTHROPIC,
                """
                {"data":[],"has_more":true,"last_id":null}
                """))
        .isEqualTo(Result.PROTOCOL_ERROR);
  }

  @Test
  void enforcesItemCapsAndMapsOnlyDefinedStatuses() {
    assertThat(
            body(
                Provider.GOOGLE_GEMINI,
                "{\"models\":["
                    + "{\"name\":\"models/model-a\"},".repeat(100)
                    + "{\"name\":\"models/model-b\"}]}"))
        .isEqualTo(Result.RESPONSE_LIMIT);
    assertThat(ProviderModelsTransport.status(301)).isEqualTo(Result.PROTOCOL_ERROR);
    assertThat(ProviderModelsTransport.status(401)).isEqualTo(Result.UNAUTHENTICATED);
    assertThat(ProviderModelsTransport.status(403)).isEqualTo(Result.FORBIDDEN);
    assertThat(ProviderModelsTransport.status(429)).isEqualTo(Result.RATE_LIMITED);
    assertThat(ProviderModelsTransport.status(503)).isEqualTo(Result.PROVIDER_UNAVAILABLE);
  }

  @Test
  void blocksNonPublicResolvedAddresses() throws Exception {
    for (String address :
        new String[] {
          "127.0.0.1",
          "10.0.0.1",
          "172.16.0.1",
          "192.168.0.1",
          "169.254.169.254",
          "100.64.0.1",
          "198.18.0.1",
          "203.0.113.7",
          "224.0.0.1",
          "::1",
          "fc00::1",
          "fe80::1",
          "::ffff:127.0.0.1",
          "2001:db8::1",
          "2002::1",
          "3fff::1"
        })
      assertThat(ProviderModelsTransport.publicAddress(InetAddress.getByName(address)))
          .as(address)
          .isFalse();
    assertThat(ProviderModelsTransport.publicAddress(InetAddress.getByName("8.8.8.8"))).isTrue();
    assertThat(ProviderModelsTransport.publicAddress(InetAddress.getByName("2606:4700::1111")))
        .isTrue();
  }

  private static Result body(Provider provider, String json) {
    return ProviderModelsTransport.body(provider, json.getBytes(StandardCharsets.UTF_8));
  }
}
