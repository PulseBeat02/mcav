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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests that a player whose MCV2 client mod reports a shader pack in use, which keeps MCV2 from decoding, sees an MCV2
 * screen as a player without the pack does, and the video again once the report says otherwise.
 */
final class Mcv2ShaderFallbackTest {

  private static final UUID PACK = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000051");

  private static final UUID LATE_VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000052");

  private static final String CHANNEL = "mcav:mcv2";

  private static final byte[] SHADERS_ON = { 1, 1, 1, 0 };

  private static final byte[] SHADERS_OFF = { 1, 1, 0, 0 };

  private FakeServer server;

  private CraftPlayer player;

  private CraftPlayer latePlayer;

  private Mcv2Viewers viewers;

  @BeforeEach
  void startServer() throws ReflectiveOperationException {
    this.server = FakeServer.start();
    this.player = this.server.addPlayer(VIEWER);
    this.latePlayer = this.server.addPlayer(LATE_VIEWER);
    this.server.injectModule();
    this.viewers = new Mcv2Viewers(PACK, _ -> {});
    this.viewers.register();
  }

  @AfterEach
  void stopServer() {
    this.viewers.unregister();
    this.server.close();
  }

  @Test
  void aPlayerReportingShadersInUseGetsTheDitheredMapsUntilTheyReportThemOff() {
    this.load(this.player);
    final Mcv2Channel channel = this.channel(List.of(VIEWER));
    channel.update();
    this.server.runTasks();
    assertEquals(Set.of(), channel.update(), "with the pack loaded, the player gets the frames");

    this.server.getMessenger().dispatchIncomingMessage(this.player, CHANNEL, SHADERS_ON);
    assertEquals(Set.of(VIEWER), channel.update(), "a player reporting shaders in use still gets MCV2 frames");
    assertEquals(Set.of(), channel.getRecipients());

    this.server.getMessenger().dispatchIncomingMessage(this.player, CHANNEL, SHADERS_OFF);
    channel.update();
    this.server.runTasks();
    assertEquals(Set.of(), channel.update(), "shaders off, the player is shown the screen again without rejoining");
    assertEquals(Set.of(VIEWER), channel.getRecipients());
  }

  @Test
  void aPlayerWhoReportedShadersBeforeTheirPackLoadedIsNeverShownTheFrames() {
    this.server.getMessenger().dispatchIncomingMessage(this.latePlayer, CHANNEL, SHADERS_ON);
    this.load(this.latePlayer);
    final Mcv2Channel channel = this.channel(List.of(LATE_VIEWER));
    assertEquals(Set.of(LATE_VIEWER), channel.update());
    assertEquals(0, this.server.runTasks(), "no show is scheduled for a player who cannot decode");
    assertEquals(Set.of(LATE_VIEWER), channel.update());
    assertEquals(Set.of(), channel.getRecipients());
  }

  @Test
  void listensToTheChannelWhileRegisteredAndForgetsAPlayerWhoLeft() {
    assertTrue(this.server.getMessenger().isIncomingChannelRegistered(this.server.getPlugin(), CHANNEL));
    this.load(this.player);
    this.server.getMessenger().dispatchIncomingMessage(this.player, CHANNEL, SHADERS_ON);
    assertTrue(this.viewers.hasShaderReport(VIEWER));
    assertFalse(this.viewers.isLoaded(VIEWER));
    assertFalse(this.viewers.hasShaderReport(LATE_VIEWER), "a report is its sender's alone");

    this.viewers.handleQuit(new PlayerQuitEvent(this.player, (Component) null, PlayerQuitEvent.QuitReason.DISCONNECTED));
    assertFalse(this.viewers.hasShaderReport(VIEWER));
    this.load(this.player);
    assertTrue(this.viewers.isLoaded(VIEWER), "a player who joins again without a report decodes as before");

    this.viewers.unregister();
    assertFalse(this.server.getMessenger().isIncomingChannelRegistered(this.server.getPlugin(), CHANNEL));
    this.server.getMessenger().dispatchIncomingMessage(this.player, CHANNEL, SHADERS_ON);
    assertTrue(this.viewers.isLoaded(VIEWER), "an unregistered tracker reads no reports");
  }

  private void load(final CraftPlayer viewer) {
    this.viewers.handleStatus(new PlayerResourcePackStatusEvent(viewer, PACK, PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED));
  }

  private Mcv2Channel channel(final List<UUID> selected) {
    final Mcv2Configuration configuration = Mcv2Configuration.builder()
      .viewers(selected)
      .origin(new Location(mock(World.class), 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(100)
      .columns(1)
      .rows(1)
      .pageMap(500)
      .build();
    return new Mcv2Channel(configuration, this.viewers, mock(Mcv2Screen.class));
  }
}
