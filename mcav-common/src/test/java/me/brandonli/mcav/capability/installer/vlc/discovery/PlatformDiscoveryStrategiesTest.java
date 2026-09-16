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
package me.brandonli.mcav.capability.installer.vlc.discovery;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import me.brandonli.mcav.capability.installer.vlc.discovery.DirectoryProviderDiscoveryStrategy.EnvironmentSetter;
import me.brandonli.mcav.capability.installer.vlc.discovery.DirectoryProviderDiscoveryStrategy.SearchProvider;
import me.brandonli.mcav.capability.installer.vlc.discovery.DirectoryProviderDiscoveryStrategyTest.StaticDirectoryProvider;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.OSUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import uk.co.caprica.vlcj.binding.lib.LibC;
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil;
import uk.co.caprica.vlcj.factory.discovery.strategy.NativeDiscoveryStrategy;

/**
 * Tests the platform strategies {@link LinuxNativeDiscoveryStrategy}, {@link OsxNativeDiscoveryStrategy} and
 * {@link WindowsNativeDiscoveryStrategy}, together with {@link VLCDiscoveryStrategies}, {@link FixedDirectoryProvider}
 * and {@link EnvironmentVariables}.
 */
final class PlatformDiscoveryStrategiesTest {

  private static final EnvironmentSetter IGNORING_SETTER = (_, _) -> true;
  private static final String TEST_VARIABLE = "MCAV_DISCOVERY_TEST";
  private static final String[] EVERY_LIBRARY = {
    "libvlc.so",
    "libvlccore.so",
    "libvlc.dylib",
    "libvlccore.dylib",
    "libvlc.dll",
    "libvlccore.dll",
  };

  @TempDir
  private Path temp;

  @Test
  void exactlyTheStrategyOfTheRunningSystemIsSupported() {
    final LinuxNativeDiscoveryStrategy linux = new LinuxNativeDiscoveryStrategy();
    final OsxNativeDiscoveryStrategy osx = new OsxNativeDiscoveryStrategy();
    final WindowsNativeDiscoveryStrategy windows = new WindowsNativeDiscoveryStrategy();
    final OS operatingSystem = OSUtils.getOS();
    final boolean linuxSupported = linux.supported();
    final boolean osxSupported = osx.supported();
    final boolean windowsSupported = windows.supported();
    assertEquals(operatingSystem == OS.LINUX || operatingSystem == OS.FREEBSD || operatingSystem == OS.OTHER, linuxSupported);
    assertEquals(operatingSystem == OS.MAC, osxSupported);
    assertEquals(operatingSystem == OS.WINDOWS, windowsSupported);
  }

  @ParameterizedTest
  @CsvSource(
    {
      "libvlc.so, libvlccore.so, true",
      "libvlc.so.5, libvlccore.so.9, true",
      "libvlc.so.5.6.1, libvlccore.so.9.0.1, true",
      "libvlc.so.12, libvlccore.so.10.0.12, true",
      "libvlc.so.x, libvlccore.so, false",
      "libvlc.so.5a, libvlccore.so, false",
      "libvlc.so-5, libvlccore.so, false",
      "libvlc.dylib, libvlccore.dylib, false",
    }
  )
  void linuxRecognizesVersionedSharedLibraries(final String library, final String coreLibrary, final boolean expected) throws IOException {
    final Path directory = createFiles(this.temp, "lib", library, coreLibrary);
    final List<SearchProvider> providers = providersFor(directory);
    final LinuxNativeDiscoveryStrategy strategy = new LinuxNativeDiscoveryStrategy(providers, IGNORING_SETTER);
    final String found = strategy.discover();
    final String expectedPath = expected ? directory.toString() : null;
    assertEquals(expectedPath, found);
  }

  @Test
  void linuxPublishesThePluginsBelowTheLibraryDirectory() throws IOException {
    final Path directory = createFiles(this.temp, "lib", "libvlc.so.5", "libvlccore.so.9");
    final Path relativePlugins = Path.of("vlc", "plugins");
    final Path plugins = directory.resolve(relativePlugins);
    Files.createDirectories(plugins);
    final List<String> published = new ArrayList<>();
    final List<SearchProvider> providers = providersFor(directory);
    final EnvironmentSetter setter = recordingSetter(published);
    final LinuxNativeDiscoveryStrategy strategy = new LinuxNativeDiscoveryStrategy(providers, setter);
    final String rawDirectory = directory.toString();
    final boolean result = strategy.onSetPluginPath(rawDirectory);
    final List<String> expected = List.of("VLC_PLUGIN_PATH=" + rawDirectory + "/vlc/plugins");
    assertTrue(result);
    assertEquals(expected, published);
  }

