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
package me.brandonli.mcav.plugin.e2e;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

final class SoundListener implements WebSocket.Listener, AutoCloseable {

  private static final int SAMPLE_RATE = 48_000;
  private static final int FRAME_BYTES = 4;

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

  private static final int POLL_INTERVAL_MILLISECONDS = 100;

  private static final double CROSSINGS_PER_CYCLE = 2.0;

  private final HttpClient client;
  private final ByteArrayOutputStream pcm;
  private final WebSocket socket;

  private SoundListener(final HttpClient client, final URI uri) {
    this.client = client;
    this.pcm = new ByteArrayOutputStream();
    this.socket = client.newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT).buildAsync(uri, this).join();
  }

  static SoundListener connect(final int port) {
    final HttpClient client = HttpClient.newHttpClient();
    final URI uri = URI.create("ws://127.0.0.1:%s/audio".formatted(port));
    return new SoundListener(client, uri);
  }

  @Override
  public void close() {
    this.socket.abort();
    this.client.close();
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

  void clear() {
    synchronized (this.pcm) {
      this.pcm.reset();
    }
  }

  double awaitFrequency(final int seconds, final Duration timeout) throws InterruptedException {
    final long deadline = System.nanoTime() + timeout.toNanos();
    final int wanted = seconds * SAMPLE_RATE * FRAME_BYTES;
    final byte[] heard = this.awaitSound(wanted, deadline, timeout);
    return this.measureFrequency(heard);
  }

  private byte[] awaitSound(final int wanted, final long deadline, final Duration timeout) throws InterruptedException {
    byte[] heard = this.copyHeardBytes();
    while (heard.length < wanted) {
      if (System.nanoTime() > deadline) {
        final String message = "Within %s, only %s bytes of sound arrived!".formatted(timeout, heard.length);
        throw new AssertionError(message);
      }
      TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MILLISECONDS);
      heard = this.copyHeardBytes();
    }

    return heard;
  }

  private double measureFrequency(final byte[] heard) {
    final ByteBuffer samples = ByteBuffer.wrap(heard).order(ByteOrder.LITTLE_ENDIAN);
    final int frames = heard.length / FRAME_BYTES;
    int crossings = 0;
    boolean previousNegative = samples.getShort((frames - SAMPLE_RATE) * FRAME_BYTES) < 0;
    for (int frame = frames - SAMPLE_RATE + 1; frame < frames; frame++) {
      final boolean currentNegative = samples.getShort(frame * FRAME_BYTES) < 0;
      if (currentNegative != previousNegative) {
        crossings++;
      }
      previousNegative = currentNegative;
    }
    return crossings / CROSSINGS_PER_CYCLE;
  }

  private byte[] copyHeardBytes() {
    synchronized (this.pcm) {
      return this.pcm.toByteArray();
    }
  }
}
