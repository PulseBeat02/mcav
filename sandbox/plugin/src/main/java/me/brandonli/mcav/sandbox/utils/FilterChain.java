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
import me.brandonli.mcav.media.player.pipeline.filter.video.FPSFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.FlipFilter;
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
 */
public final class FilterChain {

  /** The chain of no filter. */
  public static final FilterChain NONE = new FilterChain(List.of());

  /** The most filters of a chain. */
  public static final int MAX_FILTERS = 8;

  /** The longest chain, in characters. */
  public static final int MAX_LENGTH = 256;

  /** The largest radius of {@code blur}, whose kernel is twice the radius plus one wide. */
  public static final int MAX_BLUR = 15;

  /** The largest diameter of {@code bilateral}, whose work grows with its square. */
  public static final int MAX_BILATERAL = 9;

  /** The largest kernel of {@code dilate} and {@code erode}. */
  public static final int MAX_MORPHOLOGY = 15;

  /** The largest contrast factor of {@code luminance}. */
  public static final double MAX_CONTRAST = 3.0;

  /** The largest brightness shift of {@code luminance}, either way. */
  public static final int MAX_BRIGHTNESS = 255;

  /** The longest text of {@code text}. */
  public static final int MAX_TEXT = 32;

  /** The largest overlay file. */
  public static final long MAX_OVERLAY_BYTES = 4L * 1024 * 1024;

  /** The largest side of an overlay: a larger picture is scaled down to it. */
  public static final int MAX_OVERLAY_SIDE = 1024;

  /** The folder in the plugin's data folder that overlays are read from. */
  public static final String OVERLAY_FOLDER = "overlays";

  /** The names of the filters, as the sender types them. */
  public static final List<String> NAMES = List.of(
    "grayscale",
    "invert",
    "blur",
    "bilateral",
    "threshold",
    "luminance",
    "colormap",
    "tint",
    "flip",
    "rotate",
    "crop",
    "rectangle",
    "dilate",
    "erode",
    "text",
    "overlay",
    "fps"
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

  private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]{6}");

  private static final Pattern WHOLE = Pattern.compile("-?\\d{1,4}");

  private static final Pattern DECIMAL = Pattern.compile("\\d{1,2}(\\.\\d{1,3})?");

  private static final Pattern TEXT = Pattern.compile("[A-Za-z0-9 .!?'()+-]{1," + MAX_TEXT + "}");

  private static final Pattern OVERLAY_NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

  private static final String OVERLAY_SUFFIX = ".png";

  private static final String NO_OVERLAY = "no overlay %s in the overlays folder";

  private static final int PERCENT = 100;

  private static final int MAX_LEVEL = 255;

  private static final double BILATERAL_SIGMA = 75;

  private static final int TEXT_X = 4;

  private static final int TEXT_Y = 16;

  private static final double TEXT_SCALE = 0.5;

  private static final double[] WHITE = { MAX_LEVEL, MAX_LEVEL, MAX_LEVEL };

  private static final double DEFAULT_TINT = 0.5;

  private static final int HEX_RADIX = 16;

  private static final int RED_SHIFT = 16;

  private static final int GREEN_SHIFT = 8;

  private static final int BYTE = 0xff;

  private final List<Supplier<VideoFilter>> filters;

  private FilterChain(final List<Supplier<VideoFilter>> filters) {
    this.filters = List.copyOf(filters);
  }

  /**
   * Parses the {@code --filters} option.
   *
   * @param text     the option as typed, empty for no filter
   * @param overlays the folder overlays are read from
   * @param video    whether the pictures are the frames of a video, which {@code fps} needs
   * @return the chain
   * @throws IllegalArgumentException with the reason for the sender, if the text is not a chain of allowed filters
   */
  public static FilterChain parse(final String text, final Path overlays, final boolean video) {
    Preconditions.checkNotNull(text, "Text must not be null");
    Preconditions.checkNotNull(overlays, "Overlay folder must not be null");
    if (text.isEmpty()) {
      return NONE;
    }
    if (text.length() > MAX_LENGTH) {
      throw new IllegalArgumentException("at most %d characters of filters".formatted(MAX_LENGTH));
    }
    final String[] specs = text.split(",", -1);
    if (specs.length > MAX_FILTERS) {
      throw new IllegalArgumentException("at most %d filters".formatted(MAX_FILTERS));
    }
    final List<Supplier<VideoFilter>> filters = new ArrayList<>();
    for (final String spec : specs) {
      filters.add(parseFilter(spec, overlays, video));
    }
    return new FilterChain(filters);
  }

