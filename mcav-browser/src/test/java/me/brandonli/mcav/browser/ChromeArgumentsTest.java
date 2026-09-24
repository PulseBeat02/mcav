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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import me.brandonli.mcav.browser.testing.UtilityClassAssertions;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.OSUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link ChromeArguments}.
 */
final class ChromeArgumentsTest {

  private static final String NO_SANDBOX = "--no-sandbox";
  private static final String NO_DEV_SHM = "--disable-dev-shm-usage";
  private static final List<String> NO_PLATFORM_ARGUMENTS = List.of();

  @TempDir
  private Path directory;

  @Test
  void keepsChromeHeadlessUnlessTheCallerChoosesAMode() {
    final String[] requested = { "--mute-audio" };
    final List<String> arguments = ChromeArguments.resolve(requested, NO_PLATFORM_ARGUMENTS);
    final List<String> expected = List.of("--headless=new", "--mute-audio");
    assertEquals(expected, arguments);
  }

  @Test
  void keepsTheHeadlessModeTheCallerChose() {
    final String[] requested = { "--mute-audio", "--headless=old" };
    final List<String> arguments = ChromeArguments.resolve(requested, NO_PLATFORM_ARGUMENTS);
    final List<String> expected = List.of("--mute-audio", "--headless=old");
    assertEquals(expected, arguments);
  }

  @Test
  void opensAWindowWhenAskedAndDoesNotPassTheRequestOn() {
    final String[] window = { BrowserPlayer.SHOW_WINDOW, "--mute-audio" };
    final String[] windowAfterMode = { "--headless=new", BrowserPlayer.SHOW_WINDOW };
    final List<String> windowArguments = ChromeArguments.resolve(window, NO_PLATFORM_ARGUMENTS);
    final List<String> windowAfterModeArguments = ChromeArguments.resolve(windowAfterMode, NO_PLATFORM_ARGUMENTS);
    final List<String> expectedWindow = List.of("--mute-audio");
    final List<String> expectedWindowAfterMode = List.of("--headless=new");
    assertEquals(expectedWindow, windowArguments);
    assertEquals(expectedWindowAfterMode, windowAfterModeArguments);
  }

  @Test
  void leavesTheDefaultArgumentsAsTheyAre() {
    final List<String> expected = BrowserPlayer.DEFAULT_CHROME_ARGUMENTS;
    final String[] defaultArguments = expected.toArray(String[]::new);
    final List<String> arguments = ChromeArguments.resolve(defaultArguments, NO_PLATFORM_ARGUMENTS);
    assertEquals(expected, arguments);
  }

  @Test
  void addsEachPlatformArgumentOnce() {
    final String[] requested = { NO_SANDBOX };
    final List<String> platform = List.of(NO_DEV_SHM, NO_SANDBOX);
    final List<String> arguments = ChromeArguments.resolve(requested, platform);
    final List<String> expected = List.of("--headless=new", NO_SANDBOX, NO_DEV_SHM);
    assertEquals(expected, arguments);
  }

  @Test
  void disablesTheSandboxOnlyForRootOrContainersOnLinux() {
    final List<String> windows = ChromeArguments.platformArguments(OS.WINDOWS, true, true);
    final List<String> mac = ChromeArguments.platformArguments(OS.MAC, true, false);
    final List<String> freeBsd = ChromeArguments.platformArguments(OS.FREEBSD, false, true);
    final List<String> linuxUser = ChromeArguments.platformArguments(OS.LINUX, false, false);
    final List<String> linuxRoot = ChromeArguments.platformArguments(OS.LINUX, true, false);
    final List<String> linuxContainer = ChromeArguments.platformArguments(OS.LINUX, false, true);
    final List<String> none = List.of();
    final List<String> sharedMemory = List.of(NO_DEV_SHM);
    final List<String> sandboxOff = List.of(NO_DEV_SHM, NO_SANDBOX);
    assertEquals(none, windows);
    assertEquals(none, mac);
    assertEquals(none, freeBsd);
    assertEquals(sharedMemory, linuxUser);
    assertEquals(sandboxOff, linuxRoot);
    assertEquals(sandboxOff, linuxContainer);
  }

  @Test
  void recognizesRootByItsUserId() {
    final boolean root = ChromeArguments.isRoot(() -> 0);
    final boolean user = ChromeArguments.isRoot(() -> 1000);
    final boolean unreadable = ChromeArguments.isRoot(() -> {
      throw new IOException("no /proc");
    });
    final boolean noUserIds = ChromeArguments.isRoot(() -> {
      throw new UnsupportedOperationException("View 'unix' not available");
    });
    final boolean unknownAttribute = ChromeArguments.isRoot(() -> {
      throw new IllegalArgumentException("'uid' not recognized");
    });
    assertTrue(root);
    assertFalse(user);
    assertFalse(unreadable);
    assertFalse(noUserIds);
    assertFalse(unknownAttribute);
  }

  @Test
  void recognizesContainersByTheirMarkerFiles() throws IOException {
    final Path docker = this.directory.resolve(".dockerenv");
    final Path podman = this.directory.resolve(".containerenv");
    final List<Path> markers = List.of(docker, podman);
    final boolean before = ChromeArguments.isContainer(markers);
    Files.writeString(podman, "");
    final boolean after = ChromeArguments.isContainer(markers);
    assertFalse(before);
    assertTrue(after);
  }

  @Test
  void detectsRootEvenOutsideAContainer() {
    final Path process = Path.of("/proc/self");
    try (
      final MockedStatic<Files> files = Mockito.mockStatic(Files.class);
      final MockedStatic<OSUtils> os = Mockito.mockStatic(OSUtils.class)
    ) {
      os.when(OSUtils::getOS).thenReturn(OS.LINUX);
      files.when(() -> Files.getAttribute(process, "unix:uid")).thenReturn(0);
      final List<String> arguments = ChromeArguments.detectPlatformArguments();
      final List<String> expected = List.of(NO_DEV_SHM, NO_SANDBOX);
      assertEquals(expected, arguments);
    }
  }

  @Test
  void detectsTheArgumentsOfThisMachine() {
    final List<String> arguments = ChromeArguments.detectPlatformArguments();
    final OS os = OSUtils.getOS();
    if (os == OS.LINUX) {
      final boolean sharedMemory = arguments.contains(NO_DEV_SHM);
      assertTrue(sharedMemory, arguments::toString);
      return;
    }
    final List<String> none = List.of();
    assertEquals(none, arguments);
  }

  @Test
  void resolvesTheArgumentsOfThisMachine() {
    final String[] requested = { "--mute-audio" };
    final List<String> arguments = ChromeArguments.resolve(requested);
    final List<String> platform = ChromeArguments.detectPlatformArguments();
    final List<String> expected = ChromeArguments.resolve(requested, platform);
    assertEquals(expected, arguments);
  }

  @Test
  void cannotBeInstantiated() {
    UtilityClassAssertions.assertNotInstantiable(ChromeArguments.class);
  }
}
