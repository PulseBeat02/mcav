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
package me.brandonli.mcav.browser;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import me.brandonli.mcav.media.player.PlayerException;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

final class HelperSessionCleanupTest {

  @TempDir
  private Path directory;

  @ParameterizedTest
  @ValueSource(booleans = { false, true })
  void closesTheAcceptedChannelWhenSocketUnlinkFails(final boolean unchecked) throws Exception {
    final Path folder = Files.createDirectory(this.directory.resolve("session"));
    final Path socket = folder.resolve("s");
    final HelperLauncher launcher = mock(HelperLauncher.class);
    final BrowserSession.Listener listener = mock(BrowserSession.Listener.class);
    final Process process = mock(Process.class);
    final ProcessBuilder builder = mock(ProcessBuilder.class);
    final ServerSocketChannel server = mock(ServerSocketChannel.class);
    final Throwable failure = unchecked ? new IllegalStateException("unlink failed") : new IOException("unlink failed");
    when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());
    when(process.descendants()).thenAnswer(_ -> Stream.empty());
    when(process.waitFor(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
    when(builder.start()).thenReturn(process);
    when(launcher.getStartTimeoutMillis()).thenReturn(1000L);
    try (
      final SocketChannel accepted = SocketChannel.open();
      final MockedStatic<HelperSession> helpers = mockStatic(HelperSession.class, CALLS_REAL_METHODS);
      final MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)
    ) {
      helpers.when(() -> HelperSession.createFolder(this.directory)).thenReturn(folder);
      helpers.when(() -> HelperSession.bind(any(Path.class))).thenReturn(server);
      helpers.when(() -> HelperSession.createProcessBuilder(eq(launcher), eq(folder), isNull())).thenReturn(builder);
      helpers.when(() -> HelperSession.accept(eq(server), eq(process), anyLong())).thenReturn(accepted);
      files
        .when(() -> Files.deleteIfExists(any(Path.class)))
        .thenAnswer(invocation -> {
          final Path path = invocation.getArgument(0);
          if (path.equals(socket)) {
            throw failure;
          }
          return invocation.callRealMethod();
        });
      final BrowserSource source = BrowserSource.uri(URI.create("https://example.com/page"), 4, 3, 1);
      if (unchecked) {
        assertSame(
          failure,
          assertThrows(IllegalStateException.class, () ->
            HelperSession.open(launcher, this.directory, source, BrowserOptions.DEFAULT, listener, this.directory)
          )
        );
      } else {
        final PlayerException thrown = assertThrows(PlayerException.class, () ->
          HelperSession.open(launcher, this.directory, source, BrowserOptions.DEFAULT, listener, this.directory)
        );
        assertSame(failure, thrown.getCause());
      }
      assertFalse(accepted.isOpen(), "an accepted channel has an owner before session construction");
    }
  }
}
