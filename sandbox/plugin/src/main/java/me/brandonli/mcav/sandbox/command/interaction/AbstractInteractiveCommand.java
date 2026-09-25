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

import com.google.common.base.Equivalence;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ForwardingExecutorService;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.result.CompressedMapResult;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.audio.AudioOutputs;
import me.brandonli.mcav.sandbox.audio.AudioProvider;
import me.brandonli.mcav.sandbox.command.AnnotationCommandFeature;
import me.brandonli.mcav.sandbox.command.MapDisplaySettings;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
import me.brandonli.mcav.sandbox.utils.AudioArgument;
import me.brandonli.mcav.sandbox.utils.CleanupUtils;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.sandbox.utils.InteractUtils;
import me.brandonli.mcav.sandbox.utils.Keys;
import me.brandonli.mcav.sandbox.utils.TaskUtils;
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
 * @param <T> the type of the interactive player
 */
public abstract class AbstractInteractiveCommand<T> implements AnnotationCommandFeature, Listener {

  private static final Equivalence<Object> IDENTITY = Equivalence.identity();
  private static final Logger LOGGER = LoggerFactory.getLogger(AbstractInteractiveCommand.class);
  private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();
  private static final int REACH = 100;

  /**
   * The plugin.
   */
  protected final MCAVSandbox plugin;

  /**
   * The thread that starts players, so the main thread never waits for a browser or a virtual machine.
   */
  protected final ExecutorService service;

  private final Set<Player> activePlayers;
  private final Object lock;
  private volatile @Nullable Screen screen;
  private boolean closed;

  /**
   * The maps the player is shown on, while one is running. Changed only while holding the lock of this command.
   */
  protected volatile @Nullable CompressedMapResult result;

  /**
   * The running player. Changed only while holding the lock of this command.
   */
  protected volatile @Nullable T player;

