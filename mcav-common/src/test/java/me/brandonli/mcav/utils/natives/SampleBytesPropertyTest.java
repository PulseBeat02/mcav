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
package me.brandonli.mcav.utils.natives;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.List;
import me.brandonli.mcav.utils.audio.MonoDownmixer;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.arbitraries.ByteArbitrary;
import net.jqwik.api.arbitraries.IntegerArbitrary;
import net.jqwik.api.arbitraries.ListArbitrary;

/**
 * Properties of the sample conversions of the audio paths for every layout a buffer can have: heap or native memory,
 * a view that starts inside a larger buffer, bytes that do not belong to the samples around it, read-only, and either
 * byte order flag. The samples must come out unchanged, the caller's buffer must be left as it was, and the result must
 * be a copy the caller's buffer cannot change afterwards.
 */
final class SampleBytesPropertyTest {

  private static final String SEED = "20260925";
  private static final byte JUNK = 0x5A;

  @Provide
  Arbitrary<SampleBuffer> buffers() {
    final ByteArbitrary bytes = Arbitraries.bytes();
    final ListArbitrary<Byte> byteList = bytes.list();
    final ListArbitrary<Byte> contents = byteList.ofMaxSize(67);
    final Arbitrary<Integer> margins = between(0, 7);
    final Arbitrary<Layout> layouts = Arbitraries.of(Layout.class);
    final Arbitrary<ByteOrder> orders = Arbitraries.of(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN);
    final Combinators.Combinator5<List<Byte>, Integer, Integer, Layout, ByteOrder> buffers = Combinators.combine(
      contents,
      margins,
      margins,
      layouts,
      orders
    );
    return buffers.as(SampleBuffer::new);
  }

  /**
   * Creates integers in a range from a fresh arbitrary: jqwik 1.9 shares the range between an arbitrary and the ones
   * configured from it, so one base configured twice would hand every user the last range.
   */
  private static Arbitrary<Integer> between(final int min, final int max) {
    final IntegerArbitrary integers = Arbitraries.integers();
    return integers.between(min, max);
  }

  @Property(seed = SEED)
  void bigEndianSamplesAreTheSamplesOfTheSourceOrder(@ForAll("buffers") final SampleBuffer sample) {
    final ByteBuffer source = sample.create();
    final ByteOrder sourceOrder = source.order();

    final ByteBuffer converted = ByteUtils.toBigEndian(source);

    final ByteOrder convertedOrder = converted.order();
    assertSame(ByteOrder.BIG_ENDIAN, convertedOrder);
    assertSameSamples(sample, sourceOrder, converted);
    sample.assertUnchanged(source);
    assertIndependentCopy(sample, source, converted);
  }

  @Property(seed = SEED)
  void littleEndianSamplesAreTheSamplesOfTheSourceOrder(@ForAll("buffers") final SampleBuffer sample) {
    final ByteBuffer source = sample.create();
    final ByteOrder sourceOrder = source.order();

    final ByteBuffer converted = ByteUtils.toLittleEndian(source);

    final ByteOrder convertedOrder = converted.order();
    assertSame(ByteOrder.LITTLE_ENDIAN, convertedOrder);
    assertSameSamples(sample, sourceOrder, converted);
    sample.assertUnchanged(source);
    assertIndependentCopy(sample, source, converted);
  }

  @Property(seed = SEED)
  void shortAndFloatViewsKeepEveryValue(@ForAll("buffers") final SampleBuffer sample) {
    final ByteBuffer bytes = sample.create();
    final ShortBuffer shorts = bytes.asShortBuffer();
    final FloatBuffer floats = bytes.asFloatBuffer();
    final int shortCount = shorts.remaining();
    final int floatCount = floats.remaining();

    final ByteBuffer fromShorts = ByteUtils.toLittleEndian(shorts);
    final ByteBuffer fromFloats = ByteUtils.toLittleEndian(floats);

    final int shortBytes = fromShorts.remaining();
    final int floatBytes = fromFloats.remaining();
    final int shortStart = shorts.position();
    final int floatStart = floats.position();
    assertEquals(shortCount * Short.BYTES, shortBytes);
    for (int index = 0; index < shortCount; index++) {
      final short expected = shorts.get(shortStart + index);
      final short actual = fromShorts.getShort(index * Short.BYTES);
      assertEquals(expected, actual, "short " + index);
    }
    assertEquals(floatCount * Float.BYTES, floatBytes);
    for (int index = 0; index < floatCount; index++) {
      final float expectedValue = floats.get(floatStart + index);
      final float actualValue = fromFloats.getFloat(index * Float.BYTES);
      final int expected = Float.floatToIntBits(expectedValue);
      final int actual = Float.floatToIntBits(actualValue);
      assertEquals(expected, actual, "float " + index);
    }
  }