  private static Supplier<VideoFilter> parseFilter(final String spec, final Path overlays, final boolean video) {
    final int equals = spec.indexOf('=');
    final String name = (equals < 0 ? spec : spec.substring(0, equals)).toLowerCase(Locale.ROOT);
    final List<String> args = equals < 0 ? List.of() : List.of(spec.substring(equals + 1).split(":", -1));
    return switch (name) {
      case "grayscale" -> noArguments(name, args, GrayscaleFilter::new);
      case "invert" -> noArguments(name, args, InvertFilter::new);
      case "fps" -> fps(args, video);
      case "blur" -> blur(args);
      case "bilateral" -> bilateral(args);
      case "threshold" -> threshold(args);
      case "luminance" -> luminance(args);
      case "colormap" -> colorMap(args);
      case "tint" -> tint(args);
      case "flip" -> flip(args);
      case "rotate" -> rotate(args);
      case "crop" -> crop(args);
      case "rectangle" -> rectangle(args);
      case "dilate" -> dilate(args);
      case "erode" -> erode(args);
      case "text" -> text(args);
      case "overlay" -> overlay(args, overlays);
      default -> throw new IllegalArgumentException("no filter %s; the filters are %s".formatted(name, String.join(", ", NAMES)));
    };
  }

  private static Supplier<VideoFilter> noArguments(final String name, final List<String> args, final Supplier<VideoFilter> filter) {
    count(name, args, 0);
    return filter;
  }

  private static Supplier<VideoFilter> fps(final List<String> args, final boolean video) {
    if (!video) {
      throw new IllegalArgumentException("fps counts the frames of a video, and an image has one");
    }
    return noArguments("fps", args, FPSFilter::new);
  }

  private static Supplier<VideoFilter> blur(final List<String> args) {
    count("blur", args, 1);
    final int radius = whole("blur", args.getFirst(), 1, MAX_BLUR);
    return () -> new BlurFilter(BlurFilter.BlurType.GAUSSIAN, 2 * radius + 1);
  }

  private static Supplier<VideoFilter> bilateral(final List<String> args) {
    count("bilateral", args, 1);
    final int diameter = whole("bilateral", args.getFirst(), 1, MAX_BILATERAL);
    return () -> new BilateralFilter(diameter, BILATERAL_SIGMA, BILATERAL_SIGMA);
  }

  private static Supplier<VideoFilter> threshold(final List<String> args) {
    count("threshold", args, 1);
    final int level = whole("threshold", args.getFirst(), 0, MAX_LEVEL);
    return () -> new ThresholdFilter(level, MAX_LEVEL, opencv_imgproc.THRESH_BINARY);
  }

  private static Supplier<VideoFilter> luminance(final List<String> args) {
    count("luminance", args, 2);
    final double contrast = decimal("luminance", args.getFirst(), MAX_CONTRAST);
    final int brightness = whole("luminance", args.get(1), -MAX_BRIGHTNESS, MAX_BRIGHTNESS);
    return () -> new LuminanceFilter(contrast, brightness);
  }

  private static Supplier<VideoFilter> colorMap(final List<String> args) {
    count("colormap", args, 1);
    final Integer map = COLOR_MAPS.get(args.getFirst().toLowerCase(Locale.ROOT));
    if (map == null) {
      throw new IllegalArgumentException(
        "no colour map %s; they are %s".formatted(args.getFirst(), String.join(", ", COLOR_MAPS.keySet().stream().sorted().toList()))
      );
    }
    return () -> new ColorMapFilter(map);
  }

  private static Supplier<VideoFilter> tint(final List<String> args) {
    if (args.isEmpty() || args.size() > 2) {
      throw new IllegalArgumentException("tint takes a colour such as ff8000, and a strength from 0 to 100");
    }
    final double[] color = color("tint", args.getFirst());
    final double strength = args.size() == 2 ? whole("tint", args.get(1), 0, PERCENT) / (double) PERCENT : DEFAULT_TINT;
    return () -> new TintFilter(color, strength);
  }

