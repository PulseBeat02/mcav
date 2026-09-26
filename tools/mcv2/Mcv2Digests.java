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

  private Mcv2Digests() {}

  public static void main(final String[] args) throws Exception {
    final MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
    final HexFormat hex = HexFormat.of();
    for (final String argument : args) {
      final byte[] archive = Files.readAllBytes(Path.of(argument));
      final Mcv2Receiver receiver = new Mcv2Receiver();
      final StringBuilder line = new StringBuilder(argument);
      int offset = 0;
      while (offset < archive.length) {
        if (archive.length - offset < 4) {
          line.append(" truncated");
          break;
        }
        final long length =
          (archive[offset] & 0xFFL) | (archive[offset + 1] & 0xFFL) << 8 | (archive[offset + 2] & 0xFFL) << 16 | (archive[offset + 3] & 0xFFL) << 24;
        if (length > archive.length - offset - 4) {
          line.append(" truncated");
          break;
        }
        final byte[] frame = Arrays.copyOfRange(archive, offset + 4, offset + 4 + (int) length);
        String token;
        try {
          token = hex.formatHex(sha256.digest(receiver.accept(frame)));
        } catch (final UnsupportedSyntaxException unsupported) {
          token = "unsupported";
        } catch (final Mcv2Exception rejected) {
          token = "reject";
        }
        line.append(' ').append(token);
        offset += 4 + (int) length;
      }
      System.out.println(line);
    }
  }
}
