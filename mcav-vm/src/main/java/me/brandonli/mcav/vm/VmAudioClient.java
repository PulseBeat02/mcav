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

import com.google.common.annotations.VisibleForTesting;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;

// QEMU converts the sound to the format of the audio pipeline itself. Only the handshake has a deadline: afterwards
// the reader waits as long as the guest stays silent, and an end nobody asked for is reported once.
final class VmAudioClient implements Closeable {

  static final int HANDSHAKE_TIMEOUT_MILLISECONDS = 5_000;

  private static final String UNSTARTED_READER_THREAD_NAME = "mcav-vm-audio-not-started";
  private static final String READER_THREAD_NAME = "mcav-vm-audio";
  private static final String DEADLINE_THREAD_NAME = "mcav-vm-audio-deadline";

  private final Socket socket;
  private final DataInputStream input;
  private final Sink sink;
  private final BiConsumer<String, Throwable> failures;

  private volatile Thread reader;
  private volatile boolean closed;

  private VmAudioClient(final Socket socket, final DataInputStream input, final Sink sink, final BiConsumer<String, Throwable> failures) {
    this.socket = socket;
    this.input = input;
    this.sink = sink;
    this.failures = failures;
    this.reader = new Thread(UNSTARTED_READER_THREAD_NAME);
  }

  static VmAudioClient connect(
    final InetSocketAddress address,
    final String password,
    final Sink sink,
    final BiConsumer<String, Throwable> failures
  ) throws IOException {
    final Socket socket = new Socket();
    final CompletableFuture<Void> deadline = scheduleHandshakeDeadline(socket);
    try {
      socket.connect(address, HANDSHAKE_TIMEOUT_MILLISECONDS);

      final InputStream rawInput = socket.getInputStream();
      final DataInputStream input = new DataInputStream(new BufferedInputStream(rawInput));
      final OutputStream rawOutput = socket.getOutputStream();
      final DataOutputStream output = new DataOutputStream(new BufferedOutputStream(rawOutput));
      handshake(input, output, password);
      deadline.cancel(false);

      final VmAudioClient client = new VmAudioClient(socket, input, sink, failures);
      client.startReading();

      return client;
    } catch (final IOException exception) {
      closeQuietly(socket);
      throw exception;
    }
  }

  private static CompletableFuture<Void> scheduleHandshakeDeadline(final Socket socket) {
    // the read timeout only limits a pause, so a server that trickles its handshake is cut off once it took too long;
    // a deadline cancelled in time never runs, and one that runs gets a thread of its own, as the common pool of the
    // server may be busy with the tasks of other plugins for longer than the deadline
    final Executor later = CompletableFuture.delayedExecutor(
      HANDSHAKE_TIMEOUT_MILLISECONDS,
      TimeUnit.MILLISECONDS,
      VmAudioClient::startDeadlineThread
    );
    return CompletableFuture.runAsync(() -> closeQuietly(socket), later);
  }

  private static void handshake(final DataInputStream input, final DataOutputStream output, final String password) throws IOException {
    QemuAudioProtocol.readVersion(input);
    QemuAudioProtocol.writeVersion(output);
    output.flush();

    final boolean authenticate = QemuAudioProtocol.negotiateSecurity(input, output, password);
    output.flush();

    if (authenticate) {
      QemuAudioProtocol.answerChallenge(input, output, password);
      output.flush();
    }

    QemuAudioProtocol.readSecurityResult(input);
    QemuAudioProtocol.writeClientInitialization(output);
    output.flush();

    QemuAudioProtocol.readServerInitialization(input);
    QemuAudioProtocol.writeSetEncodings(output);
    QemuAudioProtocol.writeEnableAudio(output, AudioFilter.CHANNELS, AudioFilter.SAMPLE_RATE);
    output.flush();
  }

  private void startReading() {
    final Thread thread = new Thread(this::read, READER_THREAD_NAME);
    thread.setDaemon(true);
    this.reader = thread;
    thread.start();
  }

  private void read() {
    final byte[] buffer = new byte[QemuAudioProtocol.MAXIMUM_AUDIO_BYTES];
    try {
      while (true) {
        final QemuAudioProtocol.Message message = QemuAudioProtocol.readMessage(this.input, AudioFilter.FRAME_SIZE, size -> buffer);
        this.deliver(message);
      }
    } catch (final IOException | RuntimeException exception) {
      if (!this.closed) {
        closeQuietly(this.socket);
        this.failures.accept("The audio connection of the virtual machine ended.", exception);
      }
    }
  }

  private void deliver(final QemuAudioProtocol.Message message) {
    final QemuAudioProtocol.Kind kind = message.getKind();
    if (kind == QemuAudioProtocol.Kind.DATA) {
      final byte[] samples = message.getSamples();
      final int length = message.getLength();
      this.sink.accept(samples, length);
    } else if (kind == QemuAudioProtocol.Kind.END) {
      // QEMU sends nothing until the guest plays again, so the sink keeps the time of the pause itself
      this.sink.quiet();
    }
  }

  @Override
  public void close() {
    this.closed = true;
    closeQuietly(this.socket);
    join(this.reader);
  }

  private static void startDeadlineThread(final Runnable task) {
    Thread.ofVirtual().name(DEADLINE_THREAD_NAME).start(task);
  }

  // a filter or failure callback that releases the player runs on the thread it would wait for
  static void join(final Thread thread) {
    final Thread current = Thread.currentThread();
    final boolean ownThread = thread.equals(current);
    if (ownThread) {
      return;
    }

    try {
      thread.join(HANDSHAKE_TIMEOUT_MILLISECONDS);
    } catch (final InterruptedException exception) {
      current.interrupt();
    }
  }

  static void closeQuietly(final Closeable closeable) {
    try {
      closeable.close();
    } catch (final IOException exception) {
      // the connection is gone either way
    }
  }

  @VisibleForTesting
  Thread getReader() {
    return this.reader;
  }

  boolean isAlive() {
    return this.reader.isAlive();
  }

  @FunctionalInterface
  interface Sink {
    // the buffer is reused for the next message, so the samples must be copied
    void accept(byte[] samples, int length);

    // the guest stopped playing: nothing arrives until it plays again
    default void quiet() {}
  }
}
