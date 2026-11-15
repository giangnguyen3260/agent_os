package com.agentos.providerchecks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.providerchecks.ProviderAccessCheckDispatch.Result;
import com.agentos.providerchecks.ProviderModelsTransport.Model;
import com.agentos.providercredentials.CredentialBoundary.Provider;
import java.io.ByteArrayInputStream;
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
  void returnsValidatedPageWithDeduplicatedModelsAndOpaqueCursor() throws Exception {
    String json =
        """
        {"models":[{"name":"models/a","displayName":"é"},{"name":"models/a","displayName":"é"},
          {"name":"models/b"}],"nextPageToken":"page #+"}
        """;
    byte[] encoded = json.getBytes(StandardCharsets.UTF_8);
    var page =
        ProviderModelsTransport.page(
            Provider.GOOGLE_GEMINI, new ByteArrayInputStream(encoded), null, encoded.length);
    assertThat(page.result()).isEqualTo(Result.ACCESSIBLE);
    assertThat(page.models())
        .containsExactly(new Model("models/a", "é"), new Model("models/b", null));
    assertThat(page.bodyBytes()).isEqualTo(encoded.length);
    assertThat(page.nextCursor()).isEqualTo("page #+");

    var repeated =
        ProviderModelsTransport.page(
            Provider.GOOGLE_GEMINI,
            new ByteArrayInputStream(encoded),
            page.nextCursor(),
            encoded.length);
    assertThat(repeated.result()).isEqualTo(Result.PROTOCOL_ERROR);
    assertThat(repeated.models()).isEmpty();
    assertThat(repeated.nextCursor()).isNull();
  }

  @Test
  void leavesOverflowSentinelUncountedAndNeverReturnsPartialModels() throws Exception {
    byte[] json =
        ("{\"data\":[{\"id\":\"model-a\"}]}" + " ".repeat(100)).getBytes(StandardCharsets.UTF_8);
    int limit = json.length - 50;
    var input = new ByteArrayInputStream(json);
    var page = ProviderModelsTransport.page(Provider.OPENAI, input, null, limit);
    assertThat(page.result()).isEqualTo(Result.RESPONSE_LIMIT);
    assertThat(page.models()).isEmpty();
    assertThat(page.bodyBytes()).isEqualTo(limit);
    assertThat(input.available()).isEqualTo(json.length - limit - 1);
  }

  @Test
  void retainsDefinitiveSchemaErrorDetectedBeforeBodyCutoff() throws Exception {
    byte[] json =
        ("{\"data\":false,\"unused\":\"" + "x".repeat(100) + "\"}")
            .getBytes(StandardCharsets.UTF_8);
    var input = new ByteArrayInputStream(json);
    var page = ProviderModelsTransport.page(Provider.OPENAI, input, null, 40);
    assertThat(page.result()).isEqualTo(Result.PROTOCOL_ERROR);
    assertThat(input.available()).isGreaterThanOrEqualTo(json.length - 40);
  }

  @Test
  void encodesContinuationOnlyAsValueOfFixedProviderQueryKey() {
    String cursor = "next ?#&pageToken=other+é";
    var gemini = ProviderModelsTransport.requestUri(Provider.GOOGLE_GEMINI, cursor);
    assertThat(gemini.getScheme()).isEqualTo("https");
    assertThat(gemini.getHost()).isEqualTo("generativelanguage.googleapis.com");
    assertThat(gemini.getPath()).isEqualTo("/v1beta/models");
    assertThat(gemini.getRawQuery())
        .isEqualTo("pageSize=100&pageToken=next%20%3F%23%26pageToken%3Dother%2B%C3%A9");
    var anthropic = ProviderModelsTransport.requestUri(Provider.ANTHROPIC, cursor);
    assertThat(anthropic.getRawQuery())
        .isEqualTo("limit=100&after_id=next%20%3F%23%26pageToken%3Dother%2B%C3%A9");
    assertThatThrownBy(() -> ProviderModelsTransport.requestUri(Provider.OPENAI, cursor))
        .isInstanceOf(IllegalArgumentException.class);
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
