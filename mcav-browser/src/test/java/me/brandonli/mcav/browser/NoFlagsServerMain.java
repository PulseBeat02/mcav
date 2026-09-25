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

/**
 * The server side of {@link NoFlagsServerTest}: a JVM started like a Minecraft server on a machine without a display,
 * with no option but {@code -Djava.awt.headless=true}, installs mcav with the browser module as a plugin does, streams
 * a page and checks the color of the frame, then disables and enables the "plugin" and does it again.
 *
 * <p>The first round releases the player before mcav, as a plugin that cleans up after itself does; the second round
 * leaves the player running, so stopping the module has to end its browser.
 */
public final class NoFlagsServerMain {

  /**
   * The line printed when every check passed.
   */
  static final String PASSED = "NO-FLAGS PROOF PASSED";

  // GraalVM's JDK starts every JVM with these options for its compiler; they are not options of the server
  private static final List<String> GRAALVM_DEFAULTS = List.of(
    "-XX:ThreadPriorityPolicy=1",
    "-XX:+UnlockExperimentalVMOptions",
    "-XX:+EnableJVMCIProduct",
    "-XX:+EnableJVMCI",
    "-XX:-UnlockExperimentalVMOptions"
  );

  private NoFlagsServerMain() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Runs the proof.
   *
   * @param args the address of a red test page
   */
  public static void main(final String[] args) {
    final URI page = URI.create(args[0]);
    final List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
    System.out.println("JVM arguments: " + arguments);
    System.out.println("DISPLAY=" + System.getenv("DISPLAY") + " WAYLAND_DISPLAY=" + System.getenv("WAYLAND_DISPLAY"));
    final List<String> options = arguments.stream().filter(argument -> !GRAALVM_DEFAULTS.contains(argument)).toList();
    check(options.equals(List.of("-Djava.awt.headless=true")), "the JVM runs with no option but headless AWT");
    check(GraphicsEnvironment.isHeadless(), "AWT is headless");
    check(System.getenv("DISPLAY") == null && System.getenv("WAYLAND_DISPLAY") == null, "there is no display");
    for (int round = 1; round <= 2; round++) {
      final MCAVApi api = MCAV.api();
      api.install(BrowserModule.class);
      // the page is served on this machine, which the browser reaches only with private networks allowed
      final BrowserPlayer player = BrowserPlayer.create(BrowserOptions.builder().privateNetworks(true).build());
      final Frames frames = Frames.attach(player.getVideoAttachableCallback());
      check(player.start(BrowserSource.uri(page, 320, 240, 1)), "the browser starts");
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

  private static void check(final boolean condition, final String description) {
    if (!condition) {
      throw new AssertionError("Failed: " + description);
    }
  }
}