  @Test
  void osxFindsTheDynamicLibrariesAndPreloadsTheCoreLibrary() throws IOException {
    final Path directory = createFiles(this.temp, "lib", "libvlc.dylib", "libvlccore.dylib");
    final List<String> preloaded = new ArrayList<>();
    final List<SearchProvider> providers = providersFor(directory);
    final OsxNativeDiscoveryStrategy strategy = new OsxNativeDiscoveryStrategy(providers, IGNORING_SETTER, preloaded::add);
    final String found = strategy.discover();
    final String rawDirectory = directory.toString();
    final boolean addToSearchPath = strategy.onFound(rawDirectory);
    final List<String> expectedPreloads = List.of(rawDirectory);
    assertEquals(rawDirectory, found);
    assertTrue(addToSearchPath);
    assertEquals(expectedPreloads, preloaded);
  }

  @Test
  void osxPublishesThePluginsNextToTheLibraryDirectory() throws IOException {
    final Path relativeContents = Path.of("VLC.app", "Contents", "MacOS");
    final Path contents = this.temp.resolve(relativeContents);
    final Path directory = createFiles(contents, "lib", "libvlc.dylib", "libvlccore.dylib");
    final Path plugins = contents.resolve("plugins");
    Files.createDirectories(plugins);
    final List<String> published = new ArrayList<>();
    final List<SearchProvider> providers = providersFor(directory);
    final EnvironmentSetter setter = recordingSetter(published);
    final OsxNativeDiscoveryStrategy strategy = new OsxNativeDiscoveryStrategy(providers, setter, _ -> {});
    final String rawDirectory = directory.toString();
    final boolean result = strategy.onSetPluginPath(rawDirectory);
    final List<String> expected = List.of("VLC_PLUGIN_PATH=" + rawDirectory + "/../plugins");
    assertTrue(result);
    assertEquals(expected, published);
  }

  @Test
  void osxRejectsADirectoryWhoseCoreLibraryCannotBeLoaded() {
    // vlcj does not catch errors of onFound, so an Intel VLC on Apple silicon must be reported, not thrown
    final UnsatisfiedLinkError wrongArchitecture = new UnsatisfiedLinkError("incompatible architecture (have 'x86_64', need 'arm64')");
    final List<SearchProvider> providers = List.of();
    final OsxNativeDiscoveryStrategy strategy = new OsxNativeDiscoveryStrategy(providers, IGNORING_SETTER, _ -> {
      throw wrongArchitecture;
    });
    final boolean addToSearchPath = strategy.onFound("/Applications/VLC.app/Contents/MacOS/lib");
    assertFalse(addToSearchPath);
  }

  @Test
  void osxRejectsNullArguments() {
    final List<SearchProvider> providers = List.of();
    final OsxNativeDiscoveryStrategy strategy = new OsxNativeDiscoveryStrategy(providers, IGNORING_SETTER, _ -> {});
    assertThrows(NullPointerException.class, () -> new OsxNativeDiscoveryStrategy(providers, IGNORING_SETTER, null));
    assertThrows(NullPointerException.class, () -> strategy.onFound(null));
  }

  @Test
  void windowsFindsTheDynamicLinkLibraries() throws IOException {
    final Path directory = createFiles(this.temp, "VLC", "libvlc.dll", "libvlccore.dll", "vlc.exe");
    final List<SearchProvider> providers = providersFor(directory);
    final WindowsNativeDiscoveryStrategy strategy = new WindowsNativeDiscoveryStrategy(providers, IGNORING_SETTER);
    final String found = strategy.discover();
    final String rawDirectory = directory.toString();
    assertEquals(rawDirectory, found);
  }

  @Test
  void windowsPublishesThePluginsBelowTheInstallationDirectory() throws IOException {
    final OS operatingSystem = OSUtils.getOS();
    Assumptions.assumeTrue(operatingSystem == OS.WINDOWS, "Windows plugin paths use backslashes");
    final Path directory = createFiles(this.temp, "VLC", "libvlc.dll", "libvlccore.dll");
    final Path plugins = directory.resolve("plugins");
    Files.createDirectories(plugins);
    final List<String> published = new ArrayList<>();
    final List<SearchProvider> providers = providersFor(directory);
    final EnvironmentSetter setter = recordingSetter(published);
    final WindowsNativeDiscoveryStrategy strategy = new WindowsNativeDiscoveryStrategy(providers, setter);
    final String rawDirectory = directory.toString();
    final boolean result = strategy.onSetPluginPath(rawDirectory);
    final List<String> expected = List.of("VLC_PLUGIN_PATH=" + rawDirectory + "\\plugins");
    assertTrue(result);
    assertEquals(expected, published);
  }

