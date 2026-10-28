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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.vm.testing.VncPasswords;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class VmAudioClientTest {

  private static final String PASSWORD = "Pa55word";
  private static final SecureRandom RANDOM = new SecureRandom();

  private static final String VERSION = "RFB 003.008\n";
  private static final int VERSION_BYTES = 12;

  private static final int NO_AUTHENTICATION = 1;
  private static final int VNC_AUTHENTICATION = 2;

  private static final int CHALLENGE_BYTES = 16;

  private static final byte[] AUDIO_BEGIN = { (byte) 255, 1, 0, 1 };
  private static final byte[] AUDIO_END = { (byte) 255, 1, 0, 0 };
  private static final byte[] SAMPLES = { 1, 2, 3, 4 };

  private static final String CONNECTION_ENDED = "The audio connection of the virtual machine ended";

  private static final long TIMEOUT_SECONDS = 10L;

  // shorter than close waits for the reader
  private static final long READER_HOLD_MILLISECONDS = 1_000L;

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

  private VmAudioClient connect() throws IOException {
    return VmAudioClient.connect(
      this.address(),
      PASSWORD,
      (samples, length) -> this.chunks.add(Arrays.copyOf(samples, length)),
      (message, failure) -> this.failures.add(message)
    );
  }

  private Streams handshake(final Socket socket) throws IOException {
    return handshake(socket, PASSWORD);
  }

  private Streams handshake(final Socket socket, final String password) throws IOException {
    final DataInputStream input = new DataInputStream(socket.getInputStream());
    final DataOutputStream output = new DataOutputStream(socket.getOutputStream());
    output.write(VERSION.getBytes(StandardCharsets.US_ASCII));
    final byte[] version = new byte[VERSION_BYTES];
    input.readFully(version);
    final String clientVersion = new String(version, StandardCharsets.US_ASCII);
    assertEquals(VERSION, clientVersion);

    if (password.isEmpty()) {
      output.write(new byte[] { 1, NO_AUTHENTICATION });
      final int securityType = input.readUnsignedByte();
      assertEquals(NO_AUTHENTICATION, securityType, "no authentication");
    } else {
      output.write(new byte[] { 1, VNC_AUTHENTICATION });
      final int securityType = input.readUnsignedByte();
      assertEquals(VNC_AUTHENTICATION, securityType, "VNC authentication");
      final byte[] challenge = new byte[CHALLENGE_BYTES];
      RANDOM.nextBytes(challenge);
      output.write(challenge);
      final byte[] response = new byte[CHALLENGE_BYTES];
      input.readFully(response);
      final byte[] expectedResponse = VncPasswords.response(challenge, password);
      assertArrayEquals(expectedResponse, response, "the answer of the password");
    }

    output.writeInt(0);
    final int shared = input.readUnsignedByte();
    assertEquals(1, shared, "shared");

    output.writeShort(720);
    output.writeShort(400);
    output.write(new byte[16]);
    output.writeInt(4);
    output.write("QEMU".getBytes(StandardCharsets.US_ASCII));
    final byte[] requests = new byte[8 + 14];
    input.readFully(requests);
    final byte[] expected = QemuAudioProtocolTest.bytes(expectedOutput -> {
      QemuAudioProtocol.writeSetEncodings(expectedOutput);
      QemuAudioProtocol.writeEnableAudio(expectedOutput, AudioFilter.CHANNELS, AudioFilter.SAMPLE_RATE);
    });
    assertArrayEquals(expected, requests);

    return new Streams(input, output);
  }

  @Test
  void shouldJoinADisplayWithoutAPasswordWithoutAuthentication() throws Exception {
    final CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
      try (final Socket socket = this.server.accept()) {
        this.handshake(socket, "");
        final InputStream input = socket.getInputStream();
        input.read();
      } catch (final IOException exception) {
        throw new UncheckedIOException(exception);
      }
    });
    final VmAudioClient client = VmAudioClient.connect(this.address(), "", (samples, length) -> {}, (message, failure) -> {});
    final boolean alive = client.isAlive();
    assertTrue(alive);

    client.close();
    served.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  @Test
  void shouldEndTheHandshakeOfAWrongPasswordWithTheReasonOfTheServer() throws Exception {
    final CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
      try (final Socket socket = this.server.accept()) {
        final DataInputStream input = new DataInputStream(socket.getInputStream());
        final DataOutputStream output = new DataOutputStream(socket.getOutputStream());
        output.write(VERSION.getBytes(StandardCharsets.US_ASCII));
        input.readFully(new byte[VERSION_BYTES]);
        output.write(new byte[] { 1, VNC_AUTHENTICATION });
        final int securityType = input.readUnsignedByte();
        assertEquals(VNC_AUTHENTICATION, securityType, "VNC authentication");
        output.write(new byte[CHALLENGE_BYTES]);
        input.readFully(new byte[CHALLENGE_BYTES]);
        output.writeInt(1);
        final byte[] reason = "Authentication failed".getBytes(StandardCharsets.US_ASCII);
        output.writeInt(reason.length);
        output.write(reason);
        output.flush();
      } catch (final IOException exception) {
        throw new UncheckedIOException(exception);
      }
    });
    final ProtocolException refused = assertThrows(ProtocolException.class, this::connect);
    final String message = refused.getMessage();
    assertEquals("VNC server refused the connection because of \"Authentication failed\"!", message);

    served.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  @Test
  void shouldHandTheSoundOfTheGuestToTheSinkAndReportTheEnd() throws Exception {
    final CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
      try (final Socket socket = this.server.accept()) {
        final Streams streams = this.handshake(socket);
        final DataOutputStream output = streams.output();
        QemuAudioProtocolTest.writeAcknowledgement(output);
        output.write(AUDIO_BEGIN);
        QemuAudioProtocolTest.writeData(output, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
        QemuAudioProtocolTest.writeData(output, new byte[] { 9, 9, 9, 9 });
        output.write(AUDIO_END);
        output.flush();
        this.waitUntil(() -> this.chunks.size() == 2);
      } catch (final IOException exception) {
        throw new UncheckedIOException(exception);
      }
    });
    try (final VmAudioClient client = this.connect()) {
      served.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      final byte[] expectedFirstChunk = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 };
      final byte[] expectedSecondChunk = new byte[] { 9, 9, 9, 9 };
      final byte[] firstChunk = this.chunks.getFirst();
      final byte[] secondChunk = this.chunks.get(1);
      assertArrayEquals(expectedFirstChunk, firstChunk);
      assertArrayEquals(expectedSecondChunk, secondChunk);

      this.waitUntil(() -> !client.isAlive());
      final List<String> expected = List.of(CONNECTION_ENDED);
      assertEquals(expected, this.failures);
    }
  }

  @Test
  void shouldTellTheSinkWhenTheSoundOfTheGuestEnds() throws Exception {
    final List<String> heard = new CopyOnWriteArrayList<>();
    final VmAudioClient.Sink sink = new VmAudioClient.Sink() {
      @Override
      public void accept(final byte[] samples, final int length) {
        final String sound = "sound %s".formatted(length);
        heard.add(sound);
      }

      @Override
      public void quiet() {
        heard.add("quiet");
      }
    };
    final CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
      try (final Socket socket = this.server.accept()) {
        final Streams streams = this.handshake(socket);
        final DataOutputStream output = streams.output();
        QemuAudioProtocolTest.writeAcknowledgement(output);
        output.write(AUDIO_BEGIN);
        QemuAudioProtocolTest.writeData(output, SAMPLES);
        output.write(AUDIO_END);
        output.flush();
        this.waitUntil(() -> heard.size() == 2);
      } catch (final IOException exception) {
        throw new UncheckedIOException(exception);
      }
    });
    try (final VmAudioClient _ = VmAudioClient.connect(this.address(), PASSWORD, sink, (message, failure) -> {})) {
      served.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      final List<String> expected = List.of("sound 4", "quiet");
      assertEquals(expected, heard, "the end of the guest's sound, after its samples");
    }
  }

  @Test
  void shouldEndTheConnectionAndReportASinkThatFails() throws Exception {
    final CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
      try (final Socket socket = this.server.accept()) {
        final Streams streams = this.handshake(socket);
        final DataOutputStream output = streams.output();
        QemuAudioProtocolTest.writeAcknowledgement(output);
        output.write(AUDIO_BEGIN);
        QemuAudioProtocolTest.writeData(output, SAMPLES);
        output.flush();
        final DataInputStream input = streams.input();
        final int next = input.read();
        assertEquals(-1, next);
      } catch (final IOException exception) {
        throw new UncheckedIOException(exception);
      }
    });
    final VmAudioClient.Sink failing = (samples, length) -> {
      throw new IllegalStateException("sink broke!");
    };
    try (
      final VmAudioClient client = VmAudioClient.connect(this.address(), PASSWORD, failing, (message, failure) ->
        this.failures.add(message)
      )
    ) {
      served.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      this.waitUntil(() -> !client.isAlive());
      final List<String> expected = List.of(CONNECTION_ENDED);
      assertEquals(expected, this.failures);
    }
  }

  @Test
  void shouldHoldTheHandshakeDeadlineWhileTheCommonPoolIsBusy() throws Exception {
    final CountDownLatch release = this.occupyTheCommonPool();
    try {
      final Thread server = Thread.ofPlatform()
        .daemon()
        .start(() -> this.acceptAndHold(release));
      assertTimeoutPreemptively(Duration.ofSeconds(60), () -> assertThrows(IOException.class, this::connect));

      final boolean serverAlive = server.isAlive();
      assertTrue(serverAlive, "the client gave up, not the server");
    } finally {
      release.countDown();
    }
  }

  private CountDownLatch occupyTheCommonPool() throws InterruptedException {
    final int workers = ForkJoinPool.getCommonPoolParallelism();
    final CountDownLatch busy = new CountDownLatch(workers);
    final CountDownLatch release = new CountDownLatch(1);
    final ForkJoinPool pool = ForkJoinPool.commonPool();
    for (int worker = 0; worker < workers; worker++) {
      pool.execute(() -> this.holdUntilReleased(busy, release));
    }

    final boolean allBusy = busy.await(30, TimeUnit.SECONDS);
    assertTrue(allBusy, "every worker of the common pool is busy");

    return release;
  }

  private void holdUntilReleased(final CountDownLatch busy, final CountDownLatch release) {
    busy.countDown();
    this.awaitRelease(release);
  }

  private void awaitRelease(final CountDownLatch release) {
    try {
      release.await();
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  private void acceptAndHold(final CountDownLatch release) {
    try (final Socket _ = this.server.accept()) {
      this.awaitRelease(release);
    } catch (final IOException exception) {
      // the test ended
    }
  }

  @Test
  void shouldCutOffAServerThatTricklesItsHandshakeAtTheDeadline() throws Exception {
    final CompletableFuture<Void> served = CompletableFuture.runAsync(() -> this.trickleTheBanner(this.server));
    final long start = System.nanoTime();
    assertThrows(IOException.class, this::connect);

    final long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
    final long deadlineSeconds = TimeUnit.MILLISECONDS.toSeconds(VmAudioClient.HANDSHAKE_TIMEOUT_MILLISECONDS);
    final boolean withinTheDeadline = seconds < deadlineSeconds + 3;
    assertTrue(withinTheDeadline, "the handshake took %s s".formatted(seconds));

    served.get(20, TimeUnit.SECONDS);
  }

  // each pause is shorter than a read timeout, the whole banner longer than the handshake deadline
  private void trickleTheBanner(final ServerSocket server) {
    try (final Socket socket = server.accept()) {
      final OutputStream output = socket.getOutputStream();
      for (final byte value : VERSION.getBytes(StandardCharsets.US_ASCII)) {
        output.write(value);
        output.flush();
        Thread.sleep(1_000L);
      }
    } catch (final IOException exception) {
      // the client gave up and closed the connection
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  @Test
  void shouldNotWaitForItselfWhenAFailureCallbackClosesTheClient() throws Exception {
    final CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
      try (final Socket socket = this.server.accept()) {
        final Streams streams = this.handshake(socket);
        QemuAudioProtocolTest.writeAcknowledgement(streams.output());
      } catch (final IOException exception) {
        throw new UncheckedIOException(exception);
      }
    });
    final AtomicReference<VmAudioClient> self = new AtomicReference<>();
    final AtomicLong closeNanoseconds = new AtomicLong(-1);
    final VmAudioClient client = VmAudioClient.connect(
      this.address(),
      PASSWORD,
      (samples, length) -> {},
      (message, failure) -> {
        final long begin = System.nanoTime();
        this.waitUntil(() -> self.get() != null);
        final VmAudioClient ownClient = self.get();
        ownClient.close();
        closeNanoseconds.set(System.nanoTime() - begin);
      }
    );
    self.set(client);
    served.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    this.waitUntil(() -> closeNanoseconds.get() >= 0);
    final boolean closedQuickly = closeNanoseconds.get() < TimeUnit.SECONDS.toNanos(1);
    assertTrue(closedQuickly, "closed without waiting for its own reader");

    client.close();
  }

  @Test
  void shouldRunTheReaderAsADaemonAndWaitForItWhenClosing() throws Exception {
    final CountDownLatch delivering = new CountDownLatch(1);
    final CountDownLatch checked = new CountDownLatch(1);
    final VmAudioClient.Sink slow = (samples, length) -> this.holdTheReader(delivering, checked);
    final CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
      try (final Socket socket = this.server.accept()) {
        final Streams streams = this.handshake(socket);
        final DataOutputStream output = streams.output();
        QemuAudioProtocolTest.writeAcknowledgement(output);
        output.write(AUDIO_BEGIN);
        QemuAudioProtocolTest.writeData(output, SAMPLES);
        output.flush();
        final DataInputStream input = streams.input();
        input.read();
      } catch (final IOException exception) {
        throw new UncheckedIOException(exception);
      }
    });
    final VmAudioClient client = VmAudioClient.connect(this.address(), PASSWORD, slow, (message, failure) -> {});
    final Thread reader = client.getReader();
    final boolean daemon = reader.isDaemon();
    assertTrue(daemon, "the sound never keeps the JVM alive");

    final boolean delivered = delivering.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(delivered, "the reader is in the sink");

    client.close();
    final boolean alive = client.isAlive();
    checked.countDown();
    assertFalse(alive, "close returns once the reader ended, even while the sink held it");

    served.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  private void holdTheReader(final CountDownLatch delivering, final CountDownLatch checked) {
    delivering.countDown();
    try {
      final boolean _ = checked.await(READER_HOLD_MILLISECONDS, TimeUnit.MILLISECONDS);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  @Test
  void shouldEndQuietlyWhenClosed() throws Exception {
    final CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
      try (final Socket socket = this.server.accept()) {
        this.handshake(socket);
        final InputStream input = socket.getInputStream();
        input.read();
      } catch (final IOException exception) {
        throw new UncheckedIOException(exception);
      }
    });
    final VmAudioClient client = this.connect();
    final boolean aliveBeforeClose = client.isAlive();
    assertTrue(aliveBeforeClose);

    final Thread current = Thread.currentThread();
    current.interrupt();
    try {
      client.close();
      final boolean interrupted = current.isInterrupted();
      assertTrue(interrupted, "an interrupted close keeps the interrupt");
    } finally {
      Thread.interrupted();
    }

    client.close();
    this.waitUntil(() -> !client.isAlive());
    final boolean aliveAfterClose = client.isAlive();
    assertFalse(aliveAfterClose);

    served.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertEquals(List.of(), this.failures);
  }

  @Test
  void shouldFailAConnectionWithoutAPasswordToAServerThatWantsOne() {
    final CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
      try (final Socket socket = this.server.accept()) {
        final DataOutputStream output = new DataOutputStream(socket.getOutputStream());
        output.write(VERSION.getBytes(StandardCharsets.US_ASCII));
        output.write(new byte[] { 1, VNC_AUTHENTICATION });
        final InputStream input = socket.getInputStream();
        input.readNBytes(VERSION_BYTES);
        input.read();
      } catch (final IOException exception) {
        throw new UncheckedIOException(exception);
      }
    });
    final ProtocolException failure = assertThrows(ProtocolException.class, () ->
      VmAudioClient.connect(this.address(), "", (samples, length) -> {}, (message, cause) -> {})
    );
    final String message = failure.getMessage();
    assertEquals("VNC server asks for authentication, which the audio connection does not do!", message);

    served.join();
  }

  @Test
  void shouldChangeNothingWhenCloseFails() {
    final AtomicInteger closed = new AtomicInteger();
    VmAudioClient.closeQuietly(() -> {
      closed.incrementAndGet();
      throw new IOException("gone!");
    });
    final int closeCalls = closed.get();
    assertEquals(1, closeCalls);
  }

  private void waitUntil(final BooleanSupplier condition) {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Condition did not hold in time!");
      }
      Thread.onSpinWait();
    }
  }

  @Test
  void shouldKeepTheInterruptOfAnInterruptedWaitForAThreadOfTheSound() throws InterruptedException {
    final CountDownLatch release = new CountDownLatch(1);
    final Thread alive = new Thread(() -> this.awaitRelease(release));
    alive.start();
    final Thread current = Thread.currentThread();
    current.interrupt();
    try {
      VmAudioClient.join(alive);
      final boolean interrupted = current.isInterrupted();
      assertTrue(interrupted, "the interrupt stays for the caller");
    } finally {
      Thread.interrupted();
      release.countDown();
    }

    alive.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
    final boolean stillAlive = alive.isAlive();
    assertFalse(stillAlive);
  }

  private record Streams(DataInputStream input, DataOutputStream output) {}
}
