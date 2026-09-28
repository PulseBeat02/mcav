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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import me.brandonli.mcav.bukkit.media.result.CompressedMapResult;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.sandbox.listener.OnlinePlayers;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.testing.Components;
import me.brandonli.mcav.sandbox.testing.TestServer;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.sandbox.utils.MapCodec;
import me.brandonli.mcav.utils.immutable.Pair;
import me.brandonli.mcav.utils.interaction.MouseClick;
import me.brandonli.mcav.vnc.VNCPlayer;
import me.brandonli.mcav.vnc.VNCSource;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link VncCommand}: only a server of the allow-list is connected to, with the password of its entry, and the
 * desktop is streamed, driven and released like the browser.
 */
final class VncCommandTest {

  private static final long RELEASE_MILLIS = 5000;

  private static final VncAllowList.Entry DESKTOP = new VncAllowList.Entry("127.0.0.1", 5901, "secret");

  private VncCommand command;
  private PluginDataConfigurationMapper configuration;
  private CommandSender sender;
  private MultiplePlayerSelector selector;
  private VNCPlayer desktop;
  private VideoAttachableCallback callback;
  private MockedStatic<VNCPlayer> desktops;
  private MockedStatic<DitherFilter> dithers;
  private MockedConstruction<CompressedMapResult> maps;

  @BeforeEach
  void createCommand() {
    final Server server = TestServer.reset();
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    when(plugin.getOnlinePlayers()).thenReturn(new OnlinePlayers());
    when(plugin.getServer()).thenReturn(server);
    this.configuration = mock(PluginDataConfigurationMapper.class);
    when(plugin.getConfiguration()).thenReturn(this.configuration);
    when(this.configuration.getVncAllowList()).thenReturn(new VncAllowList(List.of(DESKTOP)));
    when(this.configuration.getMcv2DefaultCodec()).thenReturn(MapCodec.DITHER);
    this.command = new VncCommand(plugin);
    this.sender = mock(CommandSender.class);
    this.selector = mock(MultiplePlayerSelector.class);
    when(this.selector.values()).thenReturn(List.of(mock(Player.class)));
    this.desktop = mock(VNCPlayer.class);
    this.callback = mock(VideoAttachableCallback.class);
    when(this.desktop.getVideoAttachableCallback()).thenReturn(this.callback);
    this.desktops = Mockito.mockStatic(VNCPlayer.class);
    this.desktops.when(VNCPlayer::create).thenReturn(this.desktop);
    this.dithers = Mockito.mockStatic(DitherFilter.class);
    this.dithers.when(() -> DitherFilter.dither(any(), any())).thenReturn(mock(FunctionalVideoFilter.class));
    this.maps = Mockito.mockConstruction(CompressedMapResult.class);
  }

  @AfterEach
  void closeMocks() {
    this.desktops.close();
    this.dithers.close();
    this.maps.close();
    this.command.shutdown();
  }

  private void create(final String resolution, final String server) {
    this.command.createVnc(this.sender, this.selector, resolution, 20, "5x3", 0, DitheringArgument.NEAREST_COLOR, server, null);
  }

  private void assertReceived(final Component... expected) {
    assertEquals(List.of(expected), Components.received(this.sender));
  }

  @Test
  void connectsToAListedServerWithThePasswordOfItsEntry() {
    when(this.desktop.startAsync(any(VNCSource.class), any())).thenReturn(CompletableFuture.completedFuture(true));

    this.create("1280x720", "127.0.0.1:5901");

    final ArgumentCaptor<VNCSource> sources = ArgumentCaptor.forClass(VNCSource.class);
    verify(this.desktop).startAsync(sources.capture(), any());
    final VNCSource source = sources.getValue();
    assertEquals("127.0.0.1", source.getHost());
    assertEquals(5901, source.getPort());
    assertEquals("secret", source.getPassword());
    assertEquals(1280, source.getScreenWidth());
    assertEquals(720, source.getScreenHeight());
    assertEquals(20, source.getTargetFrameRate());
    verify(this.callback).attach(any(VideoPipelineStep.class));
    assertSame(this.desktop, this.command.player);
    this.assertReceived(Message.VNC_LOADING.build(), Message.VNC_CREATE.build());
  }

  @Test
  void connectsToNoServerThatIsNotListed() {
    this.create("1280x720", "10.0.0.2:5900");

    this.assertReceived(Message.VNC_NOT_ALLOWED.build("10.0.0.2:5900"));
    this.desktops.verifyNoInteractions();
    assertNull(this.command.result);
  }

  @Test
  void connectsToNothingWithInvalidDimensions() {
    this.create("wide", "127.0.0.1:5901");

    this.desktops.verifyNoInteractions();
    assertNull(this.command.result);
  }

  @Test
  void connectsToNothingWithInvalidBlockDimensions() {
    this.command.createVnc(this.sender, this.selector, "1280x720", 20, "tall", 0, DitheringArgument.NEAREST_COLOR, "127.0.0.1:5901", null);

    this.desktops.verifyNoInteractions();
    assertNull(this.command.result);
  }

  @Test
  void tellsTheSenderWhenTheServerCannotBeReached() {
    when(this.desktop.startAsync(any(VNCSource.class), any())).thenReturn(
      CompletableFuture.failedFuture(new IllegalStateException("refused"))
    );

    this.create("1280x720", "127.0.0.1:5901");

    this.assertReceived(Message.VNC_LOADING.build(), Message.VNC_ERROR.build());
  }

  @Test
  void aServerWithoutAPasswordGetsNone() {
    final VncAllowList.Entry open = new VncAllowList.Entry("127.0.0.1", 5902, null);
    final VNCSource source = VncCommand.createSource(open, Pair.pair(640, 480), 10);
    assertNull(source.getPassword());
  }

  @Test
  void forwardsClicksAndTextToTheDesktop() {
    this.command.handleLeftClick(this.desktop, 10, 20);
    this.command.handleRightClick(this.desktop, 30, 40);
    this.command.handleTextInput(this.desktop, "hello");

    verify(this.desktop).sendMouseEvent(MouseClick.LEFT, 10, 20);
    verify(this.desktop).sendMouseEvent(MouseClick.RIGHT, 30, 40);
    verify(this.desktop).sendKeyEvent("hello");
    assertEquals(VncCommand.INTERACT_PERMISSION, this.command.getInteractionPermission());
  }

  @Test
  void releasesTheDesktopWhenAsked() {
    this.command.player = this.desktop;

    this.command.releaseVnc(this.sender);

    verify(this.callback).detach();
    verify(this.desktop, timeout(RELEASE_MILLIS)).release();
    assertNull(this.command.player);
    this.assertReceived(Message.VNC_RELEASE.build());
  }

  @Test
  void togglesTheChatInteractionOfAPlayer() {
    final Player player = mock(Player.class);

    this.command.toggleInteraction(player);
    this.command.toggleInteraction(player);

    assertEquals(List.of(Message.INTERACT_ENABLE.build(), Message.INTERACT_DISABLE.build()), Components.received(player));
  }
}
