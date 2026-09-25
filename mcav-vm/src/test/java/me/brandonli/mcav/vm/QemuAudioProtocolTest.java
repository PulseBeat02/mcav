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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class QemuAudioProtocolTest {

  private static final int FRAME = 4;

  /**
   * Builds bytes with a writer.
   *
   * @param writer writes the bytes
   * @return the bytes
   * @throws IOException never
   */
  static byte[] bytes(final Writer writer) throws IOException {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    writer.write(new DataOutputStream(bytes));
    return bytes.toByteArray();
  }

  private static DataInputStream input(final byte[] bytes) {
    return new DataInputStream(new ByteArrayInputStream(bytes));
  }

  private static QemuAudioProtocol.Message read(final Writer writer) throws IOException {
    return QemuAudioProtocol.readMessage(input(bytes(writer)), FRAME, byte[]::new);
  }

  /**
   * Writes the audio data message of QEMU.
   *
   * @param out     the stream
   * @param samples the samples
   * @throws IOException never
   */
  static void writeData(final DataOutputStream out, final byte[] samples) throws IOException {
    out.writeByte(QemuAudioProtocol.SERVER_QEMU);
    out.writeByte(QemuAudioProtocol.QEMU_AUDIO);
    out.writeShort(QemuAudioProtocol.AUDIO_DATA);
    out.writeInt(samples.length);
    out.write(samples);
  }

  /**
   * Writes the confirmation of the audio pseudo-encoding.
   *
   * @param out the stream
   * @throws IOException never
   */
  static void writeAcknowledgement(final DataOutputStream out) throws IOException {
    out.writeByte(QemuAudioProtocol.SERVER_UPDATE);
    out.writeByte(0);
    out.writeShort(1);
    out.writeShort(0);
    out.writeShort(0);
    out.writeShort(640);
    out.writeShort(480);
    out.writeInt(QemuAudioProtocol.AUDIO_ENCODING);
  }

  @Test
  void theClientSpeaksRfb38WithoutAuthenticationAndSharesTheDisplay() throws IOException {
    QemuAudioProtocol.readVersion(input("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII)));
    QemuAudioProtocol.readVersion(input("RFB 004.001\n".getBytes(StandardCharsets.US_ASCII)));
    assertArrayEquals("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII), bytes(QemuAudioProtocol::writeVersion));
    final ByteArrayOutputStream chosen = new ByteArrayOutputStream();
    QemuAudioProtocol.negotiateSecurity(input(new byte[] { 2, 2, 1 }), new DataOutputStream(chosen));
    assertArrayEquals(new byte[] { 1 }, chosen.toByteArray());
    // a server that offers no authentication first and more after it: every type is read
    final DataInputStream offers = input(new byte[] { 3, 1, 2, 16, 42 });
    QemuAudioProtocol.negotiateSecurity(offers, new DataOutputStream(new ByteArrayOutputStream()));
    assertEquals(42, offers.readUnsignedByte(), "the stream stays in step");
    QemuAudioProtocol.readSecurityResult(input(new byte[] { 0, 0, 0, 0 }));
    assertArrayEquals(new byte[] { 1 }, bytes(QemuAudioProtocol::writeClientInit));
    final byte[] serverInit = bytes(out -> {
      out.writeShort(720);
      out.writeShort(400);
      out.write(new byte[16]);
      out.writeInt(4);
      out.write("QEMU".getBytes(StandardCharsets.US_ASCII));
      out.writeByte(42);
    });
    final DataInputStream init = input(serverInit);
    QemuAudioProtocol.readServerInit(init);
    assertEquals(42, init.readUnsignedByte(), "the whole description was read");
  }

  @Test
  void theClientAsksForAudioOnlyInTheFormatOfThePipeline() throws IOException {
    final byte[] encodings = bytes(QemuAudioProtocol::writeSetEncodings);
    assertArrayEquals(new byte[] { 2, 0, 0, 1, (byte) 0xFF, (byte) 0xFF, (byte) 0xFE, (byte) 0xFD }, encodings);
    final byte[] enable = bytes(out -> QemuAudioProtocol.writeEnableAudio(out, 2, 48_000));
    final byte[] expected = { (byte) 255, 1, 0, 2, 3, 2, 0, 0, (byte) 0xBB, (byte) 0x80, (byte) 255, 1, 0, 0 };
    assertArrayEquals(expected, enable);
  }

  @Test
  void aServerThatIsNotRfb38IsRefused() {
    assertThrows(ProtocolException.class, () -> QemuAudioProtocol.readVersion(input("HTTP/1.1 200".getBytes(StandardCharsets.US_ASCII))));
    final ProtocolException old = assertThrows(ProtocolException.class, () ->
      QemuAudioProtocol.readVersion(input("RFB 003.007\n".getBytes(StandardCharsets.US_ASCII)))
    );
    assertEquals("The VNC server speaks RFB 3.7 instead of 3.8", old.getMessage());
    assertThrows(ProtocolException.class, () -> QemuAudioProtocol.readVersion(input("RFB 002.009\n".getBytes(StandardCharsets.US_ASCII))));
  }

  @Test
  void aServerThatRefusesOrAsksForAPasswordEndsTheHandshake() throws IOException {
    final byte[] refusal = bytes(out -> {
      out.writeByte(0);
      out.writeInt(4);
      out.write("full".getBytes(StandardCharsets.US_ASCII));
    });
    final ProtocolException refused = assertThrows(ProtocolException.class, () ->
      QemuAudioProtocol.negotiateSecurity(input(refusal), new DataOutputStream(new ByteArrayOutputStream()))
    );
    assertEquals("The VNC server refused the connection: full", refused.getMessage());
    final ProtocolException password = assertThrows(ProtocolException.class, () ->
      QemuAudioProtocol.negotiateSecurity(input(new byte[] { 1, 2 }), new DataOutputStream(new ByteArrayOutputStream()))
    );
    assertEquals("The VNC server asks for authentication, which the audio connection does not do", password.getMessage());
    final byte[] failed = bytes(out -> {
      out.writeInt(1);
      out.writeInt(2);
      out.write("no".getBytes(StandardCharsets.US_ASCII));
    });
    assertThrows(ProtocolException.class, () -> QemuAudioProtocol.readSecurityResult(input(failed)));
    final byte[] huge = bytes(out -> {
      out.writeInt(1);
      out.writeInt(-1);
    });
    final ProtocolException tooLong = assertThrows(ProtocolException.class, () -> QemuAudioProtocol.readSecurityResult(input(huge)));
    assertEquals("The VNC server sent a text of 4294967295 bytes, more than 4096", tooLong.getMessage());
  }

  @Test
  void theMessagesOfTheAudioConnectionAreRead() throws IOException {
    assertSame(QemuAudioProtocol.Message.ACKNOWLEDGED, read(QemuAudioProtocolTest::writeAcknowledgement));
    assertSame(QemuAudioProtocol.Kind.IGNORED, read(out -> out.write(new byte[] { 0, 0, 0, 0 })).getKind());
    assertSame(QemuAudioProtocol.Message.BEGIN, read(out -> out.write(new byte[] { (byte) 255, 1, 0, 1 })));
    assertSame(QemuAudioProtocol.Message.END, read(out -> out.write(new byte[] { (byte) 255, 1, 0, 0 })));
    final QemuAudioProtocol.Message data = read(out -> writeData(out, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }));
    assertEquals(QemuAudioProtocol.Kind.DATA, data.getKind());
    assertEquals(8, data.getLength());
    assertEquals(8, data.getSamples()[7]);
    assertEquals(0, QemuAudioProtocol.Message.BEGIN.getLength());
  }

  @Test
  void messagesAVncServerSendsUnaskedAreSkipped() throws IOException {
    final byte[] stream = bytes(out -> {
      out.writeByte(QemuAudioProtocol.SERVER_BELL);
      out.writeByte(QemuAudioProtocol.SERVER_COLOUR_MAP);
      out.writeByte(0);
      out.writeShort(0);
      out.writeShort(2);
      out.write(new byte[12]);
      out.writeByte(QemuAudioProtocol.SERVER_CUT_TEXT);
      out.write(new byte[3]);
      out.writeInt(5);
      out.write("hello".getBytes(StandardCharsets.US_ASCII));
      writeData(out, new byte[] { 9, 9, 9, 9 });
    });
    final DataInputStream in = input(stream);
    assertSame(QemuAudioProtocol.Message.IGNORED, QemuAudioProtocol.readMessage(in, FRAME, byte[]::new));
    assertSame(QemuAudioProtocol.Message.IGNORED, QemuAudioProtocol.readMessage(in, FRAME, byte[]::new));
    assertSame(QemuAudioProtocol.Message.IGNORED, QemuAudioProtocol.readMessage(in, FRAME, byte[]::new));
    assertEquals(4, QemuAudioProtocol.readMessage(in, FRAME, byte[]::new).getLength());
  }

  @Test
  void aMessageTheAudioConnectionNeverAskedForEndsIt() {
    final ProtocolException type = assertThrows(ProtocolException.class, () -> read(out -> out.writeByte(7)));
    assertEquals("The VNC server sent message type 7, which the audio connection does not expect", type.getMessage());
    final ProtocolException picture = assertThrows(ProtocolException.class, () ->
      read(out -> {
        out.writeByte(0);
        out.writeByte(0);
        out.writeShort(1);
        out.write(new byte[8]);
        out.writeInt(0);
      })
    );
    assertEquals("The VNC server sent a rectangle of encoding 0, which the audio connection never asks for", picture.getMessage());
    final ProtocolException many = assertThrows(ProtocolException.class, () ->
      read(out -> {
        out.writeByte(0);
        out.writeByte(0);
        out.writeShort(17);
      })
    );
    assertEquals("The VNC server announced 17 rectangles, more than 16", many.getMessage());
    assertThrows(ProtocolException.class, () -> read(out -> out.write(new byte[] { (byte) 255, 2, 0, 0 })));
    assertThrows(ProtocolException.class, () -> read(out -> out.write(new byte[] { (byte) 255, 1, 0, 9 })));
  }

  @Test
  void theLimitsThemselvesAreAllowed() throws IOException {
    final QemuAudioProtocol.Message sixteen = read(out -> {
      out.writeByte(QemuAudioProtocol.SERVER_UPDATE);
      out.writeByte(0);
      out.writeShort(QemuAudioProtocol.MAX_RECTANGLES);
      for (int index = 0; index < QemuAudioProtocol.MAX_RECTANGLES; index++) {
        out.write(new byte[8]);
        out.writeInt(QemuAudioProtocol.AUDIO_ENCODING);
      }
    });
    assertEquals(QemuAudioProtocol.Kind.ACKNOWLEDGED, sixteen.getKind());
    final QemuAudioProtocol.Message clipboard = read(out -> {
      out.writeByte(QemuAudioProtocol.SERVER_CUT_TEXT);
      out.write(new byte[3]);
      out.writeInt(QemuAudioProtocol.MAX_CUT_TEXT_BYTES);
      out.write(new byte[QemuAudioProtocol.MAX_CUT_TEXT_BYTES]);
    });
    assertEquals(QemuAudioProtocol.Kind.IGNORED, clipboard.getKind());
  }

  @Test
  void audioAndClipboardLengthsAreBounded() {
    final ProtocolException audio = assertThrows(ProtocolException.class, () ->
      read(out -> {
        out.write(new byte[] { (byte) 255, 1, 0, 2 });
        out.writeInt(QemuAudioProtocol.MAX_AUDIO_BYTES + 4);
      })
    );
    assertEquals("The VNC server sent 65540 bytes of audio, not whole frames of at most 65536", audio.getMessage());
    assertThrows(ProtocolException.class, () ->
      read(out -> {
        out.write(new byte[] { (byte) 255, 1, 0, 2 });
        out.writeInt(3);
      })
    );
    final ProtocolException clipboard = assertThrows(ProtocolException.class, () ->
      read(out -> {
        out.writeByte(QemuAudioProtocol.SERVER_CUT_TEXT);
        out.write(new byte[3]);
        out.writeInt(-1);
      })
    );
    assertEquals("The VNC server sent 4294967295 bytes of clipboard text, more than 1048576", clipboard.getMessage());
    assertThrows(EOFException.class, () ->
      read(out -> {
        out.write(new byte[] { (byte) 255, 1, 0, 2 });
        out.writeInt(8);
        out.write(new byte[3]);
      })
    );
  }

  @Test
  void theProtocolIsNotInstantiable() throws ReflectiveOperationException {
    final java.lang.reflect.Constructor<QemuAudioProtocol> constructor = QemuAudioProtocol.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    final java.lang.reflect.InvocationTargetException failure = assertThrows(
      java.lang.reflect.InvocationTargetException.class,
      constructor::newInstance
    );
    assertEquals(UnsupportedOperationException.class, failure.getCause().getClass());
  }

  /**
   * Writes bytes.
   */
  @FunctionalInterface
  interface Writer {
    void write(DataOutputStream out) throws IOException;
  }
}
