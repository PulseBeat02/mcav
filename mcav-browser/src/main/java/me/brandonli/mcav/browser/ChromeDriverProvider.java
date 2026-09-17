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
import io.github.bonigarcia.wdm.WebDriverManager;
import io.github.bonigarcia.wdm.config.Architecture;
import io.github.bonigarcia.wdm.config.Config;
import io.github.bonigarcia.wdm.config.OperatingSystem;
import io.github.bonigarcia.wdm.config.WebDriverManagerException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.stream.Stream;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.utils.IOUtils;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chromium.ChromiumDriverLogLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads the ChromeDriver that matches the installed Chrome and runs a single ChromeDriver process shared by
 * every {@link SeleniumPlayer}. The driver is cached in the MCAV cache folder, so only the first start of a
 * machine downloads anything.
 */
final class ChromeDriverProvider {

  private static final Logger LOGGER = LoggerFactory.getLogger(ChromeDriverProvider.class);
  private static final Object LOCK = new Object();
  // held strongly so the level set on it is not lost when java.util.logging collects unused loggers
  private static final java.util.logging.Logger SELENIUM_LOGGER = java.util.logging.Logger.getLogger("org.openqa.selenium");

  // WebDriverManager caches drivers as <cache>/chromedriver/<platform>/<version>/<driver>
  private static final String DRIVER_FOLDER = "chromedriver";
  private static final File[] NO_FILES = new File[0];

  private static @Nullable ChromeDriverService service;
  private static boolean prepared;

  private ChromeDriverProvider() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Resolves and downloads the driver without starting it. Safe to call more than once.
   *
   * @throws PlayerException if the driver can neither be resolved nor be found in the cache
   */
  static void prepare() {
    synchronized (LOCK) {
      if (prepared) {
        return;
      }
      final long start = System.currentTimeMillis();
      quietSeleniumLogging();
      final Path cache = IOUtils.getCachedFolder();
      final Path driverDirectory = cache.resolve("driver");
      final WebDriverManager manager = WebDriverManager.chromedriver();
      setUpDriver(manager, driverDirectory);
      prepared = true;
      final long end = System.currentTimeMillis();
      final long elapsed = end - start;
      LOGGER.info("ChromeDriver ready in {} ms", elapsed);
    }
  }

  /**
   * Gets the file name of ChromeDriver on an operating system.
   *
   * @param os the operating system
   * @return {@code chromedriver.exe} on Windows, {@code chromedriver} everywhere else
   */
  @VisibleForTesting
  static String driverFileName(final OperatingSystem os) {
    final boolean windows = os.isWin();
    return windows ? "chromedriver.exe" : "chromedriver";
  }

  /**
   * Gets the name of the cache folder WebDriverManager keeps the drivers of a platform in, such as {@code win64},
   * {@code linux64}, {@code mac64}, or {@code mac-arm64}.
   *
   * @param os           the operating system
   * @param architecture the processor architecture
   * @return the folder name
   */
  @VisibleForTesting
  static String platformFolder(final OperatingSystem os, final Architecture architecture) {
    final String osName = os.getName();
    final String architectureName = architecture.toString();
    final String lowerArchitecture = architectureName.toLowerCase(Locale.ROOT);
    // WebDriverManager separates the ARM architecture from the operating system with a dash
    final String separator = architecture == Architecture.ARM64 ? "-" : "";
    return osName + separator + lowerArchitecture;
  }

  /**
   * Resolves ChromeDriver into the driver cache. The latest driver matching the installed Chrome is resolved online;
   * when that is impossible, for example on a server without internet access, a driver already in the cache for this
   * platform is used instead: the newest one made for the installed version of Chrome or an older one, or the newest
   * one at all if the version of Chrome is unknown or every cached driver is newer.
   *
   * @param manager         the WebDriverManager for ChromeDriver
   * @param driverDirectory the directory drivers are cached in
   * @throws PlayerException if the driver can neither be resolved nor be found in the cache
   */
  @VisibleForTesting
  static void setUpDriver(final WebDriverManager manager, final Path driverDirectory) {
    final String driverPath = driverDirectory.toString();
    manager.cachePath(driverPath);
    try {
      manager.setup();
    } catch (final WebDriverManagerException exception) {
      useCachedDriver(manager, driverDirectory, exception);
    }
  }

  private static void useCachedDriver(final WebDriverManager manager, final Path driverDirectory, final WebDriverManagerException failure) {
    final Config config = manager.config();
    final OperatingSystem os = config.getOperatingSystem();
    final Architecture architecture = config.getArchitecture();
    final String platform = platformFolder(os, architecture);
    final String fileName = driverFileName(os);
    // the version of Chrome is detected before the driver is looked up online, so it is known even offline
    final String browserVersion = manager.getResolvedBrowserVersion();
    final Optional<Path> cached = findCachedDriver(driverDirectory, platform, fileName, browserVersion);
    if (cached.isEmpty()) {
      throw new PlayerException(
        "ChromeDriver could not be resolved and no driver for " + platform + " is cached in " + driverDirectory,
        failure
      );
    }
    final Path driver = cached.get();
    final String reason = failure.getMessage();
    LOGGER.warn("Could not resolve the latest ChromeDriver ({}), using the cached driver {}", reason, driver);
    final String rawDriver = driver.toString();
    System.setProperty(ChromeDriverService.CHROME_DRIVER_EXE_PROPERTY, rawDriver);
  }

