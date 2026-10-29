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
package me.brandonli.mcav.plugin.utils;

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
import java.util.Optional;
import javax.imageio.ImageIO;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.BilateralFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.BlurFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.ColorMapFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.DilationFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.ErosionFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.FlipFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.FramesPerSecondFilter;
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
  void shouldApplyNoFilterWithoutAChain() {
    final FilterChain none = this.parse("");
    assertSame(FilterChain.NONE, none);

    final boolean empty = none.isEmpty();
    assertTrue(empty);

    final VideoPipelineStep output = VideoPipelineStep.of(VideoFilter.NO_OP);

    final VideoPipelineStep videoPipelineStep = none.prepend(output);
    assertSame(output, videoPipelineStep);

    final List<VideoFilter> videoFilters = none.create();
    assertEquals(List.of(), videoFilters);
  }

  @Test
  void shouldMakeEveryFilterInTheOrderTyped() throws IOException {
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

    final List<Class<?>> list = filters.stream().map(Object::getClass).toList();
    assertEquals(kinds, list);

    final List<VideoFilter> more = this.parse("tint=0000ff,flip=h,flip=v,flip=hv,rotate=90,rotate=180,rotate=270,dilate=1").create();

    final List<Class<?>> moreList = more.stream().map(Object::getClass).toList();
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
      moreList
    );

    final List<VideoFilter> rest = this.parse(
      "erode=15,text=Live (1)! ok?,fps,overlay=logo,crop=0:0:100:100,rectangle=10:10:20:20:00ff00"
    ).create();

    final VideoFilter restRest = rest.get(0);
    assertInstanceOf(ErosionFilter.class, restRest);

    final VideoFilter secondRest = rest.get(1);
    assertInstanceOf(TextFilter.class, secondRest);

    final VideoFilter thirdRest = rest.get(2);
    assertInstanceOf(FramesPerSecondFilter.class, thirdRest);

    final VideoFilter fourthRest = rest.get(3);
    assertInstanceOf(OverlayImageFilter.class, fourthRest);

    final int size = rest.size();
    assertEquals(6, size);

    final VideoFilter expected = filters.getFirst();
    final VideoFilter chainChain = chain.create().getFirst();
    assertNotEquals(expected, chainChain, "every video gets filters of its own");
  }

  @Test
  void shouldPutTheFiltersBeforeTheOutputInOrder() {
    final VideoPipelineStep output = VideoPipelineStep.of(VideoFilter.NO_OP);
    final VideoPipelineStep first = this.parse("grayscale,invert").prepend(output);

    final VideoFilter filter = first.getFilter();
    assertInstanceOf(GrayscaleFilter.class, filter);

    final Optional<VideoPipelineStep> afterFirst = first.next();
    final VideoPipelineStep second = afterFirst.orElseThrow();

    final VideoFilter secondFilter = second.getFilter();
    assertInstanceOf(InvertFilter.class, secondFilter);

    final Optional<VideoPipelineStep> afterSecond = second.next();
    final Optional<VideoPipelineStep> expectedOutput = Optional.of(output);
    assertEquals(expectedOutput, afterSecond);
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
  void shouldRefuseWhatIsNotAnAllowedFilter(final String text) {
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> this.parse(text), text);

    final boolean matches = !Objects.requireNonNull(refused.getMessage()).isBlank();
    assertTrue(matches, text);
  }

  @Test
  void shouldNameAFilterByWhatComesBeforeItsFirstEqualsSign() {
    final IllegalArgumentException unnamed = assertThrows(IllegalArgumentException.class, () -> this.parse("=invert"));

    final boolean prefixed = Objects.requireNonNull(unnamed.getMessage()).startsWith("Filter  does not exist, use one of ");
    assertTrue(prefixed, unnamed.getMessage());
  }

  @Test
  void shouldBoundTheChain() {
    final int size = this.parse(String.join(",", Collections.nCopies(FilterChain.MAX_FILTERS, "invert")))
      .create()
      .size();
    assertEquals(FilterChain.MAX_FILTERS, size);
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
  void shouldRefuseASecondArgumentToAFilterThatTakesOne(final String name) {
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> this.parse(name + "=1:1"));

    final String message = refused.getMessage();
    assertEquals("Filter " + name + " takes 1 argument!", message);
  }

  @Test
  void shouldCountTheArgumentsOfAFilterThatTakesSeveral() {
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> this.parse("crop=1:1:1"));

    final String message = refused.getMessage();
    assertEquals("Filter crop takes 4 arguments!", message);
  }

  @Test
  void shouldTakeTheLongestChainAndTheLargestOverlayAllowed() throws IOException {
    final String longest = String.join(",", Collections.nCopies(6, "text=" + "a".repeat(FilterChain.MAX_TEXT))) + ",text=" + "a".repeat(23);

    final int length = longest.length();
    assertEquals(FilterChain.MAX_LENGTH, length);

    final int size = this.parse(longest).create().size();
    assertEquals(7, size);

    Files.write(this.overlays.resolve("largest.png"), new byte[(int) FilterChain.MAX_OVERLAY_BYTES]);

    final boolean matches = !this.parse("overlay=largest").isEmpty();
    assertTrue(matches);

    final boolean empty = FilterChain.NONE.isEmpty();
    assertTrue(empty);

    final boolean secondMatches = !this.parse("grayscale").isEmpty();
    assertTrue(secondMatches);
  }

  @Test
  void shouldApplyTheExactValuesTyped() {
    final ImageBuffer brighter = picture(2, 2, 0x102030);
    this.parse("luminance=3:0").apply(brighter);

    final int matches = brighter.getPixels()[0] & 0xffffff;
    assertEquals(0x306090, matches, "the largest contrast");

    final ImageBuffer outlined = picture(4, 4, 0);
    this.parse("rectangle=0:0:100:100:123456").apply(outlined);

    final int secondMatches = outlined.getPixels()[0] & 0xffffff;
    assertEquals(0x123456, secondMatches);

    final int[] dot = new int[5 * 5];
    Arrays.fill(dot, OPAQUE);
    dot[2 + 5 * 2] = OPAQUE | 0xffffff;
    final ImageBuffer blurred = ImageBuffer.buffer(dot, 5, 5);
    this.parse("blur=1").apply(blurred);

    final boolean thirdMatches = (blurred.getPixels()[1 + 5 * 2] & 0xff) > 0;
    assertTrue(thirdMatches, "a blur of radius 1 reaches the next pixel");
  }

  @Test
  void shouldLetABlurReachAsFarAsItsRadius() {
    final ImageBuffer narrow = whiteDot();
    this.parse("blur=1").apply(narrow);

    final int matches = narrow.getPixels()[5 * 2] & 0xff;
    assertEquals(0, matches, "a blur of radius 1 stops before the second pixel");

    final ImageBuffer wide = whiteDot();
    this.parse("blur=2").apply(wide);

    final boolean secondMatches = (wide.getPixels()[5 * 2] & 0xff) > 0;
    assertTrue(secondMatches, "a blur of radius 2 reaches the second pixel");
  }

  // a white pixel in the middle of a black 5x5 picture
  private static ImageBuffer whiteDot() {
    final int[] dot = new int[5 * 5];
    Arrays.fill(dot, OPAQUE);
    dot[2 + 5 * 2] = OPAQUE | 0xffffff;
    return ImageBuffer.buffer(dot, 5, 5);
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
  void shouldPlaceARegionAtThePercentsTyped() {
    final VideoFilter inside = this.parse("rectangle=25:50:50:25:ffffff").create().getFirst();
    final ImageBuffer framed = picture(40, 40, 0);

    final boolean filtered = inside.applyFilter(framed);
    assertTrue(filtered);

    for (final int[] white : new int[][] { { 10, 25 }, { 29, 25 }, { 20, 20 }, { 20, 29 } }) {
      final int matches = framed.getPixels()[white[0] + 40 * white[1]] & 0xffffff;
      assertEquals(0xffffff, matches, Arrays.toString(white));
    }
    for (final int[] black : new int[][] { { 9, 25 }, { 30, 25 }, { 20, 19 }, { 20, 30 } }) {
      final int secondMatches = framed.getPixels()[black[0] + 40 * black[1]] & 0xffffff;
      assertEquals(0, secondMatches, Arrays.toString(black));
    }
    try (final ImageBuffer cropped = quadrantPicture()) {
      final boolean secondFiltered = this.parse("crop=0:0:50:50").create().getFirst().applyFilter(cropped);
      assertTrue(secondFiltered);

      final int width = cropped.getWidth();
      assertEquals(10, width);

      final int height = cropped.getHeight();
      assertEquals(10, height);

      final int[] expected = new int[100];
      Arrays.fill(expected, OPAQUE | 0x123456);

      final int[] pixels = cropped.getPixels();
      assertArrayEquals(expected, pixels, "the top-left quadrant fills the result");
    }
  }

  @Test
  void shouldReadOverlaysOnlyAsSmallPngFilesOfTheOverlayFolder() throws IOException {
    Files.write(this.overlays.resolve("big.png"), new byte[(int) FilterChain.MAX_OVERLAY_BYTES + 1]);
    assertThrows(IllegalArgumentException.class, () -> this.parse("overlay=big"));
    Files.createDirectory(this.overlays.resolve("folder.png"));
    assertThrows(IllegalArgumentException.class, () -> this.parse("overlay=folder"));
    final Path outside = Files.createTempFile("outside", ".png");
    try {
      Files.createSymbolicLink(this.overlays.resolve("link.png"), outside);
      assertThrows(IllegalArgumentException.class, () -> this.parse("overlay=link"), "links are not followed");
    } catch (final UnsupportedOperationException | IOException noLinks) {
      assumeTrue(false, "The file system must support symbolic links!");
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void shouldFilterPicturesAtTheirSize() throws IOException {
    final ImageBuffer gray = picture(4, 2, 0x204060);
    this.parse("grayscale").apply(gray);
    final int pixel = gray.getPixels()[0];
    assertEquals((pixel >> 16) & 0xff, pixel & 0xff);
    assertEquals((pixel >> 8) & 0xff, pixel & 0xff);
    final ImageBuffer inverted = picture(4, 2, 0x204060);
    this.parse("invert").apply(inverted);

    final int matches = inverted.getPixels()[3] & 0xffffff;
    assertEquals(0xdfbf9f, matches);

    try (final ImageBuffer zoomed = quadrantPicture()) {
      this.parse("crop=50:50:50:50").apply(zoomed);

      final int width = zoomed.getWidth();
      assertEquals(10, width);

      final int height = zoomed.getHeight();
      assertEquals(10, height);

      final int[] expected = new int[100];
      Arrays.fill(expected, OPAQUE | 0xABCDEF);

      final int[] pixels = zoomed.getPixels();
      assertArrayEquals(expected, pixels, "the bottom-right quadrant fills the result");
    }
    final ImageBuffer framed = picture(20, 20, 0x000000);
    this.parse("rectangle=0:0:50:50:00ff00").apply(framed);

    final int secondMatches = framed.getPixels()[0] & 0xffffff;
    assertEquals(0x00ff00, secondMatches);

    this.writeOverlay("red", 2, 2);
    final ImageBuffer covered = picture(8, 8, 0x0000ff);
    this.parse("overlay=red").apply(covered);

    final int thirdMatches = covered.getPixels()[0] & 0xffffff;
    assertEquals(0xff0000, thirdMatches);

    final int fourthMatches = covered.getPixels()[7] & 0xffffff;
    assertEquals(0x0000ff, fourthMatches);
  }

  @Test
  void shouldReleaseTheDecodedOverlayOnceItsFilterHoldsACopy() {
    final ImageBuffer decoded = picture(2, 2, 0xff0000);
    final VideoFilter overlay = FilterChain.overlayFilter(decoded);
    assertThrows(IllegalStateException.class, decoded::getWidth, "the decoded picture is released");
    final ImageBuffer covered = picture(4, 4, 0x0000ff);
    overlay.applyFilter(covered);

    final int matches = covered.getPixels()[0] & 0xffffff;
    assertEquals(0xff0000, matches, "the filter draws its own copy");

    final int secondMatches = covered.getPixels()[3] & 0xffffff;
    assertEquals(0x0000ff, secondMatches);
  }

  @Test
  void shouldScaleALargeOverlayDownAndRemakesARegionForEverySize() throws IOException {
    this.writeOverlay("wide", 2_048, 16);
    final VideoFilter overlay = this.parse("overlay=wide").create().getFirst();
    final ImageBuffer large = picture(1_100, 16, 0x0000ff);
    overlay.applyFilter(large);

    final int matches = large.getPixels()[1_023] & 0xffffff;
    assertEquals(0xff0000, matches);

    final int secondMatches = large.getPixels()[1_024 + 1_100 * 8] & 0xffffff;
    assertEquals(0x0000ff, secondMatches, "scaled to 1024 by 8");

    final int thirdMatches = large.getPixels()[1_023 + 1_100 * 7] & 0xffffff;
    assertEquals(0xff0000, thirdMatches, "eight rows high");

    final VideoFilter rectangle = this.parse("rectangle=50:0:50:50:ffffff").create().getFirst();
    final ImageBuffer small = picture(10, 10, 0);
    rectangle.applyFilter(small);

    final int fourthMatches = small.getPixels()[5] & 0xffffff;
    assertEquals(0xffffff, fourthMatches);

    rectangle.applyFilter(small);
    final ImageBuffer other = picture(20, 20, 0);
    rectangle.applyFilter(other);

    final int fifthMatches = other.getPixels()[10] & 0xffffff;
    assertEquals(0xffffff, fifthMatches);

    final ImageBuffer taller = picture(20, 40, 0);
    rectangle.applyFilter(taller);

    final int sixthMatches = taller.getPixels()[10 + 20 * 15] & 0xffffff;
    assertEquals(0xffffff, sixthMatches, "the region is remade for the taller picture");

    final int seventhMatches = taller.getPixels()[10 + 20 * 30] & 0xffffff;
    assertEquals(0, seventhMatches);

    final ImageBuffer wider = picture(40, 40, 0);
    rectangle.applyFilter(wider);

    final int eighthMatches = wider.getPixels()[20 + 40 * 5] & 0xffffff;
    assertEquals(0xffffff, eighthMatches, "the region is remade for the wider picture");
  }
}
