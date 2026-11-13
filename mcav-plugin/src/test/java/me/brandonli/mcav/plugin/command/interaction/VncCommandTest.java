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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import me.brandonli.mcav.bukkit.media.result.CompressedMapResult;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.plugin.listener.OnlinePlayers;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.testing.Components;
import me.brandonli.mcav.plugin.testing.TestServer;
import me.brandonli.mcav.plugin.utils.DitheringArgument;
import me.brandonli.mcav.plugin.utils.MapCodec;
import me.brandonli.mcav.utils.immutable.Pair;
import me.brandonli.mcav.utils.interaction.MouseClick;
import me.brandonli.mcav.vnc.VncPlayer;
import me.brandonli.mcav.vnc.VncSource;
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

final class VncCommandTest {

  private static final long RELEASE_MILLISECONDS = 5000;

  private static final String LOOPBACK_HOST = "127.0.0.1";
  private static final VncAllowList.Entry DESKTOP = VncAllowList.Entry.of(LOOPBACK_HOST, 5901, "secret");

  private static final String DESKTOP_ADDRESS = "127.0.0.1:5901";

  private static final String RESOLUTION = "1280x720";
  private static final int FRAMES_PER_SECOND = 20;

  private VncCommand command;
  private PluginDataConfigurationMapper configuration;
  private CommandSender sender;
  private MultiplePlayerSelector selector;
  private VncPlayer desktop;
  private VideoAttachableCallback callback;
  private MockedStatic<VncPlayer> desktops;
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
    when(this.configuration.getVncAllowList()).thenReturn(VncAllowList.of(List.of(DESKTOP)));
    when(this.configuration.getMcv2DefaultCodec()).thenReturn(MapCodec.DITHER);
    this.command = new VncCommand(plugin);
    this.sender = mock(CommandSender.class);
    this.selector = mock(MultiplePlayerSelector.class);
    when(this.selector.values()).thenReturn(List.of(mock(Player.class)));
    this.desktop = mock(VncPlayer.class);
    this.callback = mock(VideoAttachableCallback.class);
    when(this.desktop.getVideoAttachableCallback()).thenReturn(this.callback);
    this.desktops = Mockito.mockStatic(VncPlayer.class);
    this.desktops.when(VncPlayer::create).thenReturn(this.desktop);
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

  @Test
  void shouldConnectToAListedServerWithThePasswordOfItsEntry() {
    when(this.desktop.startAsync(any(VncSource.class), any())).thenReturn(CompletableFuture.completedFuture(true));

    this.create(RESOLUTION, DESKTOP_ADDRESS);

    final ArgumentCaptor<VncSource> sources = ArgumentCaptor.forClass(VncSource.class);
    verify(this.desktop).startAsync(sources.capture(), any());

    final VncSource source = sources.getValue();

    final String host = source.getHost();
    assertEquals(LOOPBACK_HOST, host);

    final int port = source.getPort();
    assertEquals(5901, port);

    final Optional<String> password = source.getPassword();
    final Optional<String> expectedPassword = Optional.of("secret");
    assertEquals(expectedPassword, password);

    final int screenWidth = source.getScreenWidth();
    assertEquals(1280, screenWidth);

    final int screenHeight = source.getScreenHeight();
    assertEquals(720, screenHeight);

    final int targetFrameRate = source.getTargetFrameRate();
    assertEquals(FRAMES_PER_SECOND, targetFrameRate);

    verify(this.callback).attach(any(VideoPipelineStep.class));
    assertSame(this.desktop, this.command.player);
    this.assertReceived(Message.VNC_LOADING.build(), Message.VNC_CREATE.build());
  }

  private void create(final String resolution, final String server) {
    this.command.createVnc(
      this.sender,
      this.selector,
      resolution,
      FRAMES_PER_SECOND,
      "5x3",
      0,
      DitheringArgument.NEAREST_COLOR,
      server,
      null
    );
  }

  private void assertReceived(final Component... expected) {
    final List<Component> received = Components.received(this.sender);
    final List<Component> expectedMessages = List.of(expected);
    assertEquals(expectedMessages, received);
  }

