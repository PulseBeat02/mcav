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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.MoreExecutors;
import java.util.List;
import java.util.concurrent.ExecutorService;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.testing.Components;
import me.brandonli.mcav.plugin.testing.StandardErrorCapture;
import me.brandonli.mcav.plugin.testing.TestServer;
import me.brandonli.mcav.plugin.utils.CaptureDevices;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

final class VideoDevicesCommandTest {

  private final ExecutorService directExecutor = MoreExecutors.newDirectExecutorService();

  private VideoPlayerManager manager;

  private VideoDevicesCommand command;

  private CommandSender sender;

  private MockedStatic<CaptureDevices> devices;

  @BeforeEach
  void createCommand() {
    TestServer.reset();
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    this.manager = mock(VideoPlayerManager.class);
    when(plugin.getVideoPlayerManager()).thenReturn(this.manager);
    when(this.manager.getService()).thenReturn(this.directExecutor);
    this.command = new VideoDevicesCommand(plugin);
    this.sender = mock(CommandSender.class);
    this.devices = Mockito.mockStatic(CaptureDevices.class);
  }

  @AfterEach
  void stop() {
    this.devices.close();
    this.directExecutor.shutdown();
  }

  @Test
  void shouldListTheDevicesAndRememberThemForTheDevicePlayer() {
    final List<CaptureDevices.Device> listed = List.of(
      CaptureDevices.Device.of(0, "Integrated Camera"),
      CaptureDevices.Device.of(2, "OBS Virtual Camera")
    );
    this.devices.when(CaptureDevices::list).thenReturn(listed);
    this.command.listDevices(this.sender);
    verify(this.manager).setDevices(listed);

    final List<Component> expected = List.of(Message.DEVICE_LIST.build("0: Integrated Camera, 2: OBS Virtual Camera"));
    final List<Component> received = Components.received(this.sender);
    assertEquals(expected, received);
  }

  @Test
  void shouldSayWhenThereIsNoDeviceOrTheListingFails() {
    this.devices.when(CaptureDevices::list).thenReturn(List.of());
    this.command.listDevices(this.sender);
    this.devices.when(CaptureDevices::list).thenThrow(new IllegalStateException("no sysfs!"));
    final String output;
    try (final StandardErrorCapture errors = StandardErrorCapture.start()) {
      this.command.listDevices(this.sender);
      output = errors.getOutput();
    }
    verify(this.manager, Mockito.times(2)).setDevices(List.of());

    final List<Component> expected = List.of(Message.DEVICE_NONE.build(), Message.DEVICE_NONE.build());
    final List<Component> received = Components.received(this.sender);
    assertEquals(expected, received);

    final boolean matches = output.contains("Failed to list the capture devices") && output.contains("no sysfs");
    assertTrue(matches, output);
  }

  @Test
  void shouldRefuseNullArguments() {
    final NullPointerException nullPlugin = assertThrows(NullPointerException.class, () -> new VideoDevicesCommand(null));
    final String message = nullPlugin.getMessage();
    assertEquals("Plugin must not be null!", message);

    assertThrows(NullPointerException.class, () -> this.command.listDevices(null));
  }
}
