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

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.BilateralFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.BlurFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.ColorMapFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.CropFilter;
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
import me.brandonli.mcav.media.source.file.FileSource;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The filters a video or image command applies to every picture before it is shown, in order, from the {@code --filters}
 * option: filter names separated by commas, each with its arguments after {@code =}, separated by colons, such as
 * {@code grayscale,blur=3,text=Live}. Only filters whose work is bounded are offered, with bounded arguments, and an
 * overlay only from the plugin's {@code overlays} folder, by name; the pictures are filtered at the size they are
 * shown at, which the command's resolution bounds.
 *
 * <p>This class is immutable and thread-safe; each call creates new filters.
 *
 * @since 1.0.0
 */
public final class FilterChain {

  /**
   * The chain of no filter.
   *
   * @since 1.0.0
   */
  public static final FilterChain NONE = new FilterChain(List.of());

  /**
   * The most filters of a chain.
   *
   * @since 1.0.0
   */
  public static final int MAXIMUM_FILTERS = 8;

  /**
   * The longest chain, in characters.
   *
   * @since 1.0.0
   */
  public static final int MAXIMUM_LENGTH = 256;

  /**
   * The largest radius of {@code blur}, whose kernel is twice the radius plus one wide.
   *
   * @since 1.0.0
   */
  public static final int MAXIMUM_BLUR = 15;

  /**
   * The largest diameter of {@code bilateral}, whose work grows with its square.
   *
   * @since 1.0.0
   */
  public static final int MAXIMUM_BILATERAL = 9;

  /**
   * The largest kernel of {@code dilate} and {@code erode}.
   *
   * @since 1.0.0
   */
  public static final int MAXIMUM_MORPHOLOGY = 15;

  /**
   * The largest contrast factor of {@code luminance}.
   *
   * @since 1.0.0
   */
  public static final double MAXIMUM_CONTRAST = 3.0;

  /**
   * The largest brightness shift of {@code luminance}, either way.
   *
   * @since 1.0.0
   */
  public static final int MAXIMUM_BRIGHTNESS = 255;

  /**
   * The longest text of {@code text}.
   *
   * @since 1.0.0
   */
  public static final int MAXIMUM_TEXT = 32;

  /**
   * The largest overlay file.
   *
   * @since 1.0.0
   */
  public static final long MAXIMUM_OVERLAY_BYTES = 4L * 1024 * 1024;

  /**
   * The largest side of an overlay: a larger picture is scaled down to it.
   *
   * @since 1.0.0
   */
  public static final int MAXIMUM_OVERLAY_SIDE = 1024;

  /**
   * The folder in the plugin's data folder that overlays are read from.
   *
   * @since 1.0.0
   */
  public static final String OVERLAY_FOLDER = "overlays";

  private static final String GRAYSCALE_FILTER = "grayscale";
  private static final String INVERT_FILTER = "invert";
  private static final String FRAMES_PER_SECOND_FILTER = "fps";
  private static final String BLUR_FILTER = "blur";
  private static final String BILATERAL_FILTER = "bilateral";
  private static final String THRESHOLD_FILTER = "threshold";
  private static final String LUMINANCE_FILTER = "luminance";
  private static final String COLOR_MAP_FILTER = "colormap";
  private static final String TINT_FILTER = "tint";
  private static final String FLIP_FILTER = "flip";
  private static final String ROTATE_FILTER = "rotate";
  private static final String CROP_FILTER = "crop";
  private static final String RECTANGLE_FILTER = "rectangle";
  private static final String DILATE_FILTER = "dilate";
  private static final String ERODE_FILTER = "erode";
  private static final String TEXT_FILTER = "text";
  private static final String OVERLAY_FILTER = "overlay";

