package com.agentos.providervault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.providercredentials.CredentialBoundary.Failure;
import com.agentos.providercredentials.CredentialBoundary.Provider;
import com.agentos.providercredentials.CredentialBoundary.Scope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class VaultCredentialSecretsTest {
  @TempDir Path temporary;

  @Test
  void realTlsRoundTripScopeDenialRedirectAndFailureCleanup() throws Exception {
    Path store = temporary.resolve("fixture.p12");
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair",
                "-alias",
                "fixture",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-storetype",
                "PKCS12",
                "-keystore",
                store.toString(),
                "-storepass",
                "test-only-password",
                "-dname",
                "CN=localhost",
                "-ext",
                "SAN=ip:127.0.0.1",
                "-validity",
                "1",
                "-noprompt")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    assertThat(process.waitFor()).isZero();
    var keys = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(store)) {
      keys.load(input, "test-only-password".toCharArray());
    }
    var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    managers.init(keys, "test-only-password".toCharArray());
    var trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trusts.init(keys);
    var tls = SSLContext.getInstance("TLS");
    tls.init(managers.getKeyManagers(), trusts.getTrustManagers(), null);
    var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(tls));
    var json = new ObjectMapper();
    var saved = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
    var status = new AtomicInteger(200);
    var calls = new AtomicInteger();
    var mismatch = new AtomicInteger();
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          byte[] response;
          if (exchange.getRequestMethod().equals("POST")) {
            saved.set(json.readTree(exchange.getRequestBody()).path("data"));
            response =
                "{\"data\":{\"version\":1}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          } else {
            var data = saved.get().deepCopy();
            if (mismatch.get() != 0)
              ((com.fasterxml.jackson.databind.node.ObjectNode) data).put("revision", "99");
            var root = json.createObjectNode();
            var body = root.putObject("data");
            body.set("data", data);
            body.putObject("metadata")
                .put("version", 1)
                .put("destroyed", false)
                .put("deletion_time", "");
            response = json.writeValueAsBytes(root);
          }
          exchange.getResponseHeaders().set("Location", "https://untrusted.invalid/");
          exchange.sendResponseHeaders(status.get(), response.length);
          try (var output = exchange.getResponseBody()) {
            output.write(response);
          }
          exchange.close();
        });
    server.start();
    var token = new AtomicReference<char[]>();
    var scope = new Scope(UUID.randomUUID(), UUID.randomUUID(), Provider.OPENAI, 1);
    var adapter =
        new VaultCredentialSecrets(
            URI.create("https://127.0.0.1:" + server.getAddress().getPort()),
            "agentos",
            () -> {
              char[] owned = "test-vault-token".toCharArray();
              token.set(owned);
              return owned;
            },
            (s, r) -> {},
            tls.getSocketFactory());
    try {
      var reference =
          adapter.store(scope, "fixture-key".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      assertThat(token.get()).containsOnly('\0');
      assertThat(adapter.read(reference))
          .isEqualTo("fixture-key".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      mismatch.set(1);
      assertThatThrownBy(() -> adapter.read(reference)).isInstanceOf(Failure.class).hasNoCause();
      mismatch.set(0);
      status.set(302);
      int before = calls.get();
      assertThatThrownBy(() -> adapter.read(reference)).isInstanceOf(Failure.class);
      assertThat(calls.get()).isEqualTo(before + 1);
      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        before = calls.get();
        assertThatThrownBy(() -> adapter.read(reference)).isInstanceOf(Failure.class);
        assertThat(calls.get()).isEqualTo(before);
      } finally {
        TransactionSynchronizationManager.setActualTransactionActive(false);
      }
    } finally {
      server.stop(0);
    }
  }
}
