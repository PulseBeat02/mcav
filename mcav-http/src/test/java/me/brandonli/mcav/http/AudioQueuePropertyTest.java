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
package me.brandonli.mcav.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.arbitraries.ByteArbitrary;
import net.jqwik.api.arbitraries.IntegerArbitrary;
import net.jqwik.api.arbitraries.ListArbitrary;
import net.jqwik.api.constraints.IntRange;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Properties of what the audio server hands to a browser, for every sequence of chunks and every layout of the samples
 * the pipeline passes on: the byte-limited queue of a listener drops the oldest chunks and always keeps the newest, the
 * sender writes what was kept in order, and the server sends exactly the bytes between the position and the limit of
 * the samples, copied, without moving the buffer of the pipeline.
 */
final class AudioQueuePropertyTest {

  private static final String SEED = "20260925";
  private static final long WAIT_SECONDS = 10;

  @Provide
  Arbitrary<List<Integer>> chunkSizes() {
    final IntegerArbitrary integers = Arbitraries.integers();
    final Arbitrary<Integer> sizes = integers.between(0, 3000);
    final ListArbitrary<Integer> sizeList = sizes.list();
    return sizeList.ofMaxSize(40);
  }

  @Property(seed = SEED, tries = 100)
  void theQueueDropsTheOldestChunksKeepsTheNewestAndSendsWhatItKeptInOrder(
    @ForAll("chunkSizes") final List<Integer> sizes,
    @ForAll @IntRange(min = 1, max = 4096) final int limit
  ) throws Exception {
    final Recorder recorder = new Recorder();
    final AudioListener listener = new AudioListener(recorder.session, 5_000, limit, _ -> {}, () -> 0L);
    final Deque<byte[]> kept = new ArrayDeque<>();
    long keptBytes = 0;
    for (int index = 0; index < sizes.size(); index++) {
      final byte[] chunk = new byte[sizes.get(index)];
      final byte fill = (byte) index;
      Arrays.fill(chunk, fill);
      final boolean queued = listener.offer(chunk);
      assertTrue(queued, "an open listener queues every chunk");
      kept.addLast(chunk);
      keptBytes += chunk.length;
      while (keptBytes > limit && kept.size() > 1) {
        final byte[] oldest = kept.removeFirst();
        keptBytes -= oldest.length;
      }
      final long pendingBytes = listener.getPendingBytes();
      final int pendingChunks = listener.getPendingChunkCount();
      assertEquals(keptBytes, pendingBytes, "bytes waiting after chunk " + index);
      assertEquals(kept.size(), pendingChunks, "chunks waiting after chunk " + index);
    }

    listener.start();
    try {
      for (final byte[] expected : kept) {
        final byte[] sent = recorder.next();
        assertNotNull(sent, "the sender wrote every chunk it kept");
        assertArrayEquals(expected, sent, "the sender writes the kept chunks in order");
      }
      final byte[] extra = recorder.poll();
      assertNull(extra, "nothing was sent that the queue had dropped");
    } finally {
      listener.stop();
    }
  }

  @Provide
  Arbitrary<Samples> samples() {
    final ByteArbitrary bytes = Arbitraries.bytes();
    final ListArbitrary<Byte> byteList = bytes.list();
    final ListArbitrary<Byte> contents = byteList.ofMaxSize(300);
    final IntegerArbitrary integers = Arbitraries.integers();
    final Arbitrary<Integer> margins = integers.between(0, 9);
    final Arbitrary<Boolean> flags = Arbitraries.of(true, false);
    final Combinators.Combinator4<List<Byte>, Integer, Boolean, Boolean> samples = Combinators.combine(contents, margins, flags, flags);
    return samples.as(Samples::new);
  }

  @Property(seed = SEED, tries = 100)
  void listenersReceiveACopyOfExactlyTheRemainingBytes(@ForAll("samples") final Samples samples) throws Exception {
    final HttpResultImpl result = new HttpResultImpl("localhost", 0, null, List.of());
    final Recorder recorder = new Recorder();
    result.addListener(recorder.session);
    final OriginalAudioMetadata metadata = Mockito.mock(OriginalAudioMetadata.class);
    final ByteBuffer buffer = samples.create();
    final int position = buffer.position();
    final int limit = buffer.limit();
    try {
      final boolean changed = result.applyFilter(buffer, metadata);
      // the pipeline may reuse its buffer as soon as the call returns
      samples.overwrite(buffer);

      assertFalse(changed, "the samples are only read");
      final int positionAfter = buffer.position();
      final int limitAfter = buffer.limit();
      assertEquals(position, positionAfter, "position of the pipeline's buffer");
      assertEquals(limit, limitAfter, "limit of the pipeline's buffer");
      final byte[] sent = recorder.next();
      final byte[] expected = samples.getContent();
      assertArrayEquals(expected, sent, "the listener receives the samples as they were when they were passed on");
    } finally {
      result.stop();
    }
  }