  @Test
  void systemStrategiesCoverEveryOperatingSystem() {
    final NativeDiscoveryStrategy[] strategies = VLCDiscoveryStrategies.createSystemStrategies();
    assertEquals(3, strategies.length);
    assertInstanceOf(LinuxNativeDiscoveryStrategy.class, strategies[0]);
    assertInstanceOf(OsxNativeDiscoveryStrategy.class, strategies[1]);
    assertInstanceOf(WindowsNativeDiscoveryStrategy.class, strategies[2]);
  }

  @Test
  void directoryStrategiesFindTheLibrariesOfEveryPlatformInTheGivenDirectory() throws IOException {
    final Path directory = createFiles(this.temp, "vlc", EVERY_LIBRARY);
    final NativeDiscoveryStrategy[] strategies = VLCDiscoveryStrategies.createDirectoryStrategies(directory);
    assertEquals(3, strategies.length);
    final String rawDirectory = directory.toString();
    final List<String> expectedDirectories = List.of(rawDirectory);
    for (final NativeDiscoveryStrategy strategy : strategies) {
      final DirectoryProviderDiscoveryStrategy directoryStrategy = assertInstanceOf(DirectoryProviderDiscoveryStrategy.class, strategy);
      final List<String> searched = directoryStrategy.discoveryDirectories();
      final String found = directoryStrategy.discover();
      assertEquals(expectedDirectories, searched);
      assertEquals(rawDirectory, found);
    }
  }

  @Test
  void directoryStrategiesDoNotLookAnywhereElse() throws IOException {
    final Path empty = this.temp.resolve("empty");
    Files.createDirectories(empty);
    createFiles(empty, "child", EVERY_LIBRARY);
    final NativeDiscoveryStrategy[] strategies = VLCDiscoveryStrategies.createDirectoryStrategies(empty);
    for (final NativeDiscoveryStrategy strategy : strategies) {
      final String found = strategy.discover();
      assertNull(found);
    }
  }

  @Test
  void directoryStrategiesRejectANullDirectory() {
    assertThrows(NullPointerException.class, () -> VLCDiscoveryStrategies.createDirectoryStrategies(null));
  }

  @Test
  void fixedDirectoryProviderNamesOneAbsoluteDirectoryWithTheHighestPriority() {
    final Path relative = Path.of("relative", "vlc");
    final FixedDirectoryProvider provider = new FixedDirectoryProvider(relative);
    final Path absolute = relative.toAbsolutePath();
    final String rawAbsolute = absolute.toString();
    final String[] expected = { rawAbsolute };
    final String[] directories = provider.directories();
    final int priority = provider.priority();
    final boolean supported = provider.supported();
    assertArrayEquals(expected, directories);
    assertEquals(Integer.MAX_VALUE, priority);
    assertTrue(supported);
  }

  @Test
  void setsPosixVariablesWithSetenvAndReportsFailures() {
    final LibC libc = mock(LibC.class);
    when(libc.setenv("GOOD", "value", 1)).thenReturn(0);
    when(libc.setenv("", "value", 1)).thenReturn(-1);
    final EnvironmentVariables variables = new EnvironmentVariables(libc);
    final boolean good = variables.setPosixVariable("GOOD", "value");
    final boolean bad = variables.setPosixVariable("", "value");
    assertTrue(good);
    assertFalse(bad);
    verify(libc).setenv("GOOD", "value", 1);
  }

  @Test
  void setsWindowsVariablesWithPutenvAndReportsFailures() {
    final LibC libc = mock(LibC.class);
    when(libc._putenv("GOOD=value")).thenReturn(0);
    when(libc._putenv("=value")).thenReturn(-1);
    final EnvironmentVariables variables = new EnvironmentVariables(libc);
    final boolean good = variables.setWindowsVariable("GOOD", "value");
    final boolean bad = variables.setWindowsVariable("", "value");
    assertTrue(good);
    assertFalse(bad);
    verify(libc)._putenv("GOOD=value");
  }

