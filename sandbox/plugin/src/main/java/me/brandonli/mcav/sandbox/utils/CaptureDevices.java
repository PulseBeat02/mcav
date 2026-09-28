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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import me.brandonli.mcav.utils.ThrowableUtils;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.OSUtils;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.OpenCVFrameGrabber;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The cameras and capture cards of the server, which the DEVICE player opens by number with OpenCV. A device belongs
 * to the server, so the plugin lists them with {@code /mcav video devices} and plays only a listed number. On Linux a
 * number is that of {@code /dev/video<number>}, named in sysfs, and listing opens nothing; elsewhere the first numbers
 * are tried, which briefly opens every device there is.
 */
public final class CaptureDevices {

  /** The permission to list and play capture devices. */
  public static final String PERMISSION = "mcav.command.video.device";

  /** Where Linux describes its video devices. */
  static final Path SYSFS = Path.of("/sys/class/video4linux");

  /** How many numbers are tried where the system does not list its devices. */
  static final int PROBED = 8;

  /** The longest name shown, which sysfs does not bound. */
  static final int MAX_NAME = 64;

  private static final Pattern NODE = Pattern.compile("video(\\d{1,3})");

  private CaptureDevices() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * A capture device.
   *
   * @param index the number the DEVICE player opens it by
   * @param name  what the system calls it
   */
  public record Device(int index, String name) {}

  /**
   * Lists the capture devices of the server. Off the main thread: trying devices takes a moment.
   *
   * @return the devices, by number
   */
  public static List<Device> list() {
    return OSUtils.getOS() == OS.LINUX ? listSysfs(SYSFS) : probe(PROBED, CaptureDevices::opens);
  }

  /**
   * Lists the video devices sysfs describes.
   *
   * @param folder the sysfs folder of video4linux
   * @return the devices, by number; none if the folder cannot be read
   */
  static List<Device> listSysfs(final Path folder) {
    if (!Files.isDirectory(folder)) {
      return List.of();
    }
    final List<Device> devices = new ArrayList<>();
    try (Stream<Path> nodes = Files.list(folder)) {
      for (final Path node : (Iterable<Path>) nodes::iterator) {
        final Device device = readNode(node);
        if (device != null) {
          devices.add(device);
        }
      }
    } catch (final IOException exception) {
      return List.of();
    }
    devices.sort(Comparator.comparingInt(Device::index));
    return List.copyOf(devices);
  }

  private static @Nullable Device readNode(final Path node) {
    final Matcher matcher = NODE.matcher(String.valueOf(node.getFileName()));
    if (!matcher.matches()) {
      return null;
    }
    final int index = Integer.parseInt(Objects.requireNonNull(matcher.group(1)));
    String name;
    try {
      name = Files.readString(node.resolve("name"), StandardCharsets.UTF_8).strip();
    } catch (final IOException exception) {
      name = "";
    }
    final String shown = name.isEmpty() ? matcher.group() : name.substring(0, Math.min(name.length(), MAX_NAME));
    return new Device(index, shown);
  }

  /**
   * Tries the first numbers.
   *
   * @param count  how many numbers
   * @param opens  whether the device of a number opens
   * @return the devices that opened, named by their number
   */
  static List<Device> probe(final int count, final IntPredicate opens) {
    Preconditions.checkArgument(count >= 0, "Count must not be negative");
    final List<Device> devices = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      if (opens.test(index)) {
        devices.add(new Device(index, "device " + index));
      }
    }
    return List.copyOf(devices);
  }

  /** Whether the device of a number opens, which briefly opens it. */
  static boolean opens(final int index) {
    return opens(OpenCVFrameGrabber::new, index);
  }

  /**
   * Whether the device of a number opens, which briefly opens it.
   *
   * @param grabbers makes the grabber of a number
   * @param index    the number
   * @return true if its grabber started
   */
  static boolean opens(final IntFunction<? extends FrameGrabber> grabbers, final int index) {
    try (FrameGrabber grabber = grabbers.apply(index)) {
      grabber.start();
      return true;
    } catch (final IOException | RuntimeException | LinkageError exception) {
      ThrowableUtils.throwIfFatal(exception);
      return false;
    }
  }

  /**
   * Finds a listed device by the number a sender typed.
   *
   * @param devices the devices listed
   * @param typed   the number as typed
   * @return the device, or null if the text is not the number of a listed device
   */
  public static @Nullable Device find(final List<Device> devices, final String typed) {
    Preconditions.checkNotNull(devices, "Devices must not be null");
    Preconditions.checkNotNull(typed, "Typed number must not be null");
    for (final Device device : devices) {
      if (Integer.toString(device.index()).equals(typed)) {
        return device;
      }
    }
    return null;
  }
}
