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

/**
 * The audio connection to QEMU's VNC server: a second, shared RFB client of the display of the machine, which
 * receives the samples the guest plays through {@link QemuAudioProtocol} and hands them to a {@link Sink}.
 *
 * <p>QEMU is asked for the format of the audio pipeline, 16-bit little-endian stereo at 48 kHz, and converts to it
 * itself, so the samples need no conversion here. The whole handshake must finish within
 * {@value #HANDSHAKE_TIMEOUT_MILLIS} ms; afterwards a reader thread waits for as long as the guest stays silent. A
 * connection that ends or breaks the protocol while nobody closed it, or whose sink fails, is reported, once.
 */
final class VMAudioClient implements Closeable {

  static final int HANDSHAKE_TIMEOUT_MILLIS = 5_000;

  private final Socket socket;
  private final DataInputStream in;
  private final Sink sink;
  private final BiConsumer<String, Throwable> failures;
  private volatile Thread reader;
  private volatile boolean closed;

  private VMAudioClient(final Socket socket, final DataInputStream in, final Sink sink, final BiConsumer<String, Throwable> failures) {
    this.socket = socket;
    this.in = in;
    this.sink = sink;
    this.failures = failures;
    // replaced by the running reader once the client exists
    this.reader = new Thread("mcav-vm-audio-not-started");
  }

  private void startReading() {
    final Thread thread = new Thread(this::read, "mcav-vm-audio");
    thread.setDaemon(true);
    this.reader = thread;
    thread.start();
  }

  /**
   * Connects to the VNC server of a machine, enables its audio and starts receiving samples.
   *
   * @param address  the address of the VNC server
   * @param sink     receives the samples
   * @param failures receives an unexpected end of the connection
   * @return the connected client
   * @throws IOException if the connection or the handshake fails
   */
  static VMAudioClient connect(final InetSocketAddress address, final Sink sink, final BiConsumer<String, Throwable> failures)
    throws IOException {
    final Socket socket = new Socket();
    // the read timeout only limits a pause, so a server that trickles its handshake is cut off once it took too long;
    // a deadline cancelled in time never runs
    final Executor later = CompletableFuture.delayedExecutor(HANDSHAKE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    final CompletableFuture<Void> deadline = CompletableFuture.runAsync(() -> closeQuietly(socket), later);
    try {
      socket.connect(address, HANDSHAKE_TIMEOUT_MILLIS);
      socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
      socket.setTcpNoDelay(true);
      final InputStream rawInput = socket.getInputStream();
      final DataInputStream in = new DataInputStream(new BufferedInputStream(rawInput));
      final OutputStream rawOutput = socket.getOutputStream();
      final DataOutputStream out = new DataOutputStream(new BufferedOutputStream(rawOutput));
      handshake(in, out);
      deadline.cancel(false);
      // the guest may stay silent for as long as it likes
      socket.setSoTimeout(0);
      final VMAudioClient client = new VMAudioClient(socket, in, sink, failures);
      client.startReading();
      return client;
    } catch (final IOException failure) {
      closeQuietly(socket);
      throw failure;
    }
  }

  /**
   * Agrees on RFB 3.8 without authentication, shares the display, and enables the audio in the format of the audio
   * pipeline.
   *
   * @param in  the stream from the server
   * @param out the stream to the server
   * @throws IOException if the handshake fails
   */
  static void handshake(final DataInputStream in, final DataOutputStream out) throws IOException {
    QemuAudioProtocol.readVersion(in);
    QemuAudioProtocol.writeVersion(out);
    out.flush();
    QemuAudioProtocol.negotiateSecurity(in, out);
    out.flush();
    QemuAudioProtocol.readSecurityResult(in);
    QemuAudioProtocol.writeClientInit(out);
    out.flush();
    QemuAudioProtocol.readServerInit(in);
    QemuAudioProtocol.writeSetEncodings(out);
    QemuAudioProtocol.writeEnableAudio(out, AudioFilter.CHANNELS, AudioFilter.SAMPLE_RATE);
    out.flush();
  }

  private void read() {
    final byte[] buffer = new byte[QemuAudioProtocol.MAX_AUDIO_BYTES];
    try {
      while (true) {
        final QemuAudioProtocol.Message message = QemuAudioProtocol.readMessage(this.in, AudioFilter.FRAME_SIZE, size -> buffer);
        if (message.getKind() == QemuAudioProtocol.Kind.DATA) {
          this.sink.accept(message.getSamples(), message.getLength());
        }
      }
    } catch (final IOException | RuntimeException exception) {
      // a sink that fails ends the connection like a server that breaks the protocol
      if (!this.closed) {
        closeQuietly(this.socket);
        this.failures.accept("The audio connection of the virtual machine ended", exception);
      }
    }
  }

  /**
   * Checks whether the reader thread still runs.
   *
   * @return true while samples can arrive
   */
  boolean isAlive() {
    return this.reader.isAlive();
  }

  /**
   * Closes the connection and waits for the reader thread to end. Safe to call more than once.
   */
  @Override
  public void close() {
    this.closed = true;
    closeQuietly(this.socket);
    join(this.reader);
  }

  /**
   * Waits for a thread of the sound to end, at most {@value #HANDSHAKE_TIMEOUT_MILLIS} ms, unless it is the calling
   * thread: a filter or a failure callback that releases the player runs on the thread it would wait for.
   *
   * @param thread the thread
   */
  static void join(final Thread thread) {
    final Thread caller = Thread.currentThread();
    if (thread.equals(caller)) {
      return;
    }
    try {
      thread.join(HANDSHAKE_TIMEOUT_MILLIS);
    } catch (final InterruptedException exception) {
      caller.interrupt();
    }
  }

  /**
   * Closes a socket whose failure to close changes nothing, as the connection is gone either way.
   *
   * @param closeable the socket
   */
  static void closeQuietly(final Closeable closeable) {
    try {
      closeable.close();
    } catch (final IOException exception) {
      // the connection is gone either way
    }
  }

  /**
   * Receives the samples of the guest.
   */
  @FunctionalInterface
  interface Sink {
    /**
     * Takes samples, which must be copied: the buffer is reused for the next message.
     *
     * @param samples the buffer, 16-bit little-endian stereo at 48 kHz
     * @param length  the number of bytes of samples at its start, whole frames
     */
    void accept(byte[] samples, int length);
  }
}
