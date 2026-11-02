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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Provider;
import java.security.Security;
import java.util.List;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.vm.testing.VncPasswords;
import org.junit.jupiter.api.Test;

final class QemuAudioProtocolTest {

  private static final int FRAME = 4;

  private static final String PASSWORD = "Pa55word";

  private static final int NO_AUTHENTICATION = 1;
  private static final int VNC_AUTHENTICATION = 2;

  private static final int CHALLENGE_BYTES = 16;

  private static final int FRAMEBUFFER_SIZE_BYTES = 4;
  private static final int PIXEL_FORMAT_BYTES = 16;

  private static final int RECTANGLE_BYTES = 8;

  private static final int CUT_TEXT_PADDING_BYTES = 3;

  static byte[] bytes(final Writer writer) throws IOException {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final DataOutputStream output = new DataOutputStream(bytes);
    writer.write(output);

    return bytes.toByteArray();
  }

  private DataInputStream input(final byte[] bytes) {
    final ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
    return new DataInputStream(stream);
  }

  private QemuAudioProtocol.Message read(final Writer writer) throws IOException {
    final byte[] bytes = bytes(writer);
    final DataInputStream input = this.input(bytes);
    return QemuAudioProtocol.readMessage(input, FRAME, byte[]::new);
  }

  static void writeData(final DataOutputStream output, final byte[] samples) throws IOException {
    output.writeByte(QemuAudioProtocol.SERVER_QEMU);
    output.writeByte(QemuAudioProtocol.QEMU_AUDIO);
    output.writeShort(QemuAudioProtocol.AUDIO_DATA);
    output.writeInt(samples.length);
    output.write(samples);
  }

  static void writeAcknowledgement(final DataOutputStream output) throws IOException {
    output.writeByte(QemuAudioProtocol.SERVER_UPDATE);
    output.writeByte(0);
    output.writeShort(1);
    output.writeShort(0);
    output.writeShort(0);
    output.writeShort(640);
    output.writeShort(480);
    output.writeInt(QemuAudioProtocol.AUDIO_ENCODING);
  }

