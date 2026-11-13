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

import java.awt.GraphicsEnvironment;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.util.List;
import me.brandonli.mcav.MCAV;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.browser.testing.Await;
import me.brandonli.mcav.browser.testing.Frames;
import me.brandonli.mcav.browser.testing.TestPages;

// NoFlagsServerTest's child JVM, run twice like a plugin: its player released before MCAV, then left running for the module stop to end.
public final class NoFlagsServerMain {

  static final String HEADLESS_OPTION = "-Djava.awt.headless=true";

  private static final String DISPLAY_VARIABLE = "DISPLAY";
  private static final String WAYLAND_DISPLAY_VARIABLE = "WAYLAND_DISPLAY";

  private static final int ROUNDS = 2;

  private static final int FRAME_INTERVAL = 1;

  static final String PASSED = "NO-FLAGS PROOF PASSED";

  private static final List<String> GRAALVM_DEFAULTS = List.of(
    "-XX:ThreadPriorityPolicy=1",
    "-XX:+UnlockExperimentalVMOptions",
    "-XX:+EnableJVMCIProduct",
    "-XX:+EnableJVMCI",
    "-XX:-UnlockExperimentalVMOptions"
  );

  private NoFlagsServerMain() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  public static void main(final String[] args) {
    final URI page = URI.create(args[0]);
    final List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
    System.out.println("JVM arguments: %s".formatted(arguments));
    final String display = System.getenv(DISPLAY_VARIABLE);
    final String waylandDisplay = System.getenv(WAYLAND_DISPLAY_VARIABLE);
    System.out.println("DISPLAY=%s WAYLAND_DISPLAY=%s".formatted(display, waylandDisplay));
    final List<String> options = arguments
      .stream()
      .filter(argument -> !GRAALVM_DEFAULTS.contains(argument))
      .toList();
    check(options.equals(List.of(HEADLESS_OPTION)), "the JVM runs with no option but headless AWT");
    check(GraphicsEnvironment.isHeadless(), "AWT is headless");
    check(display == null && waylandDisplay == null, "there is no display");
    for (int round = 1; round <= ROUNDS; round++) {
      final MCAVApi api = MCAV.api();
      api.install(BrowserModule.class);
      final BrowserPlayer player = BrowserPlayer.create(BrowserOptions.builder().privateNetworks(true).build());
      final Frames frames = Frames.attach(player.getVideoAttachableCallback());
      check(player.start(BrowserSource.uri(page, 320, 240, FRAME_INTERVAL)), "the browser starts");
      Await.until("a red frame", () -> frames.lastShows(TestPages.MAIN_COLOR));
      final Frames.Frame frame = frames.requireLast();
      System.out.printf("round %d: a %dx%d frame, center pixel %06x%n", round, frame.getWidth(), frame.getHeight(), frame.getCenter());
      check(CefBrowserIntegrationTest.countBrowserProcesses() > 0, "the browser runs in processes of its own");
      if (round == 1) {
        player.release();
      }
      api.release();
      check(HelperProcesses.count() == 0, "no browser session is left");
      Await.until("every browser process ended", () -> CefBrowserIntegrationTest.countBrowserProcesses() == 0);
      System.out.printf("round %d: disabled, no browser process left%n", round);
    }

    System.out.println(PASSED);
    System.exit(0);
  }

  private static void check(final boolean holds, final String description) {
    if (!holds) {
      final String message = "The check that %s failed!".formatted(description);
      throw new AssertionError(message);
    }
  }
}