  private static Supplier<VideoFilter> flip(final List<String> args) {
    count("flip", args, 1);
    final FlipFilter.FlipDirection direction = switch (args.getFirst()) {
      case "h" -> FlipFilter.FlipDirection.HORIZONTAL;
      case "v" -> FlipFilter.FlipDirection.VERTICAL;
      case "hv" -> FlipFilter.FlipDirection.BOTH;
      default -> throw new IllegalArgumentException("flip takes h, v or hv, not " + args.getFirst());
    };
    return () -> new FlipFilter(direction);
  }

  private static Supplier<VideoFilter> rotate(final List<String> args) {
    count("rotate", args, 1);
    final RotationFilter.Rotation rotation = switch (args.getFirst()) {
      case "90" -> RotationFilter.Rotation.ROTATE_90_CLOCKWISE;
      case "180" -> RotationFilter.Rotation.ROTATE_180;
      case "270" -> RotationFilter.Rotation.ROTATE_90_COUNTERCLOCKWISE;
      default -> throw new IllegalArgumentException("rotate takes 90, 180 or 270, not " + args.getFirst());
    };
    return () -> new RotationFilter(rotation);
  }

  private static Supplier<VideoFilter> crop(final List<String> args) {
    count("crop", args, 4);
    final Region region = region("crop", args);
    return () -> new RegionFilter(region, CropZoom::new);
  }

  private static Supplier<VideoFilter> rectangle(final List<String> args) {
    count("rectangle", args, 5);
    final Region region = region("rectangle", args);
    final double[] color = color("rectangle", args.get(4));
    return () -> new RegionFilter(region, (left, top, width, height, _, _) -> new RectangleFilter(left, top, width, height, color));
  }

  private static Supplier<VideoFilter> dilate(final List<String> args) {
    count("dilate", args, 1);
    final int kernel = whole("dilate", args.getFirst(), 1, MAX_MORPHOLOGY);
    return () -> new DilationFilter(kernel);
  }

  private static Supplier<VideoFilter> erode(final List<String> args) {
    count("erode", args, 1);
    final int kernel = whole("erode", args.getFirst(), 1, MAX_MORPHOLOGY);
    return () -> new ErosionFilter(kernel);
  }

  private static Supplier<VideoFilter> text(final List<String> args) {
    count("text", args, 1);
    final String text = args.getFirst();
    if (!TEXT.matcher(text).matches()) {
      throw new IllegalArgumentException("text takes up to %d letters, digits, spaces and .!?'()+-".formatted(MAX_TEXT));
    }
    return () -> new TextFilter(text, TEXT_X, TEXT_Y, TextFilter.DEFAULT_FONT, TEXT_SCALE, WHITE);
  }