  /**
   * Finds the cached ChromeDriver to use for a platform, whose drivers lie in
   * {@code chromedriver/<platform>/<version>/}. Among the drivers whose major version is no higher than the major
   * version of Chrome, the newest is picked; if there is none, or the version of Chrome is unknown, the newest driver
   * of the platform is picked.
   *
   * @param driverDirectory the directory drivers are cached in
   * @param platform        the platform folder, see {@link #platformFolder(OperatingSystem, Architecture)}
   * @param fileName        the file name of the driver
   * @param browserVersion  the version of the installed Chrome, or null if unknown
   * @return the driver, or empty if the cache holds no driver for the platform
   */
  @VisibleForTesting
  static Optional<Path> findCachedDriver(
    final Path driverDirectory,
    final String platform,
    final String fileName,
    final @Nullable String browserVersion
  ) {
    final Path driverRoot = driverDirectory.resolve(DRIVER_FOLDER);
    final Path platformDirectory = driverRoot.resolve(platform);
    final List<Path> drivers = listDrivers(platformDirectory, fileName);

    final long browserMajor = browserVersion == null ? Long.MAX_VALUE : majorVersion(browserVersion);
    final List<Path> compatible = new ArrayList<>();
    for (final Path driver : drivers) {
      final String versionName = getVersion(driver);
      final long driverMajor = majorVersion(versionName);
      if (driverMajor <= browserMajor) {
        compatible.add(driver);
      }
    }

    final List<Path> candidates = compatible.isEmpty() ? drivers : compatible;
    final Comparator<Path> byVersion = Comparator.comparing(ChromeDriverProvider::getVersion, ChromeDriverProvider::compareVersions);
    final Stream<Path> candidateStream = candidates.stream();
    return candidateStream.max(byVersion);
  }

  /**
   * Lists the cached drivers of a platform, one in each version folder that holds a driver file.
   *
   * @param platformDirectory the platform folder
   * @param fileName          the file name of the driver
   * @return the driver files
   */
  private static List<Path> listDrivers(final Path platformDirectory, final String fileName) {
    final File platformFile = platformDirectory.toFile();
    final File[] versions = listDirectories(platformFile);
    final List<Path> drivers = new ArrayList<>();
    for (final File version : versions) {
      final File driver = new File(version, fileName);
      final boolean file = driver.isFile();
      if (file) {
        final Path driverPath = driver.toPath();
        drivers.add(driverPath);
      }
    }
    return drivers;
  }

  private static File[] listDirectories(final File directory) {
    final File[] children = directory.listFiles(File::isDirectory);
    return Objects.requireNonNullElse(children, NO_FILES);
  }

  private static String getVersion(final Path driver) {
    final Path parentOrNull = driver.getParent();
    final Path versionDirectory = Objects.requireNonNull(parentOrNull, "A cached driver lies in a version folder");
    final Path versionName = versionDirectory.getFileName();
    return Objects.toString(versionName, "");
  }

  private static long majorVersion(final String version) {
    final String[] parts = version.split("\\.");
    return parsePart(parts, 0);
  }

  /**
   * Compares two version strings part by part as numbers, so {@code 100.0} is newer than {@code 99.9}. Parts that
   * are not numbers count as zero.
   *
   * @param first  the first version
   * @param second the second version
   * @return a negative number, zero or a positive number if the first version is older, equal or newer
   */
  @VisibleForTesting
  static int compareVersions(final String first, final String second) {
    final String[] firstParts = first.split("\\.");
    final String[] secondParts = second.split("\\.");
    final int length = Math.max(firstParts.length, secondParts.length);
    for (int index = 0; index < length; index++) {
      final long firstPart = parsePart(firstParts, index);
      final long secondPart = parsePart(secondParts, index);
      final int comparison = Long.compare(firstPart, secondPart);
      if (comparison != 0) {
        return comparison;
      }
    }
    return 0;
  }

  private static long parsePart(final String[] parts, final int index) {
    if (index >= parts.length) {
      return 0L;
    }
    try {
      return Long.parseLong(parts[index]);
    } catch (final NumberFormatException exception) {
      return 0L;
    }
  }

  // Selenium logs every protocol message through java.util.logging; the host's logging setup is left alone
  private static void quietSeleniumLogging() {
    SELENIUM_LOGGER.setLevel(Level.WARNING);
  }

  /**
   * Gets the shared driver process, starting it on first use.
   *
   * @return the running service
   * @throws PlayerException if the driver cannot be resolved or started
   */
  static ChromeDriverService getService() {
    synchronized (LOCK) {
      prepare();
      final ChromeDriverService current = service;
      if (current != null && current.isRunning()) {
        return current;
      }
      if (current != null) {
        // a stopped service still holds its process handle and port; replacing it without closing leaks one per
        // browser restart
        current.close();
        service = null;
      }
      final ChromeDriverService.Builder builder = new ChromeDriverService.Builder();
      builder.withLogLevel(ChromiumDriverLogLevel.WARNING);
      builder.usingAnyFreePort();
      final ChromeDriverService created = builder.build();
      startService(created);
      service = created;
      return created;
    }
  }

  /**
   * Starts a ChromeDriver service.
   *
   * @param created the service
   * @throws PlayerException if the service cannot be started
   */
  @VisibleForTesting
  static void startService(final ChromeDriverService created) {
    try {
      created.start();
    } catch (final IOException exception) {
      final String message = exception.getMessage();
      throw new PlayerException("Failed to start ChromeDriver: " + message, exception);
    }
  }

  /**
   * Stops the shared driver process if it is running.
   */
  static void shutdown() {
    synchronized (LOCK) {
      final ChromeDriverService current = service;
      service = null;
      if (current == null) {
        return;
      }
      final boolean running = current.isRunning();
      if (running) {
        current.stop();
      }
      current.close();
    }
  }
}
