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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import me.brandonli.mcav.media.player.pipeline.filter.video.RectangleFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
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
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

final class FilterChainTest {

  private static final int OPAQUE = 0xff000000;

  private static final int RED = 0xff0000;
  private static final int BLUE = 0x0000ff;
  private static final int WHITE = 0xffffff;

  private static final int TOP_LEFT_COLOR = 0x123456;
  private static final int BOTTOM_RIGHT_COLOR = 0xABCDEF;
  private static final int QUADRANT_PICTURE_SIZE = 10;
  private static final int QUADRANT_PIXEL_COUNT = 100;

  private static final int DOT_SIZE = 5;

  private static final int LARGE_PICTURE_SIDE = 40;
  private static final int SMALL_PICTURE_SIDE = 20;

  private static final int OVERLAY_HEIGHT = 16;

  private static final int RGB_MASK = 0xffffff;
  private static final int CHANNEL_MASK = 0xff;

  private static final String FILTER_SEPARATOR = ",";
  private static final String NARROW_BLUR_FILTER = "blur=1";
  private static final String GRAYSCALE_FILTER = "grayscale";
  private static final String INVERT_FILTER = "invert";

  private static final String TEXT_FILTER_PREFIX = "text=";
  private static final String FILLER_TEXT = "a";

  private static final String PNG_SUFFIX = ".png";
  private static final String LARGEST_OVERLAY_FILE_NAME = "largest.png";
  private static final String BIG_OVERLAY_FILE_NAME = "big.png";
  private static final String FOLDER_OVERLAY_FILE_NAME = "folder.png";
  private static final String OUTSIDE_FILE_PREFIX = "outside";
  private static final String LINK_OVERLAY_FILE_NAME = "link.png";

  @TempDir
  Path overlays;

  @Test
  void shouldApplyNoFilterWithoutAChain() {
    final FilterChain none = this.parse("");
    assertSame(FilterChain.NONE, none);

    final boolean empty = none.isEmpty();
    assertTrue(empty);

    final VideoPipelineStep output = VideoPipelineStep.of(VideoFilter.NO_OPERATION);

    final VideoPipelineStep videoPipelineStep = none.prepend(output);
    assertSame(output, videoPipelineStep);

    final List<VideoFilter> videoFilters = none.createFilters();
    assertEquals(List.of(), videoFilters);
  }

  private FilterChain parse(final String text) {
    return FilterChain.parse(text, this.overlays, MediaKind.VIDEO);
  }

  @Test
  void shouldMakeEveryFilterInTheOrderTyped() throws IOException {
    this.writeOverlay("logo", 2, 2);
    final FilterChain chain = this.parse(
      "grayscale,INVERT,blur=15,bilateral=9,threshold=128,luminance=1.5:-20,colormap=Turbo,tint=ff8000:30"
    );
    final List<VideoFilter> filters = chain.createFilters();
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

    final List<VideoFilter> more = this.parse("tint=0000ff,flip=h,flip=v,flip=hv,rotate=90,rotate=180,rotate=270,dilate=1").createFilters();

    final List<Class<?>> moreList = more.stream().map(Object::getClass).toList();
    final List<Class<?>> expectedKinds = List.of(
      TintFilter.class,
      FlipFilter.class,
      FlipFilter.class,
      FlipFilter.class,
      RotationFilter.class,
      RotationFilter.class,
      RotationFilter.class,
      DilationFilter.class
    );
    assertEquals(expectedKinds, moreList);

    final List<VideoFilter> rest = this.parse(
      "erode=15,text=Live (1)! ok?,fps,overlay=logo,crop=0:0:100:100,rectangle=10:10:20:20:00ff00"
    ).createFilters();

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
    final VideoFilter chainChain = chain.createFilters().getFirst();
    assertNotEquals(expected, chainChain, "every video gets filters of its own");
  }

  private void writeOverlay(final String name, final int width, final int height) throws IOException {
    final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    for (int row = 0; row < height; row++) {
      for (int column = 0; column < width; column++) {
        image.setRGB(column, row, RED);
      }
    }
    ImageIO.write(image, "png", this.overlays.resolve(name + PNG_SUFFIX).toFile());
  }

