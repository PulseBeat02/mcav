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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.bonigarcia.wdm.WebDriverManager;
import io.github.bonigarcia.wdm.config.Architecture;
import io.github.bonigarcia.wdm.config.Config;
import io.github.bonigarcia.wdm.config.OperatingSystem;
import io.github.bonigarcia.wdm.config.WebDriverManagerException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import me.brandonli.mcav.media.player.PlayerException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests how {@link ChromeDriverProvider} falls back to a cached ChromeDriver when the latest one cannot be resolved.
 */
final class ChromeDriverFallbackTest {

  private static final String DRIVER_PROPERTY = "webdriver.chrome.driver";
  private static final String WINDOWS_DRIVER = "chromedriver.exe";
  private static final String UNIX_DRIVER = "chromedriver";

  @TempDir
  private Path cache;

  private String previousDriver;

  @BeforeEach
  void rememberDriverProperty() {
    this.previousDriver = System.getProperty(DRIVER_PROPERTY);
  }

  @AfterEach
  void restoreDriverProperty() {
    if (this.previousDriver == null) {
      System.clearProperty(DRIVER_PROPERTY);
      return;
    }
    System.setProperty(DRIVER_PROPERTY, this.previousDriver);
  }

  private static WebDriverManager offlineManager(final String os, final Architecture architecture, final String browserVersion) {
    final WebDriverManager manager = mock(WebDriverManager.class);
    final WebDriverManagerException offline = new WebDriverManagerException("Unable to resolve the ChromeDriver version");
    doThrow(offline).when(manager).setup();
    final Config config = new Config();
    config.setOs(os);
    config.setArchitecture(architecture);
    when(manager.config()).thenReturn(config);
    when(manager.getResolvedBrowserVersion()).thenReturn(browserVersion);
    return manager;
  }

  private Path createDriver(final String versionDirectory, final String fileName) throws IOException {
    final Path directory = this.cache.resolve(versionDirectory);
    Files.createDirectories(directory);
    final Path driver = directory.resolve(fileName);
    Files.writeString(driver, "driver");
    final Path unrelated = directory.resolve("LICENSE.chromedriver");
    Files.writeString(unrelated, "license");
    return driver;
  }

  private String chosenDriver(final WebDriverManager manager) {
    ChromeDriverProvider.setUpDriver(manager, this.cache);
    return System.getProperty(DRIVER_PROPERTY);
  }

  @Test
  void resolvesTheDriverIntoTheCacheWhenOnline() {
    final WebDriverManager manager = mock(WebDriverManager.class);
    ChromeDriverProvider.setUpDriver(manager, this.cache);
    final String cachePath = this.cache.toString();
    verify(manager).cachePath(cachePath);
    verify(manager).setup();
  }

  @Test
  void usesOnlyTheDriversOfTheCurrentPlatform() throws IOException {
    final Path expected = this.createDriver("chromedriver/mac-arm64/120.0.1", UNIX_DRIVER);
    // Intel Macs share the file name of the driver, so a newer Intel driver is the most likely wrong pick
    this.createDriver("chromedriver/mac64/121.0.0", UNIX_DRIVER);
    this.createDriver("chromedriver/linux64/130.0.0", UNIX_DRIVER);
    this.createDriver("chromedriver/win64/140.0.0", WINDOWS_DRIVER);
    final WebDriverManager manager = offlineManager("MAC", Architecture.ARM64, "121.0.6167.85");
    final String driver = this.chosenDriver(manager);
    final String expectedDriver = expected.toString();
    assertEquals(expectedDriver, driver);
  }

  @Test
  void prefersTheNewestDriverTheInstalledChromeSupports() throws IOException {
    this.createDriver("chromedriver/win64/119.0.6045.105", WINDOWS_DRIVER);
    final Path expected = this.createDriver("chromedriver/win64/120.0.6099.109", WINDOWS_DRIVER);
    this.createDriver("chromedriver/win64/120.0.6099.71", WINDOWS_DRIVER);
    this.createDriver("chromedriver/win64/121.0.6167.85", WINDOWS_DRIVER);
    final WebDriverManager manager = offlineManager("WIN", Architecture.X64, "120.0.6099.200");
    final String driver = this.chosenDriver(manager);
    final String expectedDriver = expected.toString();
    assertEquals(expectedDriver, driver);
  }

  @Test
  void usesTheNewestDriverWhenEveryDriverIsNewerThanChrome() throws IOException {
    this.createDriver("chromedriver/linux64/120.0.1", UNIX_DRIVER);
    final Path newest = this.createDriver("chromedriver/linux64/121.0.1", UNIX_DRIVER);
    final WebDriverManager manager = offlineManager("LINUX", Architecture.X64, "100.0.4896.60");
    final String driver = this.chosenDriver(manager);
    final String expectedDriver = newest.toString();
    assertEquals(expectedDriver, driver);
  }

  @Test
  void usesTheNewestDriverWhenTheVersionOfChromeIsUnknown() throws IOException {
    this.createDriver("chromedriver/linux-arm64/99.0.9", UNIX_DRIVER);
    final Path newest = this.createDriver("chromedriver/linux-arm64/100.0.1", UNIX_DRIVER);
    final WebDriverManager unknown = offlineManager("LINUX", Architecture.ARM64, null);
    final String unknownDriver = this.chosenDriver(unknown);
    final WebDriverManager unreadable = offlineManager("LINUX", Architecture.ARM64, "beta");
    final String unreadableDriver = this.chosenDriver(unreadable);
    final String expectedDriver = newest.toString();
    assertEquals(expectedDriver, unknownDriver);
    assertEquals(expectedDriver, unreadableDriver);
  }