  @Test
  void setsVariablesThroughTheCLibraryOfThisProcess() {
    final OS operatingSystem = OSUtils.getOS();
    final boolean windows = operatingSystem == OS.WINDOWS;
    final EnvironmentSetter setter = windows ? WindowsNativeDiscoveryStrategy.nativeSetter() : LinuxNativeDiscoveryStrategy.nativeSetter();
    final String libraryName = windows ? "msvcrt" : "c";
    final EnvironmentReader reader = Native.load(libraryName, EnvironmentReader.class);
    final boolean set = setter.set(TEST_VARIABLE, "value");
    final String value = reader.getenv(TEST_VARIABLE);
    unsetTestVariable(windows);
    final String afterUnset = reader.getenv(TEST_VARIABLE);
    assertTrue(set);
    assertEquals("value", value, "native code such as libvlc sees the variable");
    assertNull(afterUnset);
  }

  @Test
  void loadsTheCLibraryOnlyWhenTheFirstVariableIsSet() {
    final AtomicInteger loads = new AtomicInteger();
    final LibC libc = mock(LibC.class);
    when(libc.setenv("NAME", "value", 1)).thenReturn(0);
    final EnvironmentVariables variables = new EnvironmentVariables(() -> {
      loads.incrementAndGet();
      return libc;
    });
    final int loadsBeforeUse = loads.get();
    final boolean first = variables.setPosixVariable("NAME", "value");
    final boolean second = variables.setPosixVariable("NAME", "value");
    final int loadsAfterUse = loads.get();
    assertEquals(0, loadsBeforeUse, "creating the setter does not load the C library");
    assertTrue(first);
    assertTrue(second);
    assertEquals(1, loadsAfterUse, "a loaded C library is remembered");
  }

  @Test
  void reportsVariablesAsNotSetWhenTheCLibraryCannotBeLoaded() {
    final UnsatisfiedLinkError missingJna = new UnsatisfiedLinkError("Unable to load library 'jnidispatch'");
    final EnvironmentVariables variables = new EnvironmentVariables(() -> {
      throw missingJna;
    });
    final boolean posix = variables.setPosixVariable("NAME", "value");
    final boolean windows = variables.setWindowsVariable("NAME", "value");
    assertFalse(posix);
    assertFalse(windows);
  }

  @Test
  void preloadsTheCoreLibraryFromTheGivenDirectory() {
    final Path systemDirectory = findSystemInstallation();
    Assumptions.assumeTrue(systemDirectory != null, "VLC is not installed on this machine");
    final String rawDirectory = systemDirectory.toString();
    OsxNativeDiscoveryStrategy.preloadCoreLibrary(rawDirectory);
    final String coreLibraryName = RuntimeUtil.getLibVlcCoreLibraryName();
    final NativeLibrary coreLibrary = NativeLibrary.getInstance(coreLibraryName);
    final File coreFile = coreLibrary.getFile();
    final File coreDirectory = coreFile.getParentFile();
    final Path loadedFrom = coreDirectory.toPath();
    assertEquals(systemDirectory, loadedFrom);
  }

  @Test
  void helperClassesCannotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(VLCDiscoveryStrategies.class);
  }

  /**
   * The C library function that reads environment variables, which vlcj's {@link LibC} does not declare.
   */
  interface EnvironmentReader extends Library {
    /**
     * Reads a variable.
     *
     * @param name the name of the variable
     * @return the value, or null if the variable is not set
     */
    String getenv(String name);
  }

  private static void unsetTestVariable(final boolean windows) {
    final LibC libc = LibC.INSTANCE;
    if (windows) {
      libc._putenv(TEST_VARIABLE + "=");
    } else {
      libc.unsetenv(TEST_VARIABLE);
    }
  }

  private static Path findSystemInstallation() {
    final NativeDiscoveryStrategy[] strategies = VLCDiscoveryStrategies.createSystemStrategies();
    for (final NativeDiscoveryStrategy strategy : strategies) {
      final boolean supported = strategy.supported();
      if (supported) {
        final String directory = strategy.discover();
        return directory == null ? null : Path.of(directory);
      }
    }
    return null;
  }

  private static Path createFiles(final Path parent, final String directoryName, final String... names) throws IOException {
    final Path directory = parent.resolve(directoryName);
    Files.createDirectories(directory);
    for (final String name : names) {
      final Path file = directory.resolve(name);
      Files.createFile(file);
    }
    return directory;
  }

  private static List<SearchProvider> providersFor(final Path directory) {
    final String rawDirectory = directory.toString();
    final StaticDirectoryProvider provider = new StaticDirectoryProvider(1, true, rawDirectory);
    final SearchProvider searchProvider = new SearchProvider(provider, 0);
    return List.of(searchProvider);
  }

  private static EnvironmentSetter recordingSetter(final List<String> published) {
    return (name, value) -> {
      published.add(name + "=" + value);
      return true;
    };
  }
}
