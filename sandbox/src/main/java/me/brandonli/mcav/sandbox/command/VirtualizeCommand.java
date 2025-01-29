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
package me.brandonli.mcav.sandbox.command;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.result.MapResult;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.player.vm.VMConfiguration;
import me.brandonli.mcav.media.player.vm.VMPlayer;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.sandbox.utils.InteractUtils;
import me.brandonli.mcav.sandbox.utils.Keys;
import me.brandonli.mcav.utils.ExecutorUtils;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.RayTraceResult;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotation.specifier.Quoted;
import org.incendo.cloud.annotation.specifier.Range;
import org.incendo.cloud.annotations.*;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;

public final class VirtualizeCommand implements AnnotationCommandFeature, Listener {

  private static final PlainTextComponentSerializer PLAIN_TEXT_SERIALIZER = PlainTextComponentSerializer.plainText();

  private @Nullable VMPlayer vmPlayer;

  private Set<Player> activePlayers;
  private ExecutorService service;

  @Override
  public void registerFeature(final MCAVSandbox plugin, final AnnotationParser<CommandSender> parser) {
    this.service = Executors.newVirtualThreadPerTaskExecutor();
    this.activePlayers = Collections.newSetFromMap(new WeakHashMap<>());
    final Server server = plugin.getServer();
    final PluginManager pluginManager = server.getPluginManager();
    pluginManager.registerEvents(this, plugin);
  }

  @EventHandler
  public void onEntityDamageByEntity(final EntityDamageByEntityEvent event) {
    final VMPlayer vm = this.vmPlayer;
    if (vm == null) {
      return;
    }

    final Entity damager = event.getDamager();
    if (!(damager instanceof final Player player)) {
      return;
    }

    final Entity entity = event.getEntity();
    if (!(entity instanceof final ItemFrame frame)) {
      return;
    }

    final PersistentDataContainer data = frame.getPersistentDataContainer();
    if (!data.has(Keys.MAP_KEY, PersistentDataType.BOOLEAN)) {
      return;
    }
    event.setCancelled(true);

    final int[] coordinates = InteractUtils.getBoardCoordinates(player);
    final int x = coordinates[0];
    final int y = coordinates[1];
    vm.moveMouse(x, y);
    vm.updateMouseButton(1, true);
    vm.updateMouseButton(1, false);
  }

  @EventHandler
  public void onPlayerInteractEntityEvent(final PlayerInteractEntityEvent event) {
    final VMPlayer vm = this.vmPlayer;
    if (vm == null) {
      return;
    }

    final Entity entity = event.getRightClicked();
    if (!(entity instanceof final ItemFrame frame)) {
      return;
    }

    final PersistentDataContainer data = frame.getPersistentDataContainer();
    if (!data.has(Keys.MAP_KEY, PersistentDataType.BOOLEAN)) {
      return;
    }
    event.setCancelled(true);

    final Player player = event.getPlayer();
    final int[] coordinates = InteractUtils.getBoardCoordinates(player);
    final int x = coordinates[0];
    final int y = coordinates[1];
    vm.moveMouse(x, y);
    vm.updateMouseButton(3, true);
    vm.updateMouseButton(3, false);
  }

  @Command("mcav vm interact")
  @Permission("mcav.vm.interact")
  @CommandDescription("mcav.command.vm.interact.info")
  public void activateInteraction(final Player player) {
    if (this.activePlayers.contains(player)) {
      this.activePlayers.remove(player);
      player.sendMessage(Message.INTERACT_DISABLE.build());
    } else {
      this.activePlayers.add(player);
      player.sendMessage(Message.INTERACT_ENABLE.build());
    }
  }

  @EventHandler
  public void onBlockBreak(final BlockBreakEvent event) {
    final VMPlayer vm = this.vmPlayer;
    if (vm == null) {
      return;
    }

    final Player player = event.getPlayer();
    if (!this.activePlayers.contains(player)) {
      return;
    }

    final RayTraceResult entityRay = player.rayTraceEntities(100, false);
    if (entityRay == null) {
      return;
    }

    final Entity entity = entityRay.getHitEntity();
    if (!(entity instanceof final ItemFrame frame)) {
      return;
    }

    final PersistentDataContainer data = frame.getPersistentDataContainer();
    if (!data.has(Keys.MAP_KEY, PersistentDataType.BOOLEAN)) {
      return;
    }
    event.setCancelled(true);

    final Block block = event.getBlock();
    final int[] coordinates = InteractUtils.getBoardCoordinates(player, block);
    final int x = coordinates[0];
    final int y = coordinates[1];
    vm.updateMouseButton(3, true);
    vm.updateMouseButton(3, false);
  }