  /**
   * The names of the filters, as the sender types them.
   *
   * @since 1.0.0
   */
  public static final List<String> NAMES = List.of(
    GRAYSCALE_FILTER,
    INVERT_FILTER,
    BLUR_FILTER,
    BILATERAL_FILTER,
    THRESHOLD_FILTER,
    LUMINANCE_FILTER,
    COLOR_MAP_FILTER,
    TINT_FILTER,
    FLIP_FILTER,
    ROTATE_FILTER,
    CROP_FILTER,
    RECTANGLE_FILTER,
    DILATE_FILTER,
    ERODE_FILTER,
    TEXT_FILTER,
    OVERLAY_FILTER,
    FRAMES_PER_SECOND_FILTER
  );

  private static final Map<String, Integer> COLOR_MAPS = Map.ofEntries(
    Map.entry("autumn", opencv_imgproc.COLORMAP_AUTUMN),
    Map.entry("bone", opencv_imgproc.COLORMAP_BONE),
    Map.entry("jet", opencv_imgproc.COLORMAP_JET),
    Map.entry("winter", opencv_imgproc.COLORMAP_WINTER),
    Map.entry("rainbow", opencv_imgproc.COLORMAP_RAINBOW),
    Map.entry("ocean", opencv_imgproc.COLORMAP_OCEAN),
    Map.entry("summer", opencv_imgproc.COLORMAP_SUMMER),
    Map.entry("spring", opencv_imgproc.COLORMAP_SPRING),
    Map.entry("cool", opencv_imgproc.COLORMAP_COOL),
    Map.entry("hsv", opencv_imgproc.COLORMAP_HSV),
    Map.entry("pink", opencv_imgproc.COLORMAP_PINK),
    Map.entry("hot", opencv_imgproc.COLORMAP_HOT),
    Map.entry("parula", opencv_imgproc.COLORMAP_PARULA),
    Map.entry("magma", opencv_imgproc.COLORMAP_MAGMA),
    Map.entry("inferno", opencv_imgproc.COLORMAP_INFERNO),
    Map.entry("plasma", opencv_imgproc.COLORMAP_PLASMA),
    Map.entry("viridis", opencv_imgproc.COLORMAP_VIRIDIS),
    Map.entry("cividis", opencv_imgproc.COLORMAP_CIVIDIS),
    Map.entry("twilight", opencv_imgproc.COLORMAP_TWILIGHT),
    Map.entry("turbo", opencv_imgproc.COLORMAP_TURBO)
  );

  private static final Pattern HEXADECIMAL = Pattern.compile("[0-9a-fA-F]{6}");

  private static final Pattern WHOLE = Pattern.compile("-?\\d{1,4}");

  private static final Pattern DECIMAL = Pattern.compile("\\d{1,2}(\\.\\d{1,3})?");

  private static final Pattern TEXT = Pattern.compile("[A-Za-z0-9 .!?'()+-]{1," + MAXIMUM_TEXT + "}");

  private static final Pattern OVERLAY_NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

  private static final String OVERLAY_SUFFIX = ".png";

  private static final String NOT_A_WHOLE_NUMBER = "Filter %s takes a whole number from %s to %s, and %s is not one!";

  private static final String NO_OVERLAY = "Overlay %s does not exist in the overlays folder!";

  private static final int PERCENT = 100;

  private static final int MAXIMUM_LEVEL = 255;

  private static final double BILATERAL_SIGMA = 75;

  private static final int TEXT_X = 4;

  private static final int TEXT_Y = 16;

  private static final double TEXT_SCALE = 0.5;

  private static final double[] WHITE = { MAXIMUM_LEVEL, MAXIMUM_LEVEL, MAXIMUM_LEVEL };

  private static final double DEFAULT_TINT = 0.5;

  private static final int HEXADECIMAL_RADIX = 16;

  private static final int RED_SHIFT = 16;

  private static final int GREEN_SHIFT = 8;

  private static final int BYTE = 0xff;

