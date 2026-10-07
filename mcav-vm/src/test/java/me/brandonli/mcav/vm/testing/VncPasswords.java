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
package me.brandonli.mcav.vm.testing;

import com.shinyhut.vernacular.client.VernacularConfig;
import com.shinyhut.vernacular.client.VncSession;
import com.shinyhut.vernacular.client.exceptions.VncException;
import com.shinyhut.vernacular.protocol.auth.VncAuthenticationHandler;
import com.shinyhut.vernacular.protocol.messages.ProtocolVersion;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Answers the challenge of VNC authentication the way Vernacular, the VNC client of mcav-vnc, answers it: an oracle
 * for the answer of the audio connection that shares none of its code.
 */
public final class VncPasswords {

  // RFB 3.8: the client sends the security type it chose before the challenge arrives
  private static final int CHOSEN_TYPE_BYTES = 1;
  private static final int RESULT_BYTES = 4;

  private VncPasswords() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Answers a challenge.
   *
   * @param challenge the 16 bytes the server sent
   * @param password  the password
   * @return the 16 bytes Vernacular sends back
   * @throws IOException if Vernacular fails
   */
  public static byte[] response(final byte[] challenge, final String password) throws IOException {
    final VernacularConfig config = new VernacularConfig();
    config.setPasswordSupplier(() -> password);
    final byte[] received = Arrays.copyOf(challenge, challenge.length + RESULT_BYTES);
    final ByteArrayInputStream in = new ByteArrayInputStream(received);
    final ByteArrayOutputStream sent = new ByteArrayOutputStream();
    final VncSession session = new VncSession(config, in, sent);
    session.setProtocolVersion(new ProtocolVersion(3, 8));
    try {
      new VncAuthenticationHandler().authenticate(session);
    } catch (final VncException exception) {
      throw new IOException(exception);
    }
    final byte[] bytes = sent.toByteArray();
    return Arrays.copyOfRange(bytes, CHOSEN_TYPE_BYTES, bytes.length);
  }
}
