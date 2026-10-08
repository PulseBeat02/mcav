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
package me.brandonli.mcav.sandbox.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.imageio.ImageIO;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.BilateralFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.BlurFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.ColorMapFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.DilationFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.ErosionFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.FPSFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.FlipFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.GrayscaleFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.InvertFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.LuminanceFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.OverlayImageFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.RotationFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.TextFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.ThresholdFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.TintFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class FilterChainTest {

  private static final int OPAQUE = 0xff000000;

  @TempDir
  Path overlays;

  private FilterChain parse(final String text) {
    return FilterChain.parse(text, this.overlays, true);
  }

  private static ImageBuffer picture(final int width, final int height, final int rgb) {
    final int[] pixels = new int[width * height];
    Arrays.fill(pixels, OPAQUE | rgb);
    return ImageBuffer.buffer(pixels, width, height);
  }

  private void writeOverlay(final String name, final int width, final int height) throws IOException {
    final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    for (int row = 0; row < height; row++) {
      for (int column = 0; column < width; column++) {
        image.setRGB(column, row, 0xff0000);
      }
    }
    ImageIO.write(image, "png", this.overlays.resolve(name + ".png").toFile());
  }

  @Test
  void appliesNoFilterWithoutAChain() {
    final FilterChain none = this.parse("");
    assertSame(FilterChain.NONE, none);
    assertTrue(none.isEmpty());
    final VideoPipelineStep output = VideoPipelineStep.of(VideoFilter.NO_OP);
    assertSame(output, none.prepend(output));
    assertEquals(List.of(), none.create());
  }

  @Test
  void makesEveryFilterInTheOrderTyped() throws IOException {
    this.writeOverlay("logo", 2, 2);
    final FilterChain chain = this.parse(
      "grayscale,INVERT,blur=15,bilateral=9,threshold=128,luminance=1.5:-20,colormap=Turbo,tint=ff8000:30"
    );
    final List<VideoFilter> filters = chain.create();
    final List<Class<?>> kinds = List.of(
      GrayscaleFilter.class,
      InvertFilter.class,
      BlurFilter.class,
      BilateralFilter.class,
      ThresholdFilter.class,
      LuminanceFilter.class,
      ColorMapFilter.class,
      TintFilter.class
    );
    assertEquals(kinds, filters.stream().map(Object::getClass).toList());
    final List<VideoFilter> more = this.parse("tint=0000ff,flip=h,flip=v,flip=hv,rotate=90,rotate=180,rotate=270,dilate=1").create();
    assertEquals(
      List.of(
        TintFilter.class,
        FlipFilter.class,
        FlipFilter.class,
        FlipFilter.class,
        RotationFilter.class,
        RotationFilter.class,
        RotationFilter.class,
        DilationFilter.class
      ),
      more.stream().map(Object::getClass).toList()
    );
    final List<VideoFilter> rest = this.parse(
      "erode=15,text=Live (1)! ok?,fps,overlay=logo,crop=0:0:100:100,rectangle=10:10:20:20:00ff00"
    ).create();
    assertInstanceOf(ErosionFilter.class, rest.get(0));
    assertInstanceOf(TextFilter.class, rest.get(1));
    assertInstanceOf(FPSFilter.class, rest.get(2));
    assertInstanceOf(OverlayImageFilter.class, rest.get(3));
    assertEquals(6, rest.size());
    assertNotEquals(filters.getFirst(), chain.create().getFirst(), "every video gets filters of its own");
  }

  @Test
  void putsTheFiltersBeforeTheOutputInOrder() {
    final VideoPipelineStep output = VideoPipelineStep.of(VideoFilter.NO_OP);
    final VideoPipelineStep first = this.parse("grayscale,invert").prepend(output);
    assertInstanceOf(GrayscaleFilter.class, first.getFilter());
    final VideoPipelineStep second = Objects.requireNonNull(first.next());
    assertInstanceOf(InvertFilter.class, second.getFilter());
    assertSame(output, second.next());
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "sharpen",
      "grayscale=1",
      "invert,",
      ",invert",
      "blur",
      "blur=0",
      "blur=16",
      "blur=x",
      "blur=1:2",
      "bilateral=10",
      "threshold=256",
      "threshold=-1",
      "luminance=1",
      "luminance=3.1:0",
      "luminance=1:256",
      "luminance=-1:0",
      "luminance=1.0001:0",
      "colormap=plaid",
      "tint",
      "tint=ff800",
      "tint=ff8000:101",
      "tint=ff8000:1:2",
      "tint=gg8000",
      "flip=x",
      "rotate=45",
      "crop=0:0:0:10",
      "crop=100:0:1:1",
      "crop=50:0:51:10",
      "crop=0:50:10:51",
      "crop=0:0:10",
      "rectangle=0:0:10:10",
      "rectangle=0:0:10:10:red",
      "dilate=16",
      "erode=0",
      "text=",
      "text=a,b",
      "text=a;b",
      "text=é",
      "overlay=../x",
      "overlay=missing",
      "overlay=a.b",
      "blur=99999",
    }
  )
  void refusesWhatIsNotAnAllowedFilter(final String text) {
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> this.parse(text), text);
    assertTrue(!Objects.requireNonNull(refused.getMessage()).isBlank(), text);
  }

  @Test
  void aFilterIsNamedByWhatComesBeforeItsFirstEqualsSign() {
    final IllegalArgumentException unnamed = assertThrows(IllegalArgumentException.class, () -> this.parse("=invert"));
    assertTrue(Objects.requireNonNull(unnamed.getMessage()).startsWith("no filter ; "), unnamed.getMessage());
  }

  @Test
  void boundsTheChain() {
    assertEquals(
      FilterChain.MAX_FILTERS,
      this.parse(String.join(",", Collections.nCopies(FilterChain.MAX_FILTERS, "invert")))
        .create()
        .size()
    );
    assertThrows(IllegalArgumentException.class, () ->
      this.parse(String.join(",", Collections.nCopies(FilterChain.MAX_FILTERS + 1, "invert")))
    );
    assertThrows(IllegalArgumentException.class, () -> this.parse("text=" + "a".repeat(FilterChain.MAX_TEXT + 1)));
    this.parse("text=" + "a".repeat(FilterChain.MAX_TEXT));
    assertThrows(IllegalArgumentException.class, () -> this.parse("invert," + "x".repeat(FilterChain.MAX_LENGTH)));
    assertThrows(IllegalArgumentException.class, () -> FilterChain.parse("fps", this.overlays, false), "an image has no frame rate");
    assertThrows(NullPointerException.class, () -> FilterChain.parse(null, this.overlays, true));
    assertThrows(NullPointerException.class, () -> FilterChain.parse("", null, true));
  }

  @ParameterizedTest
  @ValueSource(strings = { "bilateral", "colormap", "dilate", "erode", "flip", "overlay", "rotate", "text", "threshold" })
  void refusesASecondArgumentToAFilterThatTakesOne(final String name) {
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> this.parse(name + "=1:1"));
    assertEquals(name + " takes 1 argument", refused.getMessage());
  }

  @Test
  void countsTheArgumentsOfAFilterThatTakesSeveral() {
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> this.parse("crop=1:1:1"));
    assertEquals("crop takes 4 arguments", refused.getMessage());
  }

  @Test
  void takesTheLongestChainAndTheLargestOverlayAllowed() throws IOException {
    final String longest = String.join(",", Collections.nCopies(6, "text=" + "a".repeat(FilterChain.MAX_TEXT))) + ",text=" + "a".repeat(23);
    assertEquals(FilterChain.MAX_LENGTH, longest.length());
    assertEquals(7, this.parse(longest).create().size());
    Files.write(this.overlays.resolve("largest.png"), new byte[(int) FilterChain.MAX_OVERLAY_BYTES]);
    assertTrue(!this.parse("overlay=largest").isEmpty());
    assertTrue(FilterChain.NONE.isEmpty());
    assertTrue(!this.parse("grayscale").isEmpty());
  }

  @Test
  void appliesTheExactValuesTyped() {
    final ImageBuffer brighter = picture(2, 2, 0x102030);
    this.parse("luminance=3:0").apply(brighter);
    assertEquals(0x306090, brighter.getPixels()[0] & 0xffffff, "the largest contrast");
    final ImageBuffer outlined = picture(4, 4, 0);
    this.parse("rectangle=0:0:100:100:123456").apply(outlined);
    assertEquals(0x123456, outlined.getPixels()[0] & 0xffffff);
    final int[] dot = new int[5 * 5];
    Arrays.fill(dot, OPAQUE);
    dot[2 + 5 * 2] = OPAQUE | 0xffffff;
    final ImageBuffer blurred = ImageBuffer.buffer(dot, 5, 5);
    this.parse("blur=1").apply(blurred);
    assertTrue((blurred.getPixels()[1 + 5 * 2] & 0xff) > 0, "a blur of radius 1 reaches the next pixel");
  }

  private static ImageBuffer quadrantPicture() {
    final int[] pixels = new int[100];
    final int[] colors = { 0x123456, 0x789ABC, 0xFEDCBA, 0xABCDEF };
    for (int row = 0; row < 10; row++) {
      for (int column = 0; column < 10; column++) {
        pixels[row * 10 + column] = OPAQUE | colors[(row / 5) * 2 + column / 5];
      }
    }
    return ImageBuffer.buffer(pixels, 10, 10);
  }

  @Test
  void placesARegionAtThePercentsTyped() {
    final VideoFilter inside = this.parse("rectangle=25:50:50:25:ffffff").create().getFirst();
    final ImageBuffer framed = picture(40, 40, 0);
    assertTrue(inside.applyFilter(framed));
    for (final int[] white : new int[][] { { 10, 25 }, { 29, 25 }, { 20, 20 }, { 20, 29 } }) {
      assertEquals(0xffffff, framed.getPixels()[white[0] + 40 * white[1]] & 0xffffff, Arrays.toString(white));
    }
    for (final int[] black : new int[][] { { 9, 25 }, { 30, 25 }, { 20, 19 }, { 20, 30 } }) {
      assertEquals(0, framed.getPixels()[black[0] + 40 * black[1]] & 0xffffff, Arrays.toString(black));
    }
    try (final ImageBuffer cropped = quadrantPicture()) {
      assertTrue(this.parse("crop=0:0:50:50").create().getFirst().applyFilter(cropped));
      assertEquals(10, cropped.getWidth());
      assertEquals(10, cropped.getHeight());
      final int[] expected = new int[100];
      Arrays.fill(expected, OPAQUE | 0x123456);
      assertArrayEquals(expected, cropped.getPixels(), "the top-left quadrant fills the result");
    }
  }

  @Test
  void readsOverlaysOnlyAsSmallPngFilesOfTheOverlayFolder() throws IOException {
    Files.write(this.overlays.resolve("big.png"), new byte[(int) FilterChain.MAX_OVERLAY_BYTES + 1]);
    assertThrows(IllegalArgumentException.class, () -> this.parse("overlay=big"));
    Files.createDirectory(this.overlays.resolve("folder.png"));
    assertThrows(IllegalArgumentException.class, () -> this.parse("overlay=folder"));
    final Path outside = Files.createTempFile("outside", ".png");
    try {
      Files.createSymbolicLink(this.overlays.resolve("link.png"), outside);
      assertThrows(IllegalArgumentException.class, () -> this.parse("overlay=link"), "links are not followed");
    } catch (final UnsupportedOperationException | IOException noLinks) {
      assumeTrue(false, "no symbolic links here");
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void filtersPicturesAtTheirSize() throws IOException {
    final ImageBuffer gray = picture(4, 2, 0x204060);
    this.parse("grayscale").apply(gray);
    final int pixel = gray.getPixels()[0];
    assertEquals((pixel >> 16) & 0xff, pixel & 0xff);
    assertEquals((pixel >> 8) & 0xff, pixel & 0xff);
    final ImageBuffer inverted = picture(4, 2, 0x204060);
    this.parse("invert").apply(inverted);
    assertEquals(0xdfbf9f, inverted.getPixels()[3] & 0xffffff);
    try (final ImageBuffer zoomed = quadrantPicture()) {
      this.parse("crop=50:50:50:50").apply(zoomed);
      assertEquals(10, zoomed.getWidth());
      assertEquals(10, zoomed.getHeight());
      final int[] expected = new int[100];
      Arrays.fill(expected, OPAQUE | 0xABCDEF);
      assertArrayEquals(expected, zoomed.getPixels(), "the bottom-right quadrant fills the result");
    }
    final ImageBuffer framed = picture(20, 20, 0x000000);
    this.parse("rectangle=0:0:50:50:00ff00").apply(framed);
    assertEquals(0x00ff00, framed.getPixels()[0] & 0xffffff);
    this.writeOverlay("red", 2, 2);
    final ImageBuffer covered = picture(8, 8, 0x0000ff);
    this.parse("overlay=red").apply(covered);
    assertEquals(0xff0000, covered.getPixels()[0] & 0xffffff);
    assertEquals(0x0000ff, covered.getPixels()[7] & 0xffffff);
  }

  @Test
  void releasesTheDecodedOverlayOnceItsFilterHoldsACopy() {
    final ImageBuffer decoded = picture(2, 2, 0xff0000);
    final VideoFilter overlay = FilterChain.overlayFilter(decoded);
    assertThrows(IllegalStateException.class, decoded::getWidth, "the decoded picture is released");
    final ImageBuffer covered = picture(4, 4, 0x0000ff);
    overlay.applyFilter(covered);
    assertEquals(0xff0000, covered.getPixels()[0] & 0xffffff, "the filter draws its own copy");
    assertEquals(0x0000ff, covered.getPixels()[3] & 0xffffff);
  }

  @Test
  void scalesALargeOverlayDownAndRemakesARegionForEverySize() throws IOException {
    this.writeOverlay("wide", 2_048, 16);
    final VideoFilter overlay = this.parse("overlay=wide").create().getFirst();
    final ImageBuffer large = picture(1_100, 16, 0x0000ff);
    overlay.applyFilter(large);
    assertEquals(0xff0000, large.getPixels()[1_023] & 0xffffff);
    assertEquals(0x0000ff, large.getPixels()[1_024 + 1_100 * 8] & 0xffffff, "scaled to 1024 by 8");
    assertEquals(0xff0000, large.getPixels()[1_023 + 1_100 * 7] & 0xffffff, "eight rows high");
    final VideoFilter rectangle = this.parse("rectangle=50:0:50:50:ffffff").create().getFirst();
    final ImageBuffer small = picture(10, 10, 0);
    rectangle.applyFilter(small);
    assertEquals(0xffffff, small.getPixels()[5] & 0xffffff);
    rectangle.applyFilter(small);
    final ImageBuffer other = picture(20, 20, 0);
    rectangle.applyFilter(other);
    assertEquals(0xffffff, other.getPixels()[10] & 0xffffff);
    final ImageBuffer taller = picture(20, 40, 0);
    rectangle.applyFilter(taller);
    assertEquals(0xffffff, taller.getPixels()[10 + 20 * 15] & 0xffffff, "the region is remade for the taller picture");
    assertEquals(0, taller.getPixels()[10 + 20 * 30] & 0xffffff);
    final ImageBuffer wider = picture(40, 40, 0);
    rectangle.applyFilter(wider);
    assertEquals(0xffffff, wider.getPixels()[20 + 40 * 5] & 0xffffff, "the region is remade for the wider picture");
  }
}
