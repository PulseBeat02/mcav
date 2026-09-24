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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/** Owns the real processes whose death is part of the lifecycle assertion. */
@Execution(ExecutionMode.SAME_THREAD)
@ExtendWith(SharedBrowserCache.class)
final class PlaywrightBrowserCrashTest {

  private final PlaywrightPlayerTest fixture = new PlaywrightPlayerTest();

  @BeforeEach
  void startPagesAndPrepareBrowser() {
    this.fixture.startPages();
    SharedBrowserCache.evictAll();
  }

  @AfterEach
  void releasePlayers() {
    this.fixture.releasePlayers();
  }

  @Test
  void stopsPlayingWhenTheBrowserCrashesAndCanStartAgain() {
    this.fixture.stopsPlayingWhenTheBrowserCrashesAndCanStartAgain();
  }
}
