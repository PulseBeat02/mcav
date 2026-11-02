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
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

final class YtdlpReleaseProcessTest {

  private static final long FORCED_EXIT_WAIT_SECONDS = 10;
  private static final long REAP_WAIT_SECONDS = 10;
  private static final Duration VERSION_DEADLINE = Duration.ofMillis(250);

  private static final String WAIT_MODE = "wait";
  private static final String VERSION = "2026.08.19";

  private static final String JAVA_BINARY_DIRECTORY = "bin";
  private static final String JAVA_NAME = "java";

  @Test
  void shouldReadOutputIndependentlyOfTheDeadlineWaiter() throws Exception {
    final Thread caller = Thread.currentThread();
    final Process process = mock(Process.class);
    when(process.waitFor(FORCED_EXIT_WAIT_SECONDS, TimeUnit.SECONDS)).thenReturn(true);
    when(process.getInputStream()).thenReturn(new CallerCheckingInputStream(caller));
    final AssertionFailedError failure = assertThrows(AssertionFailedError.class, () ->
      YtdlpReleaseTest.runVersion(process, VERSION_DEADLINE)
    );
    final boolean didNotFinish = failure.getMessage().startsWith("yt-dlp --version did not finish");
    assertTrue(didNotFinish, failure.getMessage());
  }

  @Test
  void shouldTimeOutAndReapAChildThatKeepsStandardOutputOpen() throws Exception {
    final Process process = startChild(WAIT_MODE);
    try {
      assertTimeoutPreemptively(Duration.ofSeconds(10), () -> this.assertTheDeadlineReapsTheChild(process));
    } finally {
      process.destroyForcibly();
      final boolean exited = process.waitFor(REAP_WAIT_SECONDS, TimeUnit.SECONDS);
      assertTrue(exited);
    }
  }

  private void assertTheDeadlineReapsTheChild(final Process process) {
    final AssertionFailedError failure = assertThrows(AssertionFailedError.class, () ->
      YtdlpReleaseTest.runVersion(process, VERSION_DEADLINE)
    );
    final boolean didNotFinish = failure.getMessage().startsWith("yt-dlp --version did not finish");
    assertTrue(didNotFinish);

    final boolean alive = process.isAlive();
    assertFalse(alive, "the deadline must reap its child");
  }

  @Test
  void shouldReturnTheCompleteVersionOutput() throws Exception {
    final Process process = startChild("version");
    try {
      final String version = YtdlpReleaseTest.runVersion(process, Duration.ofSeconds(10));
      assertEquals(VERSION, version);

      final boolean alive = process.isAlive();
      assertFalse(alive);
    } finally {
      process.destroyForcibly();
      final boolean exited = process.waitFor(REAP_WAIT_SECONDS, TimeUnit.SECONDS);
      assertTrue(exited);
    }
  }

  @Test
  void shouldBoundTheForcedExitWaitAfterTheVersionDeadline() throws Exception {
    final Process process = mock(Process.class);
    when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
    when(process.waitFor(FORCED_EXIT_WAIT_SECONDS, TimeUnit.SECONDS)).thenReturn(true);
    when(process.waitFor()).thenAnswer(_ -> this.refuseToWaitWithoutADeadline());
    final AssertionFailedError failure = assertThrows(AssertionFailedError.class, () ->
      YtdlpReleaseTest.runVersion(process, VERSION_DEADLINE)
    );
    final boolean didNotFinish = failure.getMessage().startsWith("yt-dlp --version did not finish");
    assertTrue(didNotFinish);
  }

  private int refuseToWaitWithoutADeadline() {
    final String message = "Forced-exit cleanup must not wait without a deadline!";
    throw new AssertionError(message);
  }

  @Test
  void shouldCloseTheOutputStreamOnTheDrainThread() throws Exception {
    final Thread caller = Thread.currentThread();
    final AtomicReference<Thread> closer = new AtomicReference<>();
    final Process process = mock(Process.class);
    when(process.getInputStream()).thenReturn(new CloseRecordingInputStream(closer));
    when(process.waitFor(FORCED_EXIT_WAIT_SECONDS, TimeUnit.SECONDS)).thenReturn(true);
    assertThrows(AssertionFailedError.class, () -> YtdlpReleaseTest.runVersion(process, VERSION_DEADLINE));

    final Thread closingThread = closer.get();
    assertNotSame(caller, closingThread, "closing stdout must not block the deadline waiter");

    final boolean closed = closer.get() != null;
    assertTrue(closed, "the drain still owns closing stdout");
  }

  private static Process startChild(final String mode) throws IOException, URISyntaxException {
    final String javaHome = System.getProperty("java.home");
    final String executable = Path.of(javaHome, JAVA_BINARY_DIRECTORY, JAVA_NAME).toString();
    // Gradle loads test classes outside the worker launcher's java.class.path.
    final String classpath = Path.of(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    final ProcessBuilder builder = new ProcessBuilder(
      executable,
      "-Xmx64m",
      "-XX:ActiveProcessorCount=2",
      "-cp",
      classpath,
      Child.class.getName(),
      mode
    );
    final Map<String, String> environment = builder.environment();
    // JVM startup diagnostics must not become part of the simulated yt-dlp version.
    environment.remove("JAVA_TOOL_OPTIONS");
    environment.remove("JDK_JAVA_OPTIONS");
    environment.remove("_JAVA_OPTIONS");
    builder.redirectErrorStream(true);
    return builder.start();
  }

  public static final class Child {

    private Child() {}

    public static void main(final String[] arguments) throws IOException {
      if (WAIT_MODE.equals(arguments[0])) {
        System.in.read();
      } else {
        System.out.println(VERSION);
      }
    }
  }

  private static final class CallerCheckingInputStream extends InputStream {

    private final Thread caller;

    CallerCheckingInputStream(final Thread caller) {
      this.caller = caller;
    }

    @Override
    public int read() {
      final Thread readingThread = Thread.currentThread();
      assertNotSame(this.caller, readingThread, "stdout must not block the deadline waiter");

      return -1;
    }

    @Override
    public int read(final byte[] buffer, final int offset, final int length) {
      return this.read();
    }
  }

  private static final class CloseRecordingInputStream extends ByteArrayInputStream {

    private final AtomicReference<Thread> closer;

    CloseRecordingInputStream(final AtomicReference<Thread> closer) {
      super(new byte[0]);
      this.closer = closer;
    }

    @Override
    public void close() {
      this.closer.set(Thread.currentThread());
    }
  }
}
