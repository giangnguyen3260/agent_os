package com.agentos.providerpublication;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * One-connection disposable-fixture proxy. Relays PostgreSQL 3 startup and typed frames on IPv4
 * loopback only, with TLS and GSS encryption disabled. Frontend SQL and authentication are opaque.
 * No packet payload or underlying exception is logged, returned, or retained outside bounded,
 * promptly cleared frame buffers. This is controlled real loopback transport loss, not an internet
 * partition, exhaustive completion-uncertainty coverage, or server-log privacy proof.
 */
final class PostgresCommitAckProxy implements AutoCloseable {
  private static final int TIMEOUT_MS = 10_000;
  private static final int MAX_FRAME_BYTES = 1_048_576;
  private static final Pattern FIXTURE_URL =
      Pattern.compile(
          "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):([0-9]{1,5})/([A-Za-z0-9_]+)");
  private static final byte[] COMMIT_TAG = {'C', 'O', 'M', 'M', 'I', 'T', 0};

  private final ServerSocket listener;
  private final ExecutorService relays = Executors.newFixedThreadPool(2);
  private final AtomicInteger accepted = new AtomicInteger();
  private final AtomicBoolean failed = new AtomicBoolean();
  private final AtomicBoolean closing = new AtomicBoolean();
  private final CountDownLatch committedDrop = new CountDownLatch(1);
  private final int upstreamPort;
  private final String jdbcUrl;
  private volatile Socket frontend;
  private volatile Socket upstream;

  PostgresCommitAckProxy(String fixtureUrl) throws IOException {
    var match = FIXTURE_URL.matcher(fixtureUrl);
    if (!match.matches()) throw new IllegalArgumentException("Loopback fixture URL required");
    upstreamPort = Integer.parseInt(match.group(1));
    if (upstreamPort < 1 || upstreamPort > 65535) {
      throw new IllegalArgumentException("Valid fixture port required");
    }
    listener = new ServerSocket();
    try {
      listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1);
      listener.setSoTimeout(TIMEOUT_MS);
    } catch (IOException failure) {
      closeQuietly(listener);
      throw new IOException("Loopback fixture listener unavailable");
    }
    jdbcUrl =
        "jdbc:postgresql://127.0.0.1:"
            + listener.getLocalPort()
            + "/"
            + match.group(2)
            + "?sslmode=disable&gssEncMode=disable&connectTimeout=5&socketTimeout=15";
    relays.submit(this::acceptAndRelay);
  }

  String jdbcUrl() {
    return jdbcUrl;
  }

  int acceptedConnections() {
    return accepted.get();
  }

  boolean awaitCommittedDrop() throws InterruptedException {
    return committedDrop.await(5, TimeUnit.SECONDS) && !failed.get();
  }

  private void acceptAndRelay() {
    try {
      frontend = listener.accept();
      accepted.incrementAndGet();
      // There can be no second accepted connection, including automatic reconnection.
      listener.close();
      frontend.setSoTimeout(TIMEOUT_MS);
      upstream = new Socket();
      upstream.connect(
          new InetSocketAddress(InetAddress.getByName("127.0.0.1"), upstreamPort), TIMEOUT_MS);
      upstream.setSoTimeout(TIMEOUT_MS);
      var incoming = new DataInputStream(frontend.getInputStream());
      var outgoing = new DataOutputStream(upstream.getOutputStream());
      relayStartup(incoming, outgoing);
      relays.submit(() -> relayFrontend(incoming, outgoing));
      relayBackend(
          new DataInputStream(upstream.getInputStream()),
          new DataOutputStream(frontend.getOutputStream()));
    } catch (IOException | RuntimeException failure) {
      if (!closing.get()) failed.set(true);
    } finally {
      closeQuietly(frontend);
      closeQuietly(upstream);
      closeQuietly(listener);
    }
  }

  private static int checkedLength(int length) throws IOException {
    if (length < 4 || length > MAX_FRAME_BYTES) {
      throw new IOException("Invalid fixture frame length");
    }
    return length;
  }

  private static void relayStartup(DataInputStream incoming, DataOutputStream outgoing)
      throws IOException {
    int length = checkedLength(incoming.readInt());
    if (length < 8) throw new IOException("PostgreSQL 3 startup required");
    byte[] payload = new byte[length - 4];
    try {
      incoming.readFully(payload);
      // Only the protocol version is inspected; no startup parameters or credentials are parsed.
      if (payload[0] != 0 || payload[1] != 3 || payload[2] != 0 || payload[3] != 0) {
        throw new IOException("Unencrypted PostgreSQL 3 startup required");
      }
      outgoing.writeInt(length);
      outgoing.write(payload);
      outgoing.flush();
    } finally {
      Arrays.fill(payload, (byte) 0);
    }
  }

  private void relayFrontend(DataInputStream incoming, DataOutputStream outgoing) {
    try {
      while (!closing.get() && committedDrop.getCount() != 0) {
        relayFrame(incoming, outgoing, false, false);
      }
    } catch (IOException | RuntimeException failure) {
      // Socket closure after the deliberately dropped acknowledgement is expected.
      if (!closing.get() && committedDrop.getCount() != 0) {
        failed.set(true);
        closeQuietly(upstream);
        closeQuietly(frontend);
      }
    }
  }

  private void relayBackend(DataInputStream incoming, DataOutputStream outgoing)
      throws IOException {
    boolean withholding = false;
    while (!closing.get()) {
      withholding = relayFrame(incoming, outgoing, true, withholding);
      if (committedDrop.getCount() == 0) return;
    }
  }

  /** Returns only control state; payloads never escape this method. */
  private boolean relayFrame(
      DataInputStream incoming, DataOutputStream outgoing, boolean backend, boolean withholding)
      throws IOException {
    int type = incoming.readUnsignedByte();
    int length = checkedLength(incoming.readInt());
    byte[] payload = new byte[length - 4];
    try {
      incoming.readFully(payload);
      if (backend && type == 'C' && Arrays.equals(payload, COMMIT_TAG)) {
        withholding = true;
      }
      if (backend && withholding) {
        // Withhold COMMIT's CommandComplete and everything through its idle ReadyForQuery.
        if (type == 'Z') {
          if (payload.length != 1 || payload[0] != 'I') {
            throw new IOException("Idle completion required after COMMIT");
          }
          committedDrop.countDown();
          // No acknowledgement bytes have reached PGJDBC; only now sever both real sockets.
          closeQuietly(frontend);
          closeQuietly(upstream);
        } else if (type == 'E') {
          throw new IOException("Unexpected failure after COMMIT completion");
        }
      } else {
        outgoing.writeByte(type);
        outgoing.writeInt(length);
        outgoing.write(payload);
        outgoing.flush();
      }
      return withholding;
    } finally {
      Arrays.fill(payload, (byte) 0);
    }
  }

  private static void closeQuietly(Closeable resource) {
    if (resource == null) return;
    try {
      resource.close();
    } catch (IOException ignored) {
      // Fixed control state only; never propagate socket/driver diagnostics.
    }
  }

  @Override
  public void close() {
    closing.set(true);
    closeQuietly(listener);
    closeQuietly(frontend);
    closeQuietly(upstream);
    relays.shutdownNow();
    try {
      if (!relays.awaitTermination(15, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Fixture relay cleanup timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Fixture relay cleanup interrupted");
    }
  }
}
