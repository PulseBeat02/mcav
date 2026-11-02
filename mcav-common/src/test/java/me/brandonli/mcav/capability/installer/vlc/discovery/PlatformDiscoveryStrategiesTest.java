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
import com.sun.jna.Platform;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import me.brandonli.mcav.capability.installer.vlc.discovery.DirectoryProviderDiscoveryStrategy.EnvironmentSetter;
import me.brandonli.mcav.capability.installer.vlc.discovery.DirectoryProviderDiscoveryStrategy.SearchProvider;
import me.brandonli.mcav.capability.installer.vlc.discovery.DirectoryProviderDiscoveryStrategyTest.ProviderSupport;
import me.brandonli.mcav.capability.installer.vlc.discovery.DirectoryProviderDiscoveryStrategyTest.StaticDirectoryProvider;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.OsUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import uk.co.caprica.vlcj.binding.lib.LibC;
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil;
import uk.co.caprica.vlcj.factory.discovery.strategy.NativeDiscoveryStrategy;

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

  private static final String LIBRARY_DIRECTORY_NAME = "lib";
  private static final String VLC_DIRECTORY_NAME = "vlc";
  private static final String PLUGINS_DIRECTORY_NAME = "plugins";
  private static final String PARENT_DIRECTORY_NAME = "..";

  private static final String LINUX_LIBRARY_NAME = "libvlc.so.5";
  private static final String LINUX_CORE_LIBRARY_NAME = "libvlccore.so.9";

  private static final String OSX_LIBRARY_NAME = "libvlc.dylib";
  private static final String OSX_CORE_LIBRARY_NAME = "libvlccore.dylib";
  private static final String APPLICATION_BUNDLE_NAME = "VLC.app";
  private static final String CONTENTS_DIRECTORY_NAME = "Contents";
  private static final String MACOS_DIRECTORY_NAME = "MacOS";
  private static final String OSX_LIBRARY_PATH = "/Applications/VLC.app/Contents/MacOS/lib";

  private static final String WINDOWS_INSTALLATION_DIRECTORY_NAME = "VLC";
  private static final String WINDOWS_LIBRARY_NAME = "libvlc.dll";
  private static final String WINDOWS_CORE_LIBRARY_NAME = "libvlccore.dll";
  private static final String WINDOWS_EXECUTABLE_NAME = "vlc.exe";

  private static final String EMPTY_DIRECTORY_NAME = "empty";
  private static final String CHILD_DIRECTORY_NAME = "child";
  private static final String RELATIVE_DIRECTORY_NAME = "relative";

  private static final String LOADABLE_LIBRARY_DIRECTORY = "/com/sun/jna/";
  private static final String LOADABLE_LIBRARY_NAME = "jnidispatch";
  private static final String RESOURCE_SEPARATOR = "/";
  private static final String DYNAMIC_LIBRARY_SUFFIX = ".dylib";
  private static final String JNI_LIBRARY_SUFFIX = ".jnilib";

  private static final int STRATEGY_COUNT = 3;

  private static final String PLUGIN_PATH_ASSIGNMENT = "VLC_PLUGIN_PATH=";
  private static final String ASSIGNMENT_SEPARATOR = "=";

  private static final String GOOD_VARIABLE_NAME = "GOOD";
  private static final String VARIABLE_NAME = "NAME";
  private static final String VARIABLE_VALUE = "value";

  @TempDir
  private Path temporaryDirectory;

  @Test
  void shouldSupportExactlyTheStrategyOfTheRunningSystem() {
    final LinuxNativeDiscoveryStrategy linux = new LinuxNativeDiscoveryStrategy();
    final OsxNativeDiscoveryStrategy osx = new OsxNativeDiscoveryStrategy();
    final WindowsNativeDiscoveryStrategy windowsStrategy = new WindowsNativeDiscoveryStrategy();
    final Os operatingSystem = OsUtils.getOs();
    final boolean linuxSupported = linux.supported();
    final boolean osxSupported = osx.supported();
    final boolean windowsSupported = windowsStrategy.supported();
    assertEquals(operatingSystem == Os.LINUX || operatingSystem == Os.FREEBSD || operatingSystem == Os.OTHER, linuxSupported);
    assertEquals(operatingSystem == Os.MAC, osxSupported);
    assertEquals(operatingSystem == Os.WINDOWS, windowsSupported);
  }

  @ParameterizedTest
  @EnumSource(Os.class)
  void shouldSupportExactlyTheStrategyOfTheGivenSystem(final Os operatingSystem) {
    final List<SearchProvider> providers = List.of();
    final LinuxNativeDiscoveryStrategy linux = new LinuxNativeDiscoveryStrategy(providers, IGNORING_SETTER, operatingSystem);
    final OsxNativeDiscoveryStrategy osx = new OsxNativeDiscoveryStrategy(providers, IGNORING_SETTER, _ -> {}, operatingSystem);
    final WindowsNativeDiscoveryStrategy windowsStrategy = new WindowsNativeDiscoveryStrategy(providers, IGNORING_SETTER, operatingSystem);
    final boolean linuxSupported = linux.supported();
    final boolean osxSupported = osx.supported();
    final boolean windowsSupported = windowsStrategy.supported();
    assertEquals(operatingSystem == Os.LINUX || operatingSystem == Os.FREEBSD || operatingSystem == Os.OTHER, linuxSupported);
    assertEquals(operatingSystem == Os.MAC, osxSupported);
    assertEquals(operatingSystem == Os.WINDOWS, windowsSupported);
  }

  @ParameterizedTest
  @CsvSource({
    "libvlc.so, libvlccore.so, true",
    "libvlc.so.5, libvlccore.so.9, true",
    "libvlc.so.5.6.1, libvlccore.so.9.0.1, true",
    "libvlc.so.12, libvlccore.so.10.0.12, true",
    "libvlc.so.x, libvlccore.so, false",
    "libvlc.so.5a, libvlccore.so, false",
    "libvlc.so-5, libvlccore.so, false",
    "libvlc.dylib, libvlccore.dylib, false",
  })
  void shouldRecognizeVersionedSharedLibrariesOnLinux(final String library, final String coreLibrary, final boolean expected)
    throws IOException {
    final Path directory = createFiles(this.temporaryDirectory, LIBRARY_DIRECTORY_NAME, library, coreLibrary);
    final List<SearchProvider> providers = providersFor(directory);
    final LinuxNativeDiscoveryStrategy strategy = new LinuxNativeDiscoveryStrategy(providers, IGNORING_SETTER, Os.LINUX);
    final String found = strategy.discover();
    final String expectedPath = expected ? directory.toString() : null;
    assertEquals(expectedPath, found);
  }

  @Test
  void shouldPublishThePluginsBelowTheLibraryDirectoryOnLinux() throws IOException {
    final Path directory = createFiles(this.temporaryDirectory, LIBRARY_DIRECTORY_NAME, LINUX_LIBRARY_NAME, LINUX_CORE_LIBRARY_NAME);
    final Path relativePlugins = Path.of(VLC_DIRECTORY_NAME, PLUGINS_DIRECTORY_NAME);
    final Path plugins = directory.resolve(relativePlugins);
    Files.createDirectories(plugins);
    final List<String> published = new ArrayList<>();
    final List<SearchProvider> providers = providersFor(directory);
    final EnvironmentSetter setter = recordingSetter(published);
    final LinuxNativeDiscoveryStrategy strategy = new LinuxNativeDiscoveryStrategy(providers, setter, Os.LINUX);
    final String rawDirectory = directory.toString();
    final boolean pathSet = strategy.onSetPluginPath(rawDirectory);
    final List<String> expected = List.of(PLUGIN_PATH_ASSIGNMENT + plugins);
    assertTrue(pathSet);
    assertEquals(expected, published);
  }

  @Test
  void shouldFindTheDynamicLibrariesAndPreloadTheCoreLibraryOnOsx() throws IOException {
    final Path directory = createFiles(this.temporaryDirectory, LIBRARY_DIRECTORY_NAME, OSX_LIBRARY_NAME, OSX_CORE_LIBRARY_NAME);
    final List<String> preloaded = new ArrayList<>();
    final List<SearchProvider> providers = providersFor(directory);
    final OsxNativeDiscoveryStrategy strategy = new OsxNativeDiscoveryStrategy(providers, IGNORING_SETTER, preloaded::add, Os.MAC);
    final String found = strategy.discover();
    final String rawDirectory = directory.toString();
    final boolean addsToSearchPath = strategy.onFound(rawDirectory);
    final List<String> expectedPreloads = List.of(rawDirectory);
    assertEquals(rawDirectory, found);
    assertTrue(addsToSearchPath);
    assertEquals(expectedPreloads, preloaded);
  }

  @Test
  void shouldPublishThePluginsNextToTheLibraryDirectoryOnOsx() throws IOException {
    final Path relativeContents = Path.of(APPLICATION_BUNDLE_NAME, CONTENTS_DIRECTORY_NAME, MACOS_DIRECTORY_NAME);
    final Path contents = this.temporaryDirectory.resolve(relativeContents);
    final Path directory = createFiles(contents, LIBRARY_DIRECTORY_NAME, OSX_LIBRARY_NAME, OSX_CORE_LIBRARY_NAME);
    final Path plugins = contents.resolve(PLUGINS_DIRECTORY_NAME);
    Files.createDirectories(plugins);
    final List<String> published = new ArrayList<>();
    final List<SearchProvider> providers = providersFor(directory);
    final EnvironmentSetter setter = recordingSetter(published);
    final OsxNativeDiscoveryStrategy strategy = new OsxNativeDiscoveryStrategy(providers, setter, _ -> {}, Os.MAC);
    final String rawDirectory = directory.toString();
    final boolean pathSet = strategy.onSetPluginPath(rawDirectory);
    final Path relativePlugins = Path.of(PARENT_DIRECTORY_NAME, PLUGINS_DIRECTORY_NAME);
    final Path publishedPlugins = directory.resolve(relativePlugins);
    final List<String> expected = List.of(PLUGIN_PATH_ASSIGNMENT + publishedPlugins);
    assertTrue(pathSet);
    assertEquals(expected, published);
  }

  @Test
  void shouldRejectADirectoryWhoseCoreLibraryCannotBeLoadedOnOsx() {
    // vlcj does not catch errors of onFound, so an Intel VLC on Apple silicon must be reported, not thrown
    final UnsatisfiedLinkError wrongArchitecture = new UnsatisfiedLinkError("incompatible architecture (have 'x86_64', need 'arm64')!");
    final List<SearchProvider> providers = List.of();
    final OsxNativeDiscoveryStrategy strategy = new OsxNativeDiscoveryStrategy(
      providers,
      IGNORING_SETTER,
      _ -> this.failToPreload(wrongArchitecture),
      Os.MAC
    );
    final boolean addsToSearchPath = strategy.onFound(OSX_LIBRARY_PATH);
    assertFalse(addsToSearchPath);
  }

  private void failToPreload(final UnsatisfiedLinkError failure) {
    throw failure;
  }

  @Test
  void shouldRejectNullArgumentsOnOsx() {
    final List<SearchProvider> providers = List.of();
    final OsxNativeDiscoveryStrategy strategy = new OsxNativeDiscoveryStrategy(providers, IGNORING_SETTER, _ -> {}, Os.MAC);
    assertThrows(NullPointerException.class, () -> new OsxNativeDiscoveryStrategy(providers, IGNORING_SETTER, null, Os.MAC));
    assertThrows(NullPointerException.class, () -> strategy.onFound(null));
  }

  @Test
  void shouldFindTheDynamicLinkLibrariesOnWindows() throws IOException {
    final Path directory = createFiles(
      this.temporaryDirectory,
      WINDOWS_INSTALLATION_DIRECTORY_NAME,
      WINDOWS_LIBRARY_NAME,
      WINDOWS_CORE_LIBRARY_NAME,
      WINDOWS_EXECUTABLE_NAME
    );
    final List<SearchProvider> providers = providersFor(directory);
    final WindowsNativeDiscoveryStrategy strategy = new WindowsNativeDiscoveryStrategy(providers, IGNORING_SETTER, Os.WINDOWS);
    final String found = strategy.discover();
    final String rawDirectory = directory.toString();
    assertEquals(rawDirectory, found);
  }

  @Test
  void shouldPublishThePluginsBelowTheInstallationDirectoryOnWindows() throws IOException {
    final Path directory = createFiles(
      this.temporaryDirectory,
      WINDOWS_INSTALLATION_DIRECTORY_NAME,
      WINDOWS_LIBRARY_NAME,
      WINDOWS_CORE_LIBRARY_NAME
    );
    final Path plugins = directory.resolve(PLUGINS_DIRECTORY_NAME);
    Files.createDirectories(plugins);
    final List<String> published = new ArrayList<>();
    final List<SearchProvider> providers = providersFor(directory);
    final EnvironmentSetter setter = recordingSetter(published);
    final WindowsNativeDiscoveryStrategy strategy = new WindowsNativeDiscoveryStrategy(providers, setter, Os.WINDOWS);
    final String rawDirectory = directory.toString();
    final boolean pathSet = strategy.onSetPluginPath(rawDirectory);
    final List<String> expected = List.of(PLUGIN_PATH_ASSIGNMENT + plugins);
    assertTrue(pathSet);
    assertEquals(expected, published);
  }

  @Test
  void shouldCoverEveryOperatingSystemWithSystemStrategies() {
    final NativeDiscoveryStrategy[] strategies = VlcDiscoveryStrategies.createSystemStrategies();
    assertEquals(STRATEGY_COUNT, strategies.length);

    final NativeDiscoveryStrategy linux = strategies[0];
    assertInstanceOf(LinuxNativeDiscoveryStrategy.class, linux);

    final NativeDiscoveryStrategy osx = strategies[1];
    assertInstanceOf(OsxNativeDiscoveryStrategy.class, osx);

    final NativeDiscoveryStrategy windows = strategies[2];
    assertInstanceOf(WindowsNativeDiscoveryStrategy.class, windows);
  }

  @Test
  void shouldFindTheLibrariesOfEveryPlatformInTheGivenDirectoryWithDirectoryStrategies() throws IOException {
    final Path directory = createFiles(this.temporaryDirectory, VLC_DIRECTORY_NAME, EVERY_LIBRARY);
    final NativeDiscoveryStrategy[] strategies = VlcDiscoveryStrategies.createDirectoryStrategies(directory);
    assertEquals(STRATEGY_COUNT, strategies.length);

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
  void shouldNotLookAnywhereElseWithDirectoryStrategies() throws IOException {
    final Path empty = this.temporaryDirectory.resolve(EMPTY_DIRECTORY_NAME);
    Files.createDirectories(empty);
    createFiles(empty, CHILD_DIRECTORY_NAME, EVERY_LIBRARY);
    final NativeDiscoveryStrategy[] strategies = VlcDiscoveryStrategies.createDirectoryStrategies(empty);
    for (final NativeDiscoveryStrategy strategy : strategies) {
      final String found = strategy.discover();
      assertNull(found);
    }
  }

  @Test
  void shouldRejectANullDirectoryInDirectoryStrategies() {
    assertThrows(NullPointerException.class, () -> VlcDiscoveryStrategies.createDirectoryStrategies(null));
  }

  @Test
  void shouldNameOneAbsoluteDirectoryWithTheHighestPriorityInTheFixedDirectoryProvider() {
    final Path relative = Path.of(RELATIVE_DIRECTORY_NAME, VLC_DIRECTORY_NAME);
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
  void shouldSetPosixVariablesWithSetenvAndReportFailures() {
    final LibC libc = mock(LibC.class);
    when(libc.setenv(GOOD_VARIABLE_NAME, VARIABLE_VALUE, 1)).thenReturn(0);
    when(libc.setenv("", VARIABLE_VALUE, 1)).thenReturn(-1);
    final EnvironmentVariables variables = new EnvironmentVariables(libc);
    final boolean good = variables.setPosixVariable(GOOD_VARIABLE_NAME, VARIABLE_VALUE);
    final boolean bad = variables.setPosixVariable("", VARIABLE_VALUE);
    assertTrue(good);
    assertFalse(bad);
    verify(libc).setenv(GOOD_VARIABLE_NAME, VARIABLE_VALUE, 1);
  }

  @Test
  void shouldSetWindowsVariablesWithPutenvAndReportFailures() {
    final LibC libc = mock(LibC.class);
    when(libc._putenv("GOOD=value")).thenReturn(0);
    when(libc._putenv("=value")).thenReturn(-1);
    final EnvironmentVariables variables = new EnvironmentVariables(libc);
    final boolean good = variables.setWindowsVariable(GOOD_VARIABLE_NAME, VARIABLE_VALUE);
    final boolean bad = variables.setWindowsVariable("", VARIABLE_VALUE);
    assertTrue(good);
    assertFalse(bad);
    verify(libc)._putenv("GOOD=value");
  }

  @Test
  void shouldSetVariablesThroughTheCLibraryOfThisProcess() {
    final Os operatingSystem = OsUtils.getOs();
    final boolean isWindows = operatingSystem == Os.WINDOWS;
    final EnvironmentSetter setter = isWindows
      ? WindowsNativeDiscoveryStrategy.nativeSetter()
      : LinuxNativeDiscoveryStrategy.nativeSetter();
    final String libraryName = isWindows ? "msvcrt" : "c";
    final EnvironmentReader reader = Native.load(libraryName, EnvironmentReader.class);
    final boolean set = setter.set(TEST_VARIABLE, VARIABLE_VALUE);
    final String value = reader.getenv(TEST_VARIABLE);
    unsetTestVariable(operatingSystem);
    final String afterUnset = reader.getenv(TEST_VARIABLE);
    assertTrue(set);
    assertEquals(VARIABLE_VALUE, value, "native code such as libvlc sees the variable");
    assertNull(afterUnset);
  }

  @Test
  void shouldLoadTheCLibraryOnlyWhenTheFirstVariableIsSet() {
    final AtomicInteger loads = new AtomicInteger();
    final LibC libc = mock(LibC.class);
    when(libc.setenv(VARIABLE_NAME, VARIABLE_VALUE, 1)).thenReturn(0);
    final EnvironmentVariables variables = new EnvironmentVariables(() -> this.countAndLoad(loads, libc));
    final int loadsBeforeUse = loads.get();
    final boolean firstSet = variables.setPosixVariable(VARIABLE_NAME, VARIABLE_VALUE);
    final boolean secondSet = variables.setPosixVariable(VARIABLE_NAME, VARIABLE_VALUE);
    final int loadsAfterUse = loads.get();
    assertEquals(0, loadsBeforeUse, "creating the setter does not load the C library");
    assertTrue(firstSet);
    assertTrue(secondSet);
    assertEquals(1, loadsAfterUse, "a loaded C library is remembered");
  }

  private LibC countAndLoad(final AtomicInteger loads, final LibC libc) {
    loads.incrementAndGet();
    return libc;
  }

  @Test
  void shouldReportVariablesAsNotSetWhenTheCLibraryCannotBeLoaded() {
    final UnsatisfiedLinkError missingJna = new UnsatisfiedLinkError("Unable to load library 'jnidispatch'!");
    final EnvironmentVariables variables = new EnvironmentVariables(() -> this.failToLoad(missingJna));
    final boolean posixSet = variables.setPosixVariable(VARIABLE_NAME, VARIABLE_VALUE);
    final boolean windowsSet = variables.setWindowsVariable(VARIABLE_NAME, VARIABLE_VALUE);
    assertFalse(posixSet);
    assertFalse(windowsSet);
  }

  private LibC failToLoad(final UnsatisfiedLinkError failure) {
    throw failure;
  }

  @Test
  void shouldTryTheCLibraryAgainAfterAFailedLoad() {
    final AtomicInteger loads = new AtomicInteger();
    final LibC libc = mock(LibC.class);
    when(libc.setenv(VARIABLE_NAME, VARIABLE_VALUE, 1)).thenReturn(0);
    final EnvironmentVariables variables = new EnvironmentVariables(() -> this.failFirstThenLoad(loads, libc));
    final boolean firstSet = variables.setPosixVariable(VARIABLE_NAME, VARIABLE_VALUE);
    final boolean secondSet = variables.setPosixVariable(VARIABLE_NAME, VARIABLE_VALUE);
    final int loadCount = loads.get();
    assertFalse(firstSet);
    assertTrue(secondSet);
    assertEquals(2, loadCount, "a failed load of the C library is tried again");
  }

  private LibC failFirstThenLoad(final AtomicInteger loads, final LibC libc) {
    final int previousLoads = loads.getAndIncrement();
    if (previousLoads == 0) {
      final String message = "Unable to load library 'c'!";
      throw new UnsatisfiedLinkError(message);
    }

    return libc;
  }

  @Test
  void shouldPreloadTheCoreLibraryFromTheGivenDirectory() throws IOException {
    final String coreLibraryName = RuntimeUtil.getLibVlcCoreLibraryName();
    final String coreFileName = System.mapLibraryName(coreLibraryName);
    final Path directory = this.temporaryDirectory.resolve(LIBRARY_DIRECTORY_NAME);
    Files.createDirectories(directory);
    final Path coreFile = directory.resolve(coreFileName);
    this.copyLoadableLibrary(coreFile);
    final String rawDirectory = directory.toString();
    OsxNativeDiscoveryStrategy.preloadCoreLibrary(rawDirectory);

    final NativeLibrary coreLibrary = NativeLibrary.getInstance(coreLibraryName);
    final File loadedFile = coreLibrary.getFile();
    final Path loadedPath = loadedFile.toPath();
    final boolean loadedFromTheDirectory = Files.isSameFile(coreFile, loadedPath);
    coreLibrary.close();
    assertTrue(loadedFromTheDirectory);
  }

  // JNA's own native library stands in for the core library, so no VLC has to be installed
  private void copyLoadableLibrary(final Path target) throws IOException {
    final String mappedName = System.mapLibraryName(LOADABLE_LIBRARY_NAME);
    final String fileName = mappedName.replace(DYNAMIC_LIBRARY_SUFFIX, JNI_LIBRARY_SUFFIX);
    final String resource = LOADABLE_LIBRARY_DIRECTORY + Platform.RESOURCE_PREFIX + RESOURCE_SEPARATOR + fileName;
    try (final InputStream library = Native.class.getResourceAsStream(resource)) {
      Files.copy(library, target);
    }
  }

  @Test
  void shouldNotInstantiateHelperClasses() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(VlcDiscoveryStrategies.class);
  }

  private static void unsetTestVariable(final Os operatingSystem) {
    final LibC libc = LibC.INSTANCE;
    if (operatingSystem == Os.WINDOWS) {
      libc._putenv(TEST_VARIABLE + ASSIGNMENT_SEPARATOR);
    } else {
      libc.unsetenv(TEST_VARIABLE);
    }
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
    final StaticDirectoryProvider provider = new StaticDirectoryProvider(1, ProviderSupport.SUPPORTED, rawDirectory);
    final SearchProvider searchProvider = new SearchProvider(provider, 0);
    return List.of(searchProvider);
  }

  private static EnvironmentSetter recordingSetter(final List<String> published) {
    return (name, value) -> recordVariable(published, name, value);
  }

  private static boolean recordVariable(final List<String> published, final String name, final String value) {
    published.add(name + ASSIGNMENT_SEPARATOR + value);

    return true;
  }

  interface EnvironmentReader extends Library {
    String getenv(String name);
  }
}