  @Test
  void shouldPutTheFiltersBeforeTheOutputInOrder() {
    final VideoPipelineStep output = VideoPipelineStep.of(VideoFilter.NO_OPERATION);
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
    final int size = this.parse(String.join(FILTER_SEPARATOR, Collections.nCopies(FilterChain.MAXIMUM_FILTERS, INVERT_FILTER)))
      .createFilters()
      .size();
    assertEquals(FilterChain.MAXIMUM_FILTERS, size);
    assertThrows(IllegalArgumentException.class, this::parseTooLongChain);
    assertThrows(IllegalArgumentException.class, () ->
      this.parse(TEXT_FILTER_PREFIX + FILLER_TEXT.repeat(FilterChain.MAXIMUM_TEXT_LENGTH + 1))
    );

    this.parse(TEXT_FILTER_PREFIX + FILLER_TEXT.repeat(FilterChain.MAXIMUM_TEXT_LENGTH));
    assertThrows(IllegalArgumentException.class, () -> this.parse("invert,%s".formatted("x".repeat(FilterChain.MAXIMUM_LENGTH))));
  }

  private void parseTooLongChain() {
    this.parse(String.join(FILTER_SEPARATOR, Collections.nCopies(FilterChain.MAXIMUM_FILTERS + 1, INVERT_FILTER)));
  }

  @Test
  void shouldRefuseTheFramesPerSecondFilterForAnImage() {
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
      FilterChain.parse("fps", this.overlays, MediaKind.IMAGE)
    );