  private static final String FILTER_SEPARATOR = ",";
  private static final char ARGUMENT_START = '=';
  private static final String ARGUMENT_SEPARATOR = ":";
  private static final String NAME_SEPARATOR = ", ";

  private static final String HORIZONTAL_FLIP = "h";
  private static final String VERTICAL_FLIP = "v";
  private static final String BOTH_FLIPS = "hv";

  private static final String QUARTER_TURN = "90";
  private static final String HALF_TURN = "180";
  private static final String THREE_QUARTER_TURN = "270";

  private static final int KERNEL_SIDES = 2;

  private static final int LUMINANCE_ARGUMENTS = 2;
  private static final int TINT_MAXIMUM_ARGUMENTS = 2;
  private static final int CROP_ARGUMENTS = 4;
  private static final int RECTANGLE_ARGUMENTS = 5;

  private static final int RECTANGLE_COLOR_INDEX = 4;

  private static final int WIDTH_INDEX = 2;
  private static final int HEIGHT_INDEX = 3;

  private final List<Supplier<VideoFilter>> filters;

  private FilterChain(final List<Supplier<VideoFilter>> filters) {
    this.filters = List.copyOf(filters);
  }

  /**
   * Parses the {@code --filters} option.
   *
   * @param text     the option as typed, empty for no filter
   * @param overlays the folder overlays are read from
   * @param kind     what the pictures come from; only {@link MediaKind#VIDEO} allows {@code fps}
   * @return the chain
   * @throws NullPointerException     if an argument is null
   * @throws IllegalArgumentException with the reason for the sender, if the text is not a chain of allowed filters
   * @since 1.0.0
   */
  public static FilterChain parse(final String text, final Path overlays, final MediaKind kind) {
    Preconditions.checkNotNull(text, "Text must not be null!");
    Preconditions.checkNotNull(overlays, "Overlay folder must not be null!");
    Preconditions.checkNotNull(kind, "Media kind must not be null!");

    if (text.isEmpty()) {
      return NONE;
    }

    if (text.length() > MAXIMUM_LENGTH) {
      final String message = "Filters may have at most %s characters!".formatted(MAXIMUM_LENGTH);
      throw new IllegalArgumentException(message);
    }

    final String[] specifications = text.split(FILTER_SEPARATOR, -1);
    if (specifications.length > MAXIMUM_FILTERS) {
      final String message = "At most %s filters may be chained!".formatted(MAXIMUM_FILTERS);
      throw new IllegalArgumentException(message);
    }

    final List<Supplier<VideoFilter>> filters = new ArrayList<>();
    for (final String specification : specifications) {
      filters.add(parseFilter(specification, overlays, kind));
    }
    return new FilterChain(filters);
  }

  private static Supplier<VideoFilter> parseFilter(final String specification, final Path overlays, final MediaKind kind) {
    final int equals = specification.indexOf(ARGUMENT_START);
    if (equals < 0) {
      return createFilter(specification, List.of(), overlays, kind);
    }

    final String name = specification.substring(0, equals);
    final String argumentText = specification.substring(equals + 1);
    final String[] argumentValues = argumentText.split(ARGUMENT_SEPARATOR, -1);
    final List<String> arguments = List.of(argumentValues);
    return createFilter(name, arguments, overlays, kind);
  }

