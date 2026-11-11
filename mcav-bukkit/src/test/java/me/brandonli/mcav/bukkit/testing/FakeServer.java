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

import com.google.common.base.MoreObjects;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.papermc.paper.configuration.GlobalConfiguration;
import io.papermc.paper.connection.PlayerGameConnection;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import me.brandonli.mcav.bukkit.BukkitModule;
import me.brandonli.mcav.bukkit.utils.PacketUtils;
import me.brandonli.mcav.bukkit.utils.versioning.ServerEnvironment;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.StandardMessenger;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.ScoreboardManager;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

public final class FakeServer implements AutoCloseable {

  private static final String SERVER_ADDRESS = "127.0.0.1";
  private static final int SERVER_PORT = 25565;

  private static final String CONNECTION_FIELD = "connection";
  private static final String INJECTED_PLUGIN_FIELD = "injectedPlugin";

  private final MockedStatic<Bukkit> bukkit;
  private final Plugin plugin;
  private final PluginManager pluginManager;
  private final BukkitScheduler scheduler;
  private final ScoreboardManager scoreboardManager;
  private final Messenger messenger;

  private final List<Player> onlinePlayers;
  private final Map<UUID, List<Packet<?>>> sentPackets;
  private final Map<UUID, List<ChannelFutureListener>> pendingWrites;
  private final Map<UUID, Set<Entity>> visibility;

  private final List<ScheduledTask> tasks;
  private final List<ScheduledTask> laterTasks;

  private volatile boolean primaryThread;

  private FakeServer() {
    MinecraftTestBootstrap.bootstrap();

    this.plugin = mock(Plugin.class);
    this.pluginManager = mock(PluginManager.class);
    this.scheduler = mock(BukkitScheduler.class);
    this.scoreboardManager = mock(ScoreboardManager.class);
    this.messenger = new StandardMessenger();
    this.onlinePlayers = new CopyOnWriteArrayList<>();
    this.sentPackets = new ConcurrentHashMap<>();
    this.pendingWrites = new ConcurrentHashMap<>();
    this.tasks = new CopyOnWriteArrayList<>();
    this.visibility = new ConcurrentHashMap<>();
    this.laterTasks = new CopyOnWriteArrayList<>();
    this.primaryThread = true;

    when(this.plugin.isEnabled()).thenReturn(true);
    this.stubScheduler();
    this.bukkit = this.mockBukkit();
  }

  public static FakeServer start() {
    return new FakeServer();
  }

  @Override
  public void close() {
    try {
      PacketUtils.shutdown();
      clearInjectedPlugin();
    } finally {
      this.bukkit.close();
    }
  }

  public static void clearInjectedPlugin() {
    try {
      final Field field = BukkitModule.class.getDeclaredField(INJECTED_PLUGIN_FIELD);
      field.setAccessible(true);
      field.set(null, null);
    } catch (final ReflectiveOperationException exception) {
      throw new LinkageError(exception.getMessage(), exception);
    }
  }

