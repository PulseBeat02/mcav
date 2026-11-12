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
package me.brandonli.mcav.plugin.command.video;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder;
import me.brandonli.mcav.plugin.MCAVSandbox;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class Mcv2StreamFileFuzzTest {

  private static final String TEMPORARY_FILE_PREFIX = "mcav-stream-";
  private static final String TEMPORARY_FILE_SUFFIX = ".mcs";

  private static final Pattern REFUSAL = Pattern.compile(
    "Truncated frame length!|Truncated frame!|Empty stream!|MCV2 frame length \\d+ is invalid!"
  );

  private Mcv2PlayCommand command;

  @BeforeEach
  void createCommand() {
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    this.command = new Mcv2PlayCommand(plugin);
  }

  @FuzzTest(maxDuration = "30s")
  void shouldSplitOrRefuseEveryStreamFile(final byte[] data) throws IOException {
    final Path file = Files.createTempFile(TEMPORARY_FILE_PREFIX, TEMPORARY_FILE_SUFFIX);
    try {
      Files.write(file, data);
      this.assertSplitOrRefused(file, data);
    } finally {
      Files.delete(file);
    }
  }

  private void assertSplitOrRefused(final Path file, final byte[] data) {
    final List<byte[]> frames;
    try {
      frames = this.command.read(file);
    } catch (final IOException exception) {
      final @Nullable String detail = exception.getMessage();
      final String message = String.valueOf(detail);
      final Matcher refusal = REFUSAL.matcher(message);
      final boolean refused = refusal.matches();
      assertTrue(refused, message);

      return;
    }

    final boolean empty = frames.isEmpty();
    assertFalse(empty);

    for (final byte[] frame : frames) {
      this.assertFrameLength(frame);
    }

    final byte[] records = this.writeRecords(frames);
    assertArrayEquals(data, records);
  }

  private void assertFrameLength(final byte[] frame) {
    final boolean fits = frame.length >= Mcv2Decoder.HEADER_BYTES && frame.length <= Mcv2Decoder.MAXIMUM_FRAME_BYTES;
    assertTrue(fits);
  }

  private byte[] writeRecords(final List<byte[]> frames) {
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    for (final byte[] frame : frames) {
      final byte[] length = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(frame.length).array();
      output.writeBytes(length);
      output.writeBytes(frame);
    }

    return output.toByteArray();
  }
}
