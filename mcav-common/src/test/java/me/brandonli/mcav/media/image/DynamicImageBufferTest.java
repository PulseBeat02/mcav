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
package me.brandonli.mcav.media.image;

import static me.brandonli.mcav.media.ResourceAssertions.assertThrowsWhileOpening;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.testing.Images;
import me.brandonli.mcav.testing.LocalHttpServer;
import me.brandonli.mcav.testing.TestMedia;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link DynamicImageBuffer} and {@link DynamicImageBufferImpl}.
 */
final class DynamicImageBufferTest {

  private static final byte[] ANIMATION = Images.animatedGif(8, 4, 4, 0xFF0000, 0x00FF00, 0x0000FF);

  @TempDir
  private Path directory;

  private static int firstPixel(final ImageBuffer frame) {
    final int[] pixels = frame.getPixels();
    return pixels[0];
  }

  private Path writeAnimation() throws IOException {
    final Path gif = this.directory.resolve("animation.gif");
    Files.write(gif, ANIMATION);
    return gif;
  }

  /**
   * Checks the number of frames, that the frame list and the frame accessor agree, and the color of every frame.
   */
  private static void assertEveryFrameHasItsColor(final DynamicImageBuffer animation) {
    final int count = animation.getFrameCount();
    final List<ImageBuffer> frames = animation.getFrames();
    final ImageBuffer first = frames.get(0);
    final ImageBuffer second = animation.getFrame(1);
    final ImageBuffer third = frames.get(2);
    final ImageBuffer listedSecond = frames.get(1);
    final int firstColor = firstPixel(first);
    final int secondColor = firstPixel(second);
    final int thirdColor = firstPixel(third);
    assertEquals(3, count);
    assertSame(listedSecond, second);
    assertEquals(0xFFFF0000, firstColor);
    assertEquals(0xFF00FF00, secondColor);
    assertEquals(0xFF0000FF, thirdColor);
  }

  @Test
  void decodesEveryFrameOfANarrowAnimation() throws IOException {
    final Path gif = this.writeAnimation();
    final FileSource source = FileSource.path(gif);
    try (final DynamicImageBuffer animation = DynamicImageBuffer.path(source)) {
      assertEveryFrameHasItsColor(animation);
      final List<ImageBuffer> frames = animation.getFrames();
      final ImageBuffer second = animation.getFrame(1);
      final int[] secondPixels = second.getPixels();
      final int lastPixel = secondPixels[31];
      final float frameRate = animation.getFrameRate();
      final int width = second.getWidth();
      final int height = second.getHeight();
      assertEquals(0xFF00FF00, lastPixel, "rows are not shifted by the row padding of the decoder");
      assertEquals(25.0f, frameRate, 0.01f, "every frame is shown for four hundredths of a second");
      assertEquals(8, width);
      assertEquals(4, height);
      assertThrows(UnsupportedOperationException.class, () -> frames.add(second));
      assertThrows(IndexOutOfBoundsException.class, () -> animation.getFrame(3));
      assertThrows(IndexOutOfBoundsException.class, () -> animation.getFrame(-1));
    }
  }

  @Test
  void releasesItsFramesWhenClosed() throws IOException {
    final Path gif = this.writeAnimation();
    final FileSource source = FileSource.path(gif);
    final DynamicImageBuffer animation = DynamicImageBuffer.path(source);
    final ImageBuffer frame = animation.getFrame(0);
    animation.close();
    assertThrows(IllegalStateException.class, frame::getWidth);
  }

  @Test
  void downloadsAnimationsFromUris() throws IOException {
    final String previousHome = System.getProperty("user.home");
    final String temporaryHome = this.directory.toString();
    System.setProperty("user.home", temporaryHome);
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond("/animation.gif", 200, ANIMATION);
      final URI uri = server.uri("/animation.gif");
      final UriSource source = UriSource.uri(uri);
      try (final DynamicImageBuffer animation = DynamicImageBuffer.uri(source)) {
        final int count = animation.getFrameCount();
        assertEquals(3, count);
      }
    } finally {
      System.setProperty("user.home", previousHome);
    }
  }

  @Test
  void rejectsFilesWithoutImages() {
    final Path audio = TestMedia.audio(0.5);
    final FileSource audioSource = FileSource.path(audio);
    final Path missing = this.directory.resolve("missing.gif");
    final FileSource missingSource = FileSource.path(missing);
    assertThrowsWhileOpening(IllegalArgumentException.class, () -> DynamicImageBuffer.path(audioSource));
    assertThrowsWhileOpening(IOException.class, () -> DynamicImageBuffer.path(missingSource));
    assertThrowsWhileOpening(NullPointerException.class, () -> DynamicImageBuffer.path(null));
    assertThrowsWhileOpening(NullPointerException.class, () -> DynamicImageBuffer.uri(null));
  }

  @Test
  void prefersTheReportedFrameRate() {
    final float reported = DynamicImageBufferImpl.resolveFrameRate(25.0, 3, 0L, 1_000_000L);
    assertEquals(25.0f, reported);
  }

  @Test
  void derivesTheFrameRateFromTimestampsWhenNoneIsReported() {
    final float zero = DynamicImageBufferImpl.resolveFrameRate(0.0, 3, 0L, 2_000_000L);
    final float notANumber = DynamicImageBufferImpl.resolveFrameRate(Double.NaN, 5, 0L, 1_000_000L);
    final float infinite = DynamicImageBufferImpl.resolveFrameRate(Double.POSITIVE_INFINITY, 3, 0L, 1_000_000L);
    assertEquals(1.0f, zero);
    assertEquals(4.0f, notANumber);
    assertEquals(2.0f, infinite);
  }

  @Test
  void derivesTheFrameRateFromTheTimeBetweenTheFirstAndTheLastFrame() {
    final float lateStart = DynamicImageBufferImpl.resolveFrameRate(0.0, 3, 1_000_000L, 2_000_000L);
    final float sameTimestamps = DynamicImageBufferImpl.resolveFrameRate(0.0, 3, 1_000_000L, 1_000_000L);
    assertEquals(2.0f, lateStart, "two frame periods fit between one and two seconds, however late the first frame is");
    assertEquals(10.0f, sameTimestamps);
  }

  @Test
  void fallsBackToTheDefaultFrameRate() {
    final float singleFrame = DynamicImageBufferImpl.resolveFrameRate(-1.0, 1, 0L, 1_000_000L);
    final float noTimestamps = DynamicImageBufferImpl.resolveFrameRate(0.0, 3, 0L, 0L);
    assertEquals(10.0f, singleFrame);
    assertEquals(10.0f, noTimestamps);
    assertTrue(singleFrame > 0);
  }
}
