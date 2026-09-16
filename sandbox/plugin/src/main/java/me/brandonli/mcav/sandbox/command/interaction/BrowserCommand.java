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
package me.brandonli.mcav.sandbox.command.interaction;

import com.google.common.base.Preconditions;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import me.brandonli.mcav.browser.BrowserPlayer;
import me.brandonli.mcav.browser.BrowserSource;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.utils.immutable.Pair;
import me.brandonli.mcav.utils.interaction.MouseClick;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotation.specifier.Quoted;
import org.incendo.cloud.annotation.specifier.Range;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;

/**
 * {@code /mcav browser create|interact|release}: streams a web page onto a map screen.
 *
 * <p>Only web pages on {@code http} and {@code https} addresses are opened. Other addresses, such as
 * {@code file:} or {@code chrome:}, would let anyone with the permission show local files of the server, like the
 * configuration with the Discord token, on the maps.
 */
public final class BrowserCommand extends AbstractInteractiveCommand<BrowserPlayer> {

  private static final Set<String> WEB_SCHEMES = Set.of("http", "https");

  /**
   * Constructs the command.
   *
   * @param plugin the plugin
   */
  public BrowserCommand(final MCAVSandbox plugin) {
    super(plugin);
  }

  /**
   * Clicks the page of the browser with the left mouse button.
   *
   * @param current the running browser
   * @param x       the x coordinate on the page, in pixels
   * @param y       the y coordinate on the page, in pixels
   */
  @Override
  protected void handleLeftClick(final BrowserPlayer current, final int x, final int y) {
    Preconditions.checkNotNull(current, "Browser must not be null");
    current.sendMouseEvent(MouseClick.LEFT, x, y);
  }

  /**
   * Clicks the page of the browser with the right mouse button.
   *
   * @param current the running browser
   * @param x       the x coordinate on the page, in pixels
   * @param y       the y coordinate on the page, in pixels
   */
  @Override
  protected void handleRightClick(final BrowserPlayer current, final int x, final int y) {
    Preconditions.checkNotNull(current, "Browser must not be null");
    current.sendMouseEvent(MouseClick.RIGHT, x, y);
  }

  /**
   * Types text into the element of the page that has the focus.
   *
   * @param current the running browser
   * @param text    the text to type
   */
  @Override
  protected void handleTextInput(final BrowserPlayer current, final String text) {
    Preconditions.checkNotNull(current, "Browser must not be null");
    Preconditions.checkNotNull(text, "Text must not be null");
    current.sendKeyEvent(text);
  }

  /**
   * Closes a browser.
   *
   * @param current the browser to close, which is no longer the running one
   */
  @Override
  protected void releasePlayer(final BrowserPlayer current) {
    Preconditions.checkNotNull(current, "Browser must not be null");
    current.release();
  }

