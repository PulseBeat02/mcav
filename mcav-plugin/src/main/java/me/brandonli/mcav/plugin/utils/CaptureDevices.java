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

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.OsUtils;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.OpenCVFrameGrabber;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The cameras and capture cards of the server, which the DEVICE player opens by number with OpenCV. A device belongs
 * to the server, so the plugin lists them with {@code /mcav video devices} and plays only a listed number. On Linux a
 * number is that of {@code /dev/video<number>}, named in sysfs, and listing opens nothing; elsewhere the first numbers
 * are tried, which briefly opens every device there is.
 *
 * <p>This class is stateless and thread-safe.
 *
 * @since 1.0.0
 */
public final class CaptureDevices {

  /**
   * The permission to list and play capture devices.
   *
   * @since 1.0.0
   */
  public static final String PERMISSION = "mcav.command.video.device";

  static final Path SYSFS = Path.of("/sys/class/video4linux");

  // device numbers tried where the system lists no devices
  private static final int PROBED = 8;

  // sysfs does not bound device names
  static final int MAXIMUM_NAME = 64;

  private static final Pattern NODE = Pattern.compile("video(\\d{1,3})");

  private static final String NAME_FILE = "name";

  private CaptureDevices() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  /**
   * Lists the capture devices of the server. Off the main thread: trying devices takes a moment.
   *
   * @return the devices, by number
   * @since 1.0.0
   */
  public static List<Device> list() {
    return list(OsUtils.getOs(), SYSFS, CaptureDevices::opens);
  }

  static List<Device> list(final Os os, final Path sysfs, final IntPredicate opens) {
    return os == Os.LINUX ? listSysfs(sysfs) : probe(PROBED, opens);
  }

  static List<Device> listSysfs(final Path folder) {
    if (!Files.isDirectory(folder)) {
      return List.of();
    }

    final List<Device> devices = new ArrayList<>();
    try (final Stream<Path> nodes = Files.list(folder)) {
      for (final Path node : (Iterable<Path>) nodes::iterator) {
        addDeviceAt(devices, node);
      }
    } catch (final IOException exception) {
      return List.of();
    }
    devices.sort(Comparator.comparingInt(Device::getIndex));
    return List.copyOf(devices);
  }

  private static void addDeviceAt(final List<Device> devices, final Path node) {
    final Device device = readNode(node);
    if (device != null) {
      devices.add(device);
    }
  }

  private static @Nullable Device readNode(final Path node) {
    final Matcher matcher = NODE.matcher(String.valueOf(node.getFileName()));
    if (!matcher.matches()) {
      return null;
    }

    final int index = Integer.parseInt(Objects.requireNonNull(matcher.group(1)));
    String name;
    try {
      name = Files.readString(node.resolve(NAME_FILE), StandardCharsets.UTF_8).strip();
    } catch (final IOException exception) {
      name = "";
    }
    final String shown = name.isEmpty() ? matcher.group() : name.substring(0, Math.min(name.length(), MAXIMUM_NAME));
    return Device.of(index, shown);
  }

  static List<Device> probe(final int count, final IntPredicate opens) {
    Preconditions.checkArgument(count >= 0, "Count must not be negative!");

    final List<Device> devices = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      if (opens.test(index)) {
        devices.add(Device.of(index, "device " + index));
      }
    }
    return List.copyOf(devices);
  }

  static boolean opens(final int index) {
    return opens(OpenCVFrameGrabber::new, index);
  }

  static boolean opens(final IntFunction<? extends FrameGrabber> grabbers, final int index) {
    try (final FrameGrabber grabber = grabbers.apply(index)) {
      grabber.start();

      return true;
    } catch (final IOException | RuntimeException | LinkageError exception) {
      return false;
    }
  }

  /**
   * Finds a listed device by the number a sender typed.
   *
   * @param devices the devices listed
   * @param typed   the number as typed
   * @return the device, or empty if the text is not the number of a listed device
   * @throws NullPointerException if an argument is null
   * @since 1.0.0
   */
  public static Optional<Device> find(final List<Device> devices, final String typed) {
    Preconditions.checkNotNull(devices, "Devices must not be null!");
    Preconditions.checkNotNull(typed, "Typed number must not be null!");

    for (final Device device : devices) {
      if (Integer.toString(device.getIndex()).equals(typed)) {
        return Optional.of(device);
      }
    }
    return Optional.empty();
  }

  /**
   * A capture device.
   *
   * <p>This class is immutable and thread-safe.
   *
   * @since 1.0.0
   */
  public static final class Device {

    private final int index;
    private final String name;

    private Device(final int index, final String name) {
      this.index = index;
      this.name = name;
    }

    /**
     * Creates a device.
     *
     * @param index the number the DEVICE player opens it by
     * @param name  what the system calls it
     * @return the device
     * @throws NullPointerException if {@code name} is null
     * @since 1.0.0
     */
    public static Device of(final int index, final String name) {
      Preconditions.checkNotNull(name, "Name must not be null!");

      return new Device(index, name);
    }

    /**
     * Returns the number the DEVICE player opens the device by.
     *
     * @return the number
     * @since 1.0.0
     */
    public int getIndex() {
      return this.index;
    }

    /**
     * Returns what the system calls the device.
     *
     * @return the name
     * @since 1.0.0
     */
    public String getName() {
      return this.name;
    }

    /**
     * Returns whether {@code other} is a device with the same index and name.
     *
     * @param other the object to compare with
     * @return whether both match
     * @since 1.0.0
     */
    @Override
    public boolean equals(final @Nullable Object other) {
      if (this == other) {
        return true;
      }

      if (!(other instanceof final Device device)) {
        return false;
      }

      return this.index == device.index && this.name.equals(device.name);
    }

    /**
     * Returns a hash code built from the index and the name.
     *
     * @return the hash code
     * @since 1.0.0
     */
    @Override
    public int hashCode() {
      return Objects.hash(this.index, this.name);
    }

    /**
     * Returns a debugging representation with the index and the name.
     *
     * @return the representation
     * @since 1.0.0
     */
    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("index", this.index).add("name", this.name).toString();
    }
  }
}
