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
package me.brandonli.mcav.media.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import me.brandonli.mcav.media.source.device.DeviceSource;
import me.brandonli.mcav.media.source.ffmpeg.FfmpegDirectSource;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.media.source.uri.UriSource;
import org.junit.jupiter.api.Tag;

// Fuzzes source detection: it never throws, and each detected source is what its detector promises.
@Tag("fuzz")
final class SourceDetectionFuzzTest {

  private final SourceDetectionHelper helper = new SourceDetectionHelper();

  @FuzzTest(maxDuration = "30s")
  void shouldDetectOnlySourcesTheTextReallyNames(final byte[] input) {
    final String resource = new String(input, StandardCharsets.UTF_8);
    final Optional<Source> detected = this.helper.detectSource(resource);
    if (detected.isEmpty()) {
      return;
    }

    final Source source = detected.get();
    switch (source) {
      case final DeviceSource device -> {
        final int index = device.getDeviceId();
        final boolean validIndex = index >= 0;
        assertTrue(validIndex, () -> "device %s from '%s'".formatted(index, resource));

        final int written = Integer.parseInt(resource);
        assertEquals(written, index, "a device is the number that was written");
      }
      case final FfmpegDirectSource direct -> {
        final String format = direct.getFormat();
        final String mrl = direct.getMrl();
        final boolean blank = format.isBlank() || mrl.isBlank();
        assertFalse(blank, () -> "a raw FFmpeg input without a format or an input from '%s'".formatted(resource));

        final String rebuilt = "%s||%s".formatted(format, mrl);
        assertEquals(resource, rebuilt, "a raw FFmpeg input is the format and the input that were written");
      }
      case final FileSource file -> {
        final Path path = file.getPath();
        final boolean exists = Files.exists(path);
        assertTrue(exists, () -> "a file that does not exist from '%s'".formatted(resource));

        final Path written = Path.of(resource);
        assertEquals(written, path, "a file is the path that was written");
      }
      case final UriSource uri -> {
        final URI address = uri.getUri();
        final boolean complete = address.getScheme() != null && address.getHost() != null;
        assertTrue(complete, () -> "a URL without a scheme or a host from '%s'".formatted(resource));

        final String text = address.toString();
        assertEquals(resource, text, "a URL is the text that was written");
      }
      default -> {
        final String message = "Source %s from '%s' is of an unknown kind!".formatted(source, resource);
        throw new AssertionError(message);
      }
    }
  }
}
