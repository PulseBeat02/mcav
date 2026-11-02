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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import me.brandonli.mcav.utils.os.Os;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.MockSettings;
import org.mockito.MockedConstruction;

final class VmProcessPortProbeTest {

  private static final String UNUSED_EXECUTABLE = "unused-qemu";
  private static final String UNUSED_KVM_DEVICE = "unused-kvm";

  private AtomicBoolean reuseAtBind;
  private AtomicBoolean reachedBind;
  private IOException stopped;

  @BeforeEach
  void setUp() {
    this.reuseAtBind = new AtomicBoolean(true);
    this.reachedBind = new AtomicBoolean();
    this.stopped = new IOException("stop at the port-probe boundary!");
  }

  @Test
  void shouldDisableAddressReuseBeforeProbingEvenWhenTheSocketInitiallyEnablesIt() throws IOException {
    // ServerSocket's API explicitly leaves the initial SO_REUSEADDR setting undefined. Model its allowed true case.
    try (final ServerSocket delegate = new ServerSocket()) {
      delegate.setReuseAddress(true);
      final boolean reuseEnabled = delegate.getReuseAddress();
      assertTrue(reuseEnabled);

      final MockSettings settings = withSettings();
      settings.defaultAnswer(AdditionalAnswers.delegatesTo(delegate));
      try (
        final MockedConstruction<ServerSocket> sockets = mockConstruction(ServerSocket.class, settings, (socket, _) ->
          this.stopAtBind(socket, delegate)
        )
      ) {
        final VmProcess process = this.createProcess();
        final VmException failure = assertThrows(VmException.class, process::start);
        final List<ServerSocket> constructed = sockets.constructed();
        final boolean noSocketConstructed = constructed.isEmpty();
        final Throwable cause = failure.getCause();
        final boolean bound = this.reachedBind.get();
        final boolean reusedAtBind = this.reuseAtBind.get();
        assertFalse(noSocketConstructed);
        assertSame(this.stopped, cause);
        assertTrue(bound);
        assertFalse(reusedAtBind, "the preflight must test a non-reusable bind regardless of the OS default");
      }
    }
  }

  private void stopAtBind(final ServerSocket socket, final ServerSocket delegate) throws IOException {
    doAnswer(_ -> this.recordBind(delegate))
      .when(socket)
      .bind(any(SocketAddress.class));
  }

  private Object recordBind(final ServerSocket delegate) throws IOException {
    final boolean reuse = delegate.getReuseAddress();
    this.reuseAtBind.set(reuse);
    this.reachedBind.set(true);
    throw this.stopped;
  }

  private VmProcess createProcess() {
    final VmSettings display = VmSettings.of(5905, 320, 240, 15);
    final VmConfiguration configuration = VmConfiguration.builder().build();
    final Path executable = Path.of(UNUSED_EXECUTABLE);
    final QemuProgram program = QemuProgram.of(executable);
    final Path kvm = Path.of(UNUSED_KVM_DEVICE);
    final VmProcess.Launcher launcher = _ -> {
      final String message = "QEMU must not launch after the port probe fails!";
      throw new AssertionError(message);
    };
    return new VmProcess(display, VmPlayer.Architecture.X86_64, program, configuration, launcher, Os.LINUX, kvm, 1, () -> {
      final String message = "no password is written after the port probe fails!";
      throw new AssertionError(message);
    });
  }
}