  private static Supplier<VideoFilter> createFilter(
    final String typedName,
    final List<String> arguments,
    final Path overlays,
    final MediaKind kind
  ) {
    final String name = typedName.toLowerCase(Locale.ROOT);
    return switch (name) {
      case GRAYSCALE_FILTER -> noArguments(name, arguments, GrayscaleFilter::new);
      case INVERT_FILTER -> noArguments(name, arguments, InvertFilter::new);
      case FRAMES_PER_SECOND_FILTER -> framesPerSecond(arguments, kind);
      case BLUR_FILTER -> blur(arguments);
      case BILATERAL_FILTER -> bilateral(arguments);
      case THRESHOLD_FILTER -> threshold(arguments);
      case LUMINANCE_FILTER -> luminance(arguments);
      case COLOR_MAP_FILTER -> colorMap(arguments);
      case TINT_FILTER -> tint(arguments);
      case FLIP_FILTER -> flip(arguments);
      case ROTATE_FILTER -> rotate(arguments);
      case CROP_FILTER -> crop(arguments);
      case RECTANGLE_FILTER -> rectangle(arguments);
      case DILATE_FILTER -> dilate(arguments);
      case ERODE_FILTER -> erode(arguments);
      case TEXT_FILTER -> text(arguments);
      case OVERLAY_FILTER -> overlay(arguments, overlays);
      default -> {
        final String names = String.join(NAME_SEPARATOR, NAMES);
        final String message = "Filter %s does not exist, use one of %s instead!".formatted(name, names);
        throw new IllegalArgumentException(message);
      }
    };
  }

  private static Supplier<VideoFilter> noArguments(final String name, final List<String> arguments, final Supplier<VideoFilter> filter) {
    count(name, arguments, 0);

    return filter;
  }

  private static Supplier<VideoFilter> framesPerSecond(final List<String> arguments, final MediaKind kind) {
    if (kind == MediaKind.IMAGE) {
      throw new IllegalArgumentException("Filter fps counts the frames of a video, and an image has one!");
    }

    return noArguments(FRAMES_PER_SECOND_FILTER, arguments, FramesPerSecondFilter::new);
  }

  private static Supplier<VideoFilter> blur(final List<String> arguments) {
    count(BLUR_FILTER, arguments, 1);
    final int radius = whole(BLUR_FILTER, arguments.getFirst(), 1, MAXIMUM_BLUR);
    return () -> new BlurFilter(BlurFilter.BlurType.GAUSSIAN, KERNEL_SIDES * radius + 1);
  }

  private static Supplier<VideoFilter> bilateral(final List<String> arguments) {
    count(BILATERAL_FILTER, arguments, 1);
    final int diameter = whole(BILATERAL_FILTER, arguments.getFirst(), 1, MAXIMUM_BILATERAL);
    return () -> new BilateralFilter(diameter, BILATERAL_SIGMA, BILATERAL_SIGMA);
  }

  private static Supplier<VideoFilter> threshold(final List<String> arguments) {
    count(THRESHOLD_FILTER, arguments, 1);
    final int level = whole(THRESHOLD_FILTER, arguments.getFirst(), 0, MAXIMUM_LEVEL);
    return () -> new ThresholdFilter(level, MAXIMUM_LEVEL, opencv_imgproc.THRESH_BINARY);
  }

  private static Supplier<VideoFilter> luminance(final List<String> arguments) {
    count(LUMINANCE_FILTER, arguments, LUMINANCE_ARGUMENTS);
    final double contrast = decimal(LUMINANCE_FILTER, arguments.getFirst(), MAXIMUM_CONTRAST);
    final int brightness = whole(LUMINANCE_FILTER, arguments.get(1), -MAXIMUM_BRIGHTNESS, MAXIMUM_BRIGHTNESS);
    return () -> new LuminanceFilter(contrast, brightness);
  }

  private static Supplier<VideoFilter> colorMap(final List<String> arguments) {
    count(COLOR_MAP_FILTER, arguments, 1);
    final Integer map = COLOR_MAPS.get(arguments.getFirst().toLowerCase(Locale.ROOT));
    if (map == null) {
      final String names = String.join(NAME_SEPARATOR, COLOR_MAPS.keySet().stream().sorted().toList());
      final String message = "Colour map %s does not exist, use one of %s instead!".formatted(arguments.getFirst(), names);
      throw new IllegalArgumentException(message);
    }

    return () -> new ColorMapFilter(map);
  }

