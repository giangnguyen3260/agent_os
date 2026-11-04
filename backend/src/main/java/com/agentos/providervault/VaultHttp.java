package com.agentos.providervault;

import com.agentos.providercredentials.CredentialBoundary.Failure;
import java.net.Proxy;
import java.net.URI;
import java.util.Arrays;
import java.util.function.Supplier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/** Internal bounded HTTPS transport, shared by explicit Vault ports. */
final class VaultHttp {
  private final URI origin;
  private final String mount;
  private final Supplier<char[]> tokens;
  private final SSLSocketFactory tls;

  VaultHttp(URI origin, String mount, Supplier<char[]> tokens, SSLSocketFactory tls) {
    if (origin == null
        || !"https".equals(origin.getScheme())
        || origin.getHost() == null
        || origin.getRawUserInfo() != null
        || origin.getRawQuery() != null
        || origin.getRawFragment() != null
        || !(origin.getRawPath().isEmpty() || origin.getRawPath().equals("/"))
        || mount == null
        || !mount.matches("[A-Za-z0-9_-]{1,64}")
        || tokens == null
        || tls == null) throw new Failure();
    this.origin = origin.resolve("/");
    this.mount = mount;
    this.tokens = tokens;
    this.tls = tls;
  }

  byte[] exchange(String method, String path, byte[] payload) throws Exception {
    HttpsURLConnection connection = null;
    char[] token = null;
    byte[] result = null;
    try {
      token = tokens.get();
      if (token == null || token.length == 0 || token.length > 4096) throw new Failure();
      for (char value : token) {
        if (value < 0x21 || value > 0x7e) throw new Failure();
      }
      connection =
          (HttpsURLConnection)
              origin.resolve("v1/" + mount + "/" + path).toURL().openConnection(Proxy.NO_PROXY);
      connection.setSSLSocketFactory(tls);
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(5000);
      connection.setReadTimeout(5000);
      connection.setRequestMethod(method);
      connection.setRequestProperty("X-Vault-Token", new String(token));
      connection.setRequestProperty("Accept", "application/json");
      if (payload != null) {
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(payload.length);
        connection.setRequestProperty("Content-Type", "application/json");
        try (var output = connection.getOutputStream()) {
          output.write(payload);
        }
      }
      if (connection.getResponseCode() != 200) throw new Failure();
      int used = 0;
      result = new byte[32769];
      long deadline = System.nanoTime() + 10_000_000_000L;
      try (var input = connection.getInputStream()) {
        while (used < result.length) {
          int count = input.read(result, used, result.length - used);
          if (System.nanoTime() - deadline >= 0) throw new Failure();
          if (count == -1) break;
          if (count == 0) throw new Failure();
          used += count;
        }
      }
      if (used > 32768) throw new Failure();
      byte[] owned = Arrays.copyOf(result, used);
      clear(result);
      result = null;
      return owned;
    } finally {
      clear(result);
      if (token != null) Arrays.fill(token, '\0');
      if (connection != null) connection.disconnect();
    }
  }

  private static void clear(byte[] value) {
    if (value != null) Arrays.fill(value, (byte) 0);
  }
}
