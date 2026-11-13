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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.MoreExecutors;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.audio.AudioProvider;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.testing.Components;
import me.brandonli.mcav.plugin.testing.StandardErrorCapture;
import me.brandonli.mcav.plugin.testing.TestServer;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

final class VideoControlCommandTest {

  private final ExecutorService directExecutor = MoreExecutors.newDirectExecutorService();

  private VideoPlayerManager manager;
  private VideoControlCommand command;
  private CommandSender sender;
  private VideoPlayerMultiplexer player;

  @BeforeEach
  void createCommand() {
    TestServer.reset();
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    this.manager = mock(VideoPlayerManager.class);
    when(plugin.getVideoPlayerManager()).thenReturn(this.manager);
    when(this.manager.getService()).thenReturn(this.directExecutor);
    this.command = new VideoControlCommand(plugin);
    this.sender = mock(CommandSender.class);
    this.player = mock(VideoPlayerMultiplexer.class);
  }

  @AfterEach
  void stopExecutor() {
    this.directExecutor.shutdown();
  }

  @Test
  void shouldResumeTheVideo() {
    when(this.manager.getPlayer()).thenReturn(Optional.of(this.player));
    when(this.player.resume()).thenReturn(true);

    this.command.resumeVideo(this.sender);

    verify(this.player).resume();

    final Component resumed = Message.RESUME_PLAYER.build();
    this.assertReceived(resumed);
  }

  private void assertReceived(final Component... expected) {
    final List<Component> messages = Components.received(this.sender);
    final List<Component> expectedMessages = List.of(expected);
    assertEquals(expectedMessages, messages);
  }

  @Test
  void shouldTellTheSenderWhenAVideoThatEndedCannotBeResumed() {
    when(this.manager.getPlayer()).thenReturn(Optional.of(this.player));
    when(this.player.resume()).thenReturn(false);

    this.command.resumeVideo(this.sender);

    verify(this.player).resume();

    final Component failed = Message.RESUME_PLAYER_FAILED.build();
    this.assertReceived(failed);
  }

  @Test
  void shouldPauseTheVideo() {
    when(this.manager.getPlayer()).thenReturn(Optional.of(this.player));

    this.command.pauseVideo(this.sender);

    verify(this.player).pause();

    final Component paused = Message.PAUSE_PLAYER.build();
    this.assertReceived(paused);
  }

  @Test
  void shouldAnswerEvenWithoutAVideo() {
    this.command.resumeVideo(this.sender);
    this.command.pauseVideo(this.sender);

    final Component resumed = Message.RESUME_PLAYER.build();
    final Component paused = Message.PAUSE_PLAYER.build();
    this.assertReceived(resumed, paused);
  }

  @Test
  void shouldReleaseTheVideoOnTheWorkerThreadAndReportIt() {
    this.command.releaseVideo(this.sender);

    final Component start = Message.RELEASE_PLAYER_START.build();
    final Component done = Message.RELEASE_PLAYER.build();
    final InOrder order = inOrder(this.sender, this.manager);
    order.verify(this.sender).sendMessage(start);
    order.verify(this.manager).clearCurrentVideo();
    order.verify(this.sender).sendMessage(done);
    this.assertReceived(start, done);
  }

  @Test
  void shouldNotLetAQueuedReleaseCancelARequestSubmittedAfterIt() {
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    final MCAVApi api = mock(MCAVApi.class);
    final AudioProvider provider = mock(AudioProvider.class);
    when(plugin.getMCAV()).thenReturn(api);
    when(plugin.getAudioProvider()).thenReturn(provider);
    final VideoPlayerManager realManager = new VideoPlayerManager(plugin);
    final VideoPlayerManager owner = Mockito.spy(realManager);
    final ExecutorService deferred = mock(ExecutorService.class);
    final List<Runnable> pending = new ArrayList<>();
    Mockito.doAnswer(invocation -> this.deferTask(invocation, pending))
      .when(deferred)
      .execute(ArgumentMatchers.any(Runnable.class));
    Mockito.doReturn(deferred).when(owner).getService();
    when(plugin.getVideoPlayerManager()).thenReturn(owner);
    final VideoControlCommand controls = new VideoControlCommand(plugin);
    try {
      final long oldGeneration = owner.beginStart();
      controls.releaseVideo(this.sender);
      final boolean oldCurrent = owner.isCurrent(oldGeneration);
      assertFalse(oldCurrent, "release invalidates the previous request immediately");

      final long nextGeneration = owner.beginStart();
      final Runnable cleanup = pending.getFirst();
      cleanup.run();
      final boolean nextCurrent = owner.isCurrent(nextGeneration);
      assertTrue(nextCurrent, "queued cleanup must not invalidate a newer admitted request");
      verify(owner, Mockito.times(1)).cancelStart();
    } finally {
      owner.shutdown();
    }
  }

  private @Nullable Object deferTask(final InvocationOnMock invocation, final List<Runnable> pending) {
    final Runnable task = invocation.getArgument(0);
    pending.add(task);

    return null;
  }

  @Test
  void shouldLogAFailedReleaseAndTellTheSenderThatItFailed() {
    final IllegalStateException failure = new IllegalStateException("the release failed!");
    doThrow(failure).when(this.manager).clearCurrentVideo();

    final String output;
    try (final StandardErrorCapture errors = StandardErrorCapture.start()) {
      this.command.releaseVideo(this.sender);
      output = errors.getOutput();
    }

    final Component start = Message.RELEASE_PLAYER_START.build();
    final Component failed = Message.RELEASE_PLAYER_ERROR.build();
    this.assertReceived(start, failed);

    final boolean logged = output.contains("Failed to release the video player");
    final boolean withCause = output.contains("the release failed!");
    assertTrue(logged, output);
    assertTrue(withCause, output);
  }

  @Test
  void shouldSetTheLocationOfTheHologram() {
    final Location location = new Location(null, 1.0, 70.0, 1.0);

    this.command.setHologramLocation(this.sender, location);

    verify(this.manager).setHologramLocation(location);

    final Component set = Message.HOLOGRAM_LOCATION_SET.build();
    this.assertReceived(set);
  }

  @Test
  void shouldDisableTheHologram() {
    this.command.disableHologram(this.sender);

    verify(this.manager).setHologramLocation(null);

    final Component disabled = Message.HOLOGRAM_DISABLED.build();
    this.assertReceived(disabled);
  }

  @Test
  void shouldRefuseNullArguments() {
    final NullPointerException nullPlugin = assertThrows(NullPointerException.class, () -> new VideoControlCommand(null));
    final String message = nullPlugin.getMessage();
    assertEquals("Plugin must not be null!", message);

    final Location location = new Location(null, 0.0, 0.0, 0.0);
    assertThrows(NullPointerException.class, () -> this.command.resumeVideo(null));
    assertThrows(NullPointerException.class, () -> this.command.pauseVideo(null));
    assertThrows(NullPointerException.class, () -> this.command.releaseVideo(null));
    assertThrows(NullPointerException.class, () -> this.command.setHologramLocation(null, location));
    assertThrows(NullPointerException.class, () -> this.command.setHologramLocation(this.sender, null));
    assertThrows(NullPointerException.class, () -> this.command.disableHologram(null));
  }
}