  private static Supplier<VideoFilter> tint(final List<String> arguments) {
    if (arguments.isEmpty() || arguments.size() > TINT_MAXIMUM_ARGUMENTS) {
      throw new IllegalArgumentException("Filter tint takes a colour such as ff8000, and a strength from 0 to 100!");
    }

    final double[] color = color(TINT_FILTER, arguments.getFirst());
    final double strength =
      arguments.size() == TINT_MAXIMUM_ARGUMENTS ? whole(TINT_FILTER, arguments.get(1), 0, PERCENT) / (double) PERCENT : DEFAULT_TINT;
    return () -> new TintFilter(color, strength);
  }

  private static Supplier<VideoFilter> flip(final List<String> arguments) {
    count(FLIP_FILTER, arguments, 1);
    final FlipFilter.FlipDirection direction = switch (arguments.getFirst()) {
      case HORIZONTAL_FLIP -> FlipFilter.FlipDirection.HORIZONTAL;
      case VERTICAL_FLIP -> FlipFilter.FlipDirection.VERTICAL;
      case BOTH_FLIPS -> FlipFilter.FlipDirection.BOTH;
      default -> {
        final String message = "Flip direction %s must be h, v or hv!".formatted(arguments.getFirst());
        throw new IllegalArgumentException(message);
      }
    };
    return () -> new FlipFilter(direction);
  }

  private static Supplier<VideoFilter> rotate(final List<String> arguments) {
    count(ROTATE_FILTER, arguments, 1);
    final RotationFilter.Rotation rotation = switch (arguments.getFirst()) {
      case QUARTER_TURN -> RotationFilter.Rotation.ROTATE_90_CLOCKWISE;
      case HALF_TURN -> RotationFilter.Rotation.ROTATE_180;
      case THREE_QUARTER_TURN -> RotationFilter.Rotation.ROTATE_90_COUNTERCLOCKWISE;
      default -> {
        final String message = "Rotation %s must be 90, 180 or 270!".formatted(arguments.getFirst());
        throw new IllegalArgumentException(message);
      }
    };
    return () -> new RotationFilter(rotation);
  }

  private static Supplier<VideoFilter> crop(final List<String> arguments) {
    count(CROP_FILTER, arguments, CROP_ARGUMENTS);
    final Region region = region(CROP_FILTER, arguments);
    return () -> new RegionFilter(region, CropZoom::new);
  }

  private static Supplier<VideoFilter> rectangle(final List<String> arguments) {
    count(RECTANGLE_FILTER, arguments, RECTANGLE_ARGUMENTS);
    final Region region = region(RECTANGLE_FILTER, arguments);
    final double[] color = color(RECTANGLE_FILTER, arguments.get(RECTANGLE_COLOR_INDEX));
    return () -> new RegionFilter(region, (left, top, width, height, _, _) -> new RectangleFilter(left, top, width, height, color));
  }

  private static Supplier<VideoFilter> dilate(final List<String> arguments) {
    count(DILATE_FILTER, arguments, 1);
    final int kernel = whole(DILATE_FILTER, arguments.getFirst(), 1, MAXIMUM_MORPHOLOGY);
    return () -> new DilationFilter(kernel);
  }

  private static Supplier<VideoFilter> erode(final List<String> arguments) {
    count(ERODE_FILTER, arguments, 1);
    final int kernel = whole(ERODE_FILTER, arguments.getFirst(), 1, MAXIMUM_MORPHOLOGY);
    return () -> new ErosionFilter(kernel);
  }

  private static Supplier<VideoFilter> text(final List<String> arguments) {
    count(TEXT_FILTER, arguments, 1);
    final String text = arguments.getFirst();
    if (!TEXT.matcher(text).matches()) {
      final String message = "Filter text takes up to %s letters, digits, spaces and .!?'()+- characters!".formatted(MAXIMUM_TEXT);
      throw new IllegalArgumentException(message);
    }

    return () -> new TextFilter(text, TEXT_X, TEXT_Y, TextFilter.DEFAULT_FONT, TEXT_SCALE, WHITE);
  }

