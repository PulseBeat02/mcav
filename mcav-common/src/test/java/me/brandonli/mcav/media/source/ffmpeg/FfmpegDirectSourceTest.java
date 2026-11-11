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
package me.brandonli.mcav.media.source.ffmpeg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Objects;
import me.brandonli.mcav.media.source.SourceDetector;
import me.brandonli.mcav.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class FfmpegDirectSourceTest {

  private static final String DESKTOP_MRL = "desktop";

  private static final String SCREEN_CAPTURE_FORMAT = "gdigrab";
  private static final String CAMERA_FORMAT = "dshow";

  @Test
  void shouldDescribeTheInput() {
    final FfmpegDirectSource source = FfmpegDirectSource.mrl(DESKTOP_MRL, SCREEN_CAPTURE_FORMAT);
    final String mrl = source.getMrl();
    final String format = source.getFormat();
    final String resource = source.getResource();
    final String name = source.getName();
    final boolean isStatic = source.isStatic();
    final boolean isDynamic = source.isDynamic();
    final String text = source.toString();
    assertEquals(DESKTOP_MRL, mrl);
    assertEquals(SCREEN_CAPTURE_FORMAT, format);
    assertEquals(DESKTOP_MRL, resource);
    assertEquals("ffmpeg", name);
    assertFalse(isStatic, "screen capture and the other raw inputs are live");
    assertTrue(isDynamic);
    assertEquals("DefaultFfmpegDirectSource{mrl=desktop, format=gdigrab}", text);
  }

  @Test
  void shouldFollowTheEqualityContract() {
    final FfmpegDirectSource source = FfmpegDirectSource.mrl(DESKTOP_MRL, SCREEN_CAPTURE_FORMAT);
    final FfmpegDirectSource equalSource = FfmpegDirectSource.mrl(DESKTOP_MRL, SCREEN_CAPTURE_FORMAT);
    final FfmpegDirectSource otherMrl = FfmpegDirectSource.mrl("title=Game", SCREEN_CAPTURE_FORMAT);
    final FfmpegDirectSource otherFormat = FfmpegDirectSource.mrl(DESKTOP_MRL, CAMERA_FORMAT);
    EqualityAssertions.assertEqualityContract(source, equalSource, otherMrl, otherFormat);
  }

  @Test
  void shouldHashTheMrlAndTheFormat() {
    final FfmpegDirectSource source = FfmpegDirectSource.mrl(DESKTOP_MRL, SCREEN_CAPTURE_FORMAT);

    final int hash = source.hashCode();
    final int expected = Objects.hash(DESKTOP_MRL, SCREEN_CAPTURE_FORMAT);
    assertEquals(expected, hash);
  }

  @Test
  void shouldRejectInvalidArguments() {
    assertThrows(IllegalArgumentException.class, () -> FfmpegDirectSource.mrl(DESKTOP_MRL, " "));
    assertThrows(NullPointerException.class, () -> FfmpegDirectSource.mrl(null, SCREEN_CAPTURE_FORMAT));
    assertThrows(NullPointerException.class, () -> FfmpegDirectSource.mrl(DESKTOP_MRL, null));
  }

  @Test
  void shouldDetectFormatAndInputPairs() {
    final FfmpegDirectSourceDetector detector = new FfmpegDirectSourceDetector();
    final boolean detected = detector.isDetectedSource("dshow||video=Camera");
    final FfmpegDirectSource created = detector.createSource("dshow||video=Camera");
    final FfmpegDirectSource expected = FfmpegDirectSource.mrl("video=Camera", CAMERA_FORMAT);
    final int priority = detector.getPriority();
    assertTrue(detected);
    assertEquals(expected, created);
    assertEquals(SourceDetector.HIGH_PRIORITY, priority);
  }

  @ParameterizedTest
  @ValueSource(strings = { "plain", "format||", "||input", " ||input", "a||b||c", "a||b||", "format|| " })
  void shouldIgnoreTextThatIsNotAPair(final String resource) {
    final FfmpegDirectSourceDetector detector = new FfmpegDirectSourceDetector();
    final boolean detected = detector.isDetectedSource(resource);
    assertFalse(detected);
  }

  @Test
  void shouldRejectInvalidInputInTheDetector() {
    final FfmpegDirectSourceDetector detector = new FfmpegDirectSourceDetector();
    assertThrows(IllegalArgumentException.class, () -> detector.createSource("no separator"));
    assertThrows(IllegalArgumentException.class, () -> detector.createSource("a||b||"));
    assertThrows(IllegalArgumentException.class, () -> detector.createSource("||input"));
    assertThrows(NullPointerException.class, () -> detector.isDetectedSource(null));
    assertThrows(NullPointerException.class, () -> detector.createSource(null));
  }
}