    final String message = refused.getMessage();
    assertEquals("Filter fps counts the frames of a video, and an image has one!", message);
  }

  @Test
  void shouldRefuseNullArguments() {
    assertThrows(NullPointerException.class, () -> FilterChain.parse(null, this.overlays, MediaKind.VIDEO));
    assertThrows(NullPointerException.class, () -> FilterChain.parse("", null, MediaKind.VIDEO));
    assertThrows(NullPointerException.class, () -> FilterChain.parse("", this.overlays, null));
    assertThrows(NullPointerException.class, () -> FilterChain.NONE.prepend(null));
    assertThrows(NullPointerException.class, () -> FilterChain.NONE.apply(null));
  }

  @ParameterizedTest
  @ValueSource(strings = { "bilateral", "colormap", "dilate", "erode", "flip", "overlay", "rotate", "text", "threshold" })
  void shouldRefuseASecondArgumentToAFilterThatTakesOne(final String name) {
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> this.parse("%s=1:1".formatted(name)));

    final String message = refused.getMessage();
    assertEquals("Filter %s takes 1 argument!".formatted(name), message);
  }

  @Test
  void shouldCountTheArgumentsOfAFilterThatTakesSeveral() {
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> this.parse("crop=1:1:1"));

    final String message = refused.getMessage();
    assertEquals("Filter crop takes 4 arguments!", message);
  }

  @Test
  void shouldTakeTheLongestChainAndTheLargestOverlayAllowed() throws IOException {
    final String filters = String.join(
      FILTER_SEPARATOR,
      Collections.nCopies(6, TEXT_FILTER_PREFIX + FILLER_TEXT.repeat(FilterChain.MAXIMUM_TEXT_LENGTH))
    );
    final String longest = "%s,text=%s".formatted(filters, FILLER_TEXT.repeat(23));

    final int length = longest.length();
    assertEquals(FilterChain.MAXIMUM_LENGTH, length);

    final int size = this.parse(longest).createFilters().size();
    assertEquals(7, size);

    Files.write(this.overlays.resolve(LARGEST_OVERLAY_FILE_NAME), new byte[(int) FilterChain.MAXIMUM_OVERLAY_BYTES]);

    final boolean matches = !this.parse("overlay=largest").isEmpty();
    assertTrue(matches);
  }

  @Test
  void shouldApplyTheExactValuesTyped() {
    final ImageBuffer brighter = this.picture(2, 2, 0x102030);
    this.parse("luminance=3:0").apply(brighter);

    final int matches = brighter.getPixels()[0] & RGB_MASK;
    assertEquals(0x306090, matches, "the largest contrast");

    final ImageBuffer outlined = this.picture(4, 4, 0);
    this.parse("rectangle=0:0:100:100:123456").apply(outlined);

    final int secondMatches = outlined.getPixels()[0] & RGB_MASK;
    assertEquals(0x123456, secondMatches);

    final int[] dot = new int[DOT_SIZE * DOT_SIZE];
    Arrays.fill(dot, OPAQUE);
    dot[2 + DOT_SIZE * 2] = OPAQUE | WHITE;
    final ImageBuffer blurred = ImageBuffer.buffer(dot, DOT_SIZE, DOT_SIZE);
    this.parse(NARROW_BLUR_FILTER).apply(blurred);

    final boolean thirdMatches = (blurred.getPixels()[1 + DOT_SIZE * 2] & CHANNEL_MASK) > 0;
    assertTrue(thirdMatches, "a blur of radius 1 reaches the next pixel");
  }

  private ImageBuffer picture(final int width, final int height, final int rgb) {
    final int[] pixels = new int[width * height];
    Arrays.fill(pixels, OPAQUE | rgb);
    return ImageBuffer.buffer(pixels, width, height);
  }

  @Test
  void shouldTintByHalfWithoutAStrengthAndByTheStrengthTyped() {
    final ImageBuffer halfTinted = this.picture(2, 2, 0);
    this.parse("tint=804020").apply(halfTinted);

    final int halfTint = halfTinted.getPixels()[0] & RGB_MASK;
    assertEquals(0x402010, halfTint);

    final ImageBuffer fullyTinted = this.picture(2, 2, 0);
    this.parse("tint=ff0000:100").apply(fullyTinted);

    final int fullTint = fullyTinted.getPixels()[0] & RGB_MASK;
    assertEquals(RED, fullTint);
  }

  @Test
  void shouldLetABlurReachAsFarAsItsRadius() {
    final ImageBuffer narrow = this.whiteDot();
    this.parse(NARROW_BLUR_FILTER).apply(narrow);

    final int matches = narrow.getPixels()[DOT_SIZE * 2] & CHANNEL_MASK;
    assertEquals(0, matches, "a blur of radius 1 stops before the second pixel");

    final ImageBuffer wide = this.whiteDot();
    this.parse("blur=2").apply(wide);

    final boolean secondMatches = (wide.getPixels()[DOT_SIZE * 2] & CHANNEL_MASK) > 0;
    assertTrue(secondMatches, "a blur of radius 2 reaches the second pixel");
  }

  // a white pixel in the middle of a black 5x5 picture
  private ImageBuffer whiteDot() {
    final int[] dot = new int[DOT_SIZE * DOT_SIZE];
    Arrays.fill(dot, OPAQUE);
    dot[2 + DOT_SIZE * 2] = OPAQUE | WHITE;

    return ImageBuffer.buffer(dot, DOT_SIZE, DOT_SIZE);
  }

  @Test
  void shouldPlaceARegionAtThePercentsTyped() {
    final VideoFilter inside = this.parse("rectangle=25:50:50:25:ffffff").createFilters().getFirst();
    final ImageBuffer framed = this.picture(LARGE_PICTURE_SIDE, LARGE_PICTURE_SIDE, 0);

    final boolean filtered = inside.applyFilter(framed);
    assertTrue(filtered);

    for (final int[] white : new int[][] { { 10, 25 }, { 29, 25 }, { 20, 20 }, { 20, 29 } }) {
      final int matches = framed.getPixels()[white[0] + LARGE_PICTURE_SIDE * white[1]] & RGB_MASK;
      assertEquals(WHITE, matches, Arrays.toString(white));
    }
    for (final int[] black : new int[][] { { 9, 25 }, { 30, 25 }, { 20, 19 }, { 20, 30 } }) {
      final int secondMatches = framed.getPixels()[black[0] + LARGE_PICTURE_SIDE * black[1]] & RGB_MASK;
      assertEquals(0, secondMatches, Arrays.toString(black));
    }
    try (final ImageBuffer cropped = this.quadrantPicture()) {
      final boolean secondFiltered = this.parse("crop=0:0:50:50").createFilters().getFirst().applyFilter(cropped);
      assertTrue(secondFiltered);

      final int width = cropped.getWidth();
      assertEquals(QUADRANT_PICTURE_SIZE, width);

      final int height = cropped.getHeight();
      assertEquals(QUADRANT_PICTURE_SIZE, height);

      final int[] expected = new int[QUADRANT_PIXEL_COUNT];
      Arrays.fill(expected, OPAQUE | TOP_LEFT_COLOR);

      final int[] pixels = cropped.getPixels();
      assertArrayEquals(expected, pixels, "the top-left quadrant fills the result");
    }
  }

  private ImageBuffer quadrantPicture() {
    final int[] pixels = new int[QUADRANT_PIXEL_COUNT];
    final int[] colors = { TOP_LEFT_COLOR, 0x789ABC, 0xFEDCBA, BOTTOM_RIGHT_COLOR };
    for (int row = 0; row < QUADRANT_PICTURE_SIZE; row++) {
      for (int column = 0; column < QUADRANT_PICTURE_SIZE; column++) {
        pixels[row * QUADRANT_PICTURE_SIZE + column] = OPAQUE | colors[(row / 5) * 2 + column / 5];
      }
    }
    return ImageBuffer.buffer(pixels, QUADRANT_PICTURE_SIZE, QUADRANT_PICTURE_SIZE);
  }

  @Test
  void shouldReadOverlaysOnlyAsSmallPngFilesOfTheOverlayFolder() throws IOException {
    Files.write(this.overlays.resolve(BIG_OVERLAY_FILE_NAME), new byte[(int) FilterChain.MAXIMUM_OVERLAY_BYTES + 1]);
    assertThrows(IllegalArgumentException.class, () -> this.parse("overlay=big"));

    Files.createDirectory(this.overlays.resolve(FOLDER_OVERLAY_FILE_NAME));
    assertThrows(IllegalArgumentException.class, () -> this.parse("overlay=folder"));

    final Path outside = Files.createTempFile(OUTSIDE_FILE_PREFIX, PNG_SUFFIX);
    try {
      Files.createSymbolicLink(this.overlays.resolve(LINK_OVERLAY_FILE_NAME), outside);
      assertThrows(IllegalArgumentException.class, () -> this.parse("overlay=link"), "links are not followed");
    } catch (final UnsupportedOperationException | IOException exception) {
      assumeTrue(false, "The file system must support symbolic links!");
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void shouldFilterPicturesAtTheirSize() throws IOException {
    final ImageBuffer gray = this.picture(4, 2, 0x204060);
    this.parse(GRAYSCALE_FILTER).apply(gray);
    final int pixel = gray.getPixels()[0];
    assertEquals((pixel >> 16) & CHANNEL_MASK, pixel & CHANNEL_MASK);
    assertEquals((pixel >> 8) & CHANNEL_MASK, pixel & CHANNEL_MASK);

    final ImageBuffer inverted = this.picture(4, 2, 0x204060);
    this.parse(INVERT_FILTER).apply(inverted);

    final int matches = inverted.getPixels()[3] & RGB_MASK;
    assertEquals(0xdfbf9f, matches);

    try (final ImageBuffer zoomed = this.quadrantPicture()) {
      this.parse("crop=50:50:50:50").apply(zoomed);

      final int width = zoomed.getWidth();
      assertEquals(QUADRANT_PICTURE_SIZE, width);

      final int height = zoomed.getHeight();
      assertEquals(QUADRANT_PICTURE_SIZE, height);

      final int[] expected = new int[QUADRANT_PIXEL_COUNT];
      Arrays.fill(expected, OPAQUE | BOTTOM_RIGHT_COLOR);

      final int[] pixels = zoomed.getPixels();
      assertArrayEquals(expected, pixels, "the bottom-right quadrant fills the result");
    }
    final ImageBuffer framed = this.picture(SMALL_PICTURE_SIDE, SMALL_PICTURE_SIDE, 0x000000);
    this.parse("rectangle=0:0:50:50:00ff00").apply(framed);

    final int secondMatches = framed.getPixels()[0] & RGB_MASK;
    assertEquals(0x00ff00, secondMatches);

    this.writeOverlay("red", 2, 2);
    final ImageBuffer covered = this.picture(8, 8, BLUE);
    this.parse("overlay=red").apply(covered);

    final int thirdMatches = covered.getPixels()[0] & RGB_MASK;
    assertEquals(RED, thirdMatches);

    final int fourthMatches = covered.getPixels()[7] & RGB_MASK;
    assertEquals(BLUE, fourthMatches);
  }

  @Test
  void shouldReleaseTheDecodedOverlayOnceItsFilterHoldsACopy() {
    final ImageBuffer decoded = this.picture(2, 2, RED);
    final VideoFilter overlay = FilterChain.overlayFilter(decoded);
    assertThrows(IllegalStateException.class, decoded::getWidth, "the decoded picture is released");

    final ImageBuffer covered = this.picture(4, 4, BLUE);
    overlay.applyFilter(covered);

    final int matches = covered.getPixels()[0] & RGB_MASK;
    assertEquals(RED, matches, "the filter draws its own copy");

    final int secondMatches = covered.getPixels()[3] & RGB_MASK;
    assertEquals(BLUE, secondMatches);
  }

  @Test
  void shouldScaleALargeOverlayDownAndRemakesARegionForEverySize() throws IOException {
    this.writeOverlay("wide", 2_048, OVERLAY_HEIGHT);
    final VideoFilter overlay = this.parse("overlay=wide").createFilters().getFirst();
    final ImageBuffer large = this.picture(1_100, OVERLAY_HEIGHT, BLUE);
    overlay.applyFilter(large);

    final int matches = large.getPixels()[1_023] & RGB_MASK;
    assertEquals(RED, matches);

    final int secondMatches = large.getPixels()[1_024 + 1_100 * 8] & RGB_MASK;
    assertEquals(BLUE, secondMatches, "scaled to 1024 by 8");

    final int thirdMatches = large.getPixels()[1_023 + 1_100 * 7] & RGB_MASK;
    assertEquals(RED, thirdMatches, "eight rows high");

    final VideoFilter rectangle = this.parse("rectangle=50:0:50:50:ffffff").createFilters().getFirst();
    final ImageBuffer small = this.picture(10, 10, 0);
    rectangle.applyFilter(small);

    final int fourthMatches = small.getPixels()[5] & RGB_MASK;
    assertEquals(WHITE, fourthMatches);

    rectangle.applyFilter(small);
    final ImageBuffer other = this.picture(SMALL_PICTURE_SIDE, SMALL_PICTURE_SIDE, 0);
    rectangle.applyFilter(other);

    final int fifthMatches = other.getPixels()[10] & RGB_MASK;
    assertEquals(WHITE, fifthMatches);

    final ImageBuffer taller = this.picture(SMALL_PICTURE_SIDE, LARGE_PICTURE_SIDE, 0);
    rectangle.applyFilter(taller);

    final int sixthMatches = taller.getPixels()[10 + SMALL_PICTURE_SIDE * 15] & RGB_MASK;
    assertEquals(WHITE, sixthMatches, "the region is remade for the taller picture");

    final int seventhMatches = taller.getPixels()[10 + SMALL_PICTURE_SIDE * 30] & RGB_MASK;
    assertEquals(0, seventhMatches);

    final ImageBuffer wider = this.picture(LARGE_PICTURE_SIDE, LARGE_PICTURE_SIDE, 0);
    rectangle.applyFilter(wider);

    final int eighthMatches = wider.getPixels()[20 + LARGE_PICTURE_SIDE * 5] & RGB_MASK;
    assertEquals(WHITE, eighthMatches, "the region is remade for the wider picture");
  }

  @Test
  void shouldNotResizeAnOverlayAsWideAsAllowed() throws IOException {
    this.writeOverlay("widest", FilterChain.MAXIMUM_OVERLAY_SIDE, OVERLAY_HEIGHT);
    final FilterChain chain = this.parse("overlay=widest");
    try (final MockedConstruction<ResizeFilter> resizers = Mockito.mockConstruction(ResizeFilter.class)) {
      final List<VideoFilter> filters = chain.createFilters();

      final int size = filters.size();
      assertEquals(1, size);

      final List<ResizeFilter> constructed = resizers.constructed();

      final boolean empty = constructed.isEmpty();
      assertTrue(empty);
    }
  }

  @Test
  void shouldMakeARegionOncePerPictureSizeAndPassOnItsAnswer() {
    final FilterChain chain = this.parse("rectangle=50:0:50:50:ffffff");
    final List<VideoFilter> filters = chain.createFilters();
    final VideoFilter rectangle = filters.getFirst();
    final ImageBuffer picture = this.picture(10, 10, 0);
    try (final MockedConstruction<RectangleFilter> regions = Mockito.mockConstruction(RectangleFilter.class)) {
      rectangle.applyFilter(picture);
      final boolean filtered = rectangle.applyFilter(picture);
      assertFalse(filtered);

      final List<RectangleFilter> constructed = regions.constructed();

      final int size = constructed.size();
      assertEquals(1, size);
    }
  }
}
