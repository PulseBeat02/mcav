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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import org.junit.jupiter.api.Tag;

/**
 * Fuzzes the audio connection to QEMU's VNC server with whatever a server could send: the handshake, then messages.
 * A VNC server is local, but the guest can reach the loopback interface of the host through QEMU's user networking,
 * so the reader treats it as untrusted. Whatever the bytes, it either refuses them with an {@link IOException} or
 * returns messages whose samples are whole frames within the limit; nothing else escapes, and no allocation grows
 * with a claimed length.
 */
@Tag("fuzz")
final class QemuAudioProtocolFuzzTest {

  private static final int FRAME = 4;

  @FuzzTest(maxDuration = "30s")
  void readsTheHandshakeAndTheMessagesOrRefusesThem(final FuzzedDataProvider data) {
    final byte[] bytes = data.consumeRemainingAsBytes();
    final DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
    final DataOutputStream out = new DataOutputStream(OutputStream.nullOutputStream());
    try {
      QemuAudioProtocol.readVersion(in);
      QemuAudioProtocol.negotiateSecurity(in, out);
      QemuAudioProtocol.readSecurityResult(in);
      QemuAudioProtocol.readServerInit(in);
      while (true) {
        final QemuAudioProtocol.Message message = QemuAudioProtocol.readMessage(in, FRAME, byte[]::new);
        final int length = message.getLength();
        assertTrue(length >= 0 && length <= QemuAudioProtocol.MAX_AUDIO_BYTES && length % FRAME == 0, () -> "length " + length);
      }
    } catch (final IOException refused) {
      // malformed, cut short, or not expected: the connection ends
    }
  }
}
