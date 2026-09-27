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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import me.brandonli.mcav.browser.testing.Await;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.PlayerException;
import org.junit.jupiter.api.Test;

class BrowserModuleTest {

  private static final Path NATIVES = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();

  private final List<String> ends = new CopyOnWriteArrayList<>();

  private final BrowserSession.Listener listener = new BrowserSession.Listener() {
    @Override
    public void onFrame(final ImageBuffer frame) {
      frame.close();
    }

    @Override
    public void onAudio(final byte[] samples) {}

    @Override
    public void onEnded(final String reason, final Throwable cause) {
      BrowserModuleTest.this.ends.add(reason);
    }
  };

  private HelperSession open() {
    final HelperLauncher launcher = HelperSessionTest.launcher(ScriptedEngine.class.getName(), 60_000L);
    final BrowserSource source = BrowserSource.uri(URI.create("https://example.com/page"), 4, 3, 1);
    return HelperSession.open(launcher, NATIVES, source, BrowserOptions.DEFAULT, this.listener);
  }

  @Test
  void theModuleIsNamedBrowserAndStartsWithoutDownloadingAnything() {
    final BrowserModule module = new BrowserModule();
    assertEquals("browser", module.getModuleName());
    module.start();
    try {
      module.stop();
      assertEquals(0, HelperProcesses.count());
    } finally {
      module.start();
    }
  }

  @Test
  void stoppingTheModuleEndsEveryBrowserAndNoneStartsUntilItStartsAgain() {
    final BrowserModule module = new BrowserModule();
    final HelperSession session = this.open();
    final Process process = session.getProcess();
    try {
      module.stop();
      assertEquals(0, HelperProcesses.count());
      assertEquals(List.of("The browser module was stopped"), this.ends, "the player hears of it");
      Await.until("the helper ended", () -> !process.isAlive());
      final PlayerException refused = assertThrows(PlayerException.class, this::open);
      assertEquals("The browser module is stopped", refused.getMessage());
    } finally {
      module.start();
    }
    final HelperSession again = this.open();
    try {
      assertTrue(again.isAlive());
    } finally {
      again.close();
    }
  }
}