  @Test
  void shouldConnectToNoServerThatIsNotListed() {
    this.create(RESOLUTION, "10.0.0.2:5900");

    this.assertReceived(Message.VNC_NOT_ALLOWED.build("10.0.0.2:5900"));
    this.desktops.verifyNoInteractions();
    assertNull(this.command.output);
  }

  @Test
  void shouldConnectToNothingWithInvalidDimensions() {
    this.create("wide", DESKTOP_ADDRESS);

    this.assertReceived(Message.UNSUPPORTED_DIMENSION.build());
    this.desktops.verifyNoInteractions();
    assertNull(this.command.output);
  }

  @Test
  void shouldConnectToNothingWithInvalidBlockDimensions() {
    this.command.createVnc(
      this.sender,
      this.selector,
      RESOLUTION,
      FRAMES_PER_SECOND,
      "tall",
      0,
      DitheringArgument.NEAREST_COLOR,
      DESKTOP_ADDRESS,
      null
    );

    this.assertReceived(Message.UNSUPPORTED_DIMENSION.build());
    this.desktops.verifyNoInteractions();
    assertNull(this.command.output);
  }

  @Test
  void shouldTellTheSenderWhenTheServerCannotBeReached() {
    when(this.desktop.startAsync(any(VncSource.class), any())).thenReturn(
      CompletableFuture.failedFuture(new IllegalStateException("refused!"))
    );

    this.create(RESOLUTION, DESKTOP_ADDRESS);

    this.assertReceived(Message.VNC_LOADING.build(), Message.VNC_ERROR.build());
  }

  @Test
  void shouldGiveNoPasswordToAServerWithoutOne() {
    final VncAllowList.Entry open = VncAllowList.Entry.of(LOOPBACK_HOST, 5902, null);
    final VncSource source = this.command.createSource(open, Pair.pair(640, 480), 10);

    final Optional<String> password = source.getPassword();
    assertEquals(Optional.empty(), password);
  }

  @Test
  void shouldForwardClicksAndTextToTheDesktop() {
    this.command.forwardLeftClick(this.desktop, 10, 20);
    this.command.forwardRightClick(this.desktop, 30, 40);
    this.command.forwardTextInput(this.desktop, "hello");

    verify(this.desktop).sendMouseEvent(MouseClick.LEFT, 10, 20);
    verify(this.desktop).sendMouseEvent(MouseClick.RIGHT, 30, 40);
    verify(this.desktop).sendKeyEvent("hello");

    final String interactionPermission = this.command.getInteractionPermission();
    assertEquals(VncCommand.INTERACT_PERMISSION, interactionPermission);
  }

  @Test
  void shouldReleaseTheDesktopWhenAsked() {
    this.command.player = this.desktop;

    this.command.releaseVnc(this.sender);

    verify(this.callback).detach();
    verify(this.desktop, timeout(RELEASE_MILLISECONDS)).release();
    assertNull(this.command.player);
    this.assertReceived(Message.VNC_RELEASE.build());
  }

  @Test
  void shouldReleaseTheDesktopWhenCallbackAttachmentFails() {
    final IllegalStateException failure = new IllegalStateException("callback attach!");
    Mockito.doThrow(failure).when(this.callback).attach(any(VideoPipelineStep.class));
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> this.create(RESOLUTION, DESKTOP_ADDRESS));
    assertSame(failure, thrown);

    verify(this.callback).detach();
    verify(this.desktop, timeout(RELEASE_MILLISECONDS)).release();
    assertNull(this.command.player);
  }

  @Test
  void shouldToggleTheChatInteractionOfAPlayer() {
    final Player player = mock(Player.class);

    this.command.toggleInteraction(player);
    this.command.toggleInteraction(player);

    final List<Component> expected = List.of(Message.INTERACT_ENABLE.build(), Message.INTERACT_DISABLE.build());
    final List<Component> received = Components.received(player);
    assertEquals(expected, received);
  }
}
