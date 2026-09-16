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
package me.brandonli.mcav.sandbox.command.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.MoreExecutors;
import java.util.List;
import java.util.concurrent.ExecutorService;
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
import org.mockito.InOrder;

/**
 * Tests {@link ImageControlCommand}.
 */
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
  void releasesTheImageOnTheLoadingThreadAndReportsIt() {
    final CommandSender sender = mock(CommandSender.class);
    this.command.releaseImage(sender);
    final Component start = Message.RELEASE_IMAGE_START.build();
    final Component done = Message.RELEASE_IMAGE.build();
    final InOrder order = inOrder(sender, this.manager);
    order.verify(sender).sendMessage(start);
    order.verify(this.manager).releaseImage(false);
    order.verify(sender).sendMessage(done);

    final List<Component> messages = Components.received(sender);
    final List<Component> expected = List.of(start, done);
    assertEquals(expected, messages);
  }

  @Test
  void logsAFailedReleaseAndDoesNotReportTheImageAsReleased() {
    final CommandSender sender = mock(CommandSender.class);
    final IllegalStateException failure = new IllegalStateException("the release failed");
    doThrow(failure).when(this.manager).releaseImage(false);

    final String output;
    try (final StandardErrorCapture errors = StandardErrorCapture.start()) {
      this.command.releaseImage(sender);
      output = errors.getOutput();
    }

    final Component start = Message.RELEASE_IMAGE_START.build();
    final List<Component> messages = Components.received(sender);
    final List<Component> expected = List.of(start);
    assertEquals(expected, messages);
    final boolean logged = output.contains("Failed to release the image");
    final boolean withCause = output.contains("the release failed");
    assertTrue(logged, output);
    assertTrue(withCause, output);
  }

  @Test
  void refusesNullArguments() {
    assertThrows(NullPointerException.class, () -> new ImageControlCommand(null));
    assertThrows(NullPointerException.class, () -> this.command.releaseImage(null));
  }
}
