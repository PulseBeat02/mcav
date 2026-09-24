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

import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/** Shares one browser while resetting pages and player state for each test. */
@Execution(ExecutionMode.SAME_THREAD)
@ExtendWith(SharedBrowserCache.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class PlaywrightSharedBrowserTest {

  private final PlaywrightPlayerTest fixture = new PlaywrightPlayerTest();

  @BeforeEach
  void startPagesAndPrepareBrowser() {
    this.fixture.startPages();
    this.fixture.openSharedBrowser();
  }

  @AfterEach
  void releasePlayers() {
    this.fixture.releasePlayers();
  }

  @AfterAll
  void checkBrowserReleased() {
    this.fixture.closeSharedBrowser();
  }

  @Test
  void streamsThePageAndFollowsPopupsThePageOpensAndCloses() {
    this.fixture.streamsThePageAndFollowsPopupsThePageOpensAndCloses();
  }

  @Test
  void forwardsEveryKindOfMouseInput() {
    this.fixture.forwardsEveryKindOfMouseInput();
  }

  @Test
  void pressesNamedKeysAndTypesText() {
    this.fixture.pressesNamedKeysAndTypesText();
  }

  @Test
  void ignoresInputWhileThePageIsStillLoading() {
    this.fixture.ignoresInputWhileThePageIsStillLoading();
  }

  @Test
  void staysOnTheFollowedPageWhenABackgroundPageClosesAndReturnsWhenItClosesItself() {
    this.fixture.staysOnTheFollowedPageWhenABackgroundPageClosesAndReturnsWhenItClosesItself();
  }

  @Test
  void failsWhenThePageCannotBeOpened() throws IOException {
    this.fixture.failsWhenThePageCannotBeOpened();
  }
}
