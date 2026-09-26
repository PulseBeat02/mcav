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
package me.brandonli.mcav.bukkit.testing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import me.brandonli.mcav.bukkit.BukkitModule;
import me.brandonli.mcav.bukkit.utils.PacketUtils;
import me.brandonli.mcav.bukkit.utils.versioning.ServerEnvironment;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.ScoreboardManager;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

/**
 * A fake Bukkit server for tests, built from Mockito mocks.
 *
 * <p>{@link Bukkit} is mocked statically for the current thread until the server is closed. The server reports the
 * supported Minecraft version, runs on the main thread by default, and has a plugin manager, a scoreboard manager,
 * and a scheduler. Scheduled tasks never run on their own; {@link #runTasks()} runs them like one server tick.
 * Players added with {@link #addPlayer(UUID)} are online, and every packet sent to them is recorded.
 */
public final class FakeServer implements AutoCloseable {

  private final MockedStatic<Bukkit> bukkit;
  private final Plugin plugin;
  private final PluginManager pluginManager;
  private final BukkitScheduler scheduler;
  private final ScoreboardManager scoreboardManager;
  private final List<Player> onlinePlayers;
  private final Map<UUID, List<Packet<?>>> sentPackets;
  private final Map<UUID, List<ChannelFutureListener>> pendingWrites;
  private final List<ScheduledTask> tasks;

  private volatile boolean primaryThread;

  private FakeServer() {
    MinecraftTestBootstrap.bootstrap();
    this.plugin = mock(Plugin.class);
    when(this.plugin.isEnabled()).thenReturn(true);
    this.pluginManager = mock(PluginManager.class);
    this.scheduler = mock(BukkitScheduler.class);
    this.scoreboardManager = mock(ScoreboardManager.class);
    this.onlinePlayers = new CopyOnWriteArrayList<>();
    this.sentPackets = new ConcurrentHashMap<>();
    this.pendingWrites = new ConcurrentHashMap<>();
    this.tasks = new CopyOnWriteArrayList<>();
    this.primaryThread = true;

    this.stubScheduler();
    this.bukkit = this.mockBukkit();
  }

