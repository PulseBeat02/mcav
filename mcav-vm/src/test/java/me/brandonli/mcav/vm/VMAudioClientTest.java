/*
 * This file is part of mcav, a media playback library for Java
 * Copyright (C) Brandon Li <https://brandonli.me/>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package me.brandonli.mcav.vm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import me.brandonli.mcav.vm.testing.VncPasswords;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VMAudioClientTest {

  private static final String PASSWORD = "Pa55word";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final List<byte[]> chunks = new CopyOnWriteArrayList<>();
  private final List<String> failures = new CopyOnWriteArrayList<>();
  private ServerSocket server;

  @BeforeEach
  void listen() throws IOException {
    this.server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
  }

  @AfterEach
  void close() throws IOException {
    this.server.close();
  }

  private InetSocketAddress address() {
    return new InetSocketAddress(InetAddress.getLoopbackAddress(), this.server.getLocalPort());
  }

  private VMAudioClient connect() throws IOException {
    return VMAudioClient.connect(
      this.address(),
      PASSWORD,
      (samples, length) -> this.chunks.add(Arrays.copyOf(samples, length)),
      (message, failure) -> this.failures.add(message)
    );
  }

  /**
   * Plays the server side of the handshake QEMU does, and checks what the client sends.
   *
   * @param socket the connection
   * @return the streams, for what follows the handshake
   * @throws IOException if the connection fails
   */
  private static Streams handshake(final Socket socket) throws IOException {
    return handshake(socket, PASSWORD);
  }

  /**
   * Plays the server side of the handshake of a display with a password, as QEMU's with password-secret, or of one
   * without, for an empty password.
   */
  private static Streams handshake(final Socket socket, final String password) throws IOException {
    final DataInputStream in = new DataInputStream(socket.getInputStream());
    final DataOutputStream out = new DataOutputStream(socket.getOutputStream());
    out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
    final byte[] version = new byte[12];
    in.readFully(version);
    assertEquals("RFB 003.008\n", new String(version, StandardCharsets.US_ASCII));
    if (password.isEmpty()) {
      out.write(new byte[] { 1, 1 });
      assertEquals(1, in.readUnsignedByte(), "no authentication");
    } else {
      out.write(new byte[] { 1, 2 });
      assertEquals(2, in.readUnsignedByte(), "VNC authentication");
      final byte[] challenge = new byte[16];
      RANDOM.nextBytes(challenge);
      out.write(challenge);
      final byte[] response = new byte[16];
      in.readFully(response);
      assertArrayEquals(VncPasswords.response(challenge, password), response, "the answer of the password");
    }
    out.writeInt(0);
    assertEquals(1, in.readUnsignedByte(), "shared");
    out.writeShort(720);
    out.writeShort(400);
    out.write(new byte[16]);
    out.writeInt(4);
    out.write("QEMU".getBytes(StandardCharsets.US_ASCII));
    final byte[] requests = new byte[8 + 14];
    in.readFully(requests);
    final byte[] expected = QemuAudioProtocolTest.bytes(output -> {
      QemuAudioProtocol.writeSetEncodings(output);
      QemuAudioProtocol.writeEnableAudio(output, 2, 48_000);
    });
    assertArrayEquals(expected, requests);
    return new Streams(in, out);
  }

  @Test
  void aDisplayWithoutAPasswordIsJoinedWithoutAuthentication() throws Exception {
    final CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
      try (final Socket socket = this.server.accept()) {
        handshake(socket, "");
        socket.getInputStream().read();
      } catch (final IOException exception) {
        throw new UncheckedIOException(exception);
      }
    });
    final VMAudioClient client = VMAudioClient.connect(this.address(), "", (samples, length) -> {}, (message, failure) -> {});
    assertTrue(client.isAlive());
    client.close();
    served.get(10, TimeUnit.SECONDS);
  }

  @Test
  void aWrongPasswordEndsTheHandshakeWithTheReasonOfTheServer() throws Exception {
    final CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
      try (final Socket socket = this.server.accept()) {
        final DataInputStream in = new DataInputStream(socket.getInputStream());
        final DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
        in.readFully(new byte[12]);
        out.write(new byte[] { 1, 2 });
        assertEquals(2, in.readUnsignedByte(), "VNC authentication");
        out.write(new byte[16]);
        in.readFully(new byte[16]);
        out.writeInt(1);
        final byte[] reason = "Authentication failed".getBytes(StandardCharsets.US_ASCII);
        out.writeInt(reason.length);
        out.write(reason);
        out.flush();
      } catch (final IOException exception) {
        throw new UncheckedIOException(exception);
      }
    });
    final ProtocolException refused = assertThrows(ProtocolException.class, this::connect);
    assertEquals("The VNC server refused the connection: Authentication failed", refused.getMessage());
    served.get(10, TimeUnit.SECONDS);
  }

  @Test
  void theSoundOfTheGuestReachesTheSinkAndAnEndIsReported() throws Exception {
    final CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
      try (final Socket socket = this.server.accept()) {
        final Streams streams = handshake(socket);
        QemuAudioProtocolTest.writeAcknowledgement(streams.out());
        streams.out().write(new byte[] { (byte) 255, 1, 0, 1 });
        QemuAudioProtocolTest.writeData(streams.out(), new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
        QemuAudioProtocolTest.writeData(streams.out(), new byte[] { 9, 9, 9, 9 });
        streams.out().write(new byte[] { (byte) 255, 1, 0, 0 });
        streams.out().flush();
        waitUntil(() -> this.chunks.size() == 2);
      } catch (final IOException exception) {
        throw new UncheckedIOException(exception);
      }
    });
    try (final VMAudioClient client = this.connect()) {
      served.get(10, TimeUnit.SECONDS);
      assertArrayEquals(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }, this.chunks.getFirst());
      assertArrayEquals(new byte[] { 9, 9, 9, 9 }, this.chunks.get(1));
      waitUntil(() -> !client.isAlive());
      assertEquals(List.of("The audio connection of the virtual machine ended"), this.failures);
    }
  }

  @Test
  void theEndOfTheGuestsSoundTellsTheSinkItWentQuiet() throws Exception {
    final List<String> heard = new CopyOnWriteArrayList<>();
    final VMAudioClient.Sink sink = new VMAudioClient.Sink() {
      @Override
      public void accept(final byte[] samples, final int length) {
        heard.add("sound " + length);
      }

      @Override
      public void quiet() {
        heard.add("quiet");
      }
    };
    final CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
      try (final Socket socket = this.server.accept()) {
        final Streams streams = handshake(socket);
        QemuAudioProtocolTest.writeAcknowledgement(streams.out());
        streams.out().write(new byte[] { (byte) 255, 1, 0, 1 });
        QemuAudioProtocolTest.writeData(streams.out(), new byte[] { 1, 2, 3, 4 });
        streams.out().write(new byte[] { (byte) 255, 1, 0, 0 });
        streams.out().flush();
        waitUntil(() -> heard.size() == 2);
      } catch (final IOException exception) {
        throw new UncheckedIOException(exception);
      }
    });
    try (final VMAudioClient client = VMAudioClient.connect(this.address(), PASSWORD, sink, (message, failure) -> {})) {
      served.get(10, TimeUnit.SECONDS);
      assertEquals(List.of("sound 4", "quiet"), heard, "the end of the guest's sound, after its samples");
      assertTrue(client.getReader() != null);
    }
    // a sink that keeps no time ignores the end
    final VMAudioClient.Sink plain = (samples, length) -> {};
    plain.quiet();
  }

  @Test
  void aSinkThatFailsEndsTheConnectionAndIsReported() throws Exception {
    final CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
      try (final Socket socket = this.server.accept()) {
        final Streams streams = handshake(socket);
        QemuAudioProtocolTest.writeAcknowledgement(streams.out());
        streams.out().write(new byte[] { (byte) 255, 1, 0, 1 });
        QemuAudioProtocolTest.writeData(streams.out(), new byte[] { 1, 2, 3, 4 });
        streams.out().flush();
        // the client closes the connection once its sink failed
        assertEquals(-1, streams.in().read());
      } catch (final IOException exception) {
        throw new UncheckedIOException(exception);
      }
    });
    final VMAudioClient.Sink failing = (samples, length) -> {
      throw new IllegalStateException("sink broke");
    };
    try (
      final VMAudioClient client = VMAudioClient.connect(this.address(), PASSWORD, failing, (message, failure) ->
        this.failures.add(message)
      )
    ) {
      served.get(10, TimeUnit.SECONDS);
      waitUntil(() -> !client.isAlive());
      assertEquals(List.of("The audio connection of the virtual machine ended"), this.failures);
    }
  }

  /**
   * Sends the banner of RFB one byte a second: every pause is shorter than the timeout of a read, the whole banner far
   * longer than the handshake may take.
   *
   * @param server the listening socket
   */
  private static void trickleTheBanner(final ServerSocket server) {
    try (final Socket socket = server.accept()) {
      final OutputStream out = socket.getOutputStream();
      for (final byte value : "RFB 003.008\n".getBytes(StandardCharsets.US_ASCII)) {
        out.write(value);
        out.flush();
        Thread.sleep(1_000L);
      }
    } catch (final IOException exception) {
      // the client gave up and closed the connection
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void aServerThatTricklesItsHandshakeIsCutOffAtTheDeadline() throws Exception {
    final CompletableFuture<Void> served = CompletableFuture.runAsync(() -> trickleTheBanner(this.server));
    final long start = System.nanoTime();
    assertThrows(IOException.class, this::connect);
    final long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
    assertTrue(seconds < VMAudioClient.HANDSHAKE_TIMEOUT_MILLIS / 1000 + 3, "the handshake took " + seconds + " s");
    served.get(20, TimeUnit.SECONDS);
  }

  @Test
  void aFailureCallbackThatClosesTheClientDoesNotWaitForItself() throws Exception {
    final CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
      try (final Socket socket = this.server.accept()) {
        final Streams streams = handshake(socket);
        QemuAudioProtocolTest.writeAcknowledgement(streams.out());
      } catch (final IOException exception) {
        throw new UncheckedIOException(exception);
      }
    });
    final AtomicReference<VMAudioClient> self = new AtomicReference<>();
    final AtomicLong closeNanos = new AtomicLong(-1);
    final VMAudioClient client = VMAudioClient.connect(
      this.address(),
      PASSWORD,
      (samples, length) -> {},
      (message, failure) -> {
        final long begin = System.nanoTime();
        waitUntil(() -> self.get() != null);
        self.get().close();
        closeNanos.set(System.nanoTime() - begin);
      }
    );
    self.set(client);
    served.get(10, TimeUnit.SECONDS);
    waitUntil(() -> closeNanos.get() >= 0);
    assertTrue(closeNanos.get() < TimeUnit.SECONDS.toNanos(1), "closed without waiting for its own reader");
    client.close();
  }

  @Test
  void theReaderRunsAsADaemonAndClosingWaitsForIt() throws Exception {
    final CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
      try (final Socket socket = this.server.accept()) {
        handshake(socket);
        socket.getInputStream().read();
      } catch (final IOException exception) {
        throw new UncheckedIOException(exception);
      }
    });
    final VMAudioClient client = this.connect();
    assertTrue(client.getReader().isDaemon(), "the sound never keeps the JVM alive");
    client.close();
    assertFalse(client.isAlive(), "close returns once the reader ended");
    served.get(10, TimeUnit.SECONDS);
  }

  @Test
  void closingTheClientEndsItQuietly() throws Exception {
    final CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
      try (final Socket socket = this.server.accept()) {
        handshake(socket);
        socket.getInputStream().read();
      } catch (final IOException exception) {
        throw new UncheckedIOException(exception);
      }
    });
    final VMAudioClient client = this.connect();
    assertTrue(client.isAlive());
    Thread.currentThread().interrupt();
    try {
      client.close();
      assertTrue(Thread.currentThread().isInterrupted(), "an interrupted close keeps the interrupt");
    } finally {
      Thread.interrupted();
    }
    client.close();
    waitUntil(() -> !client.isAlive());
    assertFalse(client.isAlive());
    served.get(10, TimeUnit.SECONDS);
    assertEquals(List.of(), this.failures);
  }

  @Test
  void aServerThatWantsAPasswordFailsAConnectionWithoutOne() {
    final CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
      try (final Socket socket = this.server.accept()) {
        final DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
        out.write(new byte[] { 1, 2 });
        socket.getInputStream().readNBytes(12);
        socket.getInputStream().read();
      } catch (final IOException exception) {
        throw new UncheckedIOException(exception);
      }
    });
    // a client that knows the password answers the challenge instead, see theSoundOfTheGuestReachesTheSink...
    final ProtocolException failure = assertThrows(ProtocolException.class, () ->
      VMAudioClient.connect(this.address(), "", (samples, length) -> {}, (message, cause) -> {})
    );
    assertEquals("The VNC server asks for authentication, which the audio connection does not do", failure.getMessage());
    served.join();
  }

  @Test
  void aFailedCloseChangesNothing() {
    final AtomicInteger closed = new AtomicInteger();
    VMAudioClient.closeQuietly(() -> {
      closed.incrementAndGet();
      throw new IOException("gone");
    });
    assertEquals(1, closed.get());
  }

  private static void waitUntil(final BooleanSupplier condition) {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("timed out");
      }
      Thread.onSpinWait();
    }
  }

  /**
   * The streams of a connection.
   *
   * @param in  from the client
   * @param out to the client
   */
  private record Streams(DataInputStream in, DataOutputStream out) {}

  @Test
  void anInterruptedWaitForAThreadOfTheSoundKeepsTheInterrupt() throws InterruptedException {
    final CountDownLatch release = new CountDownLatch(1);
    final Thread alive = new Thread(() -> {
      try {
        release.await();
      } catch (final InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
    });
    alive.start();
    Thread.currentThread().interrupt();
    try {
      VMAudioClient.join(alive);
      assertTrue(Thread.currentThread().isInterrupted(), "the interrupt stays for the caller");
    } finally {
      Thread.interrupted();
      release.countDown();
    }
    alive.join(10_000L);
    assertFalse(alive.isAlive());
  }
}