  /** An overlay is a PNG file of the overlay folder, named without its folder or extension; links are not followed. */
  private static Supplier<VideoFilter> overlay(final List<String> args, final Path overlays) {
    count("overlay", args, 1);
    final String name = args.getFirst();
    if (!OVERLAY_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("overlay takes the name of a PNG file of the overlays folder, without .png");
    }
    final Path file = overlays.resolve(name + OVERLAY_SUFFIX);
    final BasicFileAttributes attributes;
    try {
      attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (final IOException missing) {
      throw new IllegalArgumentException(NO_OVERLAY.formatted(name), missing);
    }
    if (!attributes.isRegularFile()) {
      throw new IllegalArgumentException(NO_OVERLAY.formatted(name));
    }
    final long size = attributes.size();
    if (size > MAX_OVERLAY_BYTES) {
      throw new IllegalArgumentException("the overlay %s is larger than %d bytes".formatted(name, MAX_OVERLAY_BYTES));
    }
    return () -> new OverlayImageFilter(readOverlay(file), 0, 0);
  }

  /** Decodes an overlay, where the pictures are filtered, scaled down to at most the largest side. */
  private static ImageBuffer readOverlay(final Path file) {
    final ImageBuffer image = ImageBuffer.path(FileSource.path(file));
    final int side = Math.max(image.getWidth(), image.getHeight());
    if (side > MAX_OVERLAY_SIDE) {
      final int width = Math.max(1, (image.getWidth() * MAX_OVERLAY_SIDE) / side);
      final int height = Math.max(1, (image.getHeight() * MAX_OVERLAY_SIDE) / side);
      new ResizeFilter(width, height).applyFilter(image);
    }
    return image;
  }

  private static void count(final String name, final List<String> args, final int expected) {
    if (args.size() != expected) {
      throw new IllegalArgumentException("%s takes %d argument%s".formatted(name, expected, expected == 1 ? "" : "s"));
    }
  }

  private static int whole(final String name, final String text, final int min, final int max) {
    if (!WHOLE.matcher(text).matches()) {
      throw new IllegalArgumentException("%s takes a whole number from %d to %d, not %s".formatted(name, min, max, text));
    }
    final int value = Integer.parseInt(text);
    if (value < min || value > max) {
      throw new IllegalArgumentException("%s takes a whole number from %d to %d, not %s".formatted(name, min, max, text));
    }
    return value;
  }

  private static double decimal(final String name, final String text, final double max) {
    if (!DECIMAL.matcher(text).matches() || Double.parseDouble(text) > max) {
      throw new IllegalArgumentException("%s takes a number from 0 to %s, not %s".formatted(name, max, text));
    }
    return Double.parseDouble(text);
  }

  /** A colour typed as {@code rrggbb}, in the blue, green, red order of OpenCV. */
  private static double[] color(final String name, final String text) {
    if (!HEX.matcher(text).matches()) {
      throw new IllegalArgumentException("%s takes a colour such as ff8000, not %s".formatted(name, text));
    }
    final int rgb = Integer.parseInt(text, HEX_RADIX);
    return new double[] { rgb & BYTE, (rgb >> GREEN_SHIFT) & BYTE, (rgb >> RED_SHIFT) & BYTE };
  }

  private static Region region(final String name, final List<String> args) {
    final int left = whole(name, args.getFirst(), 0, PERCENT - 1);
    final int top = whole(name, args.get(1), 0, PERCENT - 1);
    final int width = whole(name, args.get(2), 1, PERCENT - left);
    final int height = whole(name, args.get(3), 1, PERCENT - top);
    return new Region(left, top, width, height);
  }

  /**
   * Makes new filters of the chain, which keep state between pictures, for one video or image.
   *
   * @return the filters, in order
   */
  public List<VideoFilter> create() {
    return this.filters.stream().map(Supplier::get).toList();
  }

  /**
   * Puts new filters of the chain in front of the steps that show the pictures.
   *
   * @param output the first step that shows the pictures
   * @return the first step of the filters, or the output if the chain is empty
   */
  public VideoPipelineStep prepend(final VideoPipelineStep output) {
    Preconditions.checkNotNull(output, "Output must not be null");
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
   */
  public void apply(final ImageBuffer picture) {
    Preconditions.checkNotNull(picture, "Picture must not be null");
    for (final VideoFilter filter : this.create()) {
      filter.applyFilter(picture);
    }
  }

  /**
   * Checks whether the chain has no filter.
   *
   * @return true if no filter is applied
   */
  public boolean isEmpty() {
    return this.filters.isEmpty();
  }

  /** A region in percent of the picture. */
  private record Region(int left, int top, int width, int height) {}

  /** Makes a filter for a region in pixels of pictures of a size. */
  @FunctionalInterface
  private interface RegionFactory {
    VideoFilter create(int left, int top, int width, int height, int pictureWidth, int pictureHeight);
  }

  /** A filter over a region given in percent, made again whenever the pictures change size. */
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
        final int regionWidth = Math.max(1, Math.min(pictureWidth - left, (pictureWidth * this.region.width()) / PERCENT));
        final int regionHeight = Math.max(1, Math.min(pictureHeight - top, (pictureHeight * this.region.height()) / PERCENT));
        current = this.factory.create(left, top, regionWidth, regionHeight, pictureWidth, pictureHeight);
        this.filter = current;
        this.width = pictureWidth;
        this.height = pictureHeight;
      }
      return current.applyFilter(samples, metadata);
    }
  }

  /** Crops a region and scales it back to the size of the picture, so every display gets the size it expects. */
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
