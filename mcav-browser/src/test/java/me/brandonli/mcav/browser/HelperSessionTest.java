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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import me.brandonli.mcav.browser.testing.Await;
import me.brandonli.mcav.browser.testing.OpenFiles;
import me.brandonli.mcav.browser.testing.StandardError;
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
  private final List<Path> shortDirectories = new ArrayList<>();
  private final List<HelperSession> sessions = new ArrayList<>();

  static HelperLauncher launcher(final String mainClass, final long startTimeoutMillis) {
    return launcher(mainClass, startTimeoutMillis, OSUtils.getOS());
  }

  static HelperLauncher launcher(final String mainClass, final long startTimeoutMillis, final OS os) {
    return launcher(mainClass, startTimeoutMillis, os, List.of());
  }

  static HelperLauncher launcher(final String mainClass, final long startTimeoutMillis, final OS os, final List<String> jvmOptions) {
    final String javaHome = System.getProperty("java.home");
    final Path java = Path.of(javaHome, "bin", File.separatorChar == '\\' ? "java.exe" : "java");
    final List<Path> classPath = new ArrayList<>();
    for (final String entry : System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator), -1)) {
      classPath.add(Path.of(entry));
    }
    return new HelperLauncher(java, mainClass, classPath, jvmOptions, os, System.getenv(), startTimeoutMillis);
  }

  private HelperSession open(final String mainClass, final String path) {
    return this.open(mainClass, path, BrowserOptions.DEFAULT);
  }

  private HelperSession open(final String mainClass, final String path, final BrowserOptions options) {
    final BrowserSource source = BrowserSource.uri(URI.create("https://example.com" + path), 4, 3, 1);
    final HelperSession session = HelperSession.open(launcher(mainClass, 60_000L), NATIVES, source, options, this.listener);
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
  void closeSessions() throws IOException {
    for (final HelperSession session : this.sessions) {
      session.close();
    }
    for (final Path parent : this.shortDirectories) {
      Files.deleteIfExists(parent);
    }
  }

  /**
   * Creates an empty folder with a short path: macOS allows Unix domain sockets of at most 104 bytes, which a session
   * folder exceeds one level below its temporary folder ({@code /var/folders/…/T/}, 49 characters), so {@code /tmp} is
   * used where it exists.
   *
   * @return the folder, deleted after the test
   * @throws IOException if it cannot be created
   */
  private Path shortDirectory() throws IOException {
    final Path tmp = Path.of("/tmp");
    final boolean posix = Files.isDirectory(tmp) && Files.isWritable(tmp);
    final Path parent = posix ? Files.createTempDirectory(tmp, "m") : Files.createTempDirectory("m");
    this.shortDirectories.add(parent);
    return parent;
  }

  private static int blue(final ImageBuffer frame) {
    final ByteBuffer pixels = frame.getData();
    return pixels.get(0) & 0xFF;
  }

  @Test
  void aHelperThatReportsInALoopReachesTheLogOnlyWithinItsBudget() {
    final String log;
    try (final StandardError errors = new StandardError()) {
      this.open(RawHelperMain.class.getName(), "/noisy");
      Await.until("the sound after the reports", () -> !this.listener.sound.isEmpty());
      log = errors.text();
    }
    final long reports = log
      .lines()
      .filter(line -> line.contains("Browser: noise ") || line.contains("could not load https://example.com/noise/"))
      .count();
    // the reports arrive within a moment, and the budget passes one more line per second after its burst
    final int sent = RawHelperMain.NOISY_NOTICES + RawHelperMain.NOISY_LOAD_ERRORS;
    assertTrue(reports >= LogBudget.BURST && reports <= LogBudget.BURST + 5, "logged " + reports + " of " + sent + " reports");
    // the helper is not trusted to hide the secrets of an address; the server hides them again
    assertFalse(log.contains("token=secret"), log);
    // a moment later the budget passes a line again, after the number of those it did not
    assertTrue(log.contains("more notices of the page were not logged"), log);
    assertTrue(log.contains("Browser: noise after a pause"), log);
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
  void theSoundOfThePageCrossesTheProtocolInOrderAndOnlyThroughItsBinding() throws Exception {
    // a page that may play right away; otherwise its sound would wait for a click
    final BrowserOptions autoplay = BrowserOptions.builder().autoplay(true).build();
    final HelperSession session = this.open(ScriptedEngine.class.getName(), "/sound", autoplay);
    Await.until("the sound", () -> this.listener.sound.size() == ScriptedEngine.SOUND_CHUNKS);
    for (int chunk = 1; chunk <= ScriptedEngine.SOUND_CHUNKS; chunk++) {
      final byte[] samples = this.listener.sound.get(chunk - 1);
      assertEquals(ScriptedEngine.SOUND_FRAMES * HelperProtocol.AUDIO_FRAME_BYTES, samples.length);
      for (int index = 0; index < samples.length; index += 2) {
        assertEquals(chunk, samples[index], "the low byte of every sample of chunk " + chunk);
        assertEquals(0, samples[index + 1]);
      }
    }
    // the calls of another binding were dropped in the helper; nothing else arrives
    Thread.sleep(HelperSession.REPEAT_DELAY_MILLIS * 2);
    assertEquals(ScriptedEngine.SOUND_CHUNKS, this.listener.sound.size());
    session.close();
    assertEquals(List.of(), this.listener.ended);
  }

  @Test
  void theSoundOfAPageReachesTheServerOnlyOnceAPlayerPressedAButtonOrAKey() throws Exception {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), "/sound-on-input");
    Await.until("the first frame", () -> !this.listener.frames.isEmpty());
    assertTrue(session.sendMouse(new MouseInput(HelperProtocol.MOUSE_MOVE, 1, 1, 0, 0, 0, 0)));
    Await.until("the mouse move", () -> session.getHandledInput() >= 1);
    assertTrue(session.sendMouse(new MouseInput(HelperProtocol.MOUSE_PRESS, 1, 1, 0, 1, 0, 0)));
    Await.until("the sound after the press", () -> !this.listener.sound.isEmpty());
    Thread.sleep(HelperSession.REPEAT_DELAY_MILLIS * 2);
    // the chunk of the start and the one of the move were held back; the one of the press, the second call, passed
    assertEquals(1, this.listener.sound.size());
    assertEquals(2, this.listener.sound.getFirst()[0]);
  }

  @Test
  void aSettledPageIsHandedOverAgainABoundedNumberOfTimes() throws Exception {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), "/page");
    final int expected = 1 + HelperSession.SETTLED_REPEATS;
    Await.until("the repeats of the settled page", () -> this.listener.frames.size() >= expected);
    Thread.sleep(HelperSession.REPEAT_DELAY_MILLIS * 4);
    assertEquals(expected, this.listener.frames.size(), "the repeats stop");
    // every repeat waits the repeat delay, so the last one comes that many delays after the picture itself
    final long spanNanos = this.listener.frameNanos.get(expected - 1) - this.listener.frameNanos.get(0);
    final long leastNanos = TimeUnit.MILLISECONDS.toNanos(HelperSession.SETTLED_REPEATS * HelperSession.REPEAT_DELAY_MILLIS);
    assertTrue(spanNanos >= leastNanos, "the repeats came within " + TimeUnit.NANOSECONDS.toMillis(spanNanos) + " ms");
    session.sendKey(HelperProtocol.KEY_TYPE, "a");
    Await.until("the repeats of the changed page", () -> this.listener.frames.size() >= 2 * expected);
    session.close();
    assertEquals(List.of(), this.listener.ended);
  }

  @Test
  void closingEndsTheThreadsTheConnectionAndThePictureAndRemovesTheFolder() {
    // the folder is not read from the helper's command line: Linux shows no arguments of a command line longer than a
    // page, which the class path of a test run is
    final HelperSession session = this.open(ScriptedEngine.class.getName(), "/page");
    Await.until("the first frame", () -> !this.listener.frames.isEmpty());
    final List<Thread> threads = session.getThreads();
    for (final Thread thread : threads) {
      assertTrue(thread.isDaemon(), thread.getName() + " would keep the JVM alive");
    }
    final Path folder = session.getFolder();
    assertTrue(Files.isDirectory(folder), folder.toString());
    assertTrue(session.isConnected());
    session.close();
    for (final Thread thread : threads) {
      assertFalse(thread.isAlive(), thread.getName() + " still runs after the close");
    }
    assertFalse(session.isConnected());
    assertNull(session.getCanvas().snapshot(), "the picture of the page is released");
    assertFalse(Files.exists(folder), folder.toString());
    assertFalse(session.isAlive());
  }

  @Test
  void theThreadThatWritesTheInputDoesNotKeepTheJvmAlive() {
    final Thread thread = HelperSession.createInputThread(() -> {});
    assertTrue(thread.isDaemon());
    assertEquals("mcav-browser-input", thread.getName());
  }

  @Test
  void everySessionHasARandomTokenOfItsOwn() {
    final byte[] first = HelperSession.createToken();
    final byte[] second = HelperSession.createToken();
    assertEquals(HelperProtocol.TOKEN_BYTES, first.length);
    assertFalse(java.util.Arrays.equals(first, second), "two sessions got the same token");
    assertFalse(java.util.Arrays.equals(new byte[HelperProtocol.TOKEN_BYTES], first), "the token is all zeros");
  }

  @Test
  void theHelperGetsNoEnvironmentButWhatTheLauncherKeeps() {
    final HelperLauncher launcher = new HelperLauncher(
      NATIVES.resolve("java"),
      ScriptedEngine.class.getName(),
      List.of(),
      List.of(),
      OS.LINUX,
      java.util.Map.of("PATH", "/usr/bin", "MCAV_TEST_SECRET", "hidden"),
      1_000L
    );
    final ProcessBuilder builder = HelperSession.createProcessBuilder(launcher, this.directory, null);
    // the test JVM has variables of its own, which the helper must not inherit
    assertEquals(launcher.createEnvironment(this.directory, null), builder.environment());
    assertEquals(this.directory.toFile(), builder.directory());
    assertTrue(builder.redirectErrorStream());
  }

  @Test
  void theOutputOfARunningHelperIsReadWhateverItsSize() {
    final HelperSession session = this.open(RawHelperMain.class.getName(), "/chatty");
    // far more than a pipe holds: a helper whose output nobody reads blocks before its last line
    Await.until("the last line of the helper", () -> session.getOutputTail().contains(RawHelperMain.CHATTY_END));
    assertTrue(session.isAlive());
  }

  @Test
  void aRegionAsLargeAsThePageIsAccepted() {
    this.open(RawHelperMain.class.getName(), "/full-page");
    Await.until("the first frame", () -> !this.listener.frames.isEmpty());
    assertEquals(7, blue(this.listener.frames.get(0)));
    assertEquals(List.of(), this.listener.ended);
  }

  @Test
  void aHelperThatExitsWhileItRunsEndsTheSessionWithItsExitCode() {
    this.open(RawHelperMain.class.getName(), "/exit-later");
    Await.until("the end of the session", () -> !this.listener.ended.isEmpty());
    assertEquals(List.of("The browser helper exited with code 5"), this.listener.ended);
  }

  @Test
  void inputForAClosedSessionIsIgnoredWithoutBeingCalledDropped() {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), "/page");
    session.close();
    assertTrue(session.sendMouse(new MouseInput(HelperProtocol.MOUSE_MOVE, 1, 1, HelperProtocol.BUTTON_LEFT, 0, 0, 0)));
    assertTrue(session.sendKey(HelperProtocol.KEY_TYPE, "late"));
    assertEquals(List.of(), this.listener.ended);
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void inputThatCannotBeWrittenEndsTheSession() {
    // the helper keeps its connection but reads no more, so Linux refuses to write to it (EPIPE); input goes on until
    // then, since the helper shuts its side a moment after it showed the page
    final HelperSession session = this.open(RawHelperMain.class.getName(), "/deaf");
    Await.until("the end of the session", () -> {
      session.sendKey(HelperProtocol.KEY_TYPE, "lost");
      return !this.listener.ended.isEmpty();
    });
    final String reason = this.listener.ended.get(0);
    assertTrue(reason.startsWith("Input could not be sent to the browser helper: "), reason);
  }

  @Test
  void aProcessTheHelperStartedIsKilledWhenTheSessionCloses() {
    final HelperSession session = this.open(RawHelperMain.class.getName(), "/child");
    final java.util.Set<ProcessHandle> started = java.util.concurrent.ConcurrentHashMap.newKeySet();
    Await.until("the process the helper starts", () -> {
      try (final Stream<ProcessHandle> descendants = session.getProcess().descendants()) {
        descendants.forEach(started::add);
      }
      return !started.isEmpty();
    });
    // the helper itself exits when its input ends; its process outlives it unless the session kills it
    session.close();
    Await.until("no process the helper started is left", () -> started.stream().noneMatch(ProcessHandle::isAlive));
  }

  @Test
  void aStartThatFailsBeforeTheHelperConnectsLeavesNothingBehind() throws IOException {
    final java.util.Set<ProcessHandle> before = liveDescendants();
    final BrowserSource source = BrowserSource.uri(URI.create("https://example.com/silent-stubborn"), 4, 3, 1);
    final HelperLauncher launcher = launcher(RawHelperMain.class.getName(), 1_000L);
    final Path parent = this.shortDirectory();
    final PlayerException failure = assertThrows(PlayerException.class, () ->
      HelperSession.open(launcher, NATIVES, source, BrowserOptions.DEFAULT, this.listener, parent)
    );
    assertEquals("The browser helper did not connect in time", failure.getMessage());
    // the helper ignores the end of its input, so only a kill ends it; its display ends with it
    assertEquals(java.util.Set.of(), newSince(before));
    try (final Stream<Path> left = Files.list(parent)) {
      assertEquals(List.of(), left.toList());
    }
  }

  private static java.util.Set<ProcessHandle> liveDescendants() {
    try (final Stream<ProcessHandle> descendants = ProcessHandle.current().descendants()) {
      return descendants.filter(ProcessHandle::isAlive).collect(java.util.stream.Collectors.toSet());
    }
  }

  private static java.util.Set<ProcessHandle> newSince(final java.util.Set<ProcessHandle> before) {
    final java.util.Set<ProcessHandle> now = new java.util.HashSet<>(liveDescendants());
    now.removeAll(before);
    return now;
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
  void aProgramThatIsNotJavaFailsTheStart() throws IOException {
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
    final java.util.Set<ProcessHandle> before = liveDescendants();
    final PlayerException failure = assertThrows(PlayerException.class, () ->
      HelperSession.open(broken, NATIVES, source, BrowserOptions.DEFAULT, this.listener, this.directory)
    );
    assertTrue(failure.getMessage().startsWith("The browser helper could not be started"), failure.getMessage());
    // the display that was started for the helper ends, and the folder of the session goes
    assertEquals(java.util.Set.of(), newSince(before));
    try (final Stream<Path> left = Files.list(this.directory)) {
      assertEquals(List.of(), left.toList());
    }
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
    assertFalse(session.sendMouse(new MouseInput(HelperProtocol.MOUSE_MOVE, 1, 1, HelperProtocol.BUTTON_LEFT, 0, 0, 0)));
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
    assertTrue(failure.getMessage().contains("arrived where no frame of that size is expected"), failure.getMessage());
  }

  @Test
  void aHelperGetsTheLibrariesItsLauncherLinksIntoTheSession() {
    final List<Path> linked = new java.util.concurrent.CopyOnWriteArrayList<>();
    final HelperLauncher launcher = launcher(ScriptedEngine.class.getName(), 60_000L).withLibraries(session -> {
      final Path libraries = Files.createDirectory(session.resolve("lib"));
      linked.add(libraries);
      return libraries;
    });
    final BrowserSource source = BrowserSource.uri(URI.create("https://example.com/page"), 4, 3, 1);
    final HelperSession session = HelperSession.open(launcher, NATIVES, source, BrowserOptions.DEFAULT, this.listener);
    this.sessions.add(session);
    Await.until("the first frame", () -> !this.listener.frames.isEmpty());
    assertEquals(List.of(session.getFolder().resolve("lib")), linked);
    assertTrue(Files.isDirectory(linked.getFirst()));
    session.close();
    assertFalse(Files.exists(linked.getFirst()));
  }

  @Test
  void librariesThatCannotBeLinkedFailTheStartAndLeaveNothingBehind() throws IOException {
    final HelperLauncher launcher = launcher(ScriptedEngine.class.getName(), 60_000L).withLibraries(session -> {
      throw new IOException("no space left");
    });
    final BrowserSource source = BrowserSource.uri(URI.create("https://example.com/page"), 4, 3, 1);
    final Path parent = this.shortDirectory();
    final PlayerException failure = assertThrows(PlayerException.class, () ->
      HelperSession.open(launcher, NATIVES, source, BrowserOptions.DEFAULT, this.listener, parent)
    );
    assertEquals("The browser helper could not be started: no space left", failure.getMessage());
    try (final Stream<Path> left = Files.list(parent)) {
      assertEquals(List.of(), left.toList());
    }
  }

  @Test
  void aRegionLargerThanThePageIsRefusedBeforeItsPixelsAreRead() {
    final PlayerException failure = this.openFails(RawHelperMain.class.getName(), "/oversized", 60_000L);
    // a 5x3 region for a 4x3 page: 60 bytes where at most 48 fit
    assertTrue(failure.getMessage().contains("A frame of 60 bytes arrived where no frame of that size is expected"), failure.getMessage());
  }

  @Test
  void aTextLongerThanOneMessageArrivesWhole() {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), "/page");
    Await.until("the first frame", () -> !this.listener.blues().isEmpty());
    // 5000 characters are 10000 DevTools calls in two messages; the scripted page turns the count into its blue
    assertTrue(session.sendKey(HelperProtocol.KEY_TYPE, "a".repeat(5_000)));
    Await.until("every character typed", () -> this.listener.blues().contains(10_000 & 0xFF));
  }

  @Test
  void aHelperThatNeverConnectsIsStoppedAtOnceWhenItsStartTimesOut() {
    final long start = System.nanoTime();
    final PlayerException failure = this.openFails(RawHelperMain.class.getName(), "/silent", 1_000L);
    assertTrue(failure.getMessage().contains("The browser helper did not connect in time"), failure.getMessage());
    final long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
    // the end of its input stops it, instead of the ten seconds it would be given to stop by itself
    assertTrue(seconds < 8, "the failed start took " + seconds + " s");
  }

  @Test
  void aProcessTheHelperStartsWhileItDoesNotStopIsKilledWithIt() throws InterruptedException {
    final HelperSession session = this.open(RawHelperMain.class.getName(), "/stubborn-child");
    final Process process = session.getProcess();
    // the helper ignores the end of its input, starts a process two seconds later, and is killed after ten seconds;
    // its processes are watched while the session closes, because they are no descendants once the helper is gone
    final java.util.Set<ProcessHandle> started = java.util.concurrent.ConcurrentHashMap.newKeySet();
    final java.util.concurrent.atomic.AtomicBoolean closing = new java.util.concurrent.atomic.AtomicBoolean(true);
    final Thread watcher = new Thread(() -> {
      while (closing.get()) {
        try (final Stream<ProcessHandle> descendants = process.descendants()) {
          descendants.forEach(started::add);
        }
        java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
      }
    });
    watcher.start();
    try {
      session.close();
    } finally {
      closing.set(false);
      watcher.join();
    }
    assertFalse(process.isAlive());
    assertFalse(started.isEmpty(), "the helper started a process while it did not stop");
    Await.until("no process the helper started is left", () -> started.stream().noneMatch(ProcessHandle::isAlive));
  }

  @Test
  void noHelperStartsWhileTheModuleIsStopped() {
    HelperProcesses.closeAll();
    try {
      final PlayerException failure = this.openFails(ScriptedEngine.class.getName(), "/page", 60_000L);
      assertEquals("The browser module is stopped", failure.getMessage());
    } finally {
      HelperProcesses.open();
    }
    assertTrue(this.open(ScriptedEngine.class.getName(), "/page").isAlive(), "a started module lets helpers start again");
  }

  @Test
  void stoppingTheModuleEndsEveryHelperEvenWhenTheirListenersFail() {
    final BrowserSource source = BrowserSource.uri(URI.create("https://example.com/page"), 4, 3, 1);
    final BrowserSession.Listener failing = new BrowserSession.Listener() {
      @Override
      public void onFrame(final ImageBuffer frame) {
        frame.close();
      }

      @Override
      public void onAudio(final byte[] samples) {
        // the page of this test plays nothing
      }

      @Override
      public void onEnded(final String reason, final Throwable cause) {
        throw new IllegalStateException("the listener fails: " + reason);
      }
    };
    final HelperLauncher launcher = launcher(ScriptedEngine.class.getName(), 60_000L);
    final HelperSession first = HelperSession.open(launcher, NATIVES, source, BrowserOptions.DEFAULT, failing);
    final HelperSession second = HelperSession.open(launcher, NATIVES, source, BrowserOptions.DEFAULT, failing);
    this.sessions.add(first);
    this.sessions.add(second);
    final IllegalStateException failure;
    try {
      failure = assertThrows(IllegalStateException.class, HelperProcesses::closeAll);
    } finally {
      HelperProcesses.open();
    }
    assertEquals("the listener fails: The browser module was stopped", failure.getMessage());
    assertEquals(1, failure.getSuppressed().length, "the failure of the other listener goes along");
    assertFalse(first.isAlive(), "the first helper ended");
    assertFalse(second.isAlive(), "the second helper ended");
    assertEquals(0, HelperProcesses.count());
  }

  @Test
  void aHelperThatConnectsAfterTheModuleStoppedEndsAndItsStartFails() throws IOException {
    final Path gate = this.directory.resolve("gate");
    final HelperLauncher gated = launcher(
      GatedHelperMain.class.getName(),
      60_000L,
      OSUtils.getOS(),
      List.of("-D" + GatedHelperMain.GATE_PROPERTY + "=" + gate)
    );
    final BrowserSource source = BrowserSource.uri(URI.create("https://example.com/page"), 4, 3, 1);
    final CompletableFuture<HelperSession> opening = CompletableFuture.supplyAsync(() ->
      HelperSession.open(gated, NATIVES, source, BrowserOptions.DEFAULT, this.listener)
    );
    final Path started = gate.resolveSibling(gate.getFileName() + GatedHelperMain.STARTED_SUFFIX);
    try {
      // the server waits for the helper to connect, past every check before it
      Await.until("the helper runs", () -> Files.exists(started));
      HelperProcesses.closeAll();
      Files.createFile(gate);
      final ExecutionException failure = assertThrows(ExecutionException.class, () -> opening.get(60, TimeUnit.SECONDS));
      final PlayerException cause = assertInstanceOf(PlayerException.class, failure.getCause());
      assertEquals("The browser module was stopped while the browser started", cause.getMessage());
    } finally {
      HelperProcesses.open();
    }
    assertEquals(0, HelperProcesses.count());
    Await.until("the helper ended", () ->
      ProcessHandle.current()
        .descendants()
        .noneMatch(process -> process.info().commandLine().orElse("").contains(GatedHelperMain.class.getName()))
    );
  }

  @Test
  void aHelperThatConnectsAfterTheModuleStoppedAndStartedAgainEndsAndItsStartFails() throws IOException {
    final Path gate = this.directory.resolve("gate-restart");
    final HelperLauncher gated = launcher(
      GatedHelperMain.class.getName(),
      60_000L,
      OSUtils.getOS(),
      List.of("-D" + GatedHelperMain.GATE_PROPERTY + "=" + gate)
    );
    final BrowserSource source = BrowserSource.uri(URI.create("https://example.com/page"), 4, 3, 1);
    final CompletableFuture<HelperSession> opening = CompletableFuture.supplyAsync(() ->
      HelperSession.open(gated, NATIVES, source, BrowserOptions.DEFAULT, this.listener)
    );
    final Path started = gate.resolveSibling(gate.getFileName() + GatedHelperMain.STARTED_SUFFIX);
    Await.until("the helper runs", () -> Files.exists(started));
    // a plugin disable and enable while the browser starts: the start belongs to the plugin that was disabled
    HelperProcesses.closeAll();
    HelperProcesses.open();
    Files.createFile(gate);
    final ExecutionException failure = assertThrows(ExecutionException.class, () -> opening.get(60, TimeUnit.SECONDS));
    final PlayerException cause = assertInstanceOf(PlayerException.class, failure.getCause());
    assertEquals("The browser module was stopped while the browser started", cause.getMessage());
    assertEquals(0, HelperProcesses.count());
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
      public void onAudio(final byte[] samples) {}

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
    // the socket that could not be bound is closed
    OpenFiles.leaveNoneOpen("a failed bind", () -> assertThrows(IOException.class, () -> HelperSession.bind(taken).close()));
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
   * Records the frames, the sound and the end of a session.
   */
  static final class RecordingListener implements BrowserSession.Listener {

    final List<ImageBuffer> frames = new java.util.concurrent.CopyOnWriteArrayList<>();
    final List<byte[]> sound = new java.util.concurrent.CopyOnWriteArrayList<>();
    final List<Long> frameNanos = new java.util.concurrent.CopyOnWriteArrayList<>();
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
      this.frameNanos.add(System.nanoTime());
      this.frames.add(frame);
    }

    @Override
    public void onAudio(final byte[] samples) {
      this.sound.add(samples);
    }

    @Override
    public void onEnded(final String reason, final Throwable cause) {
      this.ended.add(reason);
    }
  }
}
