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
package me.brandonli.mcav.media.source.uri;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Objects;
import me.brandonli.mcav.media.source.SourceDetector;
import me.brandonli.mcav.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;

final class UriSourceTest {

  private static final String MEDIA_URL = "https://example.com/a.mp4";

  private static final String DRIVE_PATH = "c:/videos/clip.mp4";

  private static final String RELATIVE_PATH = "videos/clip.mp4";

  @Test
  void shouldDescribeTheUri() {
    final URI uri = URI.create("https://example.com/clip.mp4");
    final UriSource source = UriSource.uri(uri);
    final URI storedUri = source.getUri();
    final String resource = source.getResource();
    final String name = source.getName();
    final boolean isDynamic = source.isDynamic();
    final String text = source.toString();
    assertEquals(uri, storedUri);
    assertEquals("https://example.com/clip.mp4", resource);
    assertEquals("uri", name);
    assertTrue(isDynamic);
    assertEquals("DefaultUriSource{uri=https://example.com/clip.mp4, direct=true}", text);
  }

  @Test
  void shouldKeepAUriWithoutASchemeOrAHost() {
    final URI relative = URI.create(RELATIVE_PATH);
    final UriSource source = UriSource.uri(relative);
    final URI kept = source.getUri();
    final String resource = source.getResource();
    assertEquals(relative, kept);
    assertEquals(RELATIVE_PATH, resource);
  }

  @Test
  void shouldKnowWhetherTheUriPointsAtAMediaFile() {
    final URI file = URI.create("https://example.com/live/stream.m3u8");
    final URI page = URI.create("https://www.youtube.com/watch?v=abc");
    final UriSource fileSource = UriSource.uri(file);
    final UriSource pageSource = UriSource.uri(page);
    final boolean fileDirect = fileSource.isDirect();
    final boolean pageDirect = pageSource.isDirect();
    assertTrue(fileDirect);
    assertFalse(pageDirect);
  }

  @Test
  void shouldFollowTheEqualityContract() {
    final URI uri = URI.create(MEDIA_URL);
    final URI otherUri = URI.create("https://example.com/b.mp4");
    final UriSource source = UriSource.uri(uri);
    final UriSource equalSource = UriSource.uri(uri);
    final UriSource otherSource = UriSource.uri(otherUri);
    EqualityAssertions.assertEqualityContract(source, equalSource, otherSource);
  }

  @Test
  void shouldHashTheUri() {
    final URI uri = URI.create("https://example.com/video.mp4");
    final UriSource source = UriSource.uri(uri);

    final int hash = source.hashCode();
    final int expected = Objects.hash(uri);
    assertEquals(expected, hash);
  }

  @Test
  void shouldDetectUrisWithSchemeAndHost() {
    final UriSourceDetector detector = new UriSourceDetector();
    final boolean isWebUri = detector.isDetectedSource("rtsp://camera.local/stream");
    final boolean isDrivePath = detector.isDetectedSource(DRIVE_PATH);
    final UriSource created = detector.createSource(MEDIA_URL);
    final URI expectedUri = URI.create(MEDIA_URL);
    final UriSource expected = UriSource.uri(expectedUri);
    final int priority = detector.getPriority();
    assertTrue(isWebUri);
    assertFalse(isDrivePath);
    assertEquals(expected, created);
    assertEquals(SourceDetector.NORMAL_PRIORITY, priority);
  }

  @Test
  void shouldRejectTextThatIsNotAUriWhenCreatingTheSource() {
    final String text = "not a uri";
    final UriSourceDetector detector = new UriSourceDetector();
    final boolean detected = detector.isDetectedSource(text);
    assertFalse(detected);
    assertThrows(IllegalArgumentException.class, () -> detector.createSource(text));
  }

  @Test
  void shouldRejectNullInput() {
    final UriSourceDetector detector = new UriSourceDetector();
    assertThrows(NullPointerException.class, () -> UriSource.uri(null));
    assertThrows(NullPointerException.class, () -> detector.isDetectedSource(null));
    assertThrows(NullPointerException.class, () -> detector.createSource(null));
  }
}