  private void stubScheduler() {
    when(this.scheduler.runTaskTimer(any(Plugin.class), any(Runnable.class), anyLong(), anyLong())).thenAnswer(this::scheduleRepeating);
    when(this.scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(this::scheduleOnce);
    when(this.scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class))).thenAnswer(this::scheduleOnce);
    when(this.scheduler.runTaskLater(any(Plugin.class), any(Runnable.class), anyLong())).thenAnswer(this::scheduleLater);
    when(this.scheduler.runTaskLaterAsynchronously(any(Plugin.class), any(Runnable.class), anyLong())).thenAnswer(this::scheduleLater);
  }

  private BukkitTask scheduleRepeating(final InvocationOnMock invocation) {
    final Runnable runnable = invocation.getArgument(1);
    return this.schedule(runnable, Repetition.REPEATING, this.tasks);
  }

  private BukkitTask scheduleOnce(final InvocationOnMock invocation) {
    final Runnable runnable = invocation.getArgument(1);
    return this.schedule(runnable, Repetition.ONCE, this.tasks);
  }

  private BukkitTask scheduleLater(final InvocationOnMock invocation) {
    final Runnable runnable = invocation.getArgument(1);
    return this.schedule(runnable, Repetition.ONCE, this.laterTasks);
  }

  // a static mock holds only on the thread that made it, so a worker thread that calls Bukkit needs its own
  public MockedStatic<Bukkit> mockBukkitOnThisThread() {
    return this.mockBukkit();
  }

  private MockedStatic<Bukkit> mockBukkit() {
    final MockedStatic<Bukkit> staticBukkit = Mockito.mockStatic(Bukkit.class);
    staticBukkit.when(Bukkit::getMinecraftVersion).thenReturn(ServerEnvironment.SUPPORTED_MINECRAFT_VERSION);
    staticBukkit.when(Bukkit::getPluginManager).thenReturn(this.pluginManager);
    staticBukkit.when(Bukkit::getScheduler).thenReturn(this.scheduler);
    staticBukkit.when(Bukkit::getScoreboardManager).thenReturn(this.scoreboardManager);
    staticBukkit.when(Bukkit::getMessenger).thenReturn(this.messenger);
    staticBukkit.when(Bukkit::getOnlinePlayers).thenAnswer(_ -> List.copyOf(this.onlinePlayers));
    staticBukkit.when(Bukkit::isPrimaryThread).thenAnswer(_ -> this.primaryThread);
    staticBukkit.when(Bukkit::getIp).thenReturn(SERVER_ADDRESS);
    staticBukkit.when(Bukkit::getPort).thenReturn(SERVER_PORT);

    return staticBukkit;
  }

  private BukkitTask schedule(final Runnable runnable, final Repetition repetition, final List<ScheduledTask> queue) {
    final BukkitTask task = mock(BukkitTask.class);
    final ScheduledTask scheduled = new ScheduledTask(runnable, repetition, task);
    doAnswer(_ -> this.cancel(queue, scheduled))
      .when(task)
      .cancel();
    queue.add(scheduled);

    return task;
  }

  private @Nullable Object cancel(final List<ScheduledTask> queue, final ScheduledTask scheduled) {
    queue.remove(scheduled);

    return null;
  }

  public void receivePluginMessage(final Player player, final String channel, final byte[] message) {
    final PlayerGameConnection connection = mock(PlayerGameConnection.class);
    when(connection.getPlayer()).thenReturn(player);
    this.messenger.dispatchIncomingMessage(connection, channel, message);
  }

  public BukkitModule injectModule() throws ReflectiveOperationException {
    final Constructor<BukkitModule> constructor = BukkitModule.class.getDeclaredConstructor();
    constructor.setAccessible(true);

    final BukkitModule module = constructor.newInstance();
    module.inject(this.plugin);

    return module;
  }

  // a player added after injectModule() receives packets once PacketUtils.initialize() runs again
  public CraftPlayer addPlayer(final UUID uuid) {
    final CraftPlayer player = mock(CraftPlayer.class);
    final ServerPlayer handle = mock(ServerPlayer.class);
    final List<Packet<?>> packets = new CopyOnWriteArrayList<>();
    final List<ChannelFutureListener> pending = new CopyOnWriteArrayList<>();
    final ServerGamePacketListenerImpl packetListener = this.mockPacketListener(packets, pending);
    handle.connection = packetListener;
    final Set<Entity> inverted = this.stubVisibility(player);
    when(player.getHandle()).thenReturn(handle);
    when(player.getUniqueId()).thenReturn(uuid);
    this.bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);

    this.visibility.put(uuid, inverted);
    this.onlinePlayers.add(player);
    this.sentPackets.put(uuid, packets);
    this.pendingWrites.put(uuid, pending);

    return player;
  }

  private ServerGamePacketListenerImpl mockPacketListener(final List<Packet<?>> packets, final List<ChannelFutureListener> pending) {
    final ServerGamePacketListenerImpl packetListener = mock(ServerGamePacketListenerImpl.class);
    doAnswer(invocation -> this.recordPacket(packets, invocation))
      .when(packetListener)
      .send(any(Packet.class));
    final Connection connection = mockConnection();
    connection.channel = this.mockRecordingChannel(packets, pending);
    connect(packetListener, connection);

    return packetListener;
  }

  private @Nullable Object recordPacket(final List<Packet<?>> packets, final InvocationOnMock invocation) {
    final Packet<?> packet = invocation.getArgument(0);
    packets.add(packet);

    return null;
  }

  private Set<Entity> stubVisibility(final CraftPlayer player) {
    // visibility as Paper keeps it: a grant inverts an entity's visibility by default, per session
    final Set<Entity> inverted = ConcurrentHashMap.newKeySet();
    doAnswer(invocation -> this.showEntity(inverted, invocation))
      .when(player)
      .showEntity(any(Plugin.class), any(Entity.class));
    doAnswer(invocation -> this.hideEntity(inverted, invocation))
      .when(player)
      .hideEntity(any(Plugin.class), any(Entity.class));
    when(player.canSee(any(Entity.class))).thenAnswer(invocation -> this.canSee(inverted, invocation));

    return inverted;
  }

  private @Nullable Object showEntity(final Set<Entity> inverted, final InvocationOnMock invocation) {
    final Entity entity = invocation.getArgument(1);
    if (entity.isVisibleByDefault()) {
      inverted.remove(entity);
    } else {
      inverted.add(entity);
    }

    return null;
  }

  private @Nullable Object hideEntity(final Set<Entity> inverted, final InvocationOnMock invocation) {
    final Entity entity = invocation.getArgument(1);
    if (entity.isVisibleByDefault()) {
      inverted.add(entity);
    } else {
      inverted.remove(entity);
    }

    return null;
  }

  private boolean canSee(final Set<Entity> inverted, final InvocationOnMock invocation) {
    final Entity entity = invocation.getArgument(0);
    return entity.isVisibleByDefault() != inverted.contains(entity);
  }

  // the connection class reads Paper's global configuration when it loads, so that is stubbed meanwhile
  public static Connection mockConnection() {
    final GlobalConfiguration configuration = mock(GlobalConfiguration.class);
    configuration.misc = mock(GlobalConfiguration.Misc.class);
    try (final MockedStatic<GlobalConfiguration> global = Mockito.mockStatic(GlobalConfiguration.class)) {
      global.when(GlobalConfiguration::get).thenReturn(configuration);
      return mock(Connection.class);
    }
  }

  public static void connect(final ServerCommonPacketListenerImpl listener, final Connection connection) {
    try {
      final Field field = ServerCommonPacketListenerImpl.class.getDeclaredField(CONNECTION_FIELD);
      field.setAccessible(true);
      field.set(listener, connection);
    } catch (final ReflectiveOperationException exception) {
      throw new LinkageError(exception.getMessage(), exception);
    }
  }

  private Channel mockRecordingChannel(final List<Packet<?>> packets, final List<ChannelFutureListener> pending) {
    final Channel channel = mock(Channel.class);
    final ChannelFuture write = mock(ChannelFuture.class);
    when(write.addListener(any())).thenAnswer(invocation -> this.addListener(pending, write, invocation));
    when(channel.writeAndFlush(any())).thenAnswer(invocation -> this.writeAndFlush(packets, write, invocation));
    when(channel.config()).thenReturn(mock(ChannelConfig.class));

    return channel;
  }

  private ChannelFuture addListener(
    final List<ChannelFutureListener> pending,
    final ChannelFuture write,
    final InvocationOnMock invocation
  ) {
    final ChannelFutureListener listener = invocation.getArgument(0);
    pending.add(listener);

    return write;
  }

  private ChannelFuture writeAndFlush(final List<Packet<?>> packets, final ChannelFuture write, final InvocationOnMock invocation) {
    final Packet<?> packet = invocation.getArgument(0);
    packets.add(packet);

    return write;
  }

  // as CraftPlayer.onEntityRemove does when Paper stops tracking the entity
  public void endTracking(final Entity entity) {
    final Collection<Set<Entity>> sessions = this.visibility.values();
    for (final Set<Entity> inverted : sessions) {
      inverted.remove(entity);
    }
  }

  public int completeWrites(final UUID uuid) throws Exception {
    final List<ChannelFutureListener> pending = this.pendingWrites.getOrDefault(uuid, new CopyOnWriteArrayList<>());
    final List<ChannelFutureListener> writes = List.copyOf(pending);
    pending.clear();

    for (final ChannelFutureListener listener : writes) {
      listener.operationComplete(mock(ChannelFuture.class));
    }

    return writes.size();
  }

  public void removePlayer(final UUID uuid) {
    this.onlinePlayers.removeIf(player -> this.hasUuid(uuid, player));
    this.bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(null);
  }

  private boolean hasUuid(final UUID uuid, final Player player) {
    final UUID playerUuid = player.getUniqueId();
    return uuid.equals(playerUuid);
  }

  public List<Packet<?>> getSentPackets(final UUID uuid) {
    final List<Packet<?>> noPackets = List.of();
    final List<Packet<?>> packets = this.sentPackets.getOrDefault(uuid, noPackets);
    return List.copyOf(packets);
  }

  public int runTasks() {
    final List<ScheduledTask> snapshot = new ArrayList<>(this.tasks);
    for (final ScheduledTask task : snapshot) {
      if (task.repetition == Repetition.ONCE) {
        this.tasks.remove(task);
      }
      task.runnable.run();
    }

    return snapshot.size();
  }

  // runTasks() never runs delayed tasks, so a test decides when their time has come
  public int runLaterTasks() {
    final List<ScheduledTask> snapshot = new ArrayList<>(this.laterTasks);
    for (final ScheduledTask task : snapshot) {
      this.laterTasks.remove(task);
      task.runnable.run();
    }

    return snapshot.size();
  }

  public MockedStatic<Bukkit> getBukkit() {
    return this.bukkit;
  }

  public Plugin getPlugin() {
    return this.plugin;
  }

  public PluginManager getPluginManager() {
    return this.pluginManager;
  }

  public BukkitScheduler getScheduler() {
    return this.scheduler;
  }

  public ScoreboardManager getScoreboardManager() {
    return this.scoreboardManager;
  }

  public Messenger getMessenger() {
    return this.messenger;
  }

  public void setPrimaryThread(final boolean primaryThread) {
    this.primaryThread = primaryThread;
  }

  public int getScheduledTaskCount() {
    return this.tasks.size();
  }

  private static final class ScheduledTask {

    private final Runnable runnable;
    private final Repetition repetition;
    private final BukkitTask task;

    ScheduledTask(final Runnable runnable, final Repetition repetition, final BukkitTask task) {
      this.runnable = runnable;
      this.repetition = repetition;
      this.task = task;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("repetition", this.repetition).add("task", this.task).toString();
    }
  }

  private enum Repetition {
    ONCE,
    REPEATING,
  }
}
