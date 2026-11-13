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
package me.brandonli.mcav.plugin.command.interaction;

import com.google.common.base.Equivalence;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ForwardingExecutorService;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Settings;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Configuration;
import me.brandonli.mcav.bukkit.media.result.CompressedMapResult;
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.audio.AudioOutputs;
import me.brandonli.mcav.plugin.audio.AudioProvider;
import me.brandonli.mcav.plugin.command.AnnotationCommandFeature;
import me.brandonli.mcav.plugin.command.MapDisplaySettings;
import me.brandonli.mcav.plugin.command.video.Mcv2Output;
import me.brandonli.mcav.plugin.command.video.Mcv2Support;
import me.brandonli.mcav.plugin.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.plugin.listener.OnlinePlayers;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.utils.ArgumentUtils;
import me.brandonli.mcav.plugin.utils.AudioArgument;
import me.brandonli.mcav.plugin.utils.CleanupUtils;
import me.brandonli.mcav.plugin.utils.DitheringArgument;
import me.brandonli.mcav.plugin.utils.InteractUtils;
import me.brandonli.mcav.plugin.utils.Keys;
import me.brandonli.mcav.plugin.utils.MapCodec;
import me.brandonli.mcav.plugin.utils.TaskUtils;
import me.brandonli.mcav.utils.ExecutorUtils;
import me.brandonli.mcav.utils.ThrowableUtils;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.RayTraceResult;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shows an interactive player, such as a browser or a virtual machine, on a map screen and forwards clicks on the
 * screen and chat messages of players who enabled interaction.
 *
 * <p>Input only reaches the running player from a player who has {@link #getInteractionPermission()}. The screen is
 * built in the world, so any player can reach it, and the page or the guest desktop on it belongs to whoever started
 * it. Protecting the frames of the screen needs no permission, so a player without it can neither break the wall nor
 * turn the maps in their frames.
 *
 * <p>This class is thread-safe: the running player and its output change only while holding the lock of this command,
 * and {@link #onChatMessage(AsyncChatEvent)}, which Paper calls off the main thread, reads them through volatile fields
 * and a synchronized set. Cloud runs the commands, and Paper the other listeners, on the server main thread; players
 * start and stop on the thread of {@link #service}. A start that fails replies and gives back its output on the main
 * thread, since an MCV2 output removes the item frames of its screen, which Paper allows there only.
 *
 * @param <PlayerType> the type of the interactive player
 * @since 1.0.0
 */
public abstract class AbstractInteractiveCommand<PlayerType> implements AnnotationCommandFeature, Listener {

  private static final String SENDER_NULL_MESSAGE = "Sender must not be null!";
  private static final String TEXT_NULL_MESSAGE = "Text must not be null!";
  private static final String EVENT_NULL_MESSAGE = "Event must not be null!";

  private static final Equivalence<Object> IDENTITY = Equivalence.identity();

  private static final Logger LOGGER = LoggerFactory.getLogger(AbstractInteractiveCommand.class);

  private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

  private static final int REACH = 100;

  private static final double HALF_BLOCK = 0.5;

  /**
   * The plugin.
   *
   * @since 1.0.0
   */
  protected final MCAVSandbox plugin;

  /**
   * The thread that starts players, so the main thread never waits for a browser or a virtual machine.
   *
   * @since 1.0.0
   */
  protected final ExecutorService service;

  private final Set<Player> activePlayers;
  private final Object lock;

  private volatile @Nullable Screen screen;
  private boolean closed;

  /**
   * The output the player's frames go to, while one is running: the dithered maps, or the MCV2 screen. Changed only
   * while holding the lock of this command.
   *
   * @since 1.0.0
   */
  protected volatile @Nullable FunctionalVideoFilter output;

  /**
   * The running player. Changed only while holding the lock of this command.
   *
   * @since 1.0.0
   */
  protected volatile @Nullable PlayerType player;

  // the provider the sound was attached to, so a release still reaches it once the disabling plugin no longer hands it out
  private volatile @Nullable AudioProvider soundProvider;

  /**
   * Creates the command and the thread that starts its players.
   *
   * @param plugin the plugin
   * @throws NullPointerException if {@code plugin} is null
   * @since 1.0.0
   */
  protected AbstractInteractiveCommand(final MCAVSandbox plugin) {
    this(plugin, Executors.newSingleThreadExecutor());
  }

  AbstractInteractiveCommand(final MCAVSandbox plugin, final ExecutorService service) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null!");
    Preconditions.checkNotNull(service, "Executor must not be null!");

    this.plugin = plugin;
    this.service = service;
    final WeakHashMap<Player, Boolean> backing = new WeakHashMap<>();
    final Set<Player> players = Collections.newSetFromMap(backing);
    this.activePlayers = Collections.synchronizedSet(players);
    this.lock = new Object();
  }

  /**
   * Registers this command as a listener, so clicks on screens and chat messages reach the running player.
   *
   * @param parser the parser the annotations of this command are about to be parsed with
   * @throws NullPointerException if {@code parser} is null
   * @since 1.0.0
   */
  @Override
  public void registerFeature(final AnnotationParser<CommandSender> parser) {
    Preconditions.checkNotNull(parser, "Parser must not be null!");

    final Server server = this.plugin.getServer();
    final PluginManager pluginManager = server.getPluginManager();
    pluginManager.registerEvents(this, this.plugin);
  }

  /**
   * Releases the running player and its maps, stops listening for events, and stops the thread that starts
   * players.
   *
   * @since 1.0.0
   */
  @Override
  public void shutdown() {
    synchronized (this.lock) {
      if (this.closed) {
        return;
      }

      this.closed = true;
    }
    CleanupUtils.runAll(
      this::releaseCurrent,
      () -> HandlerList.unregisterAll(this),
      () -> ExecutorUtils.shutdownExecutorGracefully(this.service)
    );
  }

  /**
   * Releases the running player and its maps, if any.
   *
   * @since 1.0.0
   */
  protected final void releaseCurrent() {
    final PlayerType current;
    final FunctionalVideoFilter output;
    final Screen oldScreen;
    synchronized (this.lock) {
      current = this.player;
      output = this.output;
      oldScreen = this.screen;
      this.player = null;
      this.output = null;
      this.screen = null;
    }
    final boolean workerOwnsCleanup = oldScreen != null && oldScreen.cancel();
    final @Nullable PlayerType releasable = workerOwnsCleanup ? null : current;
    CleanupUtils.runAll(() -> this.releasePlayerIfPresent(releasable), () -> this.releaseOutputIfPresent(output));
  }

  private void releasePlayerIfPresent(final @Nullable PlayerType player) {
    if (player != null) {
      this.releasePlayer(player);
    }
  }

  private void releaseOutputIfPresent(final @Nullable FunctionalVideoFilter output) {
    if (output != null) {
      output.release();
    }
  }

  // off the main thread: a browser or virtual machine takes up to ten seconds to stop
  final void releaseInTheBackground(final Runnable release) {
    final boolean closing;
    synchronized (this.lock) {
      closing = this.closed;
    }
    if (!closing) {
      try {
        this.service.execute(() -> this.releaseLogged(release));
        return;
      } catch (final RejectedExecutionException exception) {
        // the thread takes no more work, so the player stops here
      }
    }
    release.run();
  }

  private void releaseLogged(final Runnable release) {
    try {
      release.run();
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      LOGGER.error("A player failed to stop.", exception);
    }
  }

  final void attachSound(final Object owner, final AudioAttachableCallback audio, final ScreenSound sound, final String title) {
    final AudioArgument type = sound.getType();
    if (type == AudioArgument.NONE) {
      return;
    }

    final AudioProvider provider = this.plugin.getAudioProvider();
    this.soundProvider = provider;
    final UrlParseDump dump = UrlParseDump.of(title);
    final Player[] viewers = sound.getViewers();
    final AudioFilter filter = provider.constructFilter(type, dump, viewers, owner);
    audio.attach(AudioPipelineStep.of(filter));
  }

  final void sendSoundLinkWhenStarted(final CompletableFuture<Boolean> start, final Screen screen, final ScreenSound sound) {
    TaskUtils.whenComplete(start, (started, failure) -> this.sendSoundLinkIfStarted(started, failure, screen, sound));
  }

  private void sendSoundLinkIfStarted(
    final @Nullable Boolean started,
    final @Nullable Throwable failure,
    final Screen screen,
    final ScreenSound sound
  ) {
    // a release cancels the start, or later the screen; releases run on the main thread, which checks before sending the links
    if (failure == null && Boolean.TRUE.equals(started)) {
      final AudioProvider provider = this.plugin.getAudioProvider();
      TaskUtils.runOnMainThread(this.plugin, () -> this.sendSoundLinkUnlessCancelled(provider, screen, sound));
    }
  }

  private void sendSoundLinkUnlessCancelled(final AudioProvider provider, final Screen screen, final ScreenSound sound) {
    if (!screen.isCancelled()) {
      final AudioArgument type = sound.getType();
      final Player[] viewers = sound.getViewers();
      AudioOutputs.sendLink(provider, type, viewers);
    }
  }

  // skipped when a video or another player took the outputs over meanwhile
  final void releaseSound(final Object owner) {
    final AudioProvider provider = this.soundProvider;
    if (provider != null) {
      provider.releaseAudioFilter(owner);
    }
  }

  /**
   * Parses a size such as {@code 1920x1080}, telling the sender when it is not valid.
   *
   * @param sender who ran the command
   * @param text   the size as entered
   * @return the width and height, or empty if the text is not a valid size
   * @throws NullPointerException if an argument is null
   * @since 1.0.0
   */
  protected static Optional<Pair<Integer, Integer>> parseDimensions(final CommandSender sender, final String text) {
    Preconditions.checkNotNull(sender, SENDER_NULL_MESSAGE);
    Preconditions.checkNotNull(text, TEXT_NULL_MESSAGE);

    try {
      final Pair<Integer, Integer> dimensions = ArgumentUtils.parseDimensions(text);
      return Optional.of(dimensions);
    } catch (final IllegalArgumentException exception) {
      final Component message = Message.UNSUPPORTED_DIMENSION.build();
      sender.sendMessage(message);
      return Optional.empty();
    }
  }

  /**
   * Parses the size of a wall of maps such as {@code 5x5}, telling the sender when it is not valid or larger than
   * {@link ArgumentUtils#parseScreenDimensions(String)} allows.
   *
   * @param sender who ran the command
   * @param text   the size as entered
   * @return the width and height in maps, or empty if the text is not a valid wall size
   * @throws NullPointerException if an argument is null
   * @since 1.0.0
   */
  protected static Optional<Pair<Integer, Integer>> parseScreenDimensions(final CommandSender sender, final String text) {
    Preconditions.checkNotNull(sender, SENDER_NULL_MESSAGE);
    Preconditions.checkNotNull(text, TEXT_NULL_MESSAGE);

    try {
      final Pair<Integer, Integer> dimensions = ArgumentUtils.parseScreenDimensions(text);
      return Optional.of(dimensions);
    } catch (final IllegalArgumentException exception) {
      final Component message = Message.UNSUPPORTED_DIMENSION.build();
      sender.sendMessage(message);
      return Optional.empty();
    }
  }

  final Screen createScreen(final ScreenSettings settings) {
    synchronized (this.lock) {
      Preconditions.checkState(!this.closed, "The interactive command is shut down!");
    }
    this.releaseCurrent();
    final MultiplePlayerSelector viewers = settings.getViewers();
    final OnlinePlayers onlinePlayers = this.plugin.getOnlinePlayers();
    final Collection<UUID> players = ArgumentUtils.resolveViewers(viewers, onlinePlayers);
    final FunctionalVideoFilter output = this.createOutput(settings, players);
    synchronized (this.lock) {
      this.output = output;
    }
    try {
      return this.startScreen(settings, output);
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      this.cleanupAfterFailure(exception, this::releaseCurrent);
      throw exception;
    }
  }

  private Screen startScreen(final ScreenSettings settings, final FunctionalVideoFilter output) {
    final Pair<Integer, Integer> blocks = settings.getBlocks();
    final int mapId = settings.getMapId();
    output.start();
    final int columns = blocks.getFirst();
    final int rows = blocks.getSecond();
    final long mapCount = (long) columns * rows;
    final VideoPipelineStep announcement = VideoPipelineStep.of(this.announceFirstPicture(mapId, mapCount, LOGGER::info));
    final VideoPipelineStep pipeline = VideoPipelineStep.of(announcement, output);
    final Pair<Integer, Integer> resolution = settings.getResolution();
    final boolean stretched = output instanceof Mcv2Output;
    final int width = resolution.getFirst();
    final int height = resolution.getSecond();
    final WallPicture picture = WallPicture.of(columns, rows, width, height, stretched);
    final Screen created = new Screen(output, pipeline, mapId, mapCount, picture);
    synchronized (this.lock) {
      this.output = output;
      this.screen = created;
    }
    return created;
  }

  final MapCodec chooseCodec(final @Nullable MapCodec flag) {
    final PluginDataConfigurationMapper configuration = this.plugin.getConfiguration();
    final MapCodec defaultCodec = configuration.getMcv2DefaultCodec();
    return flag != null ? flag : defaultCodec;
  }

  // releasing the output releases the maps
  private FunctionalVideoFilter createOutput(final ScreenSettings settings, final Collection<UUID> players) {
    final MapCodec codec = settings.getCodec();
    if (codec == MapCodec.MCV2) {
      final Mcv2Output output = this.createMcv2Output(settings, players);
      if (output != null) {
        return output;
      }
    }
    return this.createDitheredOutput(settings, players);
  }

  private @Nullable Mcv2Output createMcv2Output(final ScreenSettings settings, final Collection<UUID> players) {
    final Pair<Integer, Integer> blocks = settings.getBlocks();
    final Pair<Integer, Integer> resolution = settings.getResolution();
    final int mapId = settings.getMapId();
    final DitheringArgument dithering = settings.getDithering();
    final Mcv2Support support = this.plugin.getMcv2Support();
    final CommandSender sender = settings.getSender();
    final Optional<Mcv2Configuration> foundConfiguration = support.configure(sender, blocks, resolution, mapId, Settings.DEFAULT, players);
    final Optional<Mcv2Output> output = foundConfiguration.flatMap(configuration -> support.output(sender, configuration, dithering));
    return output.orElse(null);
  }

  private FunctionalVideoFilter createDitheredOutput(final ScreenSettings settings, final Collection<UUID> players) {
    final Pair<Integer, Integer> blocks = settings.getBlocks();
    final Pair<Integer, Integer> resolution = settings.getResolution();
    final int mapId = settings.getMapId();
    final DitheringArgument dithering = settings.getDithering();
    final MapConfiguration configuration = MapDisplaySettings.createConfiguration(blocks, resolution, mapId, players);
    final DitherAlgorithm algorithm = dithering.createAlgorithm();
    final CompressedMapResult maps = new CompressedMapResult(configuration);
    try {
      return DitherFilter.dither(algorithm, maps);
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      this.cleanupAfterFailure(exception, maps::release);
      throw exception;
    }
  }

  VideoFilter announceFirstPicture(final int mapId, final long mapCount, final Consumer<String> log) {
    final AtomicBoolean announced = new AtomicBoolean();
    final long lastMapId = mapId + mapCount - 1;
    final String message = "Maps %s to %s show their first picture.".formatted(mapId, lastMapId);
    return (image, metadata) -> this.announceOnce(announced, message, log);
  }

  private boolean announceOnce(final AtomicBoolean announced, final String message, final Consumer<String> log) {
    if (announced.compareAndSet(false, true)) {
      log.accept(message);
    }
    return false;
  }

  // taken before attaching callbacks or submitting the start, which can fail
  final void ownCreatedPlayer(final PlayerType created) {
    synchronized (this.lock) {
      this.player = created;
    }
  }

  final void createResource(final Runnable create) {
    try {
      create.run();
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      this.cleanupAfterFailure(exception, this::releaseCurrent);
      throw exception;
    }
  }

  // a cancelled queued start is skipped; the worker cleans up a start cancelled in flight
  final ExecutorService startExecutor(final PlayerType started, final Screen screen) {
    return new ForwardingExecutorService() {
      @Override
      protected ExecutorService delegate() {
        return AbstractInteractiveCommand.this.service;
      }

      @Override
      public void execute(final Runnable task) {
        final ExecutorService executor = this.delegate();
        executor.execute(() -> AbstractInteractiveCommand.this.runStartTask(started, screen, task));
      }
    };
  }

  private void runStartTask(final PlayerType started, final Screen screen, final Runnable task) {
    if (!screen.beginStart()) {
      return;
    }

    try {
      task.run();
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      this.cleanupAfterFailure(exception, () -> this.finishStartTask(started, screen));
      throw exception;
    }
    this.finishStartTask(started, screen);
  }

  private void cleanupAfterFailure(final Throwable failure, final Runnable cleanup) {
    try {
      cleanup.run();
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      final boolean sameFailure = IDENTITY.equivalent(failure, exception);
      if (!sameFailure) {
        failure.addSuppressed(exception);
      }
    }
  }

  private void finishStartTask(final PlayerType started, final Screen screen) {
    final boolean cancelled = screen.finishStart();
    if (cancelled) {
      this.releasePlayer(started);
    }
  }

  final void reportStartWhenDone(
    final CommandSender sender,
    final PlayerType started,
    final Screen screen,
    final CompletableFuture<Boolean> start,
    final String description
  ) {
    synchronized (this.lock) {
      final boolean current = IDENTITY.equivalent(this.screen, screen);
      if (!this.closed && current) {
        this.player = started;
      }
    }
    screen.bindStart(start);
    final FunctionalVideoFilter output = screen.getOutput();
    final StartAttempt<PlayerType> attempt = new StartAttempt<>(sender, started, output, screen, description);
    TaskUtils.whenComplete(start, (succeeded, failure) -> this.onStartCompleted(attempt, succeeded, failure));
  }

  private void onStartCompleted(
    final StartAttempt<PlayerType> attempt,
    final @Nullable Boolean started,
    final @Nullable Throwable failure
  ) {
    final Screen screen = attempt.screen();
    if (screen.isCancelled()) {
      return;
    }

    final boolean succeeded = failure == null && Boolean.TRUE.equals(started);
    if (!succeeded && !this.isRefusal(failure)) {
      final String description = attempt.description();
      LOGGER.error("Starting {} failed.", description, failure);
    }

    final StartOutcome outcome = succeeded ? StartOutcome.STARTED : StartOutcome.FAILED;
    final Component message = this.createStartMessage(outcome, failure);
    final boolean handedOver = TaskUtils.runOnMainThread(this.plugin, () -> this.replyAndReleaseIfFailed(attempt, outcome, message));

    if (!handedOver) {
      this.releaseIfFailed(attempt, outcome);
    }
  }

  private void replyAndReleaseIfFailed(final StartAttempt<PlayerType> attempt, final StartOutcome outcome, final Component message) {
    final Screen screen = attempt.screen();
    final CommandSender sender = attempt.sender();
    this.sendUnlessCancelled(screen, sender, message);
    this.releaseIfFailed(attempt, outcome);
  }

  private void releaseIfFailed(final StartAttempt<PlayerType> attempt, final StartOutcome outcome) {
    if (outcome == StartOutcome.STARTED) {
      return;
    }

    final PlayerType failed = attempt.player();
    final FunctionalVideoFilter output = attempt.output();
    this.releaseIfCurrent(failed, output);
  }

  private void sendUnlessCancelled(final Screen screen, final CommandSender sender, final Component message) {
    if (!screen.isCancelled()) {
      sender.sendMessage(message);
    }
  }

  // a player or output no longer running was replaced and released by a newer command
  private void releaseIfCurrent(final PlayerType failed, final FunctionalVideoFilter output) {
    final boolean playerCurrent;
    final boolean outputCurrent;
    synchronized (this.lock) {
      final PlayerType current = this.player;
      playerCurrent = IDENTITY.equivalent(current, failed);
      if (playerCurrent) {
        this.player = null;
      }
      outputCurrent = IDENTITY.equivalent(output, this.output);
      if (outputCurrent) {
        this.output = null;
        this.screen = null;
      }
    }

    final @Nullable PlayerType releasable = playerCurrent ? failed : null;
    final @Nullable FunctionalVideoFilter releasableOutput = outputCurrent ? output : null;
    CleanupUtils.runAll(() -> this.releasePlayerIfPresent(releasable), () -> this.releaseOutputIfPresent(releasableOutput));
  }

  /**
   * Turns breaking a block in front of the screen into a left click on the running player. Called by Bukkit.
   *
   * <p>A left click on a map screen usually reaches the block behind the item frames. When a player is running, the
   * block the player looks at, up to 100 blocks away, has a screen frame on it, and the pixel the player looks at on
   * the wall can be found, the break is cancelled so the wall stays intact, and a left click is sent at that pixel if
   * the breaker has {@link #getInteractionPermission()}. Other block breaks are left alone, also behind a wall with a
   * missing frame, where no pixel can be found.
   *
   * @param event the block break
   * @throws NullPointerException if {@code event} is null
   * @since 1.0.0
   */
  @EventHandler
  public void onBlockBreak(final BlockBreakEvent event) {
    Preconditions.checkNotNull(event, EVENT_NULL_MESSAGE);

    final PlayerType current = this.player;
    if (current == null) {
      return;
    }

    final Player breaker = event.getPlayer();
    final ItemFrame frame = this.findScreenFrameInSight(breaker);
    final Screen shown = frame == null ? null : this.screenOf(frame);
    if (frame == null || shown == null) {
      return;
    }

    final Optional<int[]> foundCoordinates = InteractUtils.findBoardCoordinates(breaker, frame);
    final int @Nullable [] coordinates = foundCoordinates.orElse(null);
    if (coordinates == null) {
      return;
    }

    // the wall stays intact whoever breaks the block, but only a player with the permission clicks with it
    event.setCancelled(true);
    final boolean allowed = this.canInteract(breaker);
    final int[] pixel = shown.toSource(coordinates[0], coordinates[1]);
    if (allowed && pixel != null) {
      this.forwardLeftClick(current, pixel[0], pixel[1]);
    }
  }

  private @Nullable Screen screenOf(final ItemFrame frame) {
    final Screen current = this.screen;
    if (current == null) {
      return null;
    }

    final ItemStack item = frame.getItem();
    final ItemMeta metadata = item.getItemMeta();
    if (!(metadata instanceof final MapMeta mapMetadata)) {
      return null;
    }

    final MapView map = mapMetadata.getMapView();
    if (map == null) {
      return null;
    }

    final int mapId = map.getId();
    return current.ownsMap(mapId) ? current : null;
  }

  private @Nullable ItemFrame findScreenFrameInSight(final Player viewer) {
    final RayTraceResult ray = viewer.rayTraceBlocks(REACH, FluidCollisionMode.NEVER);
    if (ray == null) {
      return null;
    }

    final Block block = ray.getHitBlock();
    if (block == null) {
      return null;
    }

    return this.findClosestScreenFrame(block);
  }

  private @Nullable ItemFrame findClosestScreenFrame(final Block block) {
    final Location location = block.getLocation();
    final World world = location.getWorld();
    final Collection<Entity> entities = world.getNearbyEntities(location, HALF_BLOCK, HALF_BLOCK, HALF_BLOCK);
    ItemFrame closest = null;
    double closestDistanceSquared = Double.MAX_VALUE;
    for (final Entity entity : entities) {
      final boolean screenFrame = this.isScreenFrame(entity);
      if (!screenFrame) {
        continue;
      }
      final Location frameLocation = entity.getLocation();
      final double distanceSquared = frameLocation.distanceSquared(location);
      if (distanceSquared < closestDistanceSquared) {
        closestDistanceSquared = distanceSquared;
        closest = (ItemFrame) entity;
      }
    }
    return closest;
  }

  private boolean isScreenFrame(final Entity entity) {
    if (!(entity instanceof final ItemFrame frame)) {
      return false;
    }

    final PersistentDataContainer data = frame.getPersistentDataContainer();
    return data.has(Keys.MAP_KEY, PersistentDataType.BOOLEAN);
  }

  /**
   * Protects the item frames of a screen from players and turns punching one into a left click. Called by Bukkit.
   *
   * <p>Damage to a screen frame by a player, or by a projectile a player shot, is always cancelled, so the maps
   * cannot be knocked out of their frames, even when nothing is running. When a player is running, the frame was
   * punched directly and the attacker has {@link #getInteractionPermission()}, a left click is sent at the pixel the
   * player looks at; hits by projectiles are not forwarded.
   *
   * @param event the damage
   * @throws NullPointerException if {@code event} is null
   * @since 1.0.0
   */
  @EventHandler
  public void onScreenDamage(final EntityDamageByEntityEvent event) {
    Preconditions.checkNotNull(event, EVENT_NULL_MESSAGE);

    final Entity entity = event.getEntity();
    final boolean screenFrame = this.isScreenFrame(entity);
    if (!screenFrame) {
      return;
    }

    final Entity damager = event.getDamager();
    final Player attacker = this.findAttacker(damager);
    if (attacker == null) {
      return;
    }

    event.setCancelled(true);

    // only a punch is forwarded; a projectile is not the player who shot it
    final boolean punched = damager instanceof Player;
    final PlayerType current = this.player;
    final Screen shown = this.screenOf((ItemFrame) entity);
    if (current == null || !punched || shown == null) {
      return;
    }

    final boolean allowed = this.canInteract(attacker);
    if (!allowed) {
      return;
    }

    final Optional<int[]> foundCoordinates = InteractUtils.findBoardCoordinates(attacker, entity);
    final int @Nullable [] coordinates = foundCoordinates.orElse(null);
    final int[] pixel = coordinates == null ? null : shown.toSource(coordinates[0], coordinates[1]);
    if (pixel != null) {
      this.forwardLeftClick(current, pixel[0], pixel[1]);
    }
  }

  private @Nullable Player findAttacker(final Entity damager) {
    if (damager instanceof final Player direct) {
      return direct;
    }

    if (damager instanceof final Projectile projectile) {
      final ProjectileSource shooter = projectile.getShooter();
      if (shooter instanceof final Player shooterPlayer) {
        return shooterPlayer;
      }
    }
    return null;
  }

  /**
   * Turns right clicking a screen frame into a right click on the running player. Called by Bukkit.
   *
   * <p>When a player is running and the pixel the player looks at on the wall can be found, the interaction is
   * cancelled, so the map in the frame does not rotate, and a right click is sent at that pixel if the clicker has
   * {@link #getInteractionPermission()}. Otherwise the right click behaves as usual.
   *
   * @param event the interaction
   * @throws NullPointerException if {@code event} is null
   * @since 1.0.0
   */
  @EventHandler
  public void onPlayerInteractEntity(final PlayerInteractEntityEvent event) {
    Preconditions.checkNotNull(event, EVENT_NULL_MESSAGE);

    final PlayerType current = this.player;
    final Entity entity = event.getRightClicked();
    final boolean screenFrame = this.isScreenFrame(entity);
    final Screen shown = screenFrame ? this.screenOf((ItemFrame) entity) : null;
    if (current == null || shown == null) {
      return;
    }

    final Player clicker = event.getPlayer();
    final Optional<int[]> foundCoordinates = InteractUtils.findBoardCoordinates(clicker, entity);
    final int @Nullable [] coordinates = foundCoordinates.orElse(null);
    if (coordinates == null) {
      return;
    }

    // the map keeps its rotation whoever right clicks it, but only a player with the permission clicks with it
    event.setCancelled(true);
    final boolean allowed = this.canInteract(clicker);
    final int[] pixel = shown.toSource(coordinates[0], coordinates[1]);
    if (allowed && pixel != null) {
      this.forwardRightClick(current, pixel[0], pixel[1]);
    }
  }

  /**
   * Types the chat messages of players who switched on interaction into the running player. Called by Bukkit,
   * usually off the main thread.
   *
   * <p>When the sender has switched on interaction with the {@code interact} subcommand, still has
   * {@link #getInteractionPermission()} and a player is running, the message is cancelled, so no one sees it in chat,
   * and its plain text, without colors or formatting, is sent as typed text. Other chat messages are left alone.
   *
   * @param event the chat message
   * @throws NullPointerException if {@code event} is null
   * @since 1.0.0
   */
  @EventHandler
  public void onChatMessage(final AsyncChatEvent event) {
    Preconditions.checkNotNull(event, EVENT_NULL_MESSAGE);

    final Player chatter = event.getPlayer();
    final boolean active = this.activePlayers.contains(chatter);
    final PlayerType current = this.player;
    if (!active || current == null) {
      return;
    }

    // the permission is checked again here, because it may have been taken away since the sender switched this on
    final boolean allowed = this.canInteract(chatter);
    if (!allowed) {
      return;
    }

    event.setCancelled(true);
    final Component message = event.message();
    final String text = PLAIN_TEXT.serialize(message);
    this.forwardTextInput(current, text);
  }

  /**
   * Keeps the item frames of a screen from breaking. Called by Bukkit.
   *
   * <p>Breaking a screen frame for any reason, such as an explosion, a player, or the block behind it disappearing,
   * is cancelled, whether or not a player is running. To take a wall down, remove the frames with a command such as
   * {@code /kill}, which removes them without breaking them.
   *
   * @param event the break of a hanging entity
   * @throws NullPointerException if {@code event} is null
   * @since 1.0.0
   */
  @EventHandler
  public void onScreenBreak(final HangingBreakEvent event) {
    Preconditions.checkNotNull(event, EVENT_NULL_MESSAGE);

    final Entity entity = event.getEntity();
    final boolean screenFrame = this.isScreenFrame(entity);
    if (screenFrame) {
      event.setCancelled(true);
    }
  }

  /**
   * Toggles whether the chat messages of a player are forwarded to the running player.
   *
   * @param chatter        the player
   * @param enableMessage  the message shown when interaction is enabled
   * @param disableMessage the message shown when interaction is disabled
   * @throws NullPointerException if an argument is null
   * @since 1.0.0
   */
  protected void toggleInteraction(final Player chatter, final Component enableMessage, final Component disableMessage) {
    Preconditions.checkNotNull(chatter, "Player must not be null!");
    Preconditions.checkNotNull(enableMessage, "Enable message must not be null!");
    Preconditions.checkNotNull(disableMessage, "Disable message must not be null!");

    final boolean removed = this.activePlayers.remove(chatter);
    if (removed) {
      chatter.sendMessage(disableMessage);
      return;
    }

    this.activePlayers.add(chatter);
    chatter.sendMessage(enableMessage);
  }

  /**
   * Releases the running player and tells the sender.
   *
   * @param sender  who ran the command
   * @param message the message to send
   * @throws NullPointerException if an argument is null
   * @since 1.0.0
   */
  protected void releaseResource(final CommandSender sender, final Component message) {
    Preconditions.checkNotNull(sender, SENDER_NULL_MESSAGE);
    Preconditions.checkNotNull(message, "Message must not be null!");

    this.releaseCurrent();
    sender.sendMessage(message);
  }

  private boolean canInteract(final Player player) {
    final String permission = this.getInteractionPermission();
    return player.hasPermission(permission);
  }

  /**
   * Returns the permission a player needs to send clicks and chat messages to the running player. It is the permission
   * of the {@code interact} subcommand of the command, so a player who may switch chat input on may also click.
   *
   * @return the permission
   * @since 1.0.0
   */
  protected abstract String getInteractionPermission();

  /**
   * Forwards a left click.
   *
   * @param current the running player
   * @param screenX the x coordinate on the screen
   * @param screenY the y coordinate on the screen
   * @since 1.0.0
   */
  protected abstract void forwardLeftClick(final PlayerType current, final int screenX, final int screenY);

  /**
   * Forwards a right click.
   *
   * @param current the running player
   * @param screenX the x coordinate on the screen
   * @param screenY the y coordinate on the screen
   * @since 1.0.0
   */
  protected abstract void forwardRightClick(final PlayerType current, final int screenX, final int screenY);

  /**
   * Forwards typed text.
   *
   * @param current the running player
   * @param text    the text
   * @since 1.0.0
   */
  protected abstract void forwardTextInput(final PlayerType current, final String text);

  /**
   * Creates the message that tells the sender whether the player started.
   *
   * @param outcome whether the player started
   * @param failure why the player failed to start, if known
   * @return the message
   * @since 1.0.0
   */
  protected abstract Component createStartMessage(final StartOutcome outcome, final @Nullable Throwable failure);

  /**
   * Returns whether {@code failure} is a refusal that the sender's reply explains, so it is not logged as an error. By
   * default no failure is a refusal.
   *
   * @param failure why the player failed to start, if known
   * @return whether the failure is a refusal
   * @since 1.0.0
   */
  protected boolean isRefusal(final @Nullable Throwable failure) {
    return false;
  }

  /**
   * Releases a player; its maps are released by the caller.
   *
   * @param player the player to release, which is no longer the running one
   * @since 1.0.0
   */
  protected abstract void releasePlayer(final PlayerType player);

  private record StartAttempt<PlayerType>(
    CommandSender sender,
    PlayerType player,
    FunctionalVideoFilter output,
    Screen screen,
    String description
  ) {}
}