  // links in the overlay folder are not followed
  private static Supplier<VideoFilter> overlay(final List<String> arguments, final Path overlays) {
    count(OVERLAY_FILTER, arguments, 1);
    final String name = arguments.getFirst();
    if (!OVERLAY_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("Filter overlay takes the name of a PNG file of the overlays folder, without .png!");
    }

    final Path file = overlays.resolve(name + OVERLAY_SUFFIX);
    final BasicFileAttributes attributes;
    try {
      attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (final IOException exception) {
      final String message = NO_OVERLAY.formatted(name);
      throw new IllegalArgumentException(message, exception);
    }
    if (!attributes.isRegularFile()) {
      final String message = NO_OVERLAY.formatted(name);
      throw new IllegalArgumentException(message);
    }

    final long size = attributes.size();
    if (size > MAXIMUM_OVERLAY_BYTES) {
      final String message = "Overlay %s is larger than %s bytes!".formatted(name, MAXIMUM_OVERLAY_BYTES);
      throw new IllegalArgumentException(message);
    }

    return () -> overlayFilter(readOverlay(file));
  }

  // the filter copied the picture, so its native memory is released now, not at garbage collection
  static OverlayImageFilter overlayFilter(final ImageBuffer decoded) {
    try {
      return new OverlayImageFilter(decoded, 0, 0);
    } finally {
      decoded.release();
    }
  }

  private static ImageBuffer readOverlay(final Path file) {
    final ImageBuffer image = ImageBuffer.path(FileSource.path(file));
    final int side = Math.max(image.getWidth(), image.getHeight());
    if (side > MAXIMUM_OVERLAY_SIDE) {
      final int width = Math.max(1, (image.getWidth() * MAXIMUM_OVERLAY_SIDE) / side);
      final int height = Math.max(1, (image.getHeight() * MAXIMUM_OVERLAY_SIDE) / side);
      new ResizeFilter(width, height).applyFilter(image);
    }
    return image;
  }

  private static void count(final String name, final List<String> arguments, final int expected) {
    if (arguments.size() != expected) {
      final String message = "Filter %s takes %s argument%s!".formatted(name, expected, expected == 1 ? "" : "s");
      throw new IllegalArgumentException(message);
    }
  }

  private static int whole(final String name, final String text, final int minimum, final int maximum) {
    if (!WHOLE.matcher(text).matches()) {
      final String message = NOT_A_WHOLE_NUMBER.formatted(name, minimum, maximum, text);
      throw new IllegalArgumentException(message);
    }

    final int value = Integer.parseInt(text);
    if (value < minimum || value > maximum) {
      final String message = NOT_A_WHOLE_NUMBER.formatted(name, minimum, maximum, text);
      throw new IllegalArgumentException(message);
    }

    return value;
  }

  private static double decimal(final String name, final String text, final double maximum) {
    if (!DECIMAL.matcher(text).matches() || Double.parseDouble(text) > maximum) {
      final String message = "Filter %s takes a number from 0 to %s, and %s is not one!".formatted(name, maximum, text);
      throw new IllegalArgumentException(message);
    }

    return Double.parseDouble(text);
  }

  // typed as rrggbb, stored in OpenCV's blue, green, red order
  private static double[] color(final String name, final String text) {
    if (!HEXADECIMAL.matcher(text).matches()) {
      final String message = "Filter %s takes a colour such as ff8000, and %s is not one!".formatted(name, text);
      throw new IllegalArgumentException(message);
    }

    final int rgb = Integer.parseInt(text, HEXADECIMAL_RADIX);
    return new double[] { rgb & BYTE, (rgb >> GREEN_SHIFT) & BYTE, (rgb >> RED_SHIFT) & BYTE };
  }

  private static Region region(final String name, final List<String> arguments) {
    final int left = whole(name, arguments.getFirst(), 0, PERCENT - 1);
    final int top = whole(name, arguments.get(1), 0, PERCENT - 1);
    final int width = whole(name, arguments.get(WIDTH_INDEX), 1, PERCENT - left);
    final int height = whole(name, arguments.get(HEIGHT_INDEX), 1, PERCENT - top);
    return new Region(left, top, width, height);
  }

  /**
   * Makes new filters of the chain, which keep state between pictures, for one video or image.
   *
   * @return the filters, in order
   * @since 1.0.0
   */
  public List<VideoFilter> create() {
    return this.filters.stream().map(Supplier::get).toList();
  }

  /**
   * Puts new filters of the chain in front of the steps that show the pictures.
   *
   * @param output the first step that shows the pictures
   * @return the first step of the filters, or the output if the chain is empty
   * @since 1.0.0
   */
  public VideoPipelineStep prepend(final VideoPipelineStep output) {
    Preconditions.checkNotNull(output, "Output must not be null!");

    final List<VideoFilter> created = this.create();
    VideoPipelineStep first = output;
    for (int index = created.size() - 1; index >= 0; index--) {
      first = VideoPipelineStep.of(first, created.get(index));
    }
    return first;
  }

  /**
   * Filters a picture with new filters of the chain.
   *
   * @param picture the picture, changed in place
   * @since 1.0.0
   */
  public void apply(final ImageBuffer picture) {
    Preconditions.checkNotNull(picture, "Picture must not be null!");

    for (final VideoFilter filter : this.create()) {
      filter.applyFilter(picture);
    }
  }

  /**
   * Returns whether the chain has no filter.
   *
   * @return true if no filter is applied
   * @since 1.0.0
   */
  public boolean isEmpty() {
    return this.filters.isEmpty();
  }

  // in percent of the picture
  private record Region(int left, int top, int width, int height) {}

  @FunctionalInterface
  private interface RegionFactory {
    VideoFilter create(int left, int top, int width, int height, int pictureWidth, int pictureHeight);
  }

  private static final class RegionFilter implements VideoFilter {

    private final Region region;

    private final RegionFactory factory;

    private int width;

    private int height;

    private @Nullable VideoFilter filter;

    private RegionFilter(final Region region, final RegionFactory factory) {
      this.region = region;
      this.factory = factory;
    }

    @Override
    public boolean applyFilter(final ImageBuffer samples, final OriginalVideoMetadata metadata) {
      final int pictureWidth = samples.getWidth();
      final int pictureHeight = samples.getHeight();
      VideoFilter current = this.filter;
      if (current == null || pictureWidth != this.width || pictureHeight != this.height) {
        final int left = (pictureWidth * this.region.left()) / PERCENT;
        final int top = (pictureHeight * this.region.top()) / PERCENT;
        // region() caps left + width and top + height at 100 percent
        final int regionWidth = Math.max(1, (pictureWidth * this.region.width()) / PERCENT);
        final int regionHeight = Math.max(1, (pictureHeight * this.region.height()) / PERCENT);
        current = this.factory.create(left, top, regionWidth, regionHeight, pictureWidth, pictureHeight);
        this.filter = current;
        this.width = pictureWidth;
        this.height = pictureHeight;
      }
      return current.applyFilter(samples, metadata);
    }
  }

  private static final class CropZoom implements VideoFilter {

    private final CropFilter crop;

    private final ResizeFilter resize;

    private CropZoom(final int left, final int top, final int width, final int height, final int pictureWidth, final int pictureHeight) {
      this.crop = new CropFilter(left, top, width, height);
      this.resize = new ResizeFilter(pictureWidth, pictureHeight);
    }

    @Override
    public boolean applyFilter(final ImageBuffer samples, final OriginalVideoMetadata metadata) {
      this.crop.applyFilter(samples, metadata);
      this.resize.applyFilter(samples, metadata);

      return true;
    }
  }
}
