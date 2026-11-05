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
package me.brandonli.mcav.bukkit.media.mcv2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import me.brandonli.mcav.bukkit.media.mcv2.transport.PageAssembler;
import me.brandonli.mcav.bukkit.media.mcv2.transport.TransportPage;
import me.brandonli.mcav.bukkit.media.mcv2.transport.TransportPages;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;
import org.checkerframework.checker.nullness.qual.Nullable;

final class Mcv2ParserPropertyTest {

  private static final String SEED = "20260926";

  private static final String PATH_SEPARATOR = "/";

  private static final List<byte[]> FRAMES = frames();

  private static final long[] EDGES = {
    0,
    1,
    2,
    31,
    32,
    33,
    47,
    48,
    49,
    0x7FFF,
    0x8000,
    0xFFFF,
    0x10000,
    0x1FFFF,
    0x20000,
    0xFFFFFF,
    0x1000000,
    0x7FFFFFFFL,
    0x80000000L,
    0xFFFFFFFFL,
  };

  private static List<byte[]> frames() {
    final List<byte[]> frames = new ArrayList<>();
    for (final String folder : List.of("conformance", "edge")) {
      for (final String stream : Mcv2Fixtures.readDigests(folder).keySet()) {
        final List<byte[]> all = Mcv2Fixtures.splitFrames(Mcv2Fixtures.read(folder + PATH_SEPARATOR + stream));
        frames.addAll(folder.equals("edge") ? all : all.subList(0, Math.min(2, all.size())));
      }
    }

    frames.add(Mcv2Trees.twoPages());
    return List.copyOf(frames);
  }

  @Provide
  Arbitrary<List<Mutation>> mutations() {
    final Arbitrary<Mutation> mutation = Combinators.combine(
      Arbitraries.integers().between(0, 6),
      Arbitraries.integers().greaterOrEqual(0),
      Arbitraries.integers()
    ).as(Mutation::new);
    return mutation.list().ofMinSize(1).ofMaxSize(8);
  }

  private byte[] mutate(final byte[] frame, final List<Mutation> mutations) {
    byte[] bytes = frame.clone();
    for (final Mutation mutation : mutations) {
      bytes = this.mutateOnce(bytes, mutation);
    }

    return bytes;
  }

  private byte[] mutateOnce(final byte[] bytes, final Mutation mutation) {
    final int length = bytes.length;
    final int position = length == 0 ? 0 : mutation.position() % length;
    final int value = mutation.value();
    switch (mutation.kind()) {
      case 0 -> {
        if (length > 0) {
          bytes[position] ^= (byte) (value | 1);
        }
      }
      case 1 -> {
        // one of the header or initial index words, set to a value a bounds check turns on
        final int offset = 4 * (mutation.position() % 12);
        if (offset + 4 <= length) {
          Mcv2Decoder.putUnsignedInt(bytes, offset, EDGES[Math.floorMod(value, EDGES.length)]);
        }
      }
      case 2 -> {
        if (position + 2 <= length) {
          Mcv2Decoder.putUnsignedShort(bytes, position, value & 0xFFFF);
        }
      }
      case 3 -> {
        return Arrays.copyOf(bytes, position);
      }
      case 4 -> {
        final byte[] grown = Arrays.copyOf(bytes, length + Math.floorMod(value, 64));
        Arrays.fill(grown, length, grown.length, (byte) (value >>> 8));
        return grown;
      }
      case 5 -> {
        final byte[] other = FRAMES.get(Math.floorMod(value, FRAMES.size()));
        final int run = Math.min(16, Math.min(length - position, other.length - Math.min(position, other.length)));
        if (run > 0) {
          System.arraycopy(other, position, bytes, position, run);
        }
      }
      default -> {
        if (length > 0) {
          bytes[position] = (byte) EDGES[Math.floorMod(value, 6)];
        }
      }
    }

    return bytes;
  }

  private void parseAndDecode(final byte[] bytes) {
    final Mcv2Decoder.Frame frame;
    try {
      frame = Mcv2Decoder.parse(bytes);
    } catch (final Mcv2Exception exception) {
      return;
    }

    final int size = frame.getWidth() * frame.getHeight() * 3;
    final byte[] reference = new byte[size];
    for (int index = 0; index < size; index++) {
      reference[index] = (byte) (index * 37);
    }

    final byte[] picture;
    try {
      picture = Mcv2Decoder.decode(frame, reference, frame.getReferenceId());
    } catch (final Mcv2Exception exception) {
      return;
    }

    final int pictureLength = picture.length;
    assertEquals(size, pictureLength);
  }