  @Test
  void failsWhenOnlyAnotherPlatformHasACachedDriver() throws IOException {
    this.createDriver("chromedriver/linux64/130.0.0", UNIX_DRIVER);
    this.createDriver("chromedriver/win32/130.0.0", WINDOWS_DRIVER);
    final WebDriverManager manager = offlineManager("WIN", Architecture.X64, "130.0.6723.58");
    final PlayerException exception = assertThrows(PlayerException.class, () -> ChromeDriverProvider.setUpDriver(manager, this.cache));
    final String message = exception.getMessage();
    final boolean namesPlatform = message.contains("no driver for win64 is cached");
    assertTrue(namesPlatform, message);
  }

  @Test
  void failsWhenNothingIsCached() throws IOException {
    final Path emptyVersion = this.cache.resolve("chromedriver/win64/1.0");
    final Path resolution = this.cache.resolve("resolution.properties");
    Files.createDirectories(emptyVersion);
    Files.writeString(resolution, "");
    final WebDriverManager manager = offlineManager("WIN", Architecture.X64, "1.0");
    final PlayerException exception = assertThrows(PlayerException.class, () -> ChromeDriverProvider.setUpDriver(manager, this.cache));
    final Throwable cause = exception.getCause();
    final String message = exception.getMessage();
    final String cachePath = this.cache.toString();
    final boolean namesCache = message.contains(cachePath);
    assertInstanceOf(WebDriverManagerException.class, cause);
    assertTrue(namesCache, message);
  }

  @Test
  void findsNothingInAMissingCache() {
    final Path missing = this.cache.resolve("missing");
    final Optional<Path> driver = ChromeDriverProvider.findCachedDriver(missing, "win64", WINDOWS_DRIVER, null);
    final boolean empty = driver.isEmpty();
    assertTrue(empty);
  }

  @Test
  void comparesVersionFoldersNumerically() throws IOException {
    this.createDriver("chromedriver/win64/99.0.9", WINDOWS_DRIVER);
    final Path newest = this.createDriver("chromedriver/win64/100.0.1", WINDOWS_DRIVER);
    this.createDriver("chromedriver/win64/unknown", WINDOWS_DRIVER);
    final Optional<Path> driver = ChromeDriverProvider.findCachedDriver(this.cache, "win64", WINDOWS_DRIVER, null);
    final Optional<Path> expected = Optional.of(newest);
    assertEquals(expected, driver);
  }

  @Test
  void ignoresFoldersNamedLikeTheDriver() throws IOException {
    final Path versionFolder = this.cache.resolve("chromedriver/win64/200.0");
    final Path fakeDriver = versionFolder.resolve(WINDOWS_DRIVER);
    Files.createDirectories(fakeDriver);
    final Path realDriver = this.createDriver("chromedriver/win64/100.0", WINDOWS_DRIVER);
    final Optional<Path> driver = ChromeDriverProvider.findCachedDriver(this.cache, "win64", WINDOWS_DRIVER, null);
    final Optional<Path> expected = Optional.of(realDriver);
    assertEquals(expected, driver);
  }

  @Test
  void comparesVersionsPartByPart() {
    final int equal = ChromeDriverProvider.compareVersions("152.0.7977.82", "152.0.7977.82");
    final int trailingZero = ChromeDriverProvider.compareVersions("152.0", "152.0.0");
    final int longerIsNewer = ChromeDriverProvider.compareVersions("152.0.1", "152.0");
    final int numeric = ChromeDriverProvider.compareVersions("10.0", "9.9");
    final int notANumber = ChromeDriverProvider.compareVersions("beta", "0");
    assertEquals(0, equal);
    assertEquals(0, trailingZero);
    assertTrue(longerIsNewer > 0);
    assertTrue(numeric > 0);
    assertEquals(0, notANumber);
  }

  @Test
  void namesTheDriverAfterThePlatform() {
    final String windows = ChromeDriverProvider.driverFileName(OperatingSystem.WIN);
    final String linux = ChromeDriverProvider.driverFileName(OperatingSystem.LINUX);
    final String mac = ChromeDriverProvider.driverFileName(OperatingSystem.MAC);
    assertEquals(WINDOWS_DRIVER, windows);
    assertEquals(UNIX_DRIVER, linux);
    assertEquals(UNIX_DRIVER, mac);
  }

  @Test
  void namesThePlatformFoldersLikeWebDriverManager() {
    final String windows64 = ChromeDriverProvider.platformFolder(OperatingSystem.WIN, Architecture.X64);
    final String windows32 = ChromeDriverProvider.platformFolder(OperatingSystem.WIN, Architecture.X32);
    final String linux = ChromeDriverProvider.platformFolder(OperatingSystem.LINUX, Architecture.X64);
    final String linuxArm = ChromeDriverProvider.platformFolder(OperatingSystem.LINUX, Architecture.ARM64);
    final String intelMac = ChromeDriverProvider.platformFolder(OperatingSystem.MAC, Architecture.X64);
    final String appleSilicon = ChromeDriverProvider.platformFolder(OperatingSystem.MAC, Architecture.ARM64);
    assertEquals("win64", windows64);
    assertEquals("win32", windows32);
    assertEquals("linux64", linux);
    assertEquals("linux-arm64", linuxArm);
    assertEquals("mac64", intelMac);
    assertEquals("mac-arm64", appleSilicon);
  }
}