  /**
   * Handles {@code /mcav browser interact}: switches chat input to the browser on or off for the player who runs it.
   *
   * <p>While it is on, the chat messages of the player are not sent to chat. Their text is typed into the running
   * browser instead, for example into a search box that was clicked before. Clicking the map screen does not need
   * this mode: left and right clicks on the screen always reach the browser. Running the command again switches it
   * off, and the player is told which state is now active.
   *
   * <p>Requires the permission {@code mcav.browser.interact}. Only players can run it, since the console has no chat
   * to forward.
   *
   * @param sender the player who switches their chat input
   */
  @Command("mcav browser interact")
  @Permission("mcav.browser.interact")
  @CommandDescription("mcav.command.browser.interact.info")
  public void toggleInteraction(final Player sender) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    final Component enabled = Message.INTERACT_ENABLE.build();
    final Component disabled = Message.INTERACT_DISABLE.build();
    this.toggleInteraction(sender, enabled, disabled);
  }

  /**
   * Handles {@code /mcav browser release}: closes the running browser, stops streaming to its maps, and clears the
   * maps for every viewer. The wall of item frames stays in place for the next browser, video, or image.
   *
   * <p>Requires the permission {@code mcav.browser.release}; players and the console can run it. The sender is
   * told "Browser released!", also when no browser was running.
   *
   * @param sender who ran the command
   */
  @Command("mcav browser release")
  @Permission("mcav.browser.release")
  @CommandDescription("mcav.command.browser.release.info")
  public void releaseBrowser(final CommandSender sender) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    final Component released = Message.RELEASE_BROWSER.build();
    this.releaseResource(sender, released);
  }

  /**
   * Handles {@code /mcav browser create <playerSelector> <browserResolution> <quality> <nth> <blockDimensions> <mapId>
   * <ditheringAlgorithm> <url>}: opens a web page in the Chrome installed on the server, driven through Selenium,
   * and streams it onto a wall of maps.
   *
   * <p>Build the wall first with {@code /mcav screen}, using the same block dimensions and map id. Players can then
   * left click or right click the screen to click the page at that spot, and type into it after enabling
   * {@code /mcav browser interact}. Only one browser runs at a time: creating a new one closes the running one.
   *
   * <p>Requires the permission {@code mcav.command.browser.create}; players and the console can run it. Invalid
   * dimensions, or an address that is not an absolute {@code http} or {@code https} URL with a host, are reported
   * with an error message and nothing starts. Otherwise the browser starts in the background, and the sender is
   * told "Browser started!" once the page streams, or that it could not be started, with the details in the
   * console.
   *
   * @param sender             who ran the command
   * @param playerSelector     the players who see the browser on the maps, such as a player name or {@code @a}
   * @param browserResolution  the size of the browser window as {@code <width>x<height>} in pixels, such as
   *                           {@code 1280x720}; use 128 times the block dimensions to fill the wall exactly
   * @param quality            the JPEG quality of the frames the browser sends, from 1 to 100; lower values use
   *                           less CPU and bandwidth at the cost of compression artifacts
   * @param nth                stream only every n-th frame of the browser; 1 streams every frame, higher values
   *                           lower the frame rate and the load on the server
   * @param blockDimensions    the size of the wall as {@code <width>x<height>} in maps, such as {@code 5x5}
   * @param mapId              the id of the top left map of the wall, as given to {@code /mcav screen}
   * @param ditheringAlgorithm how colors are reduced to the map palette; {@code FILTER_LITE} gives the best results,
   *                           and {@code NEAREST_COLOR} keeps text sharp and stable, see {@link DitheringArgument}
   * @param url                the address of the page, such as {@code https://example.com}; the rest of the
   *                           command line. Other schemes, such as {@code file:}, are refused so that the maps
   *                           cannot show files of the server
   */
  @Command("mcav browser create <playerSelector> <browserResolution> <quality> <nth> <blockDimensions> <mapId> <ditheringAlgorithm> <url>")
  @Permission("mcav.command.browser.create")
  @CommandDescription("mcav.command.browser.create.info")
  public void createBrowser(
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    @Argument(suggestions = "resolutions") @Quoted final String browserResolution,
    @Argument(suggestions = "quality") @Range(min = "1", max = "100") final int quality,
    @Argument(suggestions = "nth") @Range(min = "1") final int nth,
    @Argument(suggestions = "dimensions") @Quoted final String blockDimensions,
    @Argument(suggestions = "ids") @Range(min = "0") final int mapId,
    final DitheringArgument ditheringAlgorithm,
    @Greedy final String url
  ) {
    Preconditions.checkNotNull(playerSelector, "Player selector must not be null");
    Preconditions.checkNotNull(ditheringAlgorithm, "Dithering algorithm must not be null");
    Preconditions.checkNotNull(url, "URL must not be null");

    final Pair<Integer, Integer> resolution = parseDimensions(sender, browserResolution);
    if (resolution == null) {
      return;
    }
    final Pair<Integer, Integer> blocks = parseDimensions(sender, blockDimensions);
    if (blocks == null) {
      return;
    }
    final URI uri = parseWebAddress(url);
    if (uri == null) {
      final Component message = Message.UNSUPPORTED_URL.build();
      sender.sendMessage(message);
      return;
    }

    final ScreenSettings settings = new ScreenSettings(playerSelector, blocks, resolution, mapId, ditheringAlgorithm);
    final Screen screen = this.createScreen(settings);
    final BrowserSource source = createSource(uri, quality, nth, resolution);
    this.startBrowser(sender, screen, source);
  }

  private static BrowserSource createSource(final URI uri, final int quality, final int nth, final Pair<Integer, Integer> resolution) {
    final int width = resolution.getFirst();
    final int height = resolution.getSecond();
    return BrowserSource.uri(uri, quality, width, height, nth);
  }

  private void startBrowser(final CommandSender sender, final Screen screen, final BrowserSource source) {
    final BrowserPlayer browser = BrowserPlayer.selenium();
    final VideoAttachableCallback callback = browser.getVideoAttachableCallback();
    final VideoPipelineStep pipeline = screen.getPipeline();
    callback.attach(pipeline);

    final CompletableFuture<Boolean> start = browser.startAsync(source, this.service);
    final URI uri = source.getUri();
    this.reportStartWhenDone(sender, browser, screen, start, "the browser for " + uri);
  }

  /**
   * Parses the address of a web page.
   *
   * @param url the address as entered
   * @return the address, or {@code null} if it is not valid or not an absolute {@code http} or {@code https}
   * address with a host
   */
  static @Nullable URI parseWebAddress(final String url) {
    final URI uri;
    try {
      uri = URI.create(url);
    } catch (final IllegalArgumentException exception) {
      return null;
    }

    final String scheme = uri.getScheme();
    final String host = uri.getHost();
    if (scheme == null || host == null) {
      return null;
    }
    final String lowerScheme = scheme.toLowerCase(Locale.ROOT);
    final boolean web = WEB_SCHEMES.contains(lowerScheme);
    return web ? uri : null;
  }

  /**
   * Creates the message that tells the sender whether the browser started. The reason of a failure is logged, not
   * shown, since it may contain details of the server.
   *
   * @param success whether the browser started
   * @param error   why the browser failed to start, if known
   * @return "Browser started!" on success, otherwise the browser error message
   */
  @Override
  protected Component createStartMessage(final boolean success, final @Nullable Throwable error) {
    if (success) {
      return Message.START_BROWSER.build();
    }
    return Message.BROWSER_ERROR.build();
  }
}
