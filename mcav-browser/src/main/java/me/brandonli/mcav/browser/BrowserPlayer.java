/*
 * This file is part of mcav, a media playback library for Minecraft
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.player.ReleasablePlayer;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.BrowserSource;
import me.brandonli.mcav.utils.interaction.MouseClick;

/**
 * Interface representing a browser-based player that supports interaction with browser elements,
 * video pipeline processing, and mouse event handling.
 * Extends the functionality of {@link ReleasablePlayer}.
 */
public interface BrowserPlayer extends ReleasablePlayer {
  String[] DEFAULT_CHROME_ARGUMENTS = { "--headless", "--disable-gpu", "--disable-software-rasterizer" };

  boolean start(final VideoPipelineStep videoPipeline, final BrowserSource combined);

  default CompletableFuture<Boolean> startAsync(
    final VideoPipelineStep videoPipeline,
    final BrowserSource combined,
    final ExecutorService service
  ) {
    return CompletableFuture.supplyAsync(() -> this.start(videoPipeline, combined), service);
  }

  default CompletableFuture<Boolean> startAsync(final VideoPipelineStep videoPipeline, final BrowserSource combined) {
    return this.startAsync(videoPipeline, combined, ForkJoinPool.commonPool());
  }

  void moveMouse(final int x, final int y);

  void sendMouseEvent(final MouseClick type, final int x, final int y);

  void sendKeyEvent(final String text);

  /**
   * Creates a default instance of {@code ChromeDriverPlayer} with pre-defined arguments to
   * configure the Chrome browser in a headless environment.
   * The default arguments include configurations for performance, security, and resource optimization.
   *
   * @return a {@code BrowserPlayer} instance configured with default Chrome arguments.
   */
  static BrowserPlayer defaultSelenium() {
    return new SeleniumPlayer(DEFAULT_CHROME_ARGUMENTS);
  }

  /**
   * Creates a new instance of a {@link BrowserPlayer} using the given arguments for the Chrome browser.
   *
   * @param args the arguments to be passed to the Chrome browser instance. These arguments
   *             can be used to customize browser behavior, such as enabling or disabling features.
   *             If no arguments are provided, default Chrome arguments can be used via {@link BrowserPlayer#defaultSelenium()}.
   * @return a {@link BrowserPlayer} instance configured with the specified Chrome arguments.
   */
  static BrowserPlayer selenium(final String... args) {
    return new SeleniumPlayer(args);
  }

  static BrowserPlayer playwright(final String... args) {
    return new PlaywrightPlayer(args);
  }
}
