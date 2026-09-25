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
package me.brandonli.mcav.sandbox.e2e;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Listens to the audio web page of the plugin like a browser does: its {@code /audio} WebSocket sends the samples of
 * the audio pipeline, 16-bit little-endian stereo at 48 kHz.
 */
final class SoundListener implements WebSocket.Listener, AutoCloseable {

  private static final int SAMPLE_RATE = 48_000;
  private static final int FRAME_BYTES = 4;

  private final HttpClient client;
  private final ByteArrayOutputStream pcm;
  private final WebSocket socket;

  private SoundListener(final HttpClient client, final URI uri) {
    this.client = client;
    this.pcm = new ByteArrayOutputStream();
    this.socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(30)).buildAsync(uri, this).join();
  }

  /**
   * Connects to the audio web page of a server.
   *
   * @param port the port of the audio web page
   * @return the connected listener
   */
  static SoundListener connect(final int port) {
    return new SoundListener(HttpClient.newHttpClient(), URI.create("ws://127.0.0.1:" + port + "/audio"));
  }

  @Override
  public CompletionStage<?> onBinary(final WebSocket webSocket, final ByteBuffer data, final boolean last) {
    final byte[] bytes = new byte[data.remaining()];
    data.get(bytes);
    synchronized (this.pcm) {
      this.pcm.writeBytes(bytes);
    }
    webSocket.request(1);
    return null;
  }

  /**
   * Forgets what was heard so far.
   */
  void clear() {
    synchronized (this.pcm) {
      this.pcm.reset();
    }
  }

  /**
   * Waits until some seconds of sound were heard, and measures the frequency of the last second by its zero crossings.
   *
   * @param seconds how much sound to wait for
   * @param timeout how long to wait at most
   * @return the frequency of the left channel in the last second, in hertz
   * @throws InterruptedException if the wait is interrupted
   */
  double awaitFrequency(final int seconds, final Duration timeout) throws InterruptedException {
    final long deadline = System.nanoTime() + timeout.toNanos();
    final int wanted = seconds * SAMPLE_RATE * FRAME_BYTES;
    byte[] heard = this.heard();
    while (heard.length < wanted) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Only " + heard.length + " bytes of sound arrived within " + timeout);
      }
      TimeUnit.MILLISECONDS.sleep(100);
      heard = this.heard();
    }
    final ByteBuffer samples = ByteBuffer.wrap(heard).order(ByteOrder.LITTLE_ENDIAN);
    final int frames = heard.length / FRAME_BYTES;
    int crossings = 0;
    boolean negative = samples.getShort((frames - SAMPLE_RATE) * FRAME_BYTES) < 0;
    for (int frame = frames - SAMPLE_RATE + 1; frame < frames; frame++) {
      final boolean now = samples.getShort(frame * FRAME_BYTES) < 0;
      if (now != negative) {
        crossings++;
      }
      negative = now;
    }
    return crossings / 2.0;
  }

  private byte[] heard() {
    synchronized (this.pcm) {
      return this.pcm.toByteArray();
    }
  }

  @Override
  public void close() {
    this.socket.abort();
    this.client.close();
  }
}