  private void stubScheduler() {
    final Answer<BukkitTask> repeatingTask = invocation -> {
      final Runnable runnable = invocation.getArgument(1);
      return this.schedule(runnable, true);
    };
    final Answer<BukkitTask> singleTask = invocation -> {
      final Runnable runnable = invocation.getArgument(1);
      return this.schedule(runnable, false);
    };
    when(this.scheduler.runTaskTimer(any(Plugin.class), any(Runnable.class), anyLong(), anyLong())).thenAnswer(repeatingTask);
    when(this.scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(singleTask);
  }

  private MockedStatic<Bukkit> mockBukkit() {
    final MockedStatic<Bukkit> staticBukkit = Mockito.mockStatic(Bukkit.class);
    staticBukkit.when(Bukkit::getMinecraftVersion).thenReturn(ServerEnvironment.SUPPORTED_MINECRAFT_VERSION);
    staticBukkit.when(Bukkit::getPluginManager).thenReturn(this.pluginManager);
    staticBukkit.when(Bukkit::getScheduler).thenReturn(this.scheduler);
    staticBukkit.when(Bukkit::getScoreboardManager).thenReturn(this.scoreboardManager);
    staticBukkit.when(Bukkit::getOnlinePlayers).thenAnswer(_ -> List.copyOf(this.onlinePlayers));
    staticBukkit.when(Bukkit::isPrimaryThread).thenAnswer(_ -> this.primaryThread);
    staticBukkit.when(Bukkit::getIp).thenReturn("127.0.0.1");
    staticBukkit.when(Bukkit::getPort).thenReturn(25565);
    return staticBukkit;
  }

  /**
   * Starts a fake server for the current thread.
   *
   * @return the running server
   */
  public static FakeServer start() {
    return new FakeServer();
  }

  private BukkitTask schedule(final Runnable runnable, final boolean repeating) {
    final BukkitTask task = mock(BukkitTask.class);
    final ScheduledTask scheduled = new ScheduledTask(runnable, repeating, task);
    doAnswer(_ -> {
      this.tasks.remove(scheduled);
      return null;
    })
      .when(task)
      .cancel();
    this.tasks.add(scheduled);
    return task;
  }

  /**
   * Gets the static mock of {@link Bukkit}, for stubbing more methods.
   *
   * @return the static mock
   */
  public MockedStatic<Bukkit> getBukkit() {
    return this.bukkit;
  }

  /**
   * Gets the plugin that is injected by {@link #injectModule()}.
   *
   * @return the plugin mock
   */
  public Plugin getPlugin() {
    return this.plugin;
  }

  /**
   * Gets the plugin manager.
   *
   * @return the plugin manager mock
   */
  public PluginManager getPluginManager() {
    return this.pluginManager;
  }

  /**
   * Gets the scheduler.
   *
   * @return the scheduler mock
   */
  public BukkitScheduler getScheduler() {
    return this.scheduler;
  }

  /**
   * Gets the scoreboard manager.
   *
   * @return the scoreboard manager mock
   */
  public ScoreboardManager getScoreboardManager() {
    return this.scoreboardManager;
  }

  /**
   * Sets whether {@link Bukkit#isPrimaryThread()} reports the main thread.
   *
   * @param primaryThread true to report the main thread
   */
  public void setPrimaryThread(final boolean primaryThread) {
    this.primaryThread = primaryThread;
  }

  /**
   * Creates the Bukkit module and injects the plugin, which also caches the connections of the online players.
   *
   * @return the module
   * @throws ReflectiveOperationException if the module cannot be created
   */
  public BukkitModule injectModule() throws ReflectiveOperationException {
    final Constructor<BukkitModule> constructor = BukkitModule.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    final BukkitModule module = constructor.newInstance();
    module.inject(this.plugin);
    return module;
  }

  /**
   * Adds an online player whose packets are recorded. Players added after {@link #injectModule()} only receive
   * packets once {@link PacketUtils#init()} runs again.
   *
   * @param uuid the UUID of the player
   * @return the player mock, which is a {@link CraftPlayer}
   */
  public CraftPlayer addPlayer(final UUID uuid) {
    final CraftPlayer player = mock(CraftPlayer.class);
    final ServerPlayer handle = mock(ServerPlayer.class);
    final ServerGamePacketListenerImpl connection = mock(ServerGamePacketListenerImpl.class);
    final List<Packet<?>> packets = new CopyOnWriteArrayList<>();
    final List<ChannelFutureListener> pending = new CopyOnWriteArrayList<>();
    doAnswer(invocation -> {
      final Packet<?> packet = invocation.getArgument(0);
      packets.add(packet);
      return null;
    })
      .when(connection)
      .send(any(Packet.class));
    // a packet sent with a listener is written only when the test says so, like a connection that has not caught up
    doAnswer(invocation -> {
      final Packet<?> packet = invocation.getArgument(0);
      packets.add(packet);
      pending.add(invocation.getArgument(1));
      return null;
    })
      .when(connection)
      .send(any(Packet.class), any(ChannelFutureListener.class));
    handle.connection = connection;
    when(player.getHandle()).thenReturn(handle);
    when(player.getUniqueId()).thenReturn(uuid);
    this.bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
    this.onlinePlayers.add(player);
    this.sentPackets.put(uuid, packets);
    this.pendingWrites.put(uuid, pending);
    return player;
  }

  /**
   * Completes the writes of the packets sent to a player with a listener so far, in order, as its connection would
   * once it wrote them.
   *
   * @param uuid the UUID of the player
   * @return the number of writes completed
   * @throws Exception if a listener throws
   */
  public int completeWrites(final UUID uuid) throws Exception {
    final List<ChannelFutureListener> pending = this.pendingWrites.getOrDefault(uuid, new CopyOnWriteArrayList<>());
    final List<ChannelFutureListener> writes = List.copyOf(pending);
    pending.clear();
    for (final ChannelFutureListener listener : writes) {
      listener.operationComplete(mock(ChannelFuture.class));
    }
    return writes.size();
  }

  /**
   * Takes a player offline. The connection cache only notices once {@link PacketUtils#init()} runs again.
   *
   * @param uuid the UUID of the player
   */
  public void removePlayer(final UUID uuid) {
    this.onlinePlayers.removeIf(player -> {
        final UUID playerUuid = player.getUniqueId();
        return uuid.equals(playerUuid);
      });
    this.bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(null);
  }

  /**
   * Gets the packets sent to a player, in order.
   *
   * @param uuid the UUID of the player
   * @return the packets, or an empty list if the player is unknown
   */
  public List<Packet<?>> getSentPackets(final UUID uuid) {
    final List<Packet<?>> noPackets = List.of();
    final List<Packet<?>> packets = this.sentPackets.getOrDefault(uuid, noPackets);
    return List.copyOf(packets);
  }

  /**
   * Runs every scheduled task once, like one server tick. Tasks that were scheduled to run once are removed.
   *
   * @return the number of tasks that ran
   */
  public int runTasks() {
    final List<ScheduledTask> snapshot = new ArrayList<>(this.tasks);
    for (final ScheduledTask task : snapshot) {
      if (!task.repeating) {
        this.tasks.remove(task);
      }
      task.runnable.run();
    }
    return snapshot.size();
  }

  /**
   * Gets the number of tasks that are scheduled and not cancelled.
   *
   * @return the number of scheduled tasks
   */
  public int getScheduledTaskCount() {
    return this.tasks.size();
  }

  /**
   * Clears the connection cache, forgets the injected plugin, and removes the static mock of {@link Bukkit}.
   */
  @Override
  public void close() {
    try {
      PacketUtils.shutdown();
      clearInjectedPlugin();
    } finally {
      this.bukkit.close();
    }
  }

  /**
   * Forgets the plugin injected into {@link BukkitModule}.
   */
  public static void clearInjectedPlugin() {
    try {
      final Field field = BukkitModule.class.getDeclaredField("PLUGIN");
      field.setAccessible(true);
      field.set(null, null);
    } catch (final ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  /**
   * A task that was handed to the scheduler.
   */
  private static final class ScheduledTask {

    private final Runnable runnable;
    private final boolean repeating;
    private final BukkitTask task;

    ScheduledTask(final Runnable runnable, final boolean repeating, final BukkitTask task) {
      this.runnable = runnable;
      this.repeating = repeating;
      this.task = task;
    }

    @Override
    public String toString() {
      return "ScheduledTask[repeating=" + this.repeating + ", task=" + this.task + "]";
    }
  }
}
