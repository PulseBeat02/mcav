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
import me.brandonli.mcav.media.source.SourceDetector;
import me.brandonli.mcav.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link UriSource}, {@link UriSourceImpl} and {@link UriSourceDetector}.
 */
final class UriSourceTest {

  @Test
  void describesTheUri() {
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
    assertEquals("UriSource[https://example.com/clip.mp4]", text);
  }

  @Test
  void knowsWhetherTheUriPointsAtAMediaFile() {
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
  void followsTheEqualityContract() {
    final URI uri = URI.create("https://example.com/a.mp4");
    final URI otherUri = URI.create("https://example.com/b.mp4");
    final UriSource source = UriSource.uri(uri);
    final UriSource equalSource = UriSource.uri(uri);
    final UriSource otherSource = UriSource.uri(otherUri);
    EqualityAssertions.assertEqualityContract(source, equalSource, otherSource);
  }

  @Test
  void detectsUrisWithSchemeAndHost() {
    final UriSourceDetector detector = new UriSourceDetector();
    final boolean web = detector.isDetectedSource("rtsp://camera.local/stream");
    final boolean drive = detector.isDetectedSource("c:/videos/clip.mp4");
    final UriSource created = detector.createSource("https://example.com/a.mp4");
    final URI expectedUri = URI.create("https://example.com/a.mp4");
    final UriSource expected = UriSource.uri(expectedUri);
    final int priority = detector.getPriority();
    assertTrue(web);
    assertFalse(drive);
    assertEquals(expected, created);
    assertEquals(SourceDetector.NORMAL_PRIORITY, priority);
  }

  @Test
  void rejectsNullInput() {
    final UriSourceDetector detector = new UriSourceDetector();
    assertThrows(NullPointerException.class, () -> UriSource.uri(null));
    assertThrows(NullPointerException.class, () -> detector.isDetectedSource(null));
    assertThrows(NullPointerException.class, () -> detector.createSource(null));
  }
}