  /**
   * Constructs the command and the thread that starts its players.
   *
   * @param plugin the plugin
   */
  protected AbstractInteractiveCommand(final MCAVSandbox plugin) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null");
    this.plugin = plugin;
    this.service = Executors.newSingleThreadExecutor();
    final WeakHashMap<Player, Boolean> backing = new WeakHashMap<>();
    final Set<Player> players = Collections.newSetFromMap(backing);
    this.activePlayers = Collections.synchronizedSet(players);
    this.lock = new Object();
  }

  AbstractInteractiveCommand(final MCAVSandbox plugin, final ExecutorService service) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null");
    Preconditions.checkNotNull(service, "Executor must not be null");
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
   */
  @Override
  public void registerFeature(final AnnotationParser<CommandSender> parser) {
    Preconditions.checkNotNull(parser, "Parser must not be null");
    final Server server = this.plugin.getServer();
    final PluginManager pluginManager = server.getPluginManager();
    pluginManager.registerEvents(this, this.plugin);
  }

  /**
   * Releases the running player and its maps, stops listening for events, and stops the thread that starts
   * players.
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
   */
  protected final void releaseCurrent() {
    final T current;
    final CompressedMapResult maps;
    final Screen oldScreen;
    synchronized (this.lock) {
      current = this.player;
      maps = this.result;
      oldScreen = this.screen;
      this.player = null;
      this.result = null;
      this.screen = null;
    }
    final boolean workerOwnsCleanup = oldScreen != null && oldScreen.cancel();
    CleanupUtils.runAll(
      () -> {
        if (current != null && !workerOwnsCleanup) {
          this.releasePlayer(current);
        }
      },
      () -> {
        if (maps != null) {
          maps.release();
        }
      }
    );
  }

  /**
   * Plays the sound of a player into the chosen audio output, which the player takes over from any video, browser or
   * virtual machine until it is released; {@link AudioArgument#NONE} plays nothing.
   *
   * @param owner the player, which owns the output while its sound plays
   * @param audio the slot of the audio pipeline of the player
   * @param sound the output and the players who hear it
   * @param title the title the output shows, such as {@code Virtual machine}
   */
  final void attachSound(final Object owner, final AudioAttachableCallback audio, final ScreenSound sound, final String title) {
    final AudioArgument type = sound.getType();
    if (type == AudioArgument.NONE) {
      return;
    }
    final AudioProvider provider = this.plugin.getAudioProvider();
    final URLParseDump dump = new URLParseDump();
    dump.title = title;
    final AudioFilter filter = provider.constructFilter(type, dump, sound.getViewers(), owner);
    audio.attach(AudioPipelineStep.of(filter));
  }

  /**
   * Sends the viewers the link of the audio output once the player started.
   *
   * @param start the start of the player
   * @param screen the screen of the player
   * @param sound the output and the players who hear it
   */
  final void sendSoundLinkWhenStarted(final CompletableFuture<Boolean> start, final Screen screen, final ScreenSound sound) {
    TaskUtils.whenComplete(start, (started, error) -> {
      // releasing the player while it starts cancels the start, and a release before the main thread sends the links
      // cancels the screen, which the main thread sees, as releases happen there too
      if (error == null && Boolean.TRUE.equals(started)) {
        final AudioProvider provider = this.plugin.getAudioProvider();
        TaskUtils.runOnMainThread(this.plugin, () -> {
          if (!screen.isCancelled()) {
            AudioOutputs.sendLink(provider, sound.getType(), sound.getViewers());
          }
        });
      }
    });
  }

  /**
   * Lets go of the audio outputs of a player that was released, unless a video or another player took them over
   * meanwhile.
   *
   * @param owner the released player
   */
  final void releaseSound(final Object owner) {
    final AudioProvider provider = this.plugin.getAudioProvider();
    provider.releaseAudioFilter(owner);
  }

  /**
   * Parses a size such as {@code 1920x1080}, telling the sender when it is not valid.
   *
   * @param sender who ran the command
   * @param text   the size as entered
   * @return the width and height, or {@code null} if the text is not a valid size
   */
  protected static @Nullable Pair<Integer, Integer> parseDimensions(final CommandSender sender, final String text) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    Preconditions.checkNotNull(text, "Text must not be null");
    try {
      return ArgumentUtils.parseDimensions(text);
    } catch (final IllegalArgumentException exception) {
      final Component message = Message.UNSUPPORTED_DIMENSION.build();
      sender.sendMessage(message);
      return null;
    }
  }

  /**
   * Parses the size of a wall of maps such as {@code 5x5}, telling the sender when it is not valid or larger than
   * {@link ArgumentUtils#parseScreenDimensions(String)} allows.
   *
   * @param sender who ran the command
   * @param text   the size as entered
   * @return the width and height in maps, or {@code null} if the text is not a valid wall size
   */
  protected static @Nullable Pair<Integer, Integer> parseScreenDimensions(final CommandSender sender, final String text) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    Preconditions.checkNotNull(text, "Text must not be null");
    try {
      return ArgumentUtils.parseScreenDimensions(text);
    } catch (final IllegalArgumentException exception) {
      final Component message = Message.UNSUPPORTED_DIMENSION.build();
      sender.sendMessage(message);
      return null;
    }
  }

  /**
   * Releases the running player and creates the maps a new player is shown on, together with the pipeline that
   * dithers the frames of the player onto them. Every screen gets its own dithering algorithm, so a stateful
   * algorithm never mixes up the frames of two players.
   *
   * @param settings the screen as entered by the sender
   * @return the maps, which become the running ones, and the pipeline to attach to the new player
   */
  final Screen createScreen(final ScreenSettings settings) {
    synchronized (this.lock) {
      Preconditions.checkState(!this.closed, "The interactive command is shut down");
    }
    this.releaseCurrent();
    final MultiplePlayerSelector viewers = settings.getViewers();
    final Collection<UUID> players = ArgumentUtils.parsePlayerSelectors(viewers);
    final Pair<Integer, Integer> blocks = settings.getBlocks();
    final Pair<Integer, Integer> resolution = settings.getResolution();
    final int mapId = settings.getMapId();
    final MapConfiguration configuration = MapDisplaySettings.createConfiguration(blocks, resolution, mapId, players);

    final DitheringArgument dithering = settings.getDithering();
    final DitherAlgorithm algorithm = dithering.createAlgorithm();
    final CompressedMapResult maps = new CompressedMapResult(configuration);
    synchronized (this.lock) {
      this.result = maps;
    }
    try {
      final FunctionalVideoFilter filter = DitherFilter.dither(algorithm, maps);
      filter.start();
      final int columns = blocks.getFirst();
      final int rows = blocks.getSecond();
      final long mapCount = (long) columns * rows;
      final VideoPipelineStep announcement = VideoPipelineStep.of(announceFirstPicture(mapId, mapCount, LOGGER::info));
      final VideoPipelineStep pipeline = VideoPipelineStep.of(announcement, filter);
      final Screen created = new Screen(maps, pipeline, mapId, mapCount);
      synchronized (this.lock) {
        this.result = maps;
        this.screen = created;
      }
      return created;
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      this.releaseAfterFailure(exception);
      throw exception;
    }
  }

  /**
   * Creates the last step of the pipeline of a screen, which tells the log once that its maps show a picture.
   *
   * @param mapId    the id of the first map
   * @param mapCount the number of maps
   * @param log      receives the message
   * @return the filter of the step
   */
  static VideoFilter announceFirstPicture(final int mapId, final long mapCount, final Consumer<String> log) {
    final AtomicBoolean announced = new AtomicBoolean();
    final String message = "Maps " + mapId + " to " + (mapId + mapCount - 1) + " show their first picture";
    return (image, metadata) -> {
      if (announced.compareAndSet(false, true)) {
        log.accept(message);
      }
      // it only watches the frame
      return false;
    };
  }

  /** Takes ownership on the main thread before callback attachment or executor submission can fail. */
  final void ownCreatedPlayer(final T created) {
    synchronized (this.lock) {
      this.player = created;
    }
  }

  /** Releases the screen and any adopted backend if synchronous startup preparation fails. */
  final void createResource(final Runnable create) {
    try {
      create.run();
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      this.releaseAfterFailure(exception);
      throw exception;
    }
  }

  private void releaseAfterFailure(final Throwable exception) {
    try {
      this.releaseCurrent();
    } catch (final RuntimeException | Error cleanup) {
      ThrowableUtils.throwIfFatal(cleanup);
      final boolean same = IDENTITY.equivalent(exception, cleanup);
      if (!same) {
        exception.addSuppressed(cleanup);
      }
    }
  }

  /**
   * Gates queued startup by this specific screen and assigns in-flight cancellation cleanup to the worker.
   * A canceled queued task is skipped; its future is canceled by Screen even if it was bound after cancellation.
   * BrowserPlayer and VMPlayer submit their CompletableFuture startup through execute. Screen creation,
   * reportStartWhenDone, release commands and shutdown are main-thread operations; startup runs on the worker.
   */
  final ExecutorService startExecutor(final T started, final Screen screen) {
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

  private void runStartTask(final T started, final Screen screen, final Runnable task) {
    if (!screen.beginStart()) {
      return;
    }
    try {
      task.run();
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      try {
        this.finishStartTask(started, screen);
      } catch (final RuntimeException | Error cleanup) {
        ThrowableUtils.throwIfFatal(cleanup);
        final boolean same = IDENTITY.equivalent(exception, cleanup);
        if (!same) {
          exception.addSuppressed(cleanup);
        }
      }
      throw exception;
    }
    this.finishStartTask(started, screen);
  }

  private void finishStartTask(final T started, final Screen screen) {
    final boolean cancelled = screen.finishStart();
    if (cancelled) {
      this.releasePlayer(started);
    }
  }

  /**
   * Makes a player that is starting the running one, and waits in the background for it to start. When it fails,
   * that player and the maps of its screen are released, unless a newer command has replaced them in the meantime,
   * which released them already; a newer player is never touched. Either way, the sender is told on the main
   * thread.
   *
   * @param sender      who ran the command
   * @param started     the player that is starting
   * @param screen      the screen created for it with {@link #createScreen(ScreenSettings)}
   * @param start       completes with whether the player started
   * @param description what was started, for the log
   */
  final void reportStartWhenDone(
    final CommandSender sender,
    final T started,
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
    final CompressedMapResult maps = screen.getMaps();
    final StartAttempt<T> attempt = new StartAttempt<>(sender, started, maps, screen, description);
    TaskUtils.whenComplete(start, (success, error) -> this.onStartCompleted(attempt, success, error));
  }

  private void onStartCompleted(final StartAttempt<T> attempt, final @Nullable Boolean started, final @Nullable Throwable error) {
    final Screen screen = attempt.getScreen();
    if (screen.isCancelled()) {
      return;
    }
    final boolean success = error == null && Boolean.TRUE.equals(started);
    if (!success) {
      final String description = attempt.getDescription();
      LOGGER.error("Failed to start {}", description, error);
      final T failed = attempt.getPlayer();
      final CompressedMapResult maps = attempt.getMaps();
      this.releaseIfCurrent(failed, maps);
    }

    final Component message = this.createStartMessage(success, error);
    final CommandSender sender = attempt.getSender();
    TaskUtils.runOnMainThread(this.plugin, () -> {
      if (!screen.isCancelled()) {
        sender.sendMessage(message);
      }
    });
  }

  /**
   * Releases a player that failed to start and the maps of its screen, each only while it is still the running
   * one. One that is no longer running was replaced by a newer command, which released it already.
   */
  private void releaseIfCurrent(final T failed, final CompressedMapResult maps) {
    final boolean playerCurrent;
    final boolean mapsCurrent;
    synchronized (this.lock) {
      final T current = this.player;
      playerCurrent = IDENTITY.equivalent(current, failed);
      if (playerCurrent) {
        this.player = null;
      }
      mapsCurrent = IDENTITY.equivalent(maps, this.result);
      if (mapsCurrent) {
        this.result = null;
        this.screen = null;
      }
    }

    CleanupUtils.runAll(
      () -> {
        if (playerCurrent) {
          this.releasePlayer(failed);
        }
      },
      () -> {
        if (mapsCurrent) {
          maps.release();
        }
      }
    );
  }

  private static boolean isScreen(final Entity entity) {
    if (!(entity instanceof final ItemFrame frame)) {
      return false;
    }
    final PersistentDataContainer data = frame.getPersistentDataContainer();
    return data.has(Keys.MAP_KEY, PersistentDataType.BOOLEAN);
  }

  private boolean ownsScreen(final ItemFrame frame) {
    final Screen current = this.screen;
    if (current == null) {
      return false;
    }
    final ItemStack item = frame.getItem();
    final ItemMeta metadata = item.getItemMeta();
    if (!(metadata instanceof final MapMeta mapMetadata)) {
      return false;
    }
    final MapView map = mapMetadata.getMapView();
    if (map == null) {
      return false;
    }
    final int mapId = map.getId();
    return current.ownsMap(mapId);
  }

  /**
   * Turns breaking a block in front of the screen into a left click on the running player. Called by Bukkit.
   *
   * <p>A left click on a map screen usually reaches the block behind the item frames. When a player is running and
   * the block the player looks at, up to 100 blocks away, has a screen frame on it, the break is
   * cancelled so the wall stays intact, and a left click is sent at the pixel the player looks at if the breaker has
   * {@link #getInteractionPermission()}. Other block breaks are left alone.
   *
   * @param event the block break
   */
  @EventHandler
  public void onBlockBreak(final BlockBreakEvent event) {
    Preconditions.checkNotNull(event, "Event must not be null");
    final T current = this.player;
    if (current == null) {
      return;
    }

    final Player breaker = event.getPlayer();
    final ItemFrame frame = findScreenInSight(breaker);
    if (frame == null || !this.ownsScreen(frame)) {
      return;
    }

    final int[] coordinates = InteractUtils.getBoardCoordinates(breaker, frame);
    if (coordinates == null) {
      return;
    }
    // the wall stays intact whoever breaks the block, but only a player with the permission clicks with it
    event.setCancelled(true);
    final boolean allowed = this.mayInteract(breaker);
    if (allowed) {
      this.handleLeftClick(current, coordinates[0], coordinates[1]);
    }
  }

  /**
   * Finds the screen frame on the block a player looks at, up to {@value #REACH} blocks away.
   *
   * @return the frame closest to that block, or {@code null} if the player looks at no block or it has no screen
   */
  private static @Nullable ItemFrame findScreenInSight(final Player viewer) {
    final RayTraceResult ray = viewer.rayTraceBlocks(REACH, FluidCollisionMode.NEVER);
    if (ray == null) {
      return null;
    }
    final Block block = ray.getHitBlock();
    if (block == null) {
      return null;
    }
    return findClosestScreen(block);
  }

  private static @Nullable ItemFrame findClosestScreen(final Block block) {
    final Location location = block.getLocation();
    final World world = location.getWorld();
    final Collection<Entity> entities = world.getNearbyEntities(location, 0.5, 0.5, 0.5);
    ItemFrame closest = null;
    double closestDistance = Double.MAX_VALUE;
    for (final Entity entity : entities) {
      final boolean screen = isScreen(entity);
      if (!screen) {
        continue;
      }
      final Location frameLocation = entity.getLocation();
      final double distance = frameLocation.distanceSquared(location);
      if (distance < closestDistance) {
        closestDistance = distance;
        closest = (ItemFrame) entity;
      }
    }
    return closest;
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
   */
  @EventHandler
  public void onScreenDamage(final EntityDamageByEntityEvent event) {
    Preconditions.checkNotNull(event, "Event must not be null");
    final Entity entity = event.getEntity();
    final boolean screen = isScreen(entity);
    if (!screen) {
      return;
    }

    final Entity damager = event.getDamager();
    final Player attacker = findAttacker(damager);
    if (attacker == null) {
      return;
    }
    event.setCancelled(true);

    // only a punch is forwarded; a projectile is not the player who shot it
    final boolean punched = damager instanceof Player;
    final T current = this.player;
    if (current == null || !punched || !this.ownsScreen((ItemFrame) entity)) {
      return;
    }
    final boolean allowed = this.mayInteract(attacker);
    if (!allowed) {
      return;
    }
    final int[] coordinates = InteractUtils.getBoardCoordinates(attacker, entity);
    if (coordinates != null) {
      this.handleLeftClick(current, coordinates[0], coordinates[1]);
    }
  }

  private static @Nullable Player findAttacker(final Entity damager) {
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
   */
  @EventHandler
  public void onPlayerInteractEntity(final PlayerInteractEntityEvent event) {
    Preconditions.checkNotNull(event, "Event must not be null");
    final T current = this.player;
    final Entity entity = event.getRightClicked();
    final boolean screen = isScreen(entity);
    if (current == null || !screen || !this.ownsScreen((ItemFrame) entity)) {
      return;
    }

    final Player clicker = event.getPlayer();
    final int[] coordinates = InteractUtils.getBoardCoordinates(clicker, entity);
    if (coordinates == null) {
      return;
    }
    // the map keeps its rotation whoever right clicks it, but only a player with the permission clicks with it
    event.setCancelled(true);
    final boolean allowed = this.mayInteract(clicker);
    if (allowed) {
      this.handleRightClick(current, coordinates[0], coordinates[1]);
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
   */
  @EventHandler
  public void onChatMessage(final AsyncChatEvent event) {
    Preconditions.checkNotNull(event, "Event must not be null");
    final Player chatter = event.getPlayer();
    final boolean active = this.activePlayers.contains(chatter);
    final T current = this.player;
    if (!active || current == null) {
      return;
    }
    // the permission is checked again here, because it may have been taken away since the sender switched this on
    final boolean allowed = this.mayInteract(chatter);
    if (!allowed) {
      return;
    }

    event.setCancelled(true);
    final Component message = event.message();
    final String text = PLAIN_TEXT.serialize(message);
    this.handleTextInput(current, text);
  }

  /**
   * Keeps the item frames of a screen from breaking. Called by Bukkit.
   *
   * <p>Breaking a screen frame for any reason, such as an explosion, a player, or the block behind it disappearing,
   * is cancelled, whether or not a player is running. To take a wall down, remove the frames with a command such as
   * {@code /kill}, which removes them without breaking them.
   *
   * @param event the break of a hanging entity
   */
  @EventHandler
  public void onScreenBreak(final HangingBreakEvent event) {
    Preconditions.checkNotNull(event, "Event must not be null");
    final Entity entity = event.getEntity();
    final boolean screen = isScreen(entity);
    if (screen) {
      event.setCancelled(true);
    }
  }

  /**
   * Toggles whether the chat messages of a player are forwarded to the running player.
   *
   * @param chatter        the player
   * @param enableMessage  the message shown when interaction is enabled
   * @param disableMessage the message shown when interaction is disabled
   */
  protected void toggleInteraction(final Player chatter, final Component enableMessage, final Component disableMessage) {
    Preconditions.checkNotNull(chatter, "Player must not be null");
    Preconditions.checkNotNull(enableMessage, "Enable message must not be null");
    Preconditions.checkNotNull(disableMessage, "Disable message must not be null");
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
   */
  protected void releaseResource(final CommandSender sender, final Component message) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    Preconditions.checkNotNull(message, "Message must not be null");
    this.releaseCurrent();
    sender.sendMessage(message);
  }

  /**
   * Whether a player may send input to the running player.
   */
  private boolean mayInteract(final Player player) {
    final String permission = this.getInteractionPermission();
    return player.hasPermission(permission);
  }

  /**
   * Gets the permission a player needs to send clicks and chat messages to the running player. It is the permission
   * of the {@code interact} subcommand of the command, so a player who may switch chat input on may also click.
   *
   * @return the permission
   */
  protected abstract String getInteractionPermission();

  /**
   * Forwards a left click.
   *
   * @param current the running player
   * @param x       the x coordinate on the screen
   * @param y       the y coordinate on the screen
   */
  protected abstract void handleLeftClick(final T current, final int x, final int y);

  /**
   * Forwards a right click.
   *
   * @param current the running player
   * @param x       the x coordinate on the screen
   * @param y       the y coordinate on the screen
   */
  protected abstract void handleRightClick(final T current, final int x, final int y);

  /**
   * Forwards typed text.
   *
   * @param current the running player
   * @param text    the text
   */
  protected abstract void handleTextInput(final T current, final String text);

  /**
   * Creates the message that tells the sender whether the player started.
   *
   * @param success whether the player started
   * @param error   why the player failed to start, if known
   * @return the message
   */
  protected abstract Component createStartMessage(final boolean success, final @Nullable Throwable error);

  /**
   * Releases a player; its maps are released by the caller.
   *
   * @param current the player to release, which is no longer the running one
   */
  protected abstract void releasePlayer(final T current);

  /**
   * A player that is starting, with everything needed to report or undo its start.
   *
   * @param <T> the type of the interactive player
   */
  private static final class StartAttempt<T> {

    private final CommandSender sender;
    private final T player;
    private final CompressedMapResult maps;
    private final Screen screen;
    private final String description;

    StartAttempt(
      final CommandSender sender,
      final T player,
      final CompressedMapResult maps,
      final Screen screen,
      final String description
    ) {
      this.sender = sender;
      this.player = player;
      this.maps = maps;
      this.screen = screen;
      this.description = description;
    }

    CommandSender getSender() {
      return this.sender;
    }

    T getPlayer() {
      return this.player;
    }

    CompressedMapResult getMaps() {
      return this.maps;
    }

    Screen getScreen() {
      return this.screen;
    }

    String getDescription() {
      return this.description;
    }
  }
}
