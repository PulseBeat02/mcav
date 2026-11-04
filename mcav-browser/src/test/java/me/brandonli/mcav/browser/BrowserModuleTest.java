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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import me.brandonli.mcav.browser.testing.Await;
import me.brandonli.mcav.media.image.ImageBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrowserModuleTest {

  private static final String STALE_FOLDER_SUFFIX = "stale";

  private static final String TEMPORARY_DIRECTORY_PROPERTY = "java.io.tmpdir";

  private static final Path NATIVES = Path.of(System.getProperty(TEMPORARY_DIRECTORY_PROPERTY)).toAbsolutePath();

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
  void shouldRemoveTheFolderOfASessionWhoseServerWasKilledOnStart(@TempDir final Path temporary) throws IOException {
    final Path stale = Files.createDirectory(temporary.resolve(SessionFolders.PREFIX + STALE_FOLDER_SUFFIX));
    Files.writeString(stale.resolve(SessionFolders.OWNER_FILE), Long.MAX_VALUE + "\n2026-10-05T00:00:00Z\n", StandardCharsets.US_ASCII);
    final Path live = HelperSession.createFolder(temporary);
    final boolean hasOwnerFile = Files.isRegularFile(live.resolve(SessionFolders.OWNER_FILE));
    assertTrue(hasOwnerFile, "a new folder names its server");

    new BrowserModule().start(temporary);
    final boolean staleExists = Files.exists(stale);
    assertFalse(staleExists, "a killed server left it behind");

    final boolean liveExists = Files.exists(live);
    assertTrue(liveExists, "the folder of a session of this server stays until its browser is released");
  }

  @Test
  void shouldNameTheModuleBrowserAndStartWithoutDownloadingAnything() {
    final BrowserModule module = new BrowserModule();
    final String moduleName = module.getModuleName();
    assertEquals("browser", moduleName);

    module.start();
    try {
      module.stop();
      final int helperCount = HelperProcesses.count();
      assertEquals(0, helperCount);
    } finally {
      module.start();
    }
  }

  @Test
  void shouldEndEveryBrowserOnStopAndStartNoneUntilTheModuleStartsAgain() {
    final BrowserModule module = new BrowserModule();
    final HelperSession session = this.open();
    final Process process = session.getProcess();
    try {
      module.stop();
      final int helperCount = HelperProcesses.count();
      assertEquals(0, helperCount);
      assertEquals(List.of("The browser module was stopped!"), this.ends, "the player hears of it");

      Await.until("the helper ended", () -> !process.isAlive());
      final BrowserException refused = assertThrows(BrowserException.class, this::open);
      final String message = refused.getMessage();
      assertEquals("The browser module is stopped!", message);
    } finally {
      module.start();
    }

    final HelperSession again = this.open();
    try {
      final boolean alive = again.isAlive();
      assertTrue(alive);
    } finally {
      again.close();
    }
  }
}
