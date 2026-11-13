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
package me.brandonli.mcav.plugin.command.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.MoreExecutors;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.testing.Components;
import me.brandonli.mcav.plugin.testing.StandardErrorCapture;
import me.brandonli.mcav.plugin.testing.TestServer;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.invocation.InvocationOnMock;

final class ImageControlCommandTest {

  private final ExecutorService direct = MoreExecutors.newDirectExecutorService();

  private ImageManager manager;
  private ImageControlCommand command;

  @BeforeEach
  void createCommand() {
    TestServer.reset();
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    this.manager = mock(ImageManager.class);
    when(plugin.getImageManager()).thenReturn(this.manager);
    when(this.manager.getService()).thenReturn(this.direct);
    this.command = new ImageControlCommand(plugin);
  }

  @AfterEach
  void stopExecutor() {
    this.direct.shutdown();
  }

  @Test
  void shouldReleaseTheImageOnTheLoadingThreadAndReportIt() {
    final ExecutorService worker = mock(ExecutorService.class);
    final List<Runnable> work = new ArrayList<>();
    doAnswer(invocation -> this.deferTask(invocation, work))
      .when(worker)
      .execute(any(Runnable.class));
    when(this.manager.getService()).thenReturn(worker);
    final CommandSender sender = mock(CommandSender.class);
    this.command.releaseImage(sender);
    final Component start = Message.RELEASE_IMAGE_START.build();
    final List<Component> beforeRelease = Components.received(sender);
    final List<Component> started = List.of(start);
    assertEquals(started, beforeRelease);
    verify(this.manager, never()).releaseImage(any());

    final Runnable release = work.getFirst();
    release.run();
    final Component done = Message.RELEASE_IMAGE.build();
    final InOrder order = inOrder(sender, this.manager);
    order.verify(sender).sendMessage(start);
    order.verify(this.manager).releaseImage(ReleaseThread.MAIN_THREAD);
    order.verify(sender).sendMessage(done);

    final List<Component> messages = Components.received(sender);
    final List<Component> expected = List.of(start, done);
    assertEquals(expected, messages);
  }

  private @Nullable Object deferTask(final InvocationOnMock invocation, final List<Runnable> work) {
    final Runnable task = invocation.getArgument(0);
    work.add(task);

    return null;
  }

  @Test
  void shouldReportTheReleaseWhenNoImageIsShown() {
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    final ImageManager emptyManager = new ImageManager(plugin, this.direct);
    when(plugin.getImageManager()).thenReturn(emptyManager);
    final ImageControlCommand emptyCommand = new ImageControlCommand(plugin);
    final CommandSender sender = mock(CommandSender.class);
    emptyCommand.releaseImage(sender);

    final Component start = Message.RELEASE_IMAGE_START.build();
    final Component done = Message.RELEASE_IMAGE.build();
    final List<Component> messages = Components.received(sender);
    final List<Component> expected = List.of(start, done);
    assertEquals(expected, messages);
  }

  @Test
  void shouldLogAFailedReleaseAndTellTheSenderThatItFailed() {
    final CommandSender sender = mock(CommandSender.class);
    final IllegalStateException failure = new IllegalStateException("the release failed!");
    doThrow(failure).when(this.manager).releaseImage(ReleaseThread.MAIN_THREAD);

    final String output;
    try (final StandardErrorCapture errors = StandardErrorCapture.start()) {
      this.command.releaseImage(sender);
      output = errors.getOutput();
    }

    final Component start = Message.RELEASE_IMAGE_START.build();
    final Component failed = Message.RELEASE_IMAGE_ERROR.build();
    final List<Component> messages = Components.received(sender);
    final List<Component> expected = List.of(start, failed);
    assertEquals(expected, messages);

    final boolean logged = output.contains("Failed to release the image");
    final boolean withCause = output.contains("the release failed!");
    assertTrue(logged, output);
    assertTrue(withCause, output);
  }

  @Test
  void shouldRefuseNullArguments() {
    final NullPointerException exception = assertThrows(NullPointerException.class, () -> new ImageControlCommand(null));
    final String message = exception.getMessage();
    assertEquals("Plugin must not be null!", message);

    assertThrows(NullPointerException.class, () -> this.command.releaseImage(null));
  }
}
