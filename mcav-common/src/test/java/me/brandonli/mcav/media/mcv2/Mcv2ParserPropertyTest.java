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
package me.brandonli.mcav.media.mcv2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import me.brandonli.mcav.media.mcv2.transport.PageAssembler;
import me.brandonli.mcav.media.mcv2.transport.TransportPage;
import me.brandonli.mcav.media.mcv2.transport.TransportPages;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

/**
 * The parsers are total and the transport is lossless on generated inputs, not only on the committed corpus: frames of
 * every index form with up to eight mutations each (flipped bytes, header fields set to the values bounds checks turn
 * on, truncation, growth, runs spliced in from other frames), frames of random bytes behind a plausible header, and the
 * pages of real frames in any order. Whatever a frame's bytes, parsing succeeds or throws {@link Mcv2Exception}, and a
 * frame that parses decodes to a picture of its size or throws that exception; whatever the order of a frame's pages,
 * the assembler gives back exactly the frame once its last page arrives.
 */
final class Mcv2ParserPropertyTest {

  private static final String SEED = "20260926";

  /** The first keyframe and P frame of every edge stream, the two-page keyframe of the ship stream and the wide fallback. */
  private static final List<byte[]> FRAMES = frames();

  /** Values the bounds checks of the header and the index turn on. */
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
    0xFFFFFF,
    0x1000000,
    0x7FFFFFFFL,
    0x80000000L,
    0xFFFFFFFFL,
  };

  private static List<byte[]> frames() {
    final List<byte[]> frames = new ArrayList<>();
    for (final String stream : Mcv2Fixtures.digests("edge").keySet()) {
      final List<byte[]> all = Mcv2Fixtures.frames(Mcv2Fixtures.read("edge/" + stream));
      frames.addAll(all.subList(0, Math.min(2, all.size())));
    }
    frames.add(Mcv2Fixtures.frames(Mcv2Fixtures.read("conformance/p30r19-compact_final-65p255994.mcs")).get(0));
    return List.copyOf(frames);
  }

  /** One change to a frame: its kind, where and with what, each reduced to the frame at hand when applied. */
  record Mutation(int kind, int position, int value) {}

  @Provide
  Arbitrary<List<Mutation>> mutations() {
    final Arbitrary<Mutation> mutation = Combinators.combine(
      Arbitraries.integers().between(0, 6),
      Arbitraries.integers().greaterOrEqual(0),
      Arbitraries.integers()
    ).as(Mutation::new);
    return mutation.list().ofMinSize(1).ofMaxSize(8);
  }

  static byte[] mutate(final byte[] frame, final List<Mutation> mutations) {
    byte[] bytes = frame.clone();
    for (final Mutation mutation : mutations) {
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
          // one of the twelve header words, set to a value a bounds check turns on
          final int offset = 4 * (mutation.position() % 12);
          if (offset + 4 <= length) {
            Mcv2Format.putU32(bytes, offset, EDGES[Math.floorMod(value, EDGES.length)]);
          }
        }
        case 2 -> {
          if (position + 2 <= length) {
            Mcv2Format.putU16(bytes, position, value & 0xFFFF);
          }
        }
        case 3 -> bytes = Arrays.copyOf(bytes, position);
        case 4 -> {
          final byte[] grown = Arrays.copyOf(bytes, length + Math.floorMod(value, 64));
          Arrays.fill(grown, length, grown.length, (byte) (value >>> 8));
          bytes = grown;
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
    }
    return bytes;
  }

  /** Parses and decodes, accepting only a picture of the frame's size or the declared exception. */
  static void parseAndDecode(final byte[] bytes) {
    final Mcv2Frame frame;
    try {
      frame = FrameParser.parse(bytes);
    } catch (final Mcv2Exception rejected) {
      return;
    }
    final int size = frame.getWidth() * frame.getHeight() * 3;
    final byte[] reference = new byte[size];
    for (int i = 0; i < size; i++) {
      reference[i] = (byte) (i * 37);
    }
    final byte[] picture;
    try {
      picture = Mcv2Decoder.decode(frame, reference, frame.getReferenceId());
    } catch (final Mcv2Exception rejected) {
      return;
    }
    assertEquals(size, picture.length);
  }

  @Property(seed = SEED, tries = 2000)
  void mutatedFramesParseOrAreRejected(
    @ForAll @IntRange(min = 0, max = 1000) final int frame,
    @ForAll("mutations") final List<Mutation> mutations
  ) {
    parseAndDecode(mutate(FRAMES.get(frame % FRAMES.size()), mutations));
  }

  /**
   * Random bytes behind a header that passes the first checks - the magic, the configuration, dimensions, a root count
   * that matches them, a frame length that is the real one - so that the index and record parsing see the bytes.
   */
  @Property(seed = SEED, tries = 2000)
  void randomFramesParseOrAreRejected(
    @ForAll @IntRange(min = 1, max = 200) final int width,
    @ForAll @IntRange(min = 1, max = 130) final int height,
    @ForAll @IntRange(min = 0, max = Mcv2Format.ALL_FLAGS) final int flags,
    @ForAll @IntRange(min = 0, max = 64) final int startOffset,
    @ForAll @Size(max = 4096) final byte[] body,
    @ForAll final long seed
  ) {
    final Random random = new Random(seed);
    final byte[] bytes = new byte[Mcv2Format.HEADER_BYTES + body.length];
    System.arraycopy(body, 0, bytes, Mcv2Format.HEADER_BYTES, body.length);
    final boolean keyframe = (flags & Mcv2Format.KEYFRAME) != 0;
    final long frameId = random.nextInt(1000) + 1L;
    Mcv2Format.putU32(bytes, 0, Mcv2Format.MAGIC);
    Mcv2Format.putU32(bytes, 4, ((long) flags << 16) | Mcv2Format.CONFIGURATION);
    Mcv2Format.putU32(bytes, 8, ((long) height << 16) | width);
    Mcv2Format.putU32(bytes, 12, frameId);
    Mcv2Format.putU32(bytes, 16, keyframe ? frameId : frameId - 1);
    Mcv2Format.putU32(bytes, 20, keyframe ? 0 : random.nextInt() & 0xFFFFFFFFL);
    Mcv2Format.putU32(bytes, 24, (long) ((width + 31) / 32) * ((height + 31) / 32));
    Mcv2Format.putU32(bytes, 28, Math.min(bytes.length, Mcv2Format.HEADER_BYTES + startOffset));
    Mcv2Format.putU32(bytes, 32, bytes.length);
    Mcv2Format.putU32(bytes, 36, (flags & Mcv2Format.DEFAULT_SOLID) != 0 ? random.nextInt(0x1000000) : 0);
    parseAndDecode(bytes);
  }

  @Property(seed = SEED, tries = 300)
  void symbolsCarryAnyBytesExactly(@ForAll @Size(max = 2000) final byte[] data, @ForAll @IntRange(min = 6, max = 8) final int symbolBits)
    throws Mcv2Exception {
    assertArrayEquals(data, TransportPages.fromSymbols(TransportPages.toSymbols(data, symbolBits), symbolBits, data.length));
  }

  /**
   * The pages of a frame, each read back alone, carry the frame's identity and, in page order, exactly its bytes; and
   * pushed in any order, with duplicates, they assemble to the frame when the last missing page arrives and not
   * before.
   */
  @Property(seed = SEED, tries = 300)
  void pagesCarryTheirFrameInAnyOrder(
    @ForAll @IntRange(min = 0, max = 1000) final int index,
    @ForAll @IntRange(min = 6, max = 8) final int symbolBits,
    @ForAll @LongRange(min = 0, max = 0xFFFFFFFFL) final long stream,
    @ForAll final long seed
  ) throws Mcv2Exception {
    final byte[] frame = FRAMES.get(index % FRAMES.size());
    final Mcv2Frame parsed = FrameParser.parse(frame);
    final List<byte[]> pages = TransportPages.makePages(frame, stream, symbolBits);
    final ByteArrayOutputStream payloads = new ByteArrayOutputStream();
    for (int number = 0; number < pages.size(); number++) {
      final TransportPage page = TransportPages.readPage(pages.get(number), symbolBits);
      assertEquals(stream, page.getStreamId());
      assertEquals(parsed.getFrameId(), page.getFrameId());
      assertEquals(parsed.getReferenceId(), page.getReferenceId());
      assertEquals(number, page.getNumber());
      assertEquals(pages.size(), page.getCount());
      assertEquals(frame.length, page.getFrameBytes());
      payloads.writeBytes(page.getPayload());
    }
    assertArrayEquals(frame, payloads.toByteArray());
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
      final byte[] assembled = assembler.push(pages.get(number));
      if (missing == 0) {
        assertArrayEquals(frame, assembled);
        return;
      }
      assertNull(assembled);
    }
  }
}
