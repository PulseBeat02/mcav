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
package me.brandonli.mcav.bukkit.media.mcv2;

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import me.brandonli.mcav.bukkit.BukkitModule;
import me.brandonli.mcav.bukkit.resourcepack.provider.PackHosting;
import me.brandonli.mcav.bukkit.resourcepack.provider.http.HttpHosting;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.PluginManager;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves one MCV2 resource pack that decodes every MCV2 screen of the server, and offers it to the players who watch
 * one.
 *
 * <p>The pack decodes a set of slots, each with a video size, a number of page slots and a stream id of its own. A
 * screen that starts is given a free slot of its size, so while screens of sizes the pack already has start and stop,
 * the pack stays the same and no client reloads its resources, a hitch of a second or more. Only a screen of a new
 * size changes the pack: it gets a new slot, or a free slot of another size when the pack has
 * {@link Mcv2Pack#MAX_SCREENS} already. A slot stays in the pack after its screen stops. A changed pack is written,
 * hashed and hosted off the main thread, then every viewer is asked to swap the old pack for it, and sees the dithered
 * maps until their client loaded it.
 *
 * <p>The pack is offered to the viewers of a screen when it starts, and to a viewer who joins or changes world while
 * the screen plays. It is optional and additive: it replaces no other pack, and a player who declines keeps the
 * dithered maps and is not asked again while online. The methods may be called from any thread; the players are asked
 * on the main thread.
 */
public final class Mcv2PackServer {

  private static final Logger LOGGER = LoggerFactory.getLogger(Mcv2PackServer.class);

  private static final String PACK_SERVED = "Serving the MCV2 pack {} with {} at {}";

  private static final String PACK_FAILED = "Cannot serve the MCV2 pack with {}";

  private static final String PACK_LOADED = "{} loaded the MCV2 pack {} {} ms after it was offered";

  private static final String NO_SLOT = "No MCV2 slot is free for a {}x{} video: the screen is dithered at that size";

  private static final String FOLDER_FAILED = "Cannot clear the old MCV2 packs from {}";

  private static final String FILE_PREFIX = "mcav-mcv2-";

  private static final String FILE_SUFFIX = ".zip";

  private static final int HASH_BUFFER = 8192;

  private static final long SHUTDOWN_SECONDS = 10;

  /** The page maps every slot of a full pack takes, past a screen's first page map. */
  private static final int PAGE_MAPS = Mcv2Pack.MAX_SCREENS * Mcv2Configuration.MAX_PAGE_SLOTS;

  /**
   * A hundred frame ids a second, more than any screen sends frames, so a screen that takes over a slot starts ahead of
   * every frame id the slot's earlier screens sent.
   */
  private static final long FRAME_ID_MILLIS = 10;

  private final Path folder;

  private final Function<Path, PackHosting> hosting;

  private final boolean showsDebugView;

  private final Consumer<Player> onOffered;

  private final ExecutorService writer;

  private final LongSupplier millis;

  private final Mcv2Viewers viewers;

  /** Tracks nobody: the channel of a size no slot was free for, whose viewers all see the dithered maps. */
  private final Mcv2Viewers nobody;

  private final List<Slot> slots;

  private final Set<Lease> leases;

  private final Map<UUID, Long> offered;

  /** The hostings started and not stopped yet, which the writer thread starts and stops. */
  private final List<PackHosting> running;

  private @Nullable Published current;

  /** The number of the newest pack asked for; a pack asked for with an older number is not written or served. */
  private final AtomicInteger generation;

  private boolean flushing;

  private long releases;

  private volatile boolean stopped;

  private @Nullable Listener listener;

  /** What a slot decodes: the pack depends on nothing else of a screen. */
  private record Geometry(int width, int height, int pageSlots) {
    static Geometry of(final Mcv2Configuration configuration) {
      return new Geometry(configuration.getVideoWidth(), configuration.getVideoHeight(), configuration.getPageSlots());
    }
  }

  /**
   * A pack that is hosted.
   *
   * @param generation the number it was asked for with
   * @param id         its id, derived from its hash
   * @param hosting    where the players download it
   * @param request    what the players are sent
   * @param screens    its slots, for the log
   */
  private record Published(int generation, UUID id, PackHosting hosting, ResourcePackRequest request, String screens) {}

  /** A slot of the pack: its stream id, what it decodes, and the screen that plays in it. */
  private static final class Slot {

    private final long streamId;

    private Geometry geometry;

    private Mcv2Configuration template;

    private @Nullable Lease holder;

    private long released;

    private Slot(final long streamId, final Mcv2Configuration configuration) {
      this.streamId = streamId;
      this.geometry = Geometry.of(configuration);
      this.template = configuration.withSlot(streamId, configuration.getPageMap(), 0);
    }

    private void reshape(final Mcv2Configuration configuration) {
      this.geometry = Geometry.of(configuration);
      this.template = configuration.withSlot(this.streamId, configuration.getPageMap(), 0);
    }
  }

  /**
   * Constructs a server that writes the pack into a folder of its own and hosts it with the given strategy. Nothing is
   * written or served before {@link #start()} and the first {@link #open(Mcv2Configuration)}.
   *
   * @param folder         where the pack is written; files of earlier runs left there are deleted
   * @param hosting        hosts a written pack, for example {@code PackHosting::injector}; called off the main thread, once
   *                       for every change of the pack
   * @param showsDebugView whether the pack also draws the first slot's decoded picture one to one in the top-left corner,
   *                       for testing
   * @param onOffered      called with a player who is about to be asked to load the pack, for example to say why
   * @param onRefused      called with a player whose client declined the pack or failed to load it, who keeps the dithered
   *                       maps
   */
  public Mcv2PackServer(
    final Path folder,
    final Function<Path, PackHosting> hosting,
    final boolean showsDebugView,
    final Consumer<Player> onOffered,
    final Consumer<Player> onRefused
  ) {
    this(
      folder,
      hosting,
      showsDebugView,
      onOffered,
      onRefused,
      Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("mcav-mcv2-pack").factory()),
      System::currentTimeMillis
    );
  }

  Mcv2PackServer(
    final Path folder,
    final Function<Path, PackHosting> hosting,
    final boolean showsDebugView,
    final Consumer<Player> onOffered,
    final Consumer<Player> onRefused,
    final ExecutorService writer,
    final LongSupplier millis
  ) {
    Preconditions.checkNotNull(folder, "Folder must not be null");
    Preconditions.checkNotNull(hosting, "Hosting must not be null");
    Preconditions.checkNotNull(onOffered, "Offered callback must not be null");
    Preconditions.checkNotNull(onRefused, "Refused callback must not be null");
    this.folder = folder;
    this.hosting = hosting;
    this.showsDebugView = showsDebugView;
    this.onOffered = onOffered;
    this.writer = writer;
    this.millis = millis;
    // no pack is served yet, so no client reports a status for this id
    this.viewers = new Mcv2Viewers(new UUID(0, 0), onRefused);
    this.nobody = new Mcv2Viewers(new UUID(0, 0), onRefused);
    this.slots = new ArrayList<>();
    this.leases = new LinkedHashSet<>();
    this.offered = new HashMap<>();
    this.running = new CopyOnWriteArrayList<>();
    this.generation = new AtomicInteger();
  }

  /**
   * Gets who loaded the pack, which every screen of the server shares: a new pack starts with nobody.
   *
   * @return the viewers
   */
  public Mcv2Viewers getViewers() {
    return this.viewers;
  }

  /**
   * Starts listening to the players' pack status, and to players who join or change world, and deletes the packs an
   * earlier run left in the folder.
   */
  public synchronized void start() {
    this.viewers.register();
    final Listener events = new Listener() {};
    final PluginManager manager = Bukkit.getPluginManager();
    final EventExecutor join = (_, event) -> this.offerLater(((PlayerJoinEvent) event).getPlayer());
    final EventExecutor world = (_, event) -> this.offerLater(((PlayerChangedWorldEvent) event).getPlayer());
    final EventExecutor status = (_, event) -> this.handleStatus((PlayerResourcePackStatusEvent) event);
    final EventExecutor quit = (_, event) -> this.forget(((PlayerQuitEvent) event).getPlayer().getUniqueId());
    manager.registerEvent(PlayerJoinEvent.class, events, EventPriority.MONITOR, join, BukkitModule.getPlugin());
    manager.registerEvent(PlayerChangedWorldEvent.class, events, EventPriority.MONITOR, world, BukkitModule.getPlugin());
    manager.registerEvent(PlayerResourcePackStatusEvent.class, events, EventPriority.MONITOR, status, BukkitModule.getPlugin());
    manager.registerEvent(PlayerQuitEvent.class, events, EventPriority.MONITOR, quit, BukkitModule.getPlugin());
    this.listener = events;
    this.writer.execute(this::clearFolder);
  }

  /**
   * Gives a screen a slot of the pack: a free slot of its video size and page slots when there is one, which leaves
   * the pack as it is, otherwise a new slot or a free slot of another size, which changes the pack. The screen's
   * viewers are offered the pack if they do not have it yet; after a change, once the new pack is hosted.
   *
   * @param requested the screen
   * @return the screen's lease, which holds its slots until it is closed
   * @throws IllegalStateException    if every slot plays another screen, or the server was shut down
   * @throws IllegalArgumentException if the screen's outline colour is not the one of the screens that play
   */
  public synchronized Lease open(final Mcv2Configuration requested) {
    Preconditions.checkNotNull(requested, "Configuration must not be null");
    Preconditions.checkState(!this.stopped, "The MCV2 pack server was shut down");
    Preconditions.checkArgument(
      requested.getPageMap() <= Integer.MAX_VALUE - PAGE_MAPS,
      "The page maps of every slot of the pack must be ints: the first page map is at most %s",
      Integer.MAX_VALUE - PAGE_MAPS
    );
    final boolean sameColour = this.slots.isEmpty() || this.slots.getFirst().template.getOutlineColor().equals(requested.getOutlineColor());
    Preconditions.checkArgument(sameColour || this.leases.isEmpty(), "The screens of a pack share one outline colour");
    if (!sameColour) {
      // no screen plays: the free slots give way to a pack in the new colour
      this.slots.clear();
    }
    final Slot slot = this.acquire(requested);
    if (slot == null) {
      throw new IllegalStateException("Every one of the %d MCV2 slots plays a screen".formatted(Mcv2Pack.MAX_SCREENS));
    }
    final Lease lease = new Lease(requested, (this.millis.getAsLong() / FRAME_ID_MILLIS) & Mcv2Format.MAX_U32, slot);
    slot.holder = lease;
    this.leases.add(lease);
    Bukkit.getScheduler().runTask(BukkitModule.getPlugin(), () -> this.offerViewers(lease));
    return lease;
  }

  /** Asks the viewers of a screen that just started to load the pack, unless a changed pack is on its way. */
  private synchronized void offerViewers(final Lease lease) {
    final Published pack = this.current;
    if (lease.closed || pack == null || pack.generation() != this.generation.get()) {
      // a closed screen watches nothing, and the changed pack is offered to every viewer once it is hosted
      return;
    }
    for (final UUID viewer : lease.requested.getViewers()) {
      final Player player = Bukkit.getPlayer(viewer);
      if (player != null) {
        this.offer(player, pack);
      }
    }
  }

  /**
   * The first page map of a screen in a slot: every slot sends its pages on maps of its own, from the screen's first
   * page map on, so two screens that play at once never write each other's pages.
   */
  private static int pageMapOf(final Mcv2Configuration configuration, final Slot slot) {
    return configuration.getPageMap() + (int) (slot.streamId - 1) * Mcv2Configuration.MAX_PAGE_SLOTS;
  }

  /** Finds a slot for a screen, changing the pack if it must; null if every slot plays. */
  private @Nullable Slot acquire(final Mcv2Configuration configuration) {
    final Geometry geometry = Geometry.of(configuration);
    Slot oldest = null;
    for (final Slot slot : this.slots) {
      if (slot.holder != null) {
        continue;
      }
      if (slot.geometry.equals(geometry)) {
        return slot;
      }
      if (oldest == null || slot.released < oldest.released) {
        oldest = slot;
      }
    }
    final Slot changed;
    if (this.slots.size() < Mcv2Pack.MAX_SCREENS) {
      changed = new Slot(this.slots.size() + 1L, configuration);
      this.slots.add(changed);
    } else if (oldest != null) {
      changed = oldest;
      changed.reshape(configuration);
    } else {
      return null;
    }
    this.publish();
    return changed;
  }

  /**
   * Asks for the pack of the slots as they are at the end of the tick, so screens that start together change the pack
   * once; it is written off the main thread and served once hosted.
   */
  private void publish() {
    this.generation.incrementAndGet();
    if (!this.flushing) {
      this.flushing = true;
      Bukkit.getScheduler().runTask(BukkitModule.getPlugin(), this::flush);
    }
  }

  private synchronized void flush() {
    this.flushing = false;
    if (this.stopped) {
      return;
    }
    final int wanted = this.generation.get();
    final List<Mcv2Configuration> screens = this.slots
      .stream()
      .map(slot -> slot.template)
      .toList();
    this.writer.execute(() -> this.write(wanted, screens));
  }

  /** Writes, hashes and hosts a pack, on the writer thread, then serves it on the main thread. */
  private void write(final int wanted, final List<Mcv2Configuration> screens) {
    if (this.stopped || wanted != this.generation.get()) {
      // a newer pack was asked for before this one was written, or nothing is served any more
      return;
    }
    final String description = describe(screens);
    final Path zip = this.folder.resolve(FILE_PREFIX + wanted + FILE_SUFFIX);
    final Published published;
    try {
      Mcv2Pack.write(screens, this.showsDebugView, zip);
      final String sha1 = hash(zip, "SHA-1");
      final UUID id = UUID.nameUUIDFromBytes(("mcav-mcv2:" + sha1).getBytes(StandardCharsets.UTF_8));
      final PackHosting host = this.host(zip);
      final ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(id, URI.create(host.getRawUrl()), sha1);
      final ResourcePackRequest request = ResourcePackRequest.resourcePackRequest().packs(info).required(false).replace(false).build();
      published = new Published(wanted, id, host, request, description);
    } catch (final RuntimeException exception) {
      LOGGER.error(PACK_FAILED, description, exception);
      deleteQuietly(zip);
      return;
    }
    if (this.stopped) {
      this.retire(published);
      return;
    }
    Bukkit.getScheduler().runTask(BukkitModule.getPlugin(), () -> this.serve(published));
  }

  /** Starts hosting a pack, on the writer thread. */
  private PackHosting host(final Path zip) {
    final PackHosting host = this.hosting.apply(zip);
    if (host instanceof HttpHosting) {
      // an HTTP server binds its port, which the hosting of the pack before still holds
      this.stopRunning();
    }
    host.start();
    this.running.add(host);
    return host;
  }

  /**
   * Serves a pack that was hosted, unless a newer one was asked for meanwhile: the players are asked to remove the
   * pack before, which nobody counts as loaded any more, and the viewers are offered this one.
   */
  private synchronized void serve(final Published published) {
    if (this.stopped || published.generation() != this.generation.get()) {
      this.retireLater(published);
      return;
    }
    final Published old = this.current;
    this.current = published;
    this.viewers.retarget(published.id());
    this.offered.clear();
    LOGGER.info(PACK_SERVED, published.id(), published.screens(), published.request().packs().getFirst().uri());
    for (final Player player : Bukkit.getOnlinePlayers()) {
      if (old != null) {
        player.removeResourcePacks(old.id());
      }
      if (this.isWatching(player.getUniqueId())) {
        this.offer(player, published);
      }
    }
    if (old != null) {
      this.retireLater(old);
    }
  }

  /** Asks a player to load the pack, unless their client already answered for it. */
  private void offer(final Player player, final Published pack) {
    final UUID uuid = player.getUniqueId();
    if (this.viewers.getState(uuid) != null) {
      return;
    }
    this.viewers.requested(uuid);
    this.offered.put(uuid, this.millis.getAsLong());
    this.onOffered.accept(player);
    player.sendResourcePacks(pack.request());
  }

  private synchronized void forget(final UUID player) {
    this.offered.remove(player);
  }

  /** Offers the pack to a player who just joined or changed world, on the next tick, once they are in the world. */
  private void offerLater(final Player player) {
    Bukkit.getScheduler().runTask(BukkitModule.getPlugin(), () -> this.offerIfWatching(player));
  }

  private synchronized void offerIfWatching(final Player player) {
    final Published pack = this.current;
    if (pack != null && player.isOnline() && this.isWatching(player.getUniqueId())) {
      this.offer(player, pack);
    }
  }

  private boolean isWatching(final UUID player) {
    for (final Lease lease : this.leases) {
      if (lease.requested.getViewers().contains(player)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Logs how long a client took from the offer to the pack loaded: the download and the reload a new pack costs a
   * player.
   *
   * @return the time in milliseconds, or -1 if the event does not report the served pack loaded after an offer
   */
  synchronized long handleStatus(final PlayerResourcePackStatusEvent event) {
    final Published pack = this.current;
    if (pack == null || !pack.id().equals(event.getID()) || event.getStatus() != PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
      return -1;
    }
    final Player player = event.getPlayer();
    final Long since = this.offered.remove(player.getUniqueId());
    if (since == null) {
      return -1;
    }
    final long took = this.millis.getAsLong() - since;
    LOGGER.info(PACK_LOADED, player.getName(), pack.id(), took);
    return took;
  }

  /** Stops hosting a pack that is not served, and deletes it, on the writer thread unless it was shut down. */
  private void retireLater(final Published published) {
    try {
      this.writer.execute(() -> this.retire(published));
    } catch (final RejectedExecutionException shutDown) {
      this.retire(published);
    }
  }

  private void retire(final Published published) {
    final PackHosting host = published.hosting();
    // an HTTP hosting was stopped already for the one after it
    if (this.running.remove(host)) {
      host.shutdown();
      deleteQuietly(host.getZip());
    }
  }

  private void stopRunning() {
    for (final PackHosting host : this.running) {
      host.shutdown();
      deleteQuietly(host.getZip());
    }
    this.running.clear();
  }

  /** Deletes the packs an earlier run left, on the writer thread. */
  private void clearFolder() {
    try {
      Files.createDirectories(this.folder);
      try (final DirectoryStream<Path> old = Files.newDirectoryStream(this.folder, FILE_PREFIX + "*" + FILE_SUFFIX)) {
        for (final Path file : old) {
          Files.deleteIfExists(file);
        }
      }
    } catch (final IOException exception) {
      LOGGER.warn(FOLDER_FAILED, this.folder, exception);
    }
  }

  /**
   * Stops listening and hosting, and closes every lease. The players keep the pack they loaded, which a removal would
   * make them reload.
   */
  public synchronized void shutdown() {
    if (this.stopped) {
      return;
    }
    this.stopped = true;
    this.viewers.unregister();
    final Listener events = this.listener;
    if (events != null) {
      HandlerList.unregisterAll(events);
      this.listener = null;
    }
    for (final Lease lease : List.copyOf(this.leases)) {
      lease.close();
    }
    this.current = null;
    this.writer.execute(this::stopRunning);
    this.writer.shutdown();
    try {
      this.writer.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS);
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  /** The slots of a pack, for the log. */
  static String describe(final List<Mcv2Configuration> screens) {
    return screens
      .stream()
      .map(screen -> "%d: %dx%d".formatted(screen.getStreamId(), screen.getVideoWidth(), screen.getVideoHeight()))
      .collect(Collectors.joining(", ", "slots ", ""));
  }

  /**
   * The hash of a file; the client checks the pack it downloads against its SHA-1.
   *
   * @param file      the file
   * @param algorithm the digest algorithm
   * @return the hash as lowercase hexadecimal
   */
  static String hash(final Path file, final String algorithm) {
    try (final InputStream input = Files.newInputStream(file)) {
      final MessageDigest digest = MessageDigest.getInstance(algorithm);
      final byte[] buffer = new byte[HASH_BUFFER];
      for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
        digest.update(buffer, 0, read);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (final IOException exception) {
      throw new UncheckedIOException("Cannot hash the MCV2 pack", exception);
    } catch (final NoSuchAlgorithmException exception) {
      throw new IllegalStateException(algorithm + " is not available", exception);
    }
  }

  private static void deleteQuietly(final Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (final IOException exception) {
      // the next start clears the folder
    }
  }

  /**
   * A screen's hold on the pack: the slot of its video size, and of every smaller size it stepped down to, until it is
   * closed. It is the screen's {@link Mcv2Result.Resizer}: a size it has no slot for yet gets one, which may change
   * the pack once, and a size it had keeps its slot, so stepping down and back up again changes nothing.
   */
  public final class Lease implements Mcv2Result.Resizer {

    private final Mcv2Configuration requested;

    private final Mcv2Configuration configuration;

    private final long firstFrameId;

    private final Map<Geometry, Slot> held;

    private boolean closed;

    private Lease(final Mcv2Configuration requested, final long firstFrameId, final Slot slot) {
      this.requested = requested;
      this.firstFrameId = firstFrameId;
      this.configuration = requested.withSlot(slot.streamId, pageMapOf(requested, slot), firstFrameId);
      this.held = new HashMap<>();
      this.held.put(slot.geometry, slot);
    }

    /**
     * Gets the screen as it plays in its slot: the requested screen with the slot's stream id, and the frame id its
     * first frame gets.
     *
     * @return the configuration to create the screen's result or channel with
     */
    public Mcv2Configuration getConfiguration() {
      return this.configuration;
    }

    /**
     * Gets the channel of the screen at another video size, in the slot of that size.
     *
     * @param resized the screen at the other size
     * @return its channel, not opened yet; one whose viewers all see the dithered maps if no slot is free
     * @throws IllegalStateException if the lease was closed
     */
    @Override
    public Mcv2Channel resize(final Mcv2Configuration resized) {
      Preconditions.checkNotNull(resized, "Configuration must not be null");
      synchronized (Mcv2PackServer.this) {
        Preconditions.checkState(!this.closed, "The lease was closed");
        final Geometry geometry = Geometry.of(resized);
        Slot slot = this.held.get(geometry);
        if (slot == null) {
          slot = Mcv2PackServer.this.acquire(resized);
          if (slot == null) {
            LOGGER.warn(NO_SLOT, resized.getVideoWidth(), resized.getVideoHeight());
            return new Mcv2Channel(resized, Mcv2PackServer.this.nobody);
          }
          slot.holder = this;
          this.held.put(geometry, slot);
        }
        return new Mcv2Channel(resized.withSlot(slot.streamId, pageMapOf(resized, slot), this.firstFrameId), Mcv2PackServer.this.viewers);
      }
    }

    /**
     * Gives the lease's slots back: they stay in the pack, free for the next screen of their size.
     */
    public void close() {
      synchronized (Mcv2PackServer.this) {
        if (this.closed) {
          return;
        }
        this.closed = true;
        for (final Slot slot : this.held.values()) {
          slot.holder = null;
          slot.released = ++Mcv2PackServer.this.releases;
        }
        this.held.clear();
        Mcv2PackServer.this.leases.remove(this);
      }
    }
  }
}
