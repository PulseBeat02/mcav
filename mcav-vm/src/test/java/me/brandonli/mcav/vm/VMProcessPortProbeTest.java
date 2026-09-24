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
package me.brandonli.mcav.vm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.withSettings;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.utils.os.OS;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.MockSettings;
import org.mockito.MockedConstruction;

/** Checks the port probe's explicit pre-bind socket policy independently of platform defaults. */
final class VMProcessPortProbeTest {

  @Test
  void disablesAddressReuseBeforeProbingEvenWhenTheSocketInitiallyEnablesIt() throws IOException {
    // ServerSocket's API explicitly leaves the initial SO_REUSEADDR setting undefined. Model its allowed true case.
    try (final ServerSocket delegate = new ServerSocket()) {
      delegate.setReuseAddress(true);
      assertTrue(delegate.getReuseAddress());
      final AtomicBoolean reuseAtBind = new AtomicBoolean(true);
      final AtomicBoolean reachedBind = new AtomicBoolean();
      final IOException stopped = new IOException("stop at the port-probe boundary");
      final MockSettings settings = withSettings();
      settings.defaultAnswer(AdditionalAnswers.delegatesTo(delegate));
      try (
        final MockedConstruction<ServerSocket> sockets = mockConstruction(ServerSocket.class, settings, (socket, _) -> {
          doAnswer(_ -> {
            reuseAtBind.set(delegate.getReuseAddress());
            reachedBind.set(true);
            // Do not bind a real service or launch QEMU: observe the kernel-backed option immediately before bind.
            throw stopped;
          })
            .when(socket)
            .bind(any(SocketAddress.class));
        })
      ) {
        final VMSettings display = VMSettings.of(5905, 320, 240, 15);
        final VMConfiguration configuration = VMConfiguration.builder();
        final Path executable = Path.of("unused-qemu");
        final Path kvm = Path.of("unused-kvm");
        final VMProcess.Launcher launcher = _ -> {
          throw new AssertionError("QEMU must not launch after the port probe fails");
        };
        final VMProcess process = new VMProcess(display, executable, configuration, launcher, OS.LINUX, kvm, 1);
        final PlayerException failure = assertThrows(PlayerException.class, process::start);
        assertFalse(sockets.constructed().isEmpty());
        assertSame(stopped, failure.getCause());
        assertTrue(reachedBind.get());
        assertFalse(reuseAtBind.get(), "the preflight must test a non-reusable bind regardless of the OS default");
      }
    }
  }
}
