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
package me.brandonli.mcav.browser;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.OSUtils;

/**
 * Builds the command line Chrome is started with by the Selenium backend.
 *
 * <p>Chrome runs headless unless the caller chooses a headless mode or asks for a window with
 * {@link BrowserPlayer#SHOW_WINDOW}, so a server without a display never tries to open one. On Linux Chrome writes its
 * shared memory to {@code /tmp} instead of {@code /dev/shm}, which is only 64 MB in a Docker container, and it runs
 * without its sandbox as root or in a container, where Chrome refuses to start or cannot create the sandbox.
 */
final class ChromeArguments {

  private static final String HEADLESS = "--headless=new";
  private static final String HEADLESS_PREFIX = "--headless";
  private static final String NO_SANDBOX = "--no-sandbox";
  private static final String NO_DEV_SHM = "--disable-dev-shm-usage";
  private static final Path PROCESS_DIRECTORY = Path.of("/proc/self");
  private static final List<Path> CONTAINER_MARKERS = List.of(Path.of("/.dockerenv"), Path.of("/run/.containerenv"));

  private ChromeArguments() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Builds the arguments Chrome is started with on this machine.
   *
   * @param requested the arguments the caller passed
   * @return the arguments for Chrome
   */
  static List<String> resolve(final String[] requested) {
    final List<String> platform = detectPlatformArguments();
    return resolve(requested, platform);
  }

  /**
   * Builds the arguments Chrome is started with: {@code --headless=new} unless the caller chose a headless mode or
   * passed {@link BrowserPlayer#SHOW_WINDOW}, then the arguments of the caller without {@link BrowserPlayer#SHOW_WINDOW},
   * then the platform arguments the caller did not pass.
   *
   * @param requested the arguments the caller passed
   * @param platform  the arguments the platform needs
   * @return the arguments for Chrome
   */
  @VisibleForTesting
  static List<String> resolve(final String[] requested, final List<String> platform) {
    final List<String> arguments = new ArrayList<>();
    boolean headlessChosen = false;
    for (final String argument : requested) {
      final boolean showWindow = argument.equals(BrowserPlayer.SHOW_WINDOW);
      final boolean headless = argument.startsWith(HEADLESS_PREFIX);
      headlessChosen = headlessChosen || showWindow || headless;
      if (!showWindow) {
        arguments.add(argument);
      }
    }
    if (!headlessChosen) {
      arguments.addFirst(HEADLESS);
    }
    for (final String argument : platform) {
      final boolean present = arguments.contains(argument);
      if (!present) {
        arguments.add(argument);
      }
    }
    return List.copyOf(arguments);
  }

  /**
   * Picks the arguments Chrome needs on a platform.
   *
   * @param os        the operating system
   * @param root      whether the process runs as root
   * @param container whether the process runs in a container
   * @return {@code --disable-dev-shm-usage} on Linux, with {@code --no-sandbox} as root or in a container
   */
  @VisibleForTesting
  static List<String> platformArguments(final OS os, final boolean root, final boolean container) {
    if (os != OS.LINUX) {
      return List.of();
    }
    if (root || container) {
      return List.of(NO_DEV_SHM, NO_SANDBOX);
    }
    return List.of(NO_DEV_SHM);
  }

  /**
   * Picks the arguments Chrome needs on this machine.
   *
   * @return the platform arguments
   */
  @VisibleForTesting
  static List<String> detectPlatformArguments() {
    final OS os = OSUtils.getOS();
    final boolean root = isRoot(() -> Files.getAttribute(PROCESS_DIRECTORY, "unix:uid"));
    final boolean container = isContainer(CONTAINER_MARKERS);
    return platformArguments(os, root, container);
  }

  /**
   * Checks whether the process runs as root.
   *
   * @param userIdReader reads the user id of the process
   * @return true if the user id is 0, false if it is another id or cannot be read
   */
  @VisibleForTesting
  static boolean isRoot(final UserIdReader userIdReader) {
    try {
      final Object value = userIdReader.read();
      final Integer rootId = 0;
      return rootId.equals(value);
    } catch (final IOException | UnsupportedOperationException | IllegalArgumentException exception) {
      // Windows has no user ids, and a missing /proc means the process is not on Linux
      return false;
    }
  }

  /**
   * Checks whether the process runs in a container, which Docker and Podman mark with a file in the root folder.
   *
   * @param markers the marker files
   * @return true if any marker exists
   */
  @VisibleForTesting
  static boolean isContainer(final List<Path> markers) {
    for (final Path marker : markers) {
      final boolean exists = Files.exists(marker);
      if (exists) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reads the user id of the process.
   */
  @FunctionalInterface
  interface UserIdReader {
    /**
     * Reads the user id.
     *
     * @return the user id, an {@link Integer} on Unix
     * @throws IOException                   if the id cannot be read
     * @throws UnsupportedOperationException if the file system has no user ids
     */
    Object read() throws IOException;
  }
}