  @Provide
  Arbitrary<String> ipv6Hosts() {
    final IntegerArbitrary integers = Arbitraries.integers();
    final Arbitrary<Integer> hextets = integers.between(0, 0xFFFF);
    final ListArbitrary<Integer> hextetList = hextets.list();
    final ListArbitrary<Integer> addresses = hextetList.ofSize(8);
    final Arbitrary<String> literals = addresses.map(AudioQueuePropertyTest::formatAddress);
    final Arbitrary<String> zones = Arbitraries.of("", "%eth0", "%1", "%en0");
    final Combinators.Combinator2<String, String> hosts = Combinators.combine(literals, zones);
    return hosts.as((literal, zone) -> literal + zone);
  }

  private static String formatAddress(final List<Integer> hextets) {
    final StringBuilder address = new StringBuilder();
    for (final int hextet : hextets) {
      if (!address.isEmpty()) {
        address.append(':');
      }
      final String hex = Integer.toHexString(hextet);
      address.append(hex);
    }
    return address.toString();
  }

  /**
   * The page's address must be a valid URL for every IPv6 address and zone the server may be configured with; a zone
   * id needs its percent sign encoded, as RFC 6874 requires.
   */
  @Property(seed = SEED)
  void everyIpv6HostMakesAValidPageAddress(@ForAll("ipv6Hosts") final String host) {
    final String urlHost = HttpResultImpl.urlHost(host);
    final String url = "http://" + urlHost + ":8080/";
    final URI uri = URI.create(url);
    final String parsedHost = uri.getHost();
    final int port = uri.getPort();
    final String expectedHost = "[" + host.replace("%", "%25") + "]";

    assertEquals(expectedHost, parsedHost, "the host part of " + url);
    assertEquals(8080, port, "the port stays separate from the address");
  }

  /**
   * A session that records the payload of every binary message written to it.
   */
  private static final class Recorder {

    private final WebSocketSession session;
    private final BlockingQueue<byte[]> sent;

    Recorder() throws IOException {
      this.session = Mockito.mock(WebSocketSession.class);
      this.sent = new LinkedBlockingQueue<>();
      Mockito.when(this.session.isOpen()).thenReturn(true);
      Mockito.when(this.session.getId()).thenReturn("property");
      Mockito.doAnswer(invocation -> {
        final WebSocketMessage<?> message = invocation.getArgument(0);
        final BinaryMessage binary = (BinaryMessage) message;
        final ByteBuffer payload = binary.getPayload();
        final ByteBuffer copy = payload.duplicate();
        final byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        this.sent.add(bytes);
        return null;
      })
        .when(this.session)
        .sendMessage(ArgumentMatchers.any());
    }

    byte[] next() throws InterruptedException {
      return this.sent.poll(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    byte[] poll() throws InterruptedException {
      return this.sent.poll(50, TimeUnit.MILLISECONDS);
    }
  }

  /**
   * Samples in a buffer with other bytes around them, in heap or native memory.
   */
  static final class Samples {

    private final byte[] content;
    private final int margin;
    private final boolean direct;
    private final boolean sliced;

    Samples(final List<Byte> content, final int margin, final boolean direct, final boolean sliced) {
      this.content = new byte[content.size()];
      for (int index = 0; index < this.content.length; index++) {
        this.content[index] = content.get(index);
      }
      this.margin = margin;
      this.direct = direct;
      this.sliced = sliced;
    }

    byte[] getContent() {
      return this.content.clone();
    }

    ByteBuffer create() {
      final int capacity = this.content.length + 2 * this.margin;
      final ByteBuffer whole = this.direct ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
      whole.put(this.margin, this.content);
      whole.position(this.margin);
      whole.limit(this.margin + this.content.length);
      return this.sliced ? whole.slice() : whole;
    }

    void overwrite(final ByteBuffer buffer) {
      for (int index = buffer.position(); index < buffer.limit(); index++) {
        final byte current = buffer.get(index);
        buffer.put(index, (byte) ~current);
      }
    }

    @Override
    public String toString() {
      final String kind = this.direct ? "direct" : "heap";
      final String view = this.sliced ? " slice" : "";
      return this.content.length + " bytes, margin " + this.margin + ", " + kind + view;
    }
  }
}
