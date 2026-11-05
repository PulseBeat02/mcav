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

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;

final class QemuAudioProtocolPropertyTest {

  private static final String SEED = "20260925";
  private static final int FRAME = 4;

  @Provide
  Arbitrary<Sent> messages() {
    final Arbitrary<Sent> data = Arbitraries.integers()
      .between(0, 256)
      .flatMap(frames ->
        Arbitraries.bytes()
          .array(byte[].class)
          .ofSize(frames * FRAME)
      )
      .map(samples -> new Sent(QemuAudioProtocol.Kind.DATA, samples));
    final Arbitrary<Sent> others = Arbitraries.of(
      new Sent(QemuAudioProtocol.Kind.BEGIN, new byte[0]),
      new Sent(QemuAudioProtocol.Kind.END, new byte[0]),
      new Sent(QemuAudioProtocol.Kind.ACKNOWLEDGED, new byte[0]),
      new Sent(QemuAudioProtocol.Kind.IGNORED, new byte[0])
    );
    return Arbitraries.oneOf(data, others);
  }

  @Property(seed = SEED, tries = 300)
  void shouldReadEveryMessageAsSentHoweverTheStreamIsCut(
    @ForAll @Size(max = 20) final List<@From("messages") Sent> messages,
    @ForAll final long cuts
  ) throws IOException {
    final byte[] stream = QemuAudioProtocolTest.bytes(output -> this.writeMessages(output, messages));
    final DataInputStream input = new DataInputStream(new Trickle(stream, new Random(cuts)));
    for (final Sent expected : messages) {
      final QemuAudioProtocol.Message read = QemuAudioProtocol.readMessage(input, FRAME, byte[]::new);
      final QemuAudioProtocol.Kind expectedKind = expected.kind();
      final QemuAudioProtocol.Kind kind = read.getKind();
      final byte[] expectedSamples = expected.samples();
      final byte[] samples = Arrays.copyOf(read.getSamples(), read.getLength());
      assertEquals(expectedKind, kind);
      assertArrayEquals(expectedSamples, samples);
    }

    final int next = input.read();
    assertEquals(-1, next, "nothing is left over");
  }

  private void write(final DataOutputStream output, final Sent message) throws IOException {
    switch (message.kind()) {
      case DATA -> QemuAudioProtocolTest.writeData(output, message.samples());
      case BEGIN -> output.write(new byte[] { (byte) 255, 1, 0, 1 });
      case END -> output.write(new byte[] { (byte) 255, 1, 0, 0 });
      case ACKNOWLEDGED -> QemuAudioProtocolTest.writeAcknowledgement(output);
      case IGNORED -> {
        output.writeByte(QemuAudioProtocol.SERVER_CUT_TEXT);
        output.write(new byte[3]);
        output.writeInt(3);
        output.write(new byte[] { 'a', 'b', 'c' });
      }
    }
  }

  private void writeMessages(final DataOutputStream output, final List<Sent> messages) throws IOException {
    for (final Sent message : messages) {
      this.write(output, message);
    }
  }

  private static final class Sent {

    private final QemuAudioProtocol.Kind kind;
    private final byte[] samples;

    Sent(final QemuAudioProtocol.Kind kind, final byte[] samples) {
      this.kind = kind;
      this.samples = samples;
    }

    QemuAudioProtocol.Kind kind() {
      return this.kind;
    }

    byte[] samples() {
      return this.samples;
    }

    @Override
    public String toString() {
      return "%s(%s)".formatted(this.kind, this.samples.length);
    }
  }

  // hands out its bytes in pieces of random sizes, as a network connection does
  private static final class Trickle extends InputStream {

    private final ByteArrayInputStream bytes;
    private final Random random;

    Trickle(final byte[] data, final Random random) {
      this.bytes = new ByteArrayInputStream(data);
      this.random = random;
    }

    @Override
    public int read() {
      return this.bytes.read();
    }

    @Override
    public int read(final byte[] buffer, final int offset, final int length) {
      if (length == 0) {
        return 0;
      }

      final int piece = 1 + this.random.nextInt(Math.min(length, 7));
      return this.bytes.read(buffer, offset, piece);
    }
  }
}
