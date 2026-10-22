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
import java.util.Collection;
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
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Stubber;
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
  private static final long SILENCE_MILLISECONDS = 50;
  private static final long SEND_TIME_LIMIT_MILLISECONDS = 5_000;

  private static final int MAXIMUM_CHUNK_BYTES = 3000;
  private static final int MAXIMUM_CHUNK_COUNT = 40;
  private static final int MAXIMUM_LIMIT_BYTES = 4096;

  private static final int MAXIMUM_SAMPLE_BYTES = 300;
  private static final int MAXIMUM_MARGIN_BYTES = 9;

  private static final int MAXIMUM_HEXTET = 0xFFFF;
  private static final int HEXTET_COUNT = 8;
  private static final char HEXTET_SEPARATOR = ':';

  private static final String ZONE_SEPARATOR = "%";
  private static final String ENCODED_ZONE_SEPARATOR = "%25";

  private static final int PORT = 8080;

  @Provide
  Arbitrary<List<Integer>> chunkSizes() {
    final IntegerArbitrary integers = Arbitraries.integers();
    final Arbitrary<Integer> sizes = integers.between(0, MAXIMUM_CHUNK_BYTES);
    final ListArbitrary<Integer> sizeList = sizes.list();
    return sizeList.ofMaxSize(MAXIMUM_CHUNK_COUNT);
  }

  @Property(seed = SEED, tries = 100)
  void shouldDropTheOldestChunksKeepTheNewestAndSendWhatTheQueueKeptInOrder(
    @ForAll("chunkSizes") final List<Integer> sizes,
    @ForAll @IntRange(min = 1, max = MAXIMUM_LIMIT_BYTES) final int limit
  ) throws Exception {
    final Recorder recorder = new Recorder();
    final AudioListener listener = new AudioListener(recorder.session, SEND_TIME_LIMIT_MILLISECONDS, limit, _ -> {}, () -> 0L);
    final Deque<byte[]> kept = this.offerEveryChunk(listener, sizes, limit);

    listener.start();
    try {
      this.assertSentInOrder(recorder, kept);
    } finally {
      listener.stop();
    }
  }

  private Deque<byte[]> offerEveryChunk(final AudioListener listener, final List<Integer> sizes, final int limit) {
    final Deque<byte[]> kept = new ArrayDeque<>();
    for (int index = 0; index < sizes.size(); index++) {
      final int size = sizes.get(index);
      final byte[] chunk = this.createChunk(size, index);
      final boolean queued = listener.offer(chunk);
      assertTrue(queued, "an open listener queues every chunk");

      kept.addLast(chunk);
      this.dropOldestOverTheLimit(kept, limit);
      this.assertPending(listener, kept, index);
    }

    return kept;
  }

  private byte[] createChunk(final int size, final int index) {
    final byte[] chunk = new byte[size];
    final byte fill = (byte) index;
    Arrays.fill(chunk, fill);
    return chunk;
  }

  private void dropOldestOverTheLimit(final Deque<byte[]> kept, final int limit) {
    while (this.countBytes(kept) > limit && kept.size() > 1) {
      kept.removeFirst();
    }
  }

  private long countBytes(final Collection<byte[]> chunks) {
    long bytes = 0;
    for (final byte[] chunk : chunks) {
      bytes += chunk.length;
    }

    return bytes;
  }

  private void assertPending(final AudioListener listener, final Deque<byte[]> kept, final int index) {
    final long pendingBytes = listener.getPendingBytes();
    final int pendingChunks = listener.getPendingChunkCount();
    final long keptBytes = this.countBytes(kept);
    assertEquals(keptBytes, pendingBytes, "bytes waiting after chunk %s".formatted(index));

    final int keptChunks = kept.size();
    assertEquals(keptChunks, pendingChunks, "chunks waiting after chunk %s".formatted(index));
  }

  private void assertSentInOrder(final Recorder recorder, final Deque<byte[]> kept) throws InterruptedException {
    for (final byte[] expected : kept) {
      final byte[] sent = recorder.next();
      assertNotNull(sent, "the sender wrote every chunk it kept");
      assertArrayEquals(expected, sent, "the sender writes the kept chunks in order");
    }

    final byte[] extra = recorder.poll();
    assertNull(extra, "nothing was sent that the queue had dropped");
  }

  @Provide
  Arbitrary<Samples> samples() {
    final ByteArbitrary bytes = Arbitraries.bytes();
    final ListArbitrary<Byte> byteList = bytes.list();
    final ListArbitrary<Byte> contents = byteList.ofMaxSize(MAXIMUM_SAMPLE_BYTES);
    final IntegerArbitrary integers = Arbitraries.integers();
    final Arbitrary<Integer> margins = integers.between(0, MAXIMUM_MARGIN_BYTES);
    final Arbitrary<Boolean> flags = Arbitraries.of(true, false);
    final Combinators.Combinator4<List<Byte>, Integer, Boolean, Boolean> samples = Combinators.combine(contents, margins, flags, flags);
    return samples.as(Samples::new);
  }

  @Property(seed = SEED, tries = 100)
  void shouldGiveListenersACopyOfExactlyTheRemainingBytes(@ForAll("samples") final Samples samples) throws Exception {
    final SpringHttpResult result = new SpringHttpResult("localhost", 0, null, List.of());
    final Recorder recorder = new Recorder();
    result.addListener(recorder.session);
    final OriginalAudioMetadata metadata = Mockito.mock(OriginalAudioMetadata.class);
    final ByteBuffer buffer = samples.create();
    final int position = buffer.position();
    final int limit = buffer.limit();
    try {
      final boolean changed = result.applyFilter(buffer, metadata);
      this.overwrite(buffer);
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

  private void overwrite(final ByteBuffer buffer) {
    for (int index = buffer.position(); index < buffer.limit(); index++) {
      final byte current = buffer.get(index);
      buffer.put(index, (byte) ~current);
    }
  }

  @Provide
  Arbitrary<String> ipv6Hosts() {
    final IntegerArbitrary integers = Arbitraries.integers();
    final Arbitrary<Integer> hextets = integers.between(0, MAXIMUM_HEXTET);
    final ListArbitrary<Integer> hextetList = hextets.list();
    final ListArbitrary<Integer> addresses = hextetList.ofSize(HEXTET_COUNT);
    final Arbitrary<String> literals = addresses.map(this::formatAddress);
    final Arbitrary<String> zones = Arbitraries.of("", "%eth0", "%1", "%en0");
    final Combinators.Combinator2<String, String> hosts = Combinators.combine(literals, zones);
    return hosts.as((literal, zone) -> literal + zone);
  }

  private String formatAddress(final List<Integer> hextets) {
    final StringBuilder address = new StringBuilder();
    for (final int hextet : hextets) {
      if (!address.isEmpty()) {
        address.append(HEXTET_SEPARATOR);
      }
      final String hexadecimal = Integer.toHexString(hextet);
      address.append(hexadecimal);
    }

    return address.toString();
  }

  /**
   * The page's address must be a valid URL for every IPv6 address and zone the server may be configured with; a zone
   * id needs its percent sign encoded, as RFC 6874 requires.
   */
  @Property(seed = SEED)
  void shouldMakeAValidPageAddressFromEveryIpv6Host(@ForAll("ipv6Hosts") final String host) {
    final SpringHttpResult http = new SpringHttpResult(host, PORT, null);
    final String urlHost = http.urlHost(host);
    final String url = "http://%s:%s/".formatted(urlHost, PORT);
    final URI uri = URI.create(url);
    final String parsedHost = uri.getHost();
    final int port = uri.getPort();
    final String encodedHost = host.replace(ZONE_SEPARATOR, ENCODED_ZONE_SEPARATOR);
    final String expectedHost = "[%s]".formatted(encodedHost);

    assertEquals(expectedHost, parsedHost, "the host part of %s".formatted(url));
    assertEquals(PORT, port, "the port stays separate from the address");
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

      final Stubber recording = Mockito.doAnswer(this::record);
      final WebSocketSession stubbedSession = recording.when(this.session);
      stubbedSession.sendMessage(ArgumentMatchers.any());
    }

    private Void record(final InvocationOnMock invocation) {
      final WebSocketMessage<?> message = invocation.getArgument(0);
      final BinaryMessage binary = (BinaryMessage) message;
      final ByteBuffer payload = binary.getPayload();
      final ByteBuffer copy = payload.duplicate();
      final byte[] bytes = new byte[copy.remaining()];
      copy.get(bytes);
      this.sent.add(bytes);

      return null;
    }

    byte[] next() throws InterruptedException {
      return this.sent.poll(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private byte[] poll() throws InterruptedException {
      return this.sent.poll(SILENCE_MILLISECONDS, TimeUnit.MILLISECONDS);
    }
  }

  /**
   * Samples in a buffer with other bytes around them, in heap or native memory.
   */
  private static final class Samples {

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

    ByteBuffer create() {
      final int capacity = this.margin + this.content.length + this.margin;
      final ByteBuffer whole = this.direct ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
      whole.put(this.margin, this.content);
      whole.position(this.margin);
      whole.limit(this.margin + this.content.length);

      return this.sliced ? whole.slice() : whole;
    }

    private byte[] getContent() {
      return this.content.clone();
    }

    @Override
    public String toString() {
      final String kind = this.direct ? "direct" : "heap";
      final String view = this.sliced ? " slice" : "";
      return "%s bytes, margin %s, %s%s".formatted(this.content.length, this.margin, kind, view);
    }
  }
}
