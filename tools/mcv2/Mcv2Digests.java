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

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import me.brandonli.mcav.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.media.mcv2.Mcv2Receiver;
import me.brandonli.mcav.media.mcv2.UnsupportedSyntaxException;

/**
 * Decodes research archives (a little-endian u32 length before every frame) with mcav's MCV2 receiver, which commits
 * a frame only when it is valid and newer, like the reference decoder's {@code Decoder.accept}. For every archive it
 * prints one line: the archive's path, then one token per frame - the SHA-256 of the decoded RGB picture, {@code reject}
 * for a frame the receiver refuses (its state stays as it was), or {@code unsupported} for syntax mcav deliberately does
 * not implement - and {@code truncated} if the archive ends inside a frame. tools/mcv2/differential.py compares these
 * lines with the reference decoder's.
 *
 * <p>Run with a JDK (the launcher compiles this file): {@code java -cp <mcav-common classes>:<guava jar>
 * tools/mcv2/Mcv2Digests.java <archive>...}
 */
public final class Mcv2Digests {

  /** The bytes of the little-endian length before every frame of an archive. */
  private static final int LENGTH_BYTES = 4;

  private static final String TRUNCATED = "truncated";

  private static final String UNSUPPORTED = "unsupported";

  private static final String REJECTED = "reject";

  private Mcv2Digests() {}

  public static void main(final String[] args) throws Exception {
    final MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
    for (final String argument : args) {
      System.out.println(argument + digests(Files.readAllBytes(Path.of(argument)), sha256));
    }
  }

  /** One token per frame of an archive, each after a space, and a last one if the archive ends inside a frame. */
  private static String digests(final byte[] archive, final MessageDigest sha256) {
    final Mcv2Receiver receiver = new Mcv2Receiver();
    final StringBuilder line = new StringBuilder();
    int offset = 0;
    while (offset < archive.length) {
      final long length = archive.length - offset < LENGTH_BYTES ? -1 : length(archive, offset);
      if (length < 0 || length > archive.length - offset - LENGTH_BYTES) {
        line.append(' ').append(TRUNCATED);
        break;
      }
      final byte[] frame = Arrays.copyOfRange(archive, offset + LENGTH_BYTES, offset + LENGTH_BYTES + (int) length);
      line.append(' ').append(token(receiver, frame, sha256));
      offset += LENGTH_BYTES + (int) length;
    }
    return line.toString();
  }

  private static long length(final byte[] archive, final int offset) {
    long length = 0;
    for (int i = 0; i < LENGTH_BYTES; i++) {
      length |= (archive[offset + i] & 0xFFL) << (8 * i);
    }
    return length;
  }

  /** The SHA-256 of the picture the receiver decodes, or why it decodes none. */
  private static String token(final Mcv2Receiver receiver, final byte[] frame, final MessageDigest sha256) {
    try {
      return HexFormat.of().formatHex(sha256.digest(receiver.accept(frame)));
    } catch (final UnsupportedSyntaxException unsupported) {
      return UNSUPPORTED;
    } catch (final Mcv2Exception rejected) {
      return REJECTED;
    }
  }
}
