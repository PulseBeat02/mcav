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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import me.brandonli.mcav.browser.testing.Await;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.OSUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Starts real helper processes with a scripted engine instead of CEF, or with a helper that breaks the protocol, and
 * checks what the server's session makes of them.
 */
class HelperSessionTest {

  private static final Path NATIVES = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();

  @TempDir
  private Path directory;

  private final RecordingListener listener = new RecordingListener();
  private final List<HelperSession> sessions = new ArrayList<>();

  static HelperLauncher launcher(final String mainClass, final long startTimeoutMillis) {
    return launcher(mainClass, startTimeoutMillis, OSUtils.getOS());
  }

  static HelperLauncher launcher(final String mainClass, final long startTimeoutMillis, final OS os) {
    final String javaHome = System.getProperty("java.home");
    final Path java = Path.of(javaHome, "bin", File.separatorChar == '\\' ? "java.exe" : "java");
    final List<Path> classPath = new ArrayList<>();
    for (final String entry : System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator), -1)) {
      classPath.add(Path.of(entry));
    }
    return new HelperLauncher(java, mainClass, classPath, List.of(), os, System.getenv(), startTimeoutMillis);
  }

  private HelperSession open(final String mainClass, final String path) {
    final BrowserSource source = BrowserSource.uri(URI.create("https://example.com" + path), 4, 3, 1);
    final HelperSession session = HelperSession.open(launcher(mainClass, 60_000L), NATIVES, source, BrowserOptions.DEFAULT, this.listener);
    this.sessions.add(session);
    return session;
  }

  private PlayerException openFails(final String mainClass, final String path, final long timeoutMillis) {
    final BrowserSource source = BrowserSource.uri(URI.create("https://example.com" + path), 4, 3, 1);
    final int before = HelperProcesses.count();
    final PlayerException failure = assertThrows(PlayerException.class, () ->
      HelperSession.open(launcher(mainClass, timeoutMillis), NATIVES, source, BrowserOptions.DEFAULT, this.listener)
    );
    assertEquals(before, HelperProcesses.count(), "a failed start leaves no session behind");
    return failure;
  }

  @AfterEach
  void closeSessions() {
    for (final HelperSession session : this.sessions) {
      session.close();
    }
  }

  private static int blue(final ImageBuffer frame) {
    final ByteBuffer pixels = frame.getData();
    return pixels.get(0) & 0xFF;
  }

  @Test
  void aSessionStreamsThePageAndCarriesInput() throws Exception {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), "/page");
    assertTrue(session.isAlive());
    assertEquals(1, HelperProcesses.count());
    Await.until("the first frame", () -> !this.listener.blues().isEmpty());
    final ImageBuffer first = this.listener.frames.getFirst();
    assertEquals(4, first.getWidth());
    assertEquals(3, first.getHeight());
    assertEquals(ScriptedEngine.GREEN, first.getData().get(1) & 0xFF);
    assertEquals(ScriptedEngine.RED, first.getData().get(2) & 0xFF);
    assertTrue(session.sendKey(HelperProtocol.KEY_TYPE, "ab"));
    Await.until("the typed text", () -> this.listener.blues().contains(4));
    assertTrue(session.sendMouse(new MouseInput(HelperProtocol.MOUSE_MOVE, 3, 2, 0, 0, 0, 0)));
    Await.until("the mouse move", () -> this.listener.blues().contains(5));
    final int delivered = this.listener.frames.size();
    session.requestFrame();
    Await.until("the frame sent again", () -> this.listener.frames.size() > delivered);
    final Process process = session.getProcess();
    session.close();
    assertFalse(process.isAlive());
    assertEquals(0, HelperProcesses.count());
    session.close();
    assertTrue(session.sendKey(HelperProtocol.KEY_TYPE, "ignored"), "input after close is dropped quietly");
    assertEquals(List.of(), this.listener.ended);
  }

  @Test
  void aSettledPageIsHandedOverAgainABoundedNumberOfTimes() throws Exception {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), "/page");
    final int expected = 1 + HelperSession.SETTLED_REPEATS;
    Await.until("the repeats of the settled page", () -> this.listener.frames.size() >= expected);
    Thread.sleep(HelperSession.REPEAT_DELAY_MILLIS * 4);
    assertEquals(expected, this.listener.frames.size(), "the repeats stop");
    session.sendKey(HelperProtocol.KEY_TYPE, "a");
    Await.until("the repeats of the changed page", () -> this.listener.frames.size() >= 2 * expected);
    session.close();
    assertEquals(List.of(), this.listener.ended);
  }

  @Test
  void closingRemovesTheFolderOfTheSession() throws Exception {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), "/page");
    final Path folder;
    try (final Stream<ProcessHandle> handles = Stream.of(session.getProcess().toHandle())) {
      final ProcessHandle handle = handles.findFirst().orElseThrow();
      folder = Path.of(
        handle
          .info()
          .arguments()
          .map(arguments -> {
            for (final String argument : arguments) {
              if (argument.startsWith("-Djava.io.tmpdir=")) {
                return argument.substring("-Djava.io.tmpdir=".length());
              }
            }
            return "missing";
          })
          .orElse("missing")
      );
    }
    session.close();
    if (!folder.toString().equals("missing")) {
      assertFalse(Files.exists(folder), folder.toString());
    }
  }

  @Test
  void aPageThatFailsToLoadFailsTheStart() {
    final PlayerException failure = this.openFails(ScriptedEngine.class.getName(), "/load-error", 60_000L);
    assertTrue(failure.getMessage().contains("ERR_NAME_NOT_RESOLVED (-105)"), failure.getMessage());
  }

  @Test
  void aHelperThatExitsDuringTheStartFailsTheStart() {
    final PlayerException failure = this.openFails(ScriptedEngine.class.getName(), "/exit", 60_000L);
    assertTrue(
      failure.getMessage().contains("exited with code 3") || failure.getMessage().contains("closed the connection"),
      failure.getMessage()
    );
  }

  @Test
  void aBrowserThatCannotStartFailsTheStart() {
    final PlayerException failure = this.openFails(ScriptedEngine.class.getName(), "/throw", 60_000L);
    assertTrue(failure.getMessage().contains("scripted start failure"), failure.getMessage());
  }

  @Test
  void aHelperThatNeverShowsThePageTimesOut() {
    final PlayerException failure = this.openFails(ScriptedEngine.class.getName(), "/never", 3_000L);
    assertTrue(failure.getMessage().contains("did not show https://example.com/never in time"), failure.getMessage());
  }

  @Test
  void aHelperThatNeverConnectsTimesOut() {
    final PlayerException failure = this.openFails(SilentMain.class.getName(), "/page", 2_000L);
    assertEquals("The browser helper did not connect in time", failure.getMessage());
  }

  @Test
  void aProgramThatIsNotJavaFailsTheStart() {
    final BrowserSource source = BrowserSource.uri(URI.create("https://example.com/page"), 4, 3, 1);
    final HelperLauncher broken = new HelperLauncher(
      NATIVES.resolve("no-such-java"),
      ScriptedEngine.class.getName(),
      List.of(),
      List.of(),
      OSUtils.getOS(),
      System.getenv(),
      5_000L
    );
    final PlayerException failure = assertThrows(PlayerException.class, () ->
      HelperSession.open(broken, NATIVES, source, BrowserOptions.DEFAULT, this.listener)
    );
    assertTrue(failure.getMessage().startsWith("The browser helper could not be started"), failure.getMessage());
  }

  @Test
  void aBrowserThatFailsLaterEndsTheSession() {
    this.open(ScriptedEngine.class.getName(), "/fail");
    Await.until("the end of the session", () -> !this.listener.ended.isEmpty());
    assertEquals(List.of("The browser failed: scripted renderer crash"), this.listener.ended);
  }

  @Test
  void aHelperWithAnotherTokenIsRefused() {
    final PlayerException failure = this.openFails(RawHelperMain.class.getName(), "/wrong-token", 60_000L);
    assertTrue(failure.getMessage().contains("presented a wrong session token"), failure.getMessage());
  }

  @Test
  void aHelperOfAnotherProtocolVersionIsRefused() {
    final PlayerException failure = this.openFails(RawHelperMain.class.getName(), "/wrong-version", 60_000L);
    assertTrue(failure.getMessage().contains("speaks protocol 2 instead of 1"), failure.getMessage());
  }

  @Test
  void aHelperThatDoesNotIntroduceItselfIsRefused() {
    final PlayerException failure = this.openFails(RawHelperMain.class.getName(), "/no-hello", 60_000L);
    assertTrue(failure.getMessage().contains("did not introduce itself"), failure.getMessage());
  }

  @Test
  void aFrameOfAnotherPageIsRefused() {
    final PlayerException failure = this.openFails(RawHelperMain.class.getName(), "/wrong-page", 60_000L);
    assertTrue(failure.getMessage().contains("A frame of a 5x3 page arrived for a 4x3 page"), failure.getMessage());
  }

  @Test
  void aMessageOnlyTheServerSendsEndsTheSession() {
    this.open(RawHelperMain.class.getName(), "/server-message");
    Await.until("the end of the session", () -> !this.listener.ended.isEmpty());
    assertTrue(
      this.listener.ended.getFirst().contains("sent message type 18, which only the server sends"),
      this.listener.ended.getFirst()
    );
  }

  @Test
  void inputForAHelperThatStopsReadingIsDroppedOnceTheQueueIsFull() {
    final HelperSession session = this.open(RawHelperMain.class.getName(), "/stall");
    final String text = "x".repeat(HelperProtocol.MAX_TEXT_BYTES);
    boolean dropped = false;
    for (int count = 0; count < 10_000 && !dropped; count++) {
      dropped = !session.sendKey(HelperProtocol.KEY_TYPE, text);
    }
    assertTrue(dropped, "the bounded queue refuses input once the helper stops reading");
  }

  @Test
  void aHelperThatIgnoresTheEndOfItsInputIsKilled() {
    final HelperSession session = this.open(RawHelperMain.class.getName(), "/stubborn");
    final Process process = session.getProcess();
    session.close();
    assertFalse(process.isAlive());
  }

  @Test
  void aHelperThatExitsBeforeItConnectsFailsTheStart() {
    final PlayerException failure = this.openFails(RawHelperMain.class.getName(), "/exit", 60_000L);
    assertEquals("The browser helper exited with code 4 before it connected", failure.getMessage());
  }

  @Test
  void aHelperThatSendsAFrameBeforeItsHelloIsRefused() {
    final PlayerException failure = this.openFails(RawHelperMain.class.getName(), "/frame-first", 60_000L);
    assertTrue(failure.getMessage().contains("arrived where frames are not expected"), failure.getMessage());
  }

  @Test
  void aHelperOnAPlatformWithoutXvfbRunsWithoutADisplay() {
    final HelperLauncher launcher = launcher(ScriptedEngine.class.getName(), 60_000L, OS.MAC);
    final BrowserSource source = BrowserSource.uri(URI.create("https://example.com/page"), 4, 3, 1);
    final HelperSession session = HelperSession.open(launcher, NATIVES, source, BrowserOptions.DEFAULT, this.listener);
    this.sessions.add(session);
    Await.until("the first frame", () -> !this.listener.frames.isEmpty());
    session.close();
    final HelperLauncher exiting = launcher(RawHelperMain.class.getName(), 60_000L, OS.MAC);
    final BrowserSource exit = BrowserSource.uri(URI.create("https://example.com/exit"), 4, 3, 1);
    assertThrows(PlayerException.class, () -> HelperSession.open(exiting, NATIVES, exit, BrowserOptions.DEFAULT, this.listener));
  }

  @Test
  void anInterruptedStartFailsAndLeavesNothingBehind() throws InterruptedException {
    final BrowserSource source = BrowserSource.uri(URI.create("https://example.com/never"), 4, 3, 1);
    final HelperLauncher launcher = launcher(ScriptedEngine.class.getName(), 60_000L);
    final java.util.concurrent.atomic.AtomicReference<Throwable> thrown = new java.util.concurrent.atomic.AtomicReference<>();
    final java.util.concurrent.atomic.AtomicBoolean stillInterrupted = new java.util.concurrent.atomic.AtomicBoolean();
    final Thread starter = new Thread(() -> {
      try {
        HelperSession.open(launcher, NATIVES, source, BrowserOptions.DEFAULT, this.listener);
      } catch (final PlayerException failure) {
        thrown.set(failure);
        stillInterrupted.set(Thread.currentThread().isInterrupted());
      }
    });
    starter.start();
    Await.until("the helper runs", () -> HelperProcesses.count() == 1);
    starter.interrupt();
    starter.join(30_000L);
    assertEquals("Interrupted while starting the browser", thrown.get().getMessage());
    assertTrue(stillInterrupted.get(), "the interrupt is kept");
    assertEquals(0, HelperProcesses.count());
  }

  @Test
  void inputForAHelperThatHungUpEndsTheSessionOnce() {
    final HelperSession session = this.open(RawHelperMain.class.getName(), "/hang-up");
    Await.until("the end of the connection", () -> !this.listener.ended.isEmpty());
    // the connection is gone, so the input cannot be written, which is another end nobody hears of
    assertTrue(session.sendKey(HelperProtocol.KEY_TYPE, "late"));
    Await.until("the input was tried", () -> session.getHandledInput() == 1);
    assertEquals(List.of("The browser helper closed the connection"), this.listener.ended);
  }

  @Test
  void aSecondEndIsNotReported() {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), "/fail");
    Await.until("the end of the session", () -> !this.listener.ended.isEmpty());
    Await.until("the helper exited", () -> !session.isAlive());
    // the connection ended after the failure, which is a second end nobody hears of
    assertEquals(List.of("The browser failed: scripted renderer crash"), this.listener.ended);
  }

  @Test
  void aListenerMayCloseTheSessionFromItsOwnThread() {
    final java.util.concurrent.atomic.AtomicReference<HelperSession> self = new java.util.concurrent.atomic.AtomicReference<>();
    final java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
    final BrowserSession.Listener closing = new BrowserSession.Listener() {
      @Override
      public void onFrame(final ImageBuffer frame) {
        frame.close();
      }

      @Override
      public void onEnded(final String reason, final Throwable cause) {
        // called on the reader thread, which cannot wait for itself
        self.get().close();
        closed.countDown();
      }
    };
    final BrowserSource source = BrowserSource.uri(URI.create("https://example.com/fail"), 4, 3, 1);
    final HelperSession session = HelperSession.open(
      launcher(ScriptedEngine.class.getName(), 60_000L),
      NATIVES,
      source,
      BrowserOptions.DEFAULT,
      closing
    );
    self.set(session);
    Await.until("the session closed itself", () -> closed.getCount() == 0);
    assertFalse(session.isAlive());
  }

  @Test
  void closingFromAnInterruptedThreadStillEndsEverythingAndKeepsTheInterrupt() {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), "/page");
    final Process process = session.getProcess();
    Thread.currentThread().interrupt();
    try {
      session.close();
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    Await.until("the helper ended", () -> !process.isAlive());
    assertEquals(0, HelperProcesses.count());
  }

  @Test
  void anInterruptedDeliveryThreadEndsAndAClosedCanvasDeliversNothing() throws InterruptedException {
    final HelperSession first = this.open(ScriptedEngine.class.getName(), "/page");
    final Thread delivery = first.getDeliveryThread();
    delivery.interrupt();
    delivery.join(10_000L);
    assertFalse(delivery.isAlive());
    first.close();
    final HelperSession second = this.open(ScriptedEngine.class.getName(), "/page");
    second.getCanvas().close();
    second.requestFrame();
    second.getDeliveryThread().join(10_000L);
    assertFalse(second.getDeliveryThread().isAlive(), "a delivery without a picture ends");
  }

  @Test
  void theOutputOfTheHelperIsKeptLineByLineBoundedAndUntilItFails() {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), "/page");
    final StringBuilder output = new StringBuilder();
    for (int line = 0; line < 50; line++) {
      output.append("line ").append(line).append("\r\n");
    }
    output.append("x".repeat(3000));
    session.drainOutput(new java.io.ByteArrayInputStream(output.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    final List<String> tail = List.of(session.getOutputTail().split(System.lineSeparator()));
    assertEquals(40, tail.size());
    assertEquals("line 11", tail.getFirst());
    assertEquals(1024, tail.getLast().length(), "a line is cut at its limit");
    session.drainOutput(
      new java.io.InputStream() {
        @Override
        public int read() throws java.io.IOException {
          throw new java.io.IOException("gone");
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws java.io.IOException {
          throw new java.io.IOException("gone");
        }
      }
    );
    assertEquals(40, session.getOutputTail().split(System.lineSeparator()).length);
  }

  @Test
  void theFolderOfASessionIsCreatedOnEveryFileSystem() throws IOException {
    final Path posix = HelperSession.createFolder(this.directory);
    assertTrue(Files.isDirectory(posix));
    final Path zip = this.directory.resolve("folders.zip");
    try (final java.nio.file.FileSystem zipped = java.nio.file.FileSystems.newFileSystem(zip, java.util.Map.of("create", "true"))) {
      final Path root = zipped.getPath("/");
      final Path created = HelperSession.createFolder(root);
      assertTrue(Files.isDirectory(created), "a file system without POSIX permissions gets a plain folder");
    }
    final PlayerException missing = assertThrows(PlayerException.class, () -> HelperSession.createFolder(this.directory.resolve("missing"))
    );
    assertTrue(missing.getMessage().startsWith("The folder of the browser session cannot be created"), missing.getMessage());
  }

  @Test
  void aSocketPathThatIsTakenCannotBeBound() throws IOException {
    final Path taken = Files.createFile(this.directory.resolve("s"));
    assertThrows(IOException.class, () -> HelperSession.bind(taken).close());
  }

  @Test
  void closingAndDeletingQuietlyLogFailuresOnly() throws IOException {
    final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    HelperSession.closeQuietly(() -> {
      calls.incrementAndGet();
      throw new IOException("already closed");
    });
    assertEquals(1, calls.get());
    final Path folder = Files.createDirectories(this.directory.resolve("kept").resolve("inner"));
    Files.writeString(folder.resolve("file"), "x");
    final java.io.File inner = folder.toFile();
    assumeTrue(inner.setWritable(false), "the folder can be made read-only");
    try {
      HelperSession.deleteFolder(this.directory.resolve("kept"));
      assertTrue(Files.exists(folder.resolve("file")), "a folder that cannot be removed stays");
    } finally {
      inner.setWritable(true);
    }
  }

  @Test
  void theFirstMessageMustBeAHelloWithTheTokenAndVersion() throws ProtocolException {
    final byte[] token = new byte[HelperProtocol.TOKEN_BYTES];
    HelperSession.checkHello(HelperMessage.hello(token, HelperProtocol.VERSION), token);
    assertThrows(ProtocolException.class, () -> HelperSession.checkHello(HelperMessage.close(), token));
    final byte[] other = token.clone();
    other[31] = 1;
    assertThrows(ProtocolException.class, () -> HelperSession.checkHello(HelperMessage.hello(other, HelperProtocol.VERSION), token));
    assertThrows(ProtocolException.class, () -> HelperSession.checkHello(HelperMessage.hello(token, 2), token));
  }

  /**
   * A main class that never connects, so the start times out.
   */
  public static final class SilentMain {

    private SilentMain() {}

    /**
     * Waits until the standard input ends.
     *
     * @param args ignored
     * @throws java.io.IOException if the input fails
     */
    public static void main(final String[] args) throws java.io.IOException {
      while (System.in.read() >= 0) {
        // never connect
      }
    }
  }

  /**
   * Records the frames and the end of a session.
   */
  static final class RecordingListener implements BrowserSession.Listener {

    final List<ImageBuffer> frames = new java.util.concurrent.CopyOnWriteArrayList<>();
    final List<String> ended = new java.util.concurrent.CopyOnWriteArrayList<>();

    List<Integer> blues() {
      final List<Integer> blues = new ArrayList<>();
      for (final ImageBuffer frame : this.frames) {
        blues.add(blue(frame));
      }
      return blues;
    }

    @Override
    public void onFrame(final ImageBuffer frame) {
      this.frames.add(frame);
    }

    @Override
    public void onEnded(final String reason, final Throwable cause) {
      this.ended.add(reason);
    }
  }
}