  @Property(seed = SEED)
  void monoIsTheRoundedDownAverageOfTheLittleEndianChannels(@ForAll("buffers") final SampleBuffer sample) {
    final ByteBuffer stereo = sample.create();
    final byte[] content = sample.getContent();

    final short[] mono = MonoDownmixer.downmix(stereo);

    final int frames = content.length / 4;
    assertEquals(frames, mono.length, "one sample per whole stereo frame");
    for (int frame = 0; frame < frames; frame++) {
      final int left = littleEndianShort(content, frame * 4);
      final int right = littleEndianShort(content, frame * 4 + 2);
      final int expected = Math.floorDiv(left + right, 2);
      assertEquals(expected, mono[frame], "frame " + frame);
    }
    sample.assertUnchanged(stereo);
  }

  private static int littleEndianShort(final byte[] bytes, final int offset) {
    final int low = bytes[offset] & 0xFF;
    final int high = bytes[offset + 1];
    return (high << 8) | low;
  }

  /**
   * Asserts that the converted buffer holds the samples of the source read in the source's own order, and keeps a
   * trailing odd byte as it is.
   */
  private static void assertSameSamples(final SampleBuffer sample, final ByteOrder sourceOrder, final ByteBuffer converted) {
    final byte[] content = sample.getContent();
    final int sampleCount = content.length / 2;
    final int position = converted.position();
    final int remaining = converted.remaining();
    assertEquals(0, position, "the result starts at its beginning");
    assertEquals(content.length, remaining, "every byte is converted");
    final ByteBuffer expected = ByteBuffer.wrap(content);
    expected.order(sourceOrder);
    for (int index = 0; index < sampleCount; index++) {
      final short wanted = expected.getShort(index * 2);
      final short actual = converted.getShort(index * 2);
      assertEquals(wanted, actual, "sample " + index);
    }
    if (content.length % 2 == 1) {
      final byte last = content[content.length - 1];
      final byte convertedLast = converted.get(content.length - 1);
      assertEquals(last, convertedLast, "the odd trailing byte");
    }
  }

  /**
   * Asserts that changing the source afterwards does not change the result.
   */
  private static void assertIndependentCopy(final SampleBuffer sample, final ByteBuffer source, final ByteBuffer converted) {
    if (source.isReadOnly()) {
      return;
    }
    final byte[] before = new byte[converted.remaining()];
    final ByteBuffer view = converted.duplicate();
    view.get(before);
    final int start = source.position();
    for (int index = start; index < source.limit(); index++) {
      final byte current = source.get(index);
      source.put(index, (byte) ~current);
    }
    final byte[] after = new byte[converted.remaining()];
    final ByteBuffer again = converted.duplicate();
    again.get(after);
    assertArrayEquals(before, after, () -> "the result shares memory with the source: " + sample);
  }

  /**
   * Where the bytes of a buffer live.
   */
  enum Layout {
    HEAP,
    DIRECT,
    SLICED_HEAP,
    SLICED_DIRECT,
    READ_ONLY,
  }

  /**
   * Samples in a buffer of some layout, with bytes that are not samples before and after them.
   */
  static final class SampleBuffer {

    private final byte[] content;
    private final int before;
    private final int after;
    private final Layout layout;
    private final ByteOrder order;

    SampleBuffer(final List<Byte> content, final int before, final int after, final Layout layout, final ByteOrder order) {
      this.content = new byte[content.size()];
      for (int index = 0; index < this.content.length; index++) {
        this.content[index] = content.get(index);
      }
      this.before = before;
      this.after = after;
      this.layout = layout;
      this.order = order;
    }

    byte[] getContent() {
      return this.content.clone();
    }

    ByteBuffer create() {
      final int capacity = this.before + this.content.length + this.after;
      final boolean direct = this.layout == Layout.DIRECT || this.layout == Layout.SLICED_DIRECT;
      final ByteBuffer whole = direct ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
      for (int index = 0; index < capacity; index++) {
        whole.put(index, JUNK);
      }
      whole.put(this.before, this.content);
      whole.position(this.before);
      whole.limit(this.before + this.content.length);
      final ByteBuffer buffer =
        switch (this.layout) {
          case SLICED_HEAP, SLICED_DIRECT -> whole.slice();
          case READ_ONLY -> whole.asReadOnlyBuffer();
          default -> whole;
        };
      buffer.order(this.order);
      return buffer;
    }

    /**
     * Asserts that a conversion left the position, the limit, the order and the bytes of the buffer as they were.
     */
    void assertUnchanged(final ByteBuffer buffer) {
      final boolean sliced = this.layout == Layout.SLICED_HEAP || this.layout == Layout.SLICED_DIRECT;
      final int start = sliced ? 0 : this.before;
      final int position = buffer.position();
      final int limit = buffer.limit();
      final ByteOrder bufferOrder = buffer.order();
      assertEquals(start, position, "position");
      assertEquals(start + this.content.length, limit, "limit");
      assertSame(this.order, bufferOrder, "order");
      final byte[] left = new byte[this.content.length];
      final ByteBuffer view = buffer.duplicate();
      view.get(left);
      assertArrayEquals(this.content, left, "bytes");
    }

    @Override
    public String toString() {
      return this.content.length + " bytes, " + this.before + " before, " + this.after + " after, " + this.layout + ", " + this.order;
    }
  }
}