  @Test
  void shouldSpeakRfb38WithoutAuthenticationAndShareTheDisplay() throws IOException {
    QemuAudioProtocol.readVersion(this.input("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII)));
    QemuAudioProtocol.readVersion(this.input("RFB 004.001\n".getBytes(StandardCharsets.US_ASCII)));
    final byte[] expectedVersion = "RFB 003.008\n".getBytes(StandardCharsets.US_ASCII);
    final byte[] version = bytes(QemuAudioProtocol::writeVersion);
    assertArrayEquals(expectedVersion, version);

    final ByteArrayOutputStream chosen = new ByteArrayOutputStream();
    final boolean authenticates = QemuAudioProtocol.negotiateSecurity(
      this.input(new byte[] { 2, VNC_AUTHENTICATION, NO_AUTHENTICATION }),
      new DataOutputStream(chosen),
      ""
    );
    final byte[] expectedChoice = new byte[] { NO_AUTHENTICATION };
    final byte[] choice = chosen.toByteArray();
    assertFalse(authenticates, "without a password there is no challenge to answer");
    assertArrayEquals(expectedChoice, choice);

    final DataInputStream offers = this.input(new byte[] { 3, NO_AUTHENTICATION, VNC_AUTHENTICATION, 16, 42 });
    QemuAudioProtocol.negotiateSecurity(offers, new DataOutputStream(new ByteArrayOutputStream()), "");
    final int byteAfterOffers = offers.readUnsignedByte();
    assertEquals(42, byteAfterOffers, "the stream stays in step");

    QemuAudioProtocol.readSecurityResult(this.input(new byte[] { 0, 0, 0, 0 }));
    final byte[] expectedClientInitialization = new byte[] { 1 };
    final byte[] clientInitialization = bytes(QemuAudioProtocol::writeClientInitialization);
    assertArrayEquals(expectedClientInitialization, clientInitialization);

    final byte[] serverInitialization = bytes(output -> {
      output.writeShort(720);
      output.writeShort(400);
      output.write(new byte[PIXEL_FORMAT_BYTES]);
      output.writeInt(4);
      output.write("QEMU".getBytes(StandardCharsets.US_ASCII));
      output.writeByte(42);
    });
    final DataInputStream initialization = this.input(serverInitialization);
    QemuAudioProtocol.readServerInitialization(initialization);
    final int byteAfterDescription = initialization.readUnsignedByte();
    assertEquals(42, byteAfterDescription, "the whole description was read");
  }

  @Test
  void shouldAnswerThePasswordChallengeOfADisplayWithAPassword() throws IOException {
    final ByteArrayOutputStream chosen = new ByteArrayOutputStream();
    final boolean authenticates = QemuAudioProtocol.negotiateSecurity(
      this.input(new byte[] { 1, VNC_AUTHENTICATION }),
      new DataOutputStream(chosen),
      PASSWORD
    );
    final byte[] expectedChoice = new byte[] { VNC_AUTHENTICATION };
    final byte[] choice = chosen.toByteArray();
    assertTrue(authenticates);
    assertArrayEquals(expectedChoice, choice);

    final ByteArrayOutputStream preferred = new ByteArrayOutputStream();
    final boolean preferredAuthenticates = QemuAudioProtocol.negotiateSecurity(
      this.input(new byte[] { 2, NO_AUTHENTICATION, VNC_AUTHENTICATION }),
      new DataOutputStream(preferred),
      PASSWORD
    );
    final byte[] expectedPreferredChoice = new byte[] { VNC_AUTHENTICATION };
    final byte[] preferredChoice = preferred.toByteArray();
    assertTrue(preferredAuthenticates);
    assertArrayEquals(expectedPreferredChoice, preferredChoice);

    final ByteArrayOutputStream open = new ByteArrayOutputStream();
    final boolean openAuthenticates = QemuAudioProtocol.negotiateSecurity(
      this.input(new byte[] { 1, NO_AUTHENTICATION }),
      new DataOutputStream(open),
      PASSWORD
    );
    final byte[] expectedOpenChoice = new byte[] { NO_AUTHENTICATION };
    final byte[] openChoice = open.toByteArray();
    assertFalse(openAuthenticates);
    assertArrayEquals(expectedOpenChoice, openChoice);

    final byte[] challenge = new byte[CHALLENGE_BYTES];
    for (int index = 0; index < challenge.length; index++) {
      challenge[index] = (byte) (index * 37 + 11);
    }

    for (final String password : List.of(PASSWORD, "pw", "a much longer password")) {
      final ByteArrayOutputStream answer = new ByteArrayOutputStream();
      QemuAudioProtocol.answerChallenge(this.input(challenge), new DataOutputStream(answer), password);
      final byte[] expected = VncPasswords.computeResponse(challenge, password);
      final byte[] response = answer.toByteArray();
      assertArrayEquals(expected, response, password);
    }
  }

  @Test
  void shouldFailThePasswordChallengeOnARuntimeWithoutDes() throws IOException {
    final Provider[] providers = Security.getProviders();
    for (final Provider provider : providers) {
      Security.removeProvider(provider.getName());
    }

    try {
      final IOException failure = assertThrows(IOException.class, () -> QemuAudioProtocol.respond(new byte[CHALLENGE_BYTES], PASSWORD));
      final Throwable cause = failure.getCause();
      assertInstanceOf(GeneralSecurityException.class, cause);
    } finally {
      for (final Provider provider : providers) {
        Security.addProvider(provider);
      }
    }

    final int responseLength = QemuAudioProtocol.respond(new byte[CHALLENGE_BYTES], PASSWORD).length;
    assertEquals(CHALLENGE_BYTES, responseLength, "the providers are back");
  }

  @Test
  void shouldAskForAudioOnlyInTheFormatOfThePipeline() throws IOException {
    final byte[] encodings = bytes(QemuAudioProtocol::writeSetEncodings);
    final byte[] expectedEncodings = new byte[] { 2, 0, 0, 1, (byte) 0xFF, (byte) 0xFF, (byte) 0xFE, (byte) 0xFD };
    assertArrayEquals(expectedEncodings, encodings);

    final byte[] enable = bytes(output -> QemuAudioProtocol.writeEnableAudio(output, AudioFilter.CHANNELS, AudioFilter.SAMPLE_RATE));
    final byte[] expected = { (byte) 255, 1, 0, 2, 3, 2, 0, 0, (byte) 0xBB, (byte) 0x80, (byte) 255, 1, 0, 0 };
    assertArrayEquals(expected, enable);
  }

  @Test
  void shouldRefuseAServerThatIsNotRfb38() {
    assertThrows(ProtocolException.class, () ->
      QemuAudioProtocol.readVersion(this.input("HTTP/1.1 200".getBytes(StandardCharsets.US_ASCII)))
    );

    final ProtocolException old = assertThrows(ProtocolException.class, () ->
      QemuAudioProtocol.readVersion(this.input("RFB 003.007\n".getBytes(StandardCharsets.US_ASCII)))
    );
    final String message = old.getMessage();
    assertEquals("VNC server speaks RFB 3.7 instead of RFB 3.8 or later!", message);
    assertThrows(ProtocolException.class, () ->
      QemuAudioProtocol.readVersion(this.input("RFB 002.009\n".getBytes(StandardCharsets.US_ASCII)))
    );
  }

  @Test
  void shouldEndTheHandshakeWhenTheServerRefusesOrAsksForAPassword() throws IOException {
    final byte[] refusal = bytes(output -> {
      output.writeByte(0);
      output.writeInt(4);
      output.write("full".getBytes(StandardCharsets.US_ASCII));
    });
    final ProtocolException refused = assertThrows(ProtocolException.class, () ->
      QemuAudioProtocol.negotiateSecurity(this.input(refusal), new DataOutputStream(new ByteArrayOutputStream()), "")
    );
    final String refusedMessage = refused.getMessage();
    assertEquals("Because of \"full\", VNC server refused the connection!", refusedMessage);

    final ProtocolException password = assertThrows(ProtocolException.class, () ->
      QemuAudioProtocol.negotiateSecurity(
        this.input(new byte[] { 1, VNC_AUTHENTICATION }),
        new DataOutputStream(new ByteArrayOutputStream()),
        ""
      )
    );
    final String passwordMessage = password.getMessage();
    assertEquals("VNC server asks for authentication, which the audio connection does not do!", passwordMessage);

    final byte[] failed = bytes(output -> {
      output.writeInt(1);
      output.writeInt(2);
      output.write("no".getBytes(StandardCharsets.US_ASCII));
    });
    assertThrows(ProtocolException.class, () -> QemuAudioProtocol.readSecurityResult(this.input(failed)));

    final byte[] huge = bytes(output -> {
      output.writeInt(1);
      output.writeInt(-1);
    });
    final ProtocolException tooLong = assertThrows(ProtocolException.class, () -> QemuAudioProtocol.readSecurityResult(this.input(huge)));
    final String tooLongMessage = tooLong.getMessage();
    assertEquals("VNC server sent a text of 4294967295 bytes, more than the 4096 bytes allowed!", tooLongMessage);
  }

  @Test
  void shouldReadTheMessagesOfTheAudioConnection() throws IOException {
    final QemuAudioProtocol.Message acknowledgement = this.read(QemuAudioProtocolTest::writeAcknowledgement);
    final QemuAudioProtocol.Message ignored = this.read(output -> output.write(new byte[] { 0, 0, 0, 0 }));
    final QemuAudioProtocol.Kind ignoredKind = ignored.getKind();
    final QemuAudioProtocol.Message begin = this.read(output -> output.write(new byte[] { (byte) 255, 1, 0, 1 }));
    final QemuAudioProtocol.Message end = this.read(output -> output.write(new byte[] { (byte) 255, 1, 0, 0 }));
    assertSame(QemuAudioProtocol.Message.ACKNOWLEDGED, acknowledgement);
    assertSame(QemuAudioProtocol.Kind.IGNORED, ignoredKind);
    assertSame(QemuAudioProtocol.Message.BEGIN, begin);
    assertSame(QemuAudioProtocol.Message.END, end);

    final QemuAudioProtocol.Message data = this.read(output -> writeData(output, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }));
    final QemuAudioProtocol.Kind dataKind = data.getKind();
    final int dataLength = data.getLength();
    final byte lastSample = data.getSamples()[7];
    final int beginLength = QemuAudioProtocol.Message.BEGIN.getLength();
    assertEquals(QemuAudioProtocol.Kind.DATA, dataKind);
    assertEquals(8, dataLength);
    assertEquals(8, lastSample);
    assertEquals(0, beginLength);
  }

  @Test
  void shouldSkipMessagesAVncServerSendsUnasked() throws IOException {
    final byte[] stream = bytes(output -> {
      output.writeByte(QemuAudioProtocol.SERVER_BELL);
      output.writeByte(QemuAudioProtocol.SERVER_COLOR_MAP);
      output.writeByte(0);
      output.writeShort(0);
      output.writeShort(2);
      output.write(new byte[12]);
      output.writeByte(QemuAudioProtocol.SERVER_CUT_TEXT);
      output.write(new byte[CUT_TEXT_PADDING_BYTES]);
      output.writeInt(5);
      output.write("hello".getBytes(StandardCharsets.US_ASCII));
      writeData(output, new byte[] { 9, 9, 9, 9 });
    });
    final DataInputStream input = this.input(stream);
    final QemuAudioProtocol.Message bell = QemuAudioProtocol.readMessage(input, FRAME, byte[]::new);
    final QemuAudioProtocol.Message colorMap = QemuAudioProtocol.readMessage(input, FRAME, byte[]::new);
    final QemuAudioProtocol.Message cutText = QemuAudioProtocol.readMessage(input, FRAME, byte[]::new);
    final QemuAudioProtocol.Message audio = QemuAudioProtocol.readMessage(input, FRAME, byte[]::new);
    final int audioLength = audio.getLength();
    assertSame(QemuAudioProtocol.Message.IGNORED, bell);
    assertSame(QemuAudioProtocol.Message.IGNORED, colorMap);
    assertSame(QemuAudioProtocol.Message.IGNORED, cutText);
    assertEquals(4, audioLength);
  }

  @Test
  void shouldEndTheConnectionOnAMessageItNeverAskedFor() {
    final ProtocolException type = assertThrows(ProtocolException.class, () -> this.read(output -> output.writeByte(7)));
    final String typeMessage = type.getMessage();
    assertEquals("VNC server sent message type 7, which the audio connection does not expect!", typeMessage);

    final ProtocolException picture = assertThrows(ProtocolException.class, () ->
      this.read(output -> {
        output.writeByte(0);
        output.writeByte(0);
        output.writeShort(1);
        output.write(new byte[RECTANGLE_BYTES]);
        output.writeInt(0);
      })
    );
    final String pictureMessage = picture.getMessage();
    assertEquals("VNC server sent a rectangle of encoding 0, which the audio connection never asks for!", pictureMessage);

    final ProtocolException many = assertThrows(ProtocolException.class, () ->
      this.read(output -> {
        output.writeByte(0);
        output.writeByte(0);
        output.writeShort(QemuAudioProtocol.MAXIMUM_RECTANGLES + 1);
      })
    );
    final String manyMessage = many.getMessage();
    assertEquals("VNC server announced 17 rectangles, more than the 16 allowed!", manyMessage);
    assertThrows(ProtocolException.class, () -> this.read(output -> output.write(new byte[] { (byte) 255, 2, 0, 0 })));
    assertThrows(ProtocolException.class, () -> this.read(output -> output.write(new byte[] { (byte) 255, 1, 0, 9 })));
  }

  @Test
  void shouldAllowTheLimitsThemselves() throws IOException {
    final QemuAudioProtocol.Message sixteen = this.read(output -> {
      output.writeByte(QemuAudioProtocol.SERVER_UPDATE);
      output.writeByte(0);
      output.writeShort(QemuAudioProtocol.MAXIMUM_RECTANGLES);
      for (int index = 0; index < QemuAudioProtocol.MAXIMUM_RECTANGLES; index++) {
        output.write(new byte[RECTANGLE_BYTES]);
        output.writeInt(QemuAudioProtocol.AUDIO_ENCODING);
      }
    });
    final QemuAudioProtocol.Kind sixteenKind = sixteen.getKind();
    assertEquals(QemuAudioProtocol.Kind.ACKNOWLEDGED, sixteenKind);

    final QemuAudioProtocol.Message clipboard = this.read(output -> {
      output.writeByte(QemuAudioProtocol.SERVER_CUT_TEXT);
      output.write(new byte[CUT_TEXT_PADDING_BYTES]);
      output.writeInt(QemuAudioProtocol.MAXIMUM_CUT_TEXT_BYTES);
      output.write(new byte[QemuAudioProtocol.MAXIMUM_CUT_TEXT_BYTES]);
    });
    final QemuAudioProtocol.Kind clipboardKind = clipboard.getKind();
    assertEquals(QemuAudioProtocol.Kind.IGNORED, clipboardKind);

    final QemuAudioProtocol.Message largest = this.read(output -> writeData(output, new byte[QemuAudioProtocol.MAXIMUM_AUDIO_BYTES]));
    final int largestLength = largest.getLength();
    assertEquals(QemuAudioProtocol.MAXIMUM_AUDIO_BYTES, largestLength);

    final byte[] longestName = bytes(output -> {
      output.write(new byte[FRAMEBUFFER_SIZE_BYTES + PIXEL_FORMAT_BYTES]);
      output.writeInt(QemuAudioProtocol.MAXIMUM_TEXT_BYTES);
      output.write(new byte[QemuAudioProtocol.MAXIMUM_TEXT_BYTES]);
    });
    QemuAudioProtocol.readServerInitialization(this.input(longestName));
  }

  @Test
  void shouldBoundAudioAndClipboardLengths() {
    final ProtocolException audio = assertThrows(ProtocolException.class, () ->
      this.read(output -> {
        output.write(new byte[] { (byte) 255, 1, 0, 2 });
        output.writeInt(QemuAudioProtocol.MAXIMUM_AUDIO_BYTES + FRAME);
      })
    );
    final String audioMessage = audio.getMessage();
    assertEquals("VNC server sent 65540 bytes of audio, not whole frames of at most 65536 bytes!", audioMessage);
    assertThrows(ProtocolException.class, () ->
      this.read(output -> {
        output.write(new byte[] { (byte) 255, 1, 0, 2 });
        output.writeInt(3);
      })
    );

    final ProtocolException clipboard = assertThrows(ProtocolException.class, () ->
      this.read(output -> {
        output.writeByte(QemuAudioProtocol.SERVER_CUT_TEXT);
        output.write(new byte[CUT_TEXT_PADDING_BYTES]);
        output.writeInt(-1);
      })
    );
    final String clipboardMessage = clipboard.getMessage();
    assertEquals("VNC server sent 4294967295 bytes of clipboard text, more than the 1048576 bytes allowed!", clipboardMessage);
    assertThrows(EOFException.class, () ->
      this.read(output -> {
        output.write(new byte[] { (byte) 255, 1, 0, 2 });
        output.writeInt(8);
        output.write(new byte[3]);
      })
    );
  }

  @Test
  void shouldNotBeInstantiable() throws ReflectiveOperationException {
    final Constructor<QemuAudioProtocol> constructor = QemuAudioProtocol.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    final InvocationTargetException failure = assertThrows(InvocationTargetException.class, constructor::newInstance);
    final Throwable cause = failure.getCause();
    final Class<? extends Throwable> causeType = cause.getClass();
    assertEquals(UnsupportedOperationException.class, causeType);
  }

  @FunctionalInterface
  interface Writer {
    void write(final DataOutputStream output) throws IOException;
  }
}
