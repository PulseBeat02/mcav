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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import me.brandonli.mcav.browser.BrowserOptions;
import me.brandonli.mcav.browser.BrowserPlayer;
import me.brandonli.mcav.browser.BrowserSource;
import me.brandonli.mcav.browser.BrowserUnavailableException;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.audio.AudioOutputs;
import me.brandonli.mcav.sandbox.audio.AudioProvider;
import me.brandonli.mcav.sandbox.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.AudioArgument;
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
 * configuration with the Discord token, on the maps. Pages reach public addresses of the internet only, unless
 * {@code browser.allow-private-networks} in {@code config.yml} allows the server's own network.
 */
public final class BrowserCommand extends AbstractInteractiveCommand<BrowserPlayer> {

  /**
   * The permission a player needs to send input to the running browser, by chat or by clicking the screen.
   */
  static final String INTERACT_PERMISSION = "mcav.browser.interact";

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
   * Gets the permission a player needs to send input to the running browser.
   *
   * @return {@value #INTERACT_PERMISSION}
   */
  @Override
  protected String getInteractionPermission() {
    return INTERACT_PERMISSION;
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
    this.releaseSound(current);
  }

  /**
   * Handles {@code /mcav browser interact}: switches chat input to the browser on or off for the player who runs it.
   *
   * <p>While it is on, the chat messages of the player are not sent to chat. Their text is typed into the running
   * browser instead, for example into a search box that was clicked before. Clicking the map screen does not need
   * this mode: left and right clicks on the screen reach the browser for every player with this permission. Running
   * the command again switches it off, and the player is told which state is now active.
   *
   * <p>Requires the permission {@code mcav.browser.interact}. Only players can run it, since the console has no chat
   * to forward.
   *
   * @param sender the player who switches their chat input
   */
  @Command("mcav browser interact")
  @Permission(INTERACT_PERMISSION)
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
   * Handles {@code /mcav browser create <playerSelector> <browserResolution> <nth> <blockDimensions> <mapId>
   * <ditheringAlgorithm> <audioType> <url>}: opens a web page in mcav's embedded Chromium, which runs in a process of
   * its own, streams it onto a wall of maps, and its sound into the chosen audio output. The first browser on a server
   * downloads Chromium once, about 165 MB, and on Linux about 13 MB of the libraries it needs that the server lacks.
   *
   * <p>Build the wall first with {@code /mcav screen}, using the same block dimensions and map id. Players can then
   * left click or right click the screen to click the page at that spot, and type into it after enabling
   * {@code /mcav browser interact}. As in a desktop browser, a page plays sound only once a player clicked the screen
   * or typed into it. Only one browser runs at a time: creating a new one closes the running one, and the browser
   * takes over the audio output from any video or virtual machine until it is released.
   *
   * <p>Requires the permission {@code mcav.command.browser.create}; players and the console can run it. A server the
   * browser does not run on, invalid dimensions, a browser larger than {@value BrowserSource#MAX_SIDE} pixels on a
   * side, an address that is not an absolute {@code http} or {@code https} URL with a host, or an audio output that is
   * not enabled or not ready, are reported with an error message and nothing starts. Otherwise the browser starts in
   * the background, and the sender is told "Browser started!" once the page streams, and the viewers get the link of
   * the audio output; or the sender is told that the browser cannot run on this server, as when its download failed,
   * or that it could not be started, with the details in the console.
   *
   * @param sender             who ran the command
   * @param playerSelector     the players who see the browser on the maps, such as a player name or {@code @a}
   * @param browserResolution  the size of the browser window as {@code <width>x<height>} in pixels, such as
   *                           {@code 1280x720}; use 128 times the block dimensions to fill the wall exactly
   * @param nth                stream only every n-th frame of the browser; 1 streams every frame, higher values
   *                           lower the frame rate and the load on the server
   * @param blockDimensions    the size of the wall as {@code <width>x<height>} in maps, such as {@code 5x5}
   * @param mapId              the id of the top left map of the wall, as given to {@code /mcav screen}
   * @param ditheringAlgorithm how colors are reduced to the map palette; {@code FILTER_LITE} gives the best results,
   *                           and {@code NEAREST_COLOR} keeps text sharp and stable, see {@link DitheringArgument}
   * @param audioType          where the sound of the page plays, as for the video commands, see
   *                           {@link AudioArgument}; {@code NONE} keeps the page silent
   * @param url                the address of the page, such as {@code https://example.com}; the rest of the
   *                           command line. Other schemes, such as {@code file:}, are refused so that the maps
   *                           cannot show files of the server
   */
  @Command(
    "mcav browser create <playerSelector> <browserResolution> <nth> <blockDimensions> <mapId> <ditheringAlgorithm> <audioType> <url>"
  )
  @Permission("mcav.command.browser.create")
  @CommandDescription("mcav.command.browser.create.info")
  public void createBrowser(
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    @Argument(suggestions = "resolutions") @Quoted final String browserResolution,
    @Argument(suggestions = "nth") @Range(min = "1", max = "1000") final int nth,
    @Argument(suggestions = "dimensions") @Quoted final String blockDimensions,
    @Argument(suggestions = "ids") @Range(min = "0") final int mapId,
    final DitheringArgument ditheringAlgorithm,
    final AudioArgument audioType,
    @Greedy final String url
  ) {
    Preconditions.checkNotNull(playerSelector, "Player selector must not be null");
    Preconditions.checkNotNull(ditheringAlgorithm, "Dithering algorithm must not be null");
    Preconditions.checkNotNull(audioType, "Audio type must not be null");
    Preconditions.checkNotNull(url, "URL must not be null");

    if (!this.plugin.isBrowserSupported()) {
      final Component message = Message.BROWSER_UNSUPPORTED.build();
      sender.sendMessage(message);
      return;
    }

    final Pair<Integer, Integer> resolution = parseDimensions(sender, browserResolution);
    if (resolution == null) {
      return;
    }
    // the browser paints at most this many pixels per side, fewer than a wall of maps can show
    if (resolution.getFirst() > BrowserSource.MAX_SIDE || resolution.getSecond() > BrowserSource.MAX_SIDE) {
      final Component message = Message.UNSUPPORTED_DIMENSION.build();
      sender.sendMessage(message);
      return;
    }
    final Pair<Integer, Integer> blocks = parseScreenDimensions(sender, blockDimensions);
    if (blocks == null) {
      return;
    }
    final URI uri = parseWebAddress(url);
    if (uri == null) {
      final Component message = Message.UNSUPPORTED_URL.build();
      sender.sendMessage(message);
      return;
    }

    final AudioProvider provider = this.plugin.getAudioProvider();
    final Component audioProblem = AudioOutputs.findProblem(provider, audioType);
    if (audioProblem != null) {
      sender.sendMessage(audioProblem);
      return;
    }

    final BrowserSource source = createSource(uri, nth, resolution);
    final ScreenSettings settings = new ScreenSettings(playerSelector, blocks, resolution, mapId, ditheringAlgorithm);
    final Screen screen = this.createScreen(settings);
    final Player[] viewers = playerSelector.values().toArray(Player[]::new);
    final ScreenSound sound = new ScreenSound(audioType, viewers);
    this.createResource(() -> this.startBrowser(sender, screen, source, sound));
  }

  private static BrowserSource createSource(final URI uri, final int nth, final Pair<Integer, Integer> resolution) {
    final int width = resolution.getFirst();
    final int height = resolution.getSecond();
    return BrowserSource.uri(uri, width, height, nth);
  }

  /**
   * Builds the options of a new browser from {@code config.yml}.
   *
   * @return the options
   */
  BrowserOptions createOptions() {
    final PluginDataConfigurationMapper configuration = this.plugin.getConfiguration();
    final boolean privateNetworks = configuration.isBrowserPrivateNetworks();
    final boolean javaScriptJit = configuration.isBrowserJavaScriptJit();
    final boolean autoplay = configuration.isBrowserAutoplaySound();
    return BrowserOptions.builder().privateNetworks(privateNetworks).javaScriptJit(javaScriptJit).autoplay(autoplay).build();
  }

  private void startBrowser(final CommandSender sender, final Screen screen, final BrowserSource source, final ScreenSound sound) {
    final BrowserOptions options = this.createOptions();
    final BrowserPlayer browser = BrowserPlayer.create(options);
    this.ownCreatedPlayer(browser);
    final VideoAttachableCallback callback = browser.getVideoAttachableCallback();
    final VideoPipelineStep pipeline = screen.getPipeline();
    callback.attach(pipeline);
    this.attachSound(browser, browser.getAudioAttachableCallback(), sound, "Browser");

    final Component loading = Message.BROWSER_LOADING.build();
    sender.sendMessage(loading);
    final ExecutorService executor = this.startExecutor(browser, screen);
    final CompletableFuture<Boolean> start = browser.startAsync(source, executor);
    final URI uri = source.getUri();
    this.reportStartWhenDone(sender, browser, screen, start, "the browser for " + uri);
    this.sendSoundLinkWhenStarted(start, screen, sound);
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
   * Creates the message that tells the sender whether the browser started. A browser that cannot run on this server,
   * as when its download failed, gets its own message, also when wrapped in a {@link CompletionException}. The reason
   * of a failure is logged, not shown, since it may contain details of the server.
   *
   * @param success whether the browser started
   * @param error   why the browser failed to start, if known
   * @return "Browser started!" on success, otherwise the message of the failure
   */
  @Override
  protected Component createStartMessage(final boolean success, final @Nullable Throwable error) {
    if (success) {
      return Message.START_BROWSER.build();
    }
    Throwable cause = error;
    if (cause instanceof CompletionException) {
      cause = cause.getCause();
    }
    if (cause instanceof BrowserUnavailableException) {
      return Message.BROWSER_UNAVAILABLE.build();
    }
    return Message.BROWSER_ERROR.build();
  }
}