  @Property(seed = SEED, tries = 2000)
  void shouldParseOrRejectMutatedFrames(
    @ForAll @IntRange(min = 0, max = 1000) final int frame,
    @ForAll("mutations") final List<Mutation> mutations
  ) {
    this.parseAndDecode(this.mutate(FRAMES.get(frame % FRAMES.size()), mutations));
  }

  @Property(seed = SEED, tries = 2000)
  void shouldParseOrRejectRandomFrames(
    @ForAll @IntRange(min = 1, max = 200) final int width,
    @ForAll @IntRange(min = 1, max = 130) final int height,
    @ForAll final boolean isKeyframe,
    @ForAll @Size(max = 4096) final byte[] body,
    @ForAll final long seed
  ) {
    final Random random = new Random(seed);
    final byte[] bytes = new byte[Mcv2Decoder.HEADER_BYTES + body.length];
    System.arraycopy(body, 0, bytes, Mcv2Decoder.HEADER_BYTES, body.length);
    final long frameId = random.nextInt(1000) + 1L;
    Mcv2Decoder.putUnsignedInt(bytes, 0, Mcv2Decoder.MAGIC);
    bytes[4] = Mcv2Decoder.VERSION;
    Mcv2Decoder.putUnsignedInt(bytes, 8, ((long) height << 16) | width);
    Mcv2Decoder.putUnsignedInt(bytes, 12, frameId);
    Mcv2Decoder.putUnsignedInt(bytes, 16, isKeyframe ? frameId : frameId - 1);
    this.parseAndDecode(bytes);
  }

  @Property(seed = SEED, tries = 300)
  void shouldCarryAnyBytesExactlyInSymbols(@ForAll @Size(max = 2000) final byte[] data) {
    final byte[] carried = TransportPages.fromSymbols(TransportPages.toSymbols(data), data.length);
    assertArrayEquals(data, carried);
  }

  @Property(seed = SEED, tries = 300)
  void shouldCarryTheFrameAcrossPagesInAnyOrder(
    @ForAll @IntRange(min = 0, max = 1000) final int index,
    @ForAll @LongRange(min = 0, max = 0xFFFFFFFFL) final long stream,
    @ForAll final long seed
  ) {
    final int symbolBits = 6;
    final byte[] frame = FRAMES.get(index % FRAMES.size());
    final Mcv2Decoder.Frame parsed = Mcv2Decoder.parse(frame);
    final List<byte[]> pages = TransportPages.makePages(frame, stream);
    final ByteArrayOutputStream payloads = new ByteArrayOutputStream();
    for (int number = 0; number < pages.size(); number++) {
      final TransportPage page = TransportPages.readPage(pages.get(number));
      final long streamId = page.getStreamId();
      assertEquals(stream, streamId);
      final long expectedFrameId = parsed.getFrameId();
      final long frameId = page.getFrameId();
      assertEquals(expectedFrameId, frameId);
      final long expectedReferenceId = parsed.getReferenceId();
      final long referenceId = page.getReferenceId();
      assertEquals(expectedReferenceId, referenceId);
      final int pageNumber = page.getNumber();
      assertEquals(number, pageNumber);
      final int expectedCount = pages.size();
      final int count = page.getCount();
      assertEquals(expectedCount, count);
      final int expectedFrameBytes = frame.length;
      final int frameBytes = page.getFrameBytes();
      assertEquals(expectedFrameBytes, frameBytes);

      payloads.writeBytes(page.getPayload());
    }

    final byte[] carriedFrame = payloads.toByteArray();
    assertArrayEquals(frame, carriedFrame);

    final Random random = new Random(seed);
    final List<Integer> order = new ArrayList<>();
    for (int number = 0; number < pages.size(); number++) {
      for (int copies = 1 + random.nextInt(3); copies > 0; copies--) {
        order.add(number);
      }
    }

    Collections.shuffle(order, random);
    final PageAssembler assembler = new PageAssembler(stream, symbolBits);
    final boolean[] seen = new boolean[pages.size()];
    int missing = pages.size();
    for (final int number : order) {
      if (!seen[number]) {
        seen[number] = true;
        missing--;
      }
      final byte @Nullable [] assembled = assembler.push(pages.get(number)).orElse(null);
      if (missing == 0) {
        assertArrayEquals(frame, assembled);

        return;
      }
      assertNull(assembled);
    }
  }

  private record Mutation(int kind, int position, int value) {}
}
