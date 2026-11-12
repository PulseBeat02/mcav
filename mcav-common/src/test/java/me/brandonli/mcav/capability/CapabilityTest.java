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
package me.brandonli.mcav.capability;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class CapabilityTest {

  @Test
  void shouldListTheOptionalFeatures() {
    final Capability[] values = Capability.values();
    final Capability[] expected = { Capability.VLC, Capability.FFMPEG, Capability.YT_DLP, Capability.FACE_DETECTION };
    assertArrayEquals(expected, values);
  }

  @Test
  void shouldNameEveryCapabilityForMessages() {
    final String vlc = Capability.VLC.getDisplayName();
    final String ffmpeg = Capability.FFMPEG.getDisplayName();
    final String ytdlp = Capability.YT_DLP.getDisplayName();
    final String faceDetection = Capability.FACE_DETECTION.getDisplayName();
    assertEquals("VLC", vlc);
    assertEquals("FFmpeg", ffmpeg);
    assertEquals("yt-dlp", ytdlp);
    assertEquals("Face detection", faceDetection);
  }
}
