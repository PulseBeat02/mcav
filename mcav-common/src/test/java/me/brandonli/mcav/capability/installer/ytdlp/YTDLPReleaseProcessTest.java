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
package me.brandonli.mcav.capability.installer.ytdlp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

final class YTDLPReleaseProcessTest {

  @Test
  void readsOutputIndependentlyOfTheDeadlineWaiter() throws Exception {
    final Thread caller = Thread.currentThread();
    final Process process = mock(Process.class);
    when(process.waitFor(10, TimeUnit.SECONDS)).thenReturn(true);
    when(process.getInputStream()).thenReturn(
      new InputStream() {
        @Override
        public int read() {
          assertNotSame(caller, Thread.currentThread(), "stdout must not block the deadline waiter");
          return -1;
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) {
          return this.read();
        }
      }
    );
    final AssertionFailedError failure = assertThrows(AssertionFailedError.class, () ->
      YTDLPReleaseTest.runVersion(process, Duration.ofMillis(250))
    );
    assertTrue(failure.getMessage().startsWith("yt-dlp --version did not finish"), failure.getMessage());
  }

  @Test
  void timesOutAndReapsAChildThatKeepsStdoutOpen() throws Exception {
    final Process process = startChild("wait");
    try {
      assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
        final AssertionFailedError failure = assertThrows(AssertionFailedError.class, () ->
          YTDLPReleaseTest.runVersion(process, Duration.ofMillis(250))
        );
        assertTrue(failure.getMessage().startsWith("yt-dlp --version did not finish"));
        assertFalse(process.isAlive(), "the deadline must reap its child");
      });
    } finally {
      process.destroyForcibly();
      assertTrue(process.waitFor(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void returnsTheCompleteVersionOutput() throws Exception {
    final Process process = startChild("version");
    try {
      assertEquals("2026.08.19", YTDLPReleaseTest.runVersion(process, Duration.ofSeconds(10)));
      assertFalse(process.isAlive());
    } finally {
      process.destroyForcibly();
      assertTrue(process.waitFor(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void boundsTheForcedExitWaitAfterTheVersionDeadline() throws Exception {
    final Process process = mock(Process.class);
    when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
    when(process.waitFor(10, TimeUnit.SECONDS)).thenReturn(true);
    when(process.waitFor()).thenAnswer(invocation -> {
      throw new AssertionError("forced-exit cleanup must not wait without a deadline");
    });
    final AssertionFailedError failure = assertThrows(AssertionFailedError.class, () ->
      YTDLPReleaseTest.runVersion(process, Duration.ofMillis(250))
    );
    assertTrue(failure.getMessage().startsWith("yt-dlp --version did not finish"));
  }

  @Test
  void closesTheOutputStreamOnTheDrainThread() throws Exception {
    final Thread caller = Thread.currentThread();
    final AtomicReference<Thread> closer = new AtomicReference<>();
    final Process process = mock(Process.class);
    when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]) {
      @Override
      public void close() {
        closer.set(Thread.currentThread());
      }
    });
    when(process.waitFor(10, TimeUnit.SECONDS)).thenReturn(true);
    assertThrows(AssertionFailedError.class, () -> YTDLPReleaseTest.runVersion(process, Duration.ofMillis(250)));
    assertNotSame(caller, closer.get(), "closing stdout must not block the deadline waiter");
    assertTrue(closer.get() != null, "the drain still owns closing stdout");
  }

  private static Process startChild(final String mode) throws IOException {
    final String javaHome = System.getProperty("java.home");
    final String executable = Path.of(javaHome, "bin", "java").toString();
    final String classpath = System.getProperty("java.class.path");
    return new ProcessBuilder(executable, "-cp", classpath, Child.class.getName(), mode).redirectErrorStream(true).start();
  }

  public static final class Child {

    private Child() {}

    public static void main(final String[] arguments) throws IOException {
      if ("wait".equals(arguments[0])) {
        System.in.read();
      } else {
        System.out.println("2026.08.19");
      }
    }
  }
}