  @EventHandler
  public void onChatMessage(final AsyncChatEvent event) {
    final Player player = event.getPlayer();
    if (!this.activePlayers.contains(player)) {
      return;
    }

    final VMPlayer vm = this.vmPlayer;
    if (vm == null) {
      return;
    }

    final Component message = event.message();
    final String raw = PLAIN_TEXT_SERIALIZER.serialize(message);
    vm.type(raw);
  }

  @Command("mcav vm release")
  @Permission("mcav.vm.release")
  @CommandDescription("mcav.command.vm.release.info")
  public void releaseVM(final CommandSender player) {
    if (this.vmPlayer != null) {
      try {
        this.vmPlayer.release();
        this.vmPlayer = null;
      } catch (final Exception e) {
        throw new AssertionError(e);
      }
    }
    player.sendMessage(Message.VM_RELEASE.build());
  }

  @Command("mcav vm create <playerSelector> <browserResolution> <blockDimensions> <mapId> <ditheringAlgorithm> <architecture> <flags>")
  @Permission("mcav.command.vm.create")
  @CommandDescription("mcav.command.vm.create.info")
  public void playVM(
    final CommandSender player,
    final MultiplePlayerSelector playerSelector,
    @Argument(suggestions = "resolutions") @Quoted final String browserResolution,
    @Argument(suggestions = "dimensions") @Quoted final String blockDimensions,
    @Argument(suggestions = "ids") @Range(min = "0") final int mapId,
    final DitheringArgument ditheringAlgorithm,
    final VMPlayer.Architecture architecture,
    @Greedy final String flags
  ) {
    final Pair<Integer, Integer> resolution;
    final Pair<Integer, Integer> dimensions;
    try {
      resolution = ArgumentUtils.parseDimensions(browserResolution);
      dimensions = ArgumentUtils.parseDimensions(blockDimensions);
    } catch (final IllegalArgumentException e) {
      player.sendMessage(Message.UNSUPPORTED_DIMENSION.build());
      return;
    }

    if (this.vmPlayer != null) {
      try {
        this.vmPlayer.release();
        this.vmPlayer = null;
      } catch (final Exception e) {
        throw new AssertionError(e);
      }
    }

    final int resolutionWidth = resolution.getFirst();
    final int resolutionHeight = resolution.getSecond();
    final int blockWidth = dimensions.getFirst();
    final int blockHeight = dimensions.getSecond();

    final Collection<UUID> players = ArgumentUtils.parsePlayerSelectors(playerSelector);
    final MapConfiguration configuration = MapConfiguration.builder()
      .map(mapId)
      .mapBlockHeight(blockHeight)
      .mapBlockWidth(blockWidth)
      .viewers(players)
      .build();
    final DitherAlgorithm algorithm = ditheringAlgorithm.getAlgorithm();
    final MapResult result = new MapResult(configuration);
    final VideoFilter filter = DitherFilter.dither(algorithm, result);
    final VideoPipelineStep pipeline = VideoPipelineStep.of(filter);
    final VideoMetadata metadata = VideoMetadata.of(resolutionWidth, resolutionHeight);
    final VMConfiguration config = this.parseVMOptions(flags);

    player.sendMessage(Message.VM_LOADING.build());
    try {
      this.vmPlayer = VMPlayer.vm();
      this.vmPlayer.startAsync(pipeline, architecture, config, metadata, this.service).thenRun(() ->
          player.sendMessage(Message.VM_CREATE.build())
        );
    } catch (final Exception e) {
      throw new AssertionError(e);
    }
  }

  private VMConfiguration parseVMOptions(final String commandLine) {
    final VMConfiguration config = VMConfiguration.builder();
    final String[] parts = commandLine.trim().split("\\s+");
    for (int i = 0; i < parts.length; i++) {
      if (parts[i].startsWith("-")) {
        final String option = parts[i].substring(1);
        if (i + 1 < parts.length && !parts[i + 1].startsWith("-")) {
          config.option(option, parts[i + 1]);
          i++;
        } else {
          config.flag(option);
        }
      }
    }
    return config;
  }

  @Override
  public void shutdown() {
    HandlerList.unregisterAll(this);
    ExecutorUtils.shutdownExecutorGracefully(this.service);
  }
}
