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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import me.brandonli.mcav.media.player.PlayerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class XvfbDisplayTest {

  @TempDir
  Path folder;

  @Test
  void xvfbIsFoundOnThePathOnlyAsAnExecutableFile() throws IOException {
    assertEquals(Optional.empty(), XvfbDisplay.find(null));
    assertEquals(Optional.empty(), XvfbDisplay.find(""));
    final Path empty = Files.createDirectory(this.folder.resolve("empty"));
    final Path found = Files.createDirectory(this.folder.resolve("bin"));
    final Path program = Files.createFile(found.resolve(XvfbDisplay.PROGRAM));
    final String path = empty + File.pathSeparator + File.pathSeparator + found;
    final boolean posix = this.folder.getFileSystem().supportedFileAttributeViews().contains("posix");
    if (posix) {
      assertEquals(Optional.empty(), XvfbDisplay.find(path), "not executable");
      Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
    }
    assertEquals(Optional.of(program), XvfbDisplay.find(path));
    // a folder of that name is not a program
    final Path folderNamedLikeIt = Files.createDirectories(this.folder.resolve("other").resolve(XvfbDisplay.PROGRAM));
    assertEquals(Optional.empty(), XvfbDisplay.find(folderNamedLikeIt.getParent().toString()));
  }

  @Test
  void theCommandListensOnNoTcpPortAndUsesTheAuthorityFile() {
    final List<String> command = XvfbDisplay.createCommand(Path.of("/usr/bin/Xvfb"), Path.of("/tmp/s/Xauthority"));
    assertEquals(
      List.of(
        "/usr/bin/Xvfb",
        "-displayfd",
        "1",
        "-auth",
        "/tmp/s/Xauthority",
        "-nolisten",
        "tcp",
        "-screen",
        "0",
        "16x16x24",
        "-terminate"
      ),
      command
    );
  }

  @Test
  void theAuthorityEntryMatchesEveryDisplayWithOneCookie() {
    final byte[] cookie = new byte[16];
    cookie[15] = 1;
    final byte[] entry = XvfbDisplay.createAuthorityEntry(cookie);
    final byte[] name = "MIT-MAGIC-COOKIE-1".getBytes(StandardCharsets.US_ASCII);
    assertEquals(2 + 2 + 2 + 2 + name.length + 2 + 16, entry.length);
    assertEquals((byte) 0xFF, entry[0]);
    assertEquals((byte) 0xFF, entry[1]);
    assertEquals(0, entry[2] | entry[3] | entry[4] | entry[5]);
    assertEquals(name.length, entry[7]);
    assertEquals(16, entry[8 + name.length + 1]);
    assertEquals(1, entry[entry.length - 1]);
  }

  @Test
  void theAuthorityFileIsReadableByTheOwnerOnly() throws IOException {
    final Path authority = this.folder.resolve("Xauthority");
    XvfbDisplay.writeAuthority(authority, new byte[16]);
    assertArrayEquals(XvfbDisplay.createAuthorityEntry(new byte[16]), Files.readAllBytes(authority));
    final boolean posix = this.folder.getFileSystem().supportedFileAttributeViews().contains("posix");
    if (posix) {
      assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(authority));
    }
    assertThrows(IOException.class, () -> XvfbDisplay.writeAuthority(authority, new byte[16]), "an existing file is never reused");
  }

  private static InputStream text(final String value) {
    return new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII));
  }

  @Test
  void theDisplayNumberIsTheFirstLine() {
    assertEquals("7", XvfbDisplay.readDisplayNumber(text("7\n"), 5_000L));
    assertEquals("12", XvfbDisplay.readDisplayNumber(text(" 12 \nmore"), 5_000L));
    final PlayerException garbage = assertThrows(PlayerException.class, () -> XvfbDisplay.readDisplayNumber(text("oops\n"), 5_000L));
    assertEquals("Xvfb reported no display number: oops", garbage.getMessage());
    assertThrows(PlayerException.class, () -> XvfbDisplay.readDisplayNumber(text(""), 5_000L));
    assertThrows(PlayerException.class, () -> XvfbDisplay.readDisplayNumber(text("123456\n"), 5_000L));
  }

  @Test
  void aLongOrUnreadableFirstLineIsNoDisplayNumber() {
    final PlayerException longLine = assertThrows(PlayerException.class, () ->
      XvfbDisplay.readDisplayNumber(text("12345678901234567890\n"), 5_000L)
    );
    assertEquals("Xvfb reported no display number: 1234567890123456", longLine.getMessage());
    final InputStream broken = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("closed");
      }

      @Override
      public int read(final byte[] buffer, final int offset, final int length) throws IOException {
        throw new IOException("closed");
      }
    };
    final PlayerException unreadable = assertThrows(PlayerException.class, () -> XvfbDisplay.readDisplayNumber(broken, 5_000L));
    assertEquals("Xvfb did not report a display within 5000 ms", unreadable.getMessage());
  }

  @Test
  void aStubbornXvfbIsKilledAndAnInterruptKillsItAtOnce() {
    final StubbornProcess stubborn = new StubbornProcess();
    XvfbDisplay.stopProcess(stubborn, 10L);
    assertEquals(List.of("destroy", "wait", "destroyForcibly", "wait"), stubborn.calls);
    final StubbornProcess interrupted = new StubbornProcess();
    Thread.currentThread().interrupt();
    try {
      XvfbDisplay.stopProcess(interrupted, 10_000L);
      assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is kept");
    } finally {
      Thread.interrupted();
    }
    assertEquals(List.of("destroy", "destroyForcibly"), interrupted.calls);
  }

  /**
   * A process that never exits by itself and records what is done to it.
   */
  private static final class StubbornProcess extends Process {

    final List<String> calls = new java.util.ArrayList<>();

    @Override
    public java.io.OutputStream getOutputStream() {
      return java.io.OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() throws InterruptedException {
      throw new UnsupportedOperationException("waits with a timeout only");
    }

    @Override
    public boolean waitFor(final long timeout, final java.util.concurrent.TimeUnit unit) throws InterruptedException {
      if (Thread.interrupted()) {
        throw new InterruptedException("interrupted");
      }
      this.calls.add("wait");
      return false;
    }

    @Override
    public int exitValue() {
      throw new IllegalThreadStateException("still running");
    }

    @Override
    public void destroy() {
      this.calls.add("destroy");
    }

    @Override
    public Process destroyForcibly() {
      this.calls.add("destroyForcibly");
      return this;
    }
  }

  @Test
  void aSilentXvfbTimesOut() throws IOException {
    // nothing is ever written to the pipe, so reading it blocks like an Xvfb that never reports its display
    final java.io.PipedOutputStream writer = new java.io.PipedOutputStream();
    final InputStream silent = new java.io.PipedInputStream(writer);
    final PlayerException failure = assertThrows(PlayerException.class, () -> XvfbDisplay.readDisplayNumber(silent, 50L));
    assertEquals("Xvfb did not report a display within 50 ms", failure.getMessage());
  }

  @Test
  void anInterruptedWaitIsReported() throws IOException {
    // a pipe nobody writes to, so only the interrupt can end the wait; a finished read would be returned first
    final java.io.PipedOutputStream writer = new java.io.PipedOutputStream();
    final InputStream silent = new java.io.PipedInputStream(writer);
    final Thread thread = Thread.currentThread();
    thread.interrupt();
    try {
      final PlayerException failure = assertThrows(PlayerException.class, () -> XvfbDisplay.readDisplayNumber(silent, 5_000L));
      assertEquals("Interrupted while waiting for Xvfb", failure.getMessage());
      assertTrue(Thread.interrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void aRealXvfbStartsWithAPrivateDisplayAndStops() throws Exception {
    final Optional<Path> program = XvfbDisplay.find(System.getenv("PATH"));
    assumeTrue(program.isPresent(), "Xvfb is installed");
    final XvfbDisplay display = XvfbDisplay.start(program.get(), this.folder);
    try {
      assertTrue(display.getDisplay().matches(":\\d+"), display.getDisplay());
      assertEquals(this.folder.resolve("Xauthority"), display.getAuthority());
    } finally {
      display.close();
    }
    display.close();
  }

  @Test
  void aProgramThatIsNotXvfbFailsTheStart() throws IOException {
    final Path authorityTaken = this.folder.resolve("taken");
    Files.createDirectories(authorityTaken);
    Files.createFile(authorityTaken.resolve("Xauthority"));
    final PlayerException taken = assertThrows(PlayerException.class, () -> XvfbDisplay.start(Path.of("Xvfb"), authorityTaken));
    assertTrue(taken.getMessage().startsWith("The X authority file cannot be written"));
    final Path missing = this.folder.resolve("missing-program");
    final PlayerException notStarted = assertThrows(PlayerException.class, () -> XvfbDisplay.start(missing, this.folder));
    assertTrue(notStarted.getMessage().startsWith("Xvfb cannot be started"));
  }

  @Test
  void aProgramThatExitsWithoutADisplayFailsTheStart() throws IOException {
    final boolean posix = this.folder.getFileSystem().supportedFileAttributeViews().contains("posix");
    assumeTrue(posix, "a shell script can stand in for Xvfb");
    final Path fake = this.folder.resolve("fake-xvfb");
    Files.writeString(fake, "#!/bin/sh\necho nothing\n");
    Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwx------"));
    final Path session = Files.createDirectory(this.folder.resolve("session"));
    final PlayerException failure = assertThrows(PlayerException.class, () -> XvfbDisplay.start(fake, session));
    assertEquals("Xvfb reported no display number: nothing", failure.getMessage());
    assertFalse(failure.getMessage().isEmpty());
  }
}
