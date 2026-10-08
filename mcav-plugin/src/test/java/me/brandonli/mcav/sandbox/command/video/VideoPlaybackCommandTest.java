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
package me.brandonli.mcav.sandbox.command.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.concurrent.ExecutorService;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.multimedia.cv.AbstractVideoPlayerCV;
import me.brandonli.mcav.media.player.pipeline.filter.audio.VolumeFilter;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.testing.Components;
import me.brandonli.mcav.sandbox.testing.StandardErrorCapture;
import me.brandonli.mcav.sandbox.testing.TestServer;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class VideoPlaybackCommandTest {

  private final ExecutorService directExecutor = MoreExecutors.newDirectExecutorService();

  private final VolumeFilter volume = new VolumeFilter();

  private VideoPlayerManager manager;

  private VideoPlaybackCommand command;

  private CommandSender sender;

  /** A player of another kind than FFmpeg's and OpenCV's, such as VLC's. */
  private VideoPlayerMultiplexer other;

  private AbstractVideoPlayerCV decoded;

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
    this.decoded = mock(AbstractVideoPlayerCV.class);
  }

  @AfterEach
  void stopExecutor() {
    this.directExecutor.shutdown();
  }

  private void assertReceived(final Component... expected) {
    assertEquals(List.of(expected), Components.received(this.sender));
  }

  @Test
  void refusesWhatIsNotATimeAndAnswersWithoutAVideo() {
    this.command.seekVideo(this.sender, "1:60");
    this.command.seekVideo(this.sender, "+10");
    this.assertReceived(Message.SEEK_INVALID.build("1:60"), Message.SEEK_FAILED.build());
  }

  @Test
  void jumpsFromWhereADecodedVideoPlays() {
    when(this.manager.getPlayer()).thenReturn(this.decoded);
    when(this.decoded.getPositionMillis()).thenReturn(5_000L);
    when(this.decoded.seek(anyLong())).thenReturn(true);
    this.command.seekVideo(this.sender, "+10");
    this.command.seekVideo(this.sender, "-1:00");
    verify(this.decoded).seek(15_000L);
    verify(this.decoded).seek(0L);
    this.assertReceived(Message.SEEK_PLAYER.build("0:15"), Message.SEEK_PLAYER.build("0:00"));
  }

  @Test
  void jumpsOnlyToATimeFromTheStartWithAPlayerThatDoesNotTellItsPosition() {
    when(this.manager.getPlayer()).thenReturn(this.other);
    when(this.other.seek(90_000L)).thenReturn(true);
    this.command.seekVideo(this.sender, "-10");
    this.command.seekVideo(this.sender, "1:30");
    verify(this.other).seek(90_000L);
    this.assertReceived(Message.SEEK_RELATIVE_UNSUPPORTED.build(), Message.SEEK_PLAYER.build("1:30"));
  }

  @Test
  void reportsAJumpThatFailsOrThrows() {
    when(this.manager.getPlayer()).thenReturn(this.other);
    when(this.other.seek(1_000L)).thenReturn(false);
    when(this.other.seek(2_000L)).thenThrow(new IllegalStateException("the grabber broke"));
    final String output;
    try (final StandardErrorCapture errors = StandardErrorCapture.start()) {
      this.command.seekVideo(this.sender, "1");
      this.command.seekVideo(this.sender, "2");
      output = errors.getOutput();
    }
    this.assertReceived(Message.SEEK_FAILED.build(), Message.SEEK_FAILED.build());
    assertTrue(output.contains("Failed to seek the video") && output.contains("the grabber broke"), output);
  }

  @Test
  void setsTheVolumeInPercent() {
    this.command.setVolume(this.sender, 80);
    assertEquals(0.8, this.volume.getVolume());
    this.command.setVolume(this.sender, 200);
    assertEquals(VolumeFilter.MAX_VOLUME, this.volume.getVolume());
    this.assertReceived(Message.VOLUME_SET.build(80), Message.VOLUME_SET.build(200));
  }

  @Test
  void changesTheSpeedOfAFileOnly() {
    this.command.setSpeed(this.sender, 1.5);
    when(this.manager.getPlayer()).thenReturn(this.other);
    this.command.setSpeed(this.sender, 1.5);
    when(this.manager.getPlayer()).thenReturn(this.decoded);
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
  void loopsAndSaysWhenThePlayerCannot() {
    this.command.setLoop(this.sender, true);
    when(this.manager.getPlayer()).thenReturn(this.other);
    this.command.setLoop(this.sender, true);
    this.command.setLoop(this.sender, false);
    when(this.manager.getPlayer()).thenReturn(this.decoded);
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
}
