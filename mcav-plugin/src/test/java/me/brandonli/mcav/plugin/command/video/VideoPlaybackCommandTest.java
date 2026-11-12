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
package me.brandonli.mcav.plugin.command.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.MoreExecutors;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.multimedia.cv.AbstractVideoPlayerCv;
import me.brandonli.mcav.media.player.pipeline.filter.audio.VolumeFilter;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.testing.Components;
import me.brandonli.mcav.plugin.testing.StandardErrorCapture;
import me.brandonli.mcav.plugin.testing.TestServer;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class VideoPlaybackCommandTest {

  private static final String SEEK_OFFSET = "+10";

  private final ExecutorService directExecutor = MoreExecutors.newDirectExecutorService();

  private final VolumeFilter volume = new VolumeFilter();

  private VideoPlayerManager manager;

  private VideoPlaybackCommand command;

  private CommandSender sender;

  private VideoPlayerMultiplexer other;

  private AbstractVideoPlayerCv decoded;

  @BeforeEach
  void createCommand() {
    TestServer.reset();
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    this.manager = mock(VideoPlayerManager.class);
    when(plugin.getVideoPlayerManager()).thenReturn(this.manager);
    when(this.manager.getService()).thenReturn(this.directExecutor);
    when(this.manager.getVolume()).thenReturn(this.volume);
    this.command = new VideoPlaybackCommand(plugin);
    this.sender = mock(CommandSender.class);
    this.other = mock(VideoPlayerMultiplexer.class);
    this.decoded = mock(AbstractVideoPlayerCv.class);
  }

  @AfterEach
  void stopExecutor() {
    this.directExecutor.shutdown();
  }

  @Test
  void shouldRefuseWhatIsNotATimeAndAnswerWithoutAVideo() {
    this.command.seekVideo(this.sender, "1:60");
    this.command.seekVideo(this.sender, SEEK_OFFSET);
    this.assertReceived(Message.SEEK_INVALID.build("1:60"), Message.SEEK_FAILED.build());
  }

  private void assertReceived(final Component... expected) {
    final List<Component> received = Components.received(this.sender);
    final List<Component> expectedMessages = List.of(expected);
    assertEquals(expectedMessages, received);
  }

  @Test
  void shouldJumpFromWhereADecodedVideoPlays() {
    when(this.manager.getPlayer()).thenReturn(Optional.of(this.decoded));
    when(this.decoded.getPositionMilliseconds()).thenReturn(5_000L);
    when(this.decoded.seek(anyLong())).thenReturn(true);
    this.command.seekVideo(this.sender, SEEK_OFFSET);
    this.command.seekVideo(this.sender, "-1:00");
    verify(this.decoded).seek(15_000L);
    verify(this.decoded).seek(0L);
    this.assertReceived(Message.SEEK_PLAYER.build("0:15"), Message.SEEK_PLAYER.build("0:00"));
  }

  @Test
  void shouldJumpOnlyToATimeFromTheStartWithAPlayerThatDoesNotTellItsPosition() {
    when(this.manager.getPlayer()).thenReturn(Optional.of(this.other));
    when(this.other.seek(90_000L)).thenReturn(true);
    this.command.seekVideo(this.sender, "-10");
    this.command.seekVideo(this.sender, "1:30");
    verify(this.other).seek(90_000L);
    this.assertReceived(Message.SEEK_RELATIVE_UNSUPPORTED.build(), Message.SEEK_PLAYER.build("1:30"));
  }

  @Test
  void shouldReportAJumpThatFailsOrThrows() {
    when(this.manager.getPlayer()).thenReturn(Optional.of(this.other));
    when(this.other.seek(1_000L)).thenReturn(false);
    when(this.other.seek(2_000L)).thenThrow(new IllegalStateException("the grabber broke!"));
    final String output;
    try (final StandardErrorCapture errors = StandardErrorCapture.start()) {
      this.command.seekVideo(this.sender, "1");
      this.command.seekVideo(this.sender, "2");
      output = errors.getOutput();
    }
    this.assertReceived(Message.SEEK_FAILED.build(), Message.SEEK_FAILED.build());

    final boolean matches = output.contains("Failed to seek the video") && output.contains("the grabber broke");
    assertTrue(matches, output);
  }

  @Test
  void shouldSetTheVolumeInPercent() {
    this.command.setVolume(this.sender, 80);

    final double volume = this.volume.getVolume();
    assertEquals(0.8, volume);

    this.command.setVolume(this.sender, 200);

    final double volumeVolume = this.volume.getVolume();
    assertEquals(VolumeFilter.MAXIMUM_VOLUME, volumeVolume);

    this.assertReceived(Message.VOLUME_SET.build(80), Message.VOLUME_SET.build(200));
  }

  @Test
  void shouldChangeTheSpeedOfAFileOnly() {
    this.command.setSpeed(this.sender, 1.5);
    when(this.manager.getPlayer()).thenReturn(Optional.of(this.other));
    this.command.setSpeed(this.sender, 1.5);
    when(this.manager.getPlayer()).thenReturn(Optional.of(this.decoded));
    when(this.decoded.setSpeed(1.5)).thenReturn(true);
    this.command.setSpeed(this.sender, 1.5);
    when(this.decoded.setSpeed(2)).thenReturn(false);
    this.command.setSpeed(this.sender, 2);
    verify(this.other, never()).seek(anyLong());
    verifyNoInteractions(this.other);
    verify(this.decoded).setSpeed(1.5);
    verify(this.decoded).setSpeed(2);
    this.assertReceived(
      Message.SPEED_FAILED.build(),
      Message.VLC_UNSUPPORTED.build(),
      Message.SPEED_SET.build("1.5"),
      Message.SPEED_FAILED.build()
    );
  }

  @Test
  void shouldLoopAndSayWhenThePlayerCannot() {
    this.command.setLoop(this.sender, true);
    when(this.manager.getPlayer()).thenReturn(Optional.of(this.other));
    this.command.setLoop(this.sender, true);
    this.command.setLoop(this.sender, false);
    when(this.manager.getPlayer()).thenReturn(Optional.of(this.decoded));
    this.command.setLoop(this.sender, true);
    verify(this.manager, times(3)).setLooping(true);
    verify(this.manager).setLooping(false);
    this.assertReceived(
      Message.LOOP_ON.build(),
      Message.LOOP_ON.build(),
      Message.VLC_UNSUPPORTED.build(),
      Message.LOOP_OFF.build(),
      Message.LOOP_ON.build()
    );
  }

  @Test
  void shouldRefuseNullArguments() {
    final NullPointerException nullPlugin = assertThrows(NullPointerException.class, () -> new VideoPlaybackCommand(null));
    final String message = nullPlugin.getMessage();
    assertEquals("Plugin must not be null!", message);

    assertThrows(NullPointerException.class, () -> this.command.seekVideo(null, SEEK_OFFSET));
    assertThrows(NullPointerException.class, () -> this.command.seekVideo(this.sender, null));
    assertThrows(NullPointerException.class, () -> this.command.setVolume(null, 0));
    assertThrows(NullPointerException.class, () -> this.command.setSpeed(null, 0.0));
    assertThrows(NullPointerException.class, () -> this.command.setLoop(null, true));
  }
}
