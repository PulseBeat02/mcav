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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import me.brandonli.mcav.browser.testing.Await;
import me.brandonli.mcav.browser.testing.OpenFiles;
import me.brandonli.mcav.browser.testing.StandardError;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.OsUtils;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
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
    return launcher(mainClass, startTimeoutMillis, OsUtils.getOs());
  }

  static HelperLauncher launcher(final String mainClass, final long startTimeoutMillis, final Os os) {
    return launcher(mainClass, startTimeoutMillis, os, List.of());
  }

  static HelperLauncher launcher(final String mainClass, final long startTimeoutMillis, final Os os, final List<String> jvmOptions) {
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
    final int helperCount = HelperProcesses.count();
    assertEquals(before, helperCount, "a failed start leaves no session behind");
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
    final Path temporaryDirectory = Path.of("/tmp");
    final boolean isUsable = Files.isDirectory(temporaryDirectory) && Files.isWritable(temporaryDirectory);
    final Path parent = isUsable ? Files.createTempDirectory(temporaryDirectory, "m") : Files.createTempDirectory("m");
    this.shortDirectories.add(parent);
    return parent;
  }

  private static int blue(final ImageBuffer frame) {
    final ByteBuffer pixels = frame.getData();
    return pixels.get(0) & 0xFF;
  }

  @Test
  void shouldStopWaitingForTheHelperAtOnceWhenTheStartIsInterrupted() throws IOException {
    final Process alive = mock(Process.class);
    when(alive.isAlive()).thenReturn(true);
    try (final ServerSocketChannel server = ServerSocketChannel.open()) {
      server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      server.configureBlocking(false);
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      final PlayerException interrupted = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
        Thread.currentThread().interrupt();
        try {
          return assertThrows(PlayerException.class, () -> HelperSession.accept(server, alive, deadline));
        } finally {
          Thread.interrupted();
        }
      });
      final String message = interrupted.getMessage();
      assertEquals("Interrupted while the browser helper connected!", message);
    }
  }

  @Test
  void shouldLogAHelperThatReportsInALoopOnlyWithinItsBudget() {
    final String log;
    try (final StandardError errors = new StandardError()) {
      this.open(RawHelperMain.class.getName(), "/noisy");
      Await.until("the sound after the reports", () -> !this.listener.sound.isEmpty());
      log = errors.text();
    }
    final long reports = log
      .lines()
      .filter(line -> line.contains("Browser: noise ") || line.contains("Page https://example.com/noise/"))
      .count();
    final int sent = RawHelperMain.NOISY_NOTICES + RawHelperMain.NOISY_LOAD_ERRORS;
    final boolean loggedWithinBudget = reports >= LogBudget.BURST && reports <= LogBudget.BURST + 5;
    assertTrue(loggedWithinBudget, "logged " + reports + " of " + sent + " reports");
    final boolean leaksTheToken = log.contains("token=secret");
    assertFalse(leaksTheToken, log);
    final boolean reportsTheSkippedNotices = log.contains("more notices of the page were not logged");
    assertTrue(reportsTheSkippedNotices, log);
    final boolean logsTheNoiseAfterAPause = log.contains("Browser: noise after a pause");
    assertTrue(logsTheNoiseAfterAPause, log);
  }

  @Test
  void shouldStreamThePageAndCarryInput() throws Exception {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), "/page");
    final boolean sessionAlive = session.isAlive();
    assertTrue(sessionAlive);
    final int helperCountWhileOpen = HelperProcesses.count();
    assertEquals(1, helperCountWhileOpen);
    Await.until("the first frame", () -> !this.listener.blues().isEmpty());
    final ImageBuffer first = this.listener.frames.getFirst();
    final int width = first.getWidth();
    assertEquals(4, width);
    final int height = first.getHeight();
    assertEquals(3, height);
    final int greenChannel = first.getData().get(1) & 0xFF;
    assertEquals(ScriptedEngine.GREEN, greenChannel);
    final int redChannel = first.getData().get(2) & 0xFF;
    assertEquals(ScriptedEngine.RED, redChannel);
    final boolean keyAccepted = session.sendKey(HelperProtocol.KEY_TYPE, "ab");
    assertTrue(keyAccepted);
    Await.until("the typed text", () -> this.listener.blues().contains(4));
    final boolean mouseAccepted = session.sendMouse(new MouseInput(HelperProtocol.MOUSE_MOVE, 3, 2, 0, 0, 0, 0));
    assertTrue(mouseAccepted);
    Await.until("the mouse move", () -> this.listener.blues().contains(5));
    final int delivered = this.listener.frames.size();
    session.requestFrame();
    Await.until("the frame sent again", () -> this.listener.frames.size() > delivered);
    final Process process = session.getProcess();
    session.close();
    final boolean processAlive = process.isAlive();
    assertFalse(processAlive);
    final int helperCountAfterClose = HelperProcesses.count();
    assertEquals(0, helperCountAfterClose);
    session.close();
    final boolean keyAfterCloseAccepted = session.sendKey(HelperProtocol.KEY_TYPE, "ignored");
    assertTrue(keyAfterCloseAccepted, "input after close is dropped quietly");
    assertEquals(List.of(), this.listener.ended);
  }

  @Test
  void shouldCarryTheSoundOfThePageAcrossTheProtocolInOrderAndOnlyThroughItsBinding() throws Exception {
    final BrowserOptions autoplay = BrowserOptions.builder().autoplay(true).build();
    final HelperSession session = this.open(ScriptedEngine.class.getName(), "/sound", autoplay);
    Await.until("the sound", () -> this.listener.sound.size() == ScriptedEngine.SOUND_CHUNKS);
    for (int chunk = 1; chunk <= ScriptedEngine.SOUND_CHUNKS; chunk++) {
      final byte[] samples = this.listener.sound.get(chunk - 1);
      assertEquals(ScriptedEngine.SOUND_FRAMES * HelperProtocol.AUDIO_FRAME_BYTES, samples.length);
      for (int index = 0; index < samples.length; index += 2) {
        final byte lowByte = samples[index];
        assertEquals(chunk, lowByte, "the low byte of every sample of chunk " + chunk);
        final byte highByte = samples[index + 1];
        assertEquals(0, highByte);
      }
    }
    Thread.sleep(HelperSession.REPEAT_DELAY_MILLIS * 2);
    final int chunkCount = this.listener.sound.size();
    assertEquals(ScriptedEngine.SOUND_CHUNKS, chunkCount);
    session.close();
    assertEquals(List.of(), this.listener.ended);
  }

  @Test
  void shouldPassTheSoundOfAPageToTheServerOnlyOnceAPlayerPressedAButtonOrAKey() throws Exception {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), "/sound-on-input");
    Await.until("the first frame", () -> !this.listener.frames.isEmpty());
    final boolean moveAccepted = session.sendMouse(new MouseInput(HelperProtocol.MOUSE_MOVE, 1, 1, 0, 0, 0, 0));
    assertTrue(moveAccepted);
    Await.until("the mouse move", () -> session.getHandledInput() >= 1);
    final boolean pressAccepted = session.sendMouse(new MouseInput(HelperProtocol.MOUSE_PRESS, 1, 1, 0, 1, 0, 0));
    assertTrue(pressAccepted);
    Await.until("the sound after the press", () -> !this.listener.sound.isEmpty());
    Thread.sleep(HelperSession.REPEAT_DELAY_MILLIS * 2);
    final int chunkCount = this.listener.sound.size();
    assertEquals(1, chunkCount);
    final byte firstSoundByte = this.listener.sound.getFirst()[0];
    assertEquals(2, firstSoundByte);
  }

  @Test
  void shouldHandOverASettledPageAgainABoundedNumberOfTimes() throws Exception {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), "/page");
    final int expected = 1 + HelperSession.SETTLED_REPEATS;
    Await.until("the repeats of the settled page", () -> this.listener.frames.size() >= expected);
    Thread.sleep(HelperSession.REPEAT_DELAY_MILLIS * 4);
    final int frameCount = this.listener.frames.size();
    assertEquals(expected, frameCount, "the repeats stop");
    final long spanNanos = this.listener.frameNanos.get(expected - 1) - this.listener.frameNanos.get(0);
    final long leastNanos = TimeUnit.MILLISECONDS.toNanos(HelperSession.SETTLED_REPEATS * HelperSession.REPEAT_DELAY_MILLIS);
    final boolean repeatsSpreadOut = spanNanos >= leastNanos;
    assertTrue(repeatsSpreadOut, "the repeats came within " + TimeUnit.NANOSECONDS.toMillis(spanNanos) + " ms");
    session.sendKey(HelperProtocol.KEY_TYPE, "a");
    Await.until("the repeats of the changed page", () -> this.listener.frames.size() >= 2 * expected);
    session.close();
    assertEquals(List.of(), this.listener.ended);
  }

  @Test
  void shouldWaitOnCloseForTheDeliveryOfAPictureThatIsStillRunning() throws Exception {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final BrowserSession.Listener slow = new BrowserSession.Listener() {
      @Override
      public void onFrame(final ImageBuffer frame) {
        frame.close();
        entered.countDown();
        try {
          release.await();
        } catch (final InterruptedException exception) {
          Thread.currentThread().interrupt();
        }
      }

      @Override
      public void onAudio(final byte[] samples) {}

      @Override
      public void onEnded(final String reason, final Throwable cause) {}
    };
    final BrowserSource source = BrowserSource.uri(URI.create("https://example.com/page"), 4, 3, 1);
    final HelperSession session = HelperSession.open(
      launcher(ScriptedEngine.class.getName(), 60_000L),
      NATIVES,
      source,
      BrowserOptions.DEFAULT,
      slow
    );
    this.sessions.add(session);
    final boolean deliveryEntered = entered.await(30, TimeUnit.SECONDS);
    assertTrue(deliveryEntered, "a picture is handed over");
    final Thread delivery = session.getThreads().get(1);
    final Thread closer = Thread.ofPlatform().name("closer").start(session::close);
    try {
      Await.until("the close waits for the delivery", () ->
        Arrays.stream(closer.getStackTrace()).anyMatch(
          frame -> frame.getClassName().equals(Thread.class.getName()) && frame.getMethodName().equals("join")
        )
      );
      final boolean deliveryStillRunning = delivery.isAlive();
      assertTrue(deliveryStillRunning, "the delivery still hands its picture over");
    } finally {
      release.countDown();
    }
    closer.join(TimeUnit.SECONDS.toMillis(30));
    final boolean closerAlive = closer.isAlive();
    assertFalse(closerAlive);
    final boolean deliveryAlive = delivery.isAlive();
    assertFalse(deliveryAlive, "the close returned only once the delivery ended");
  }

  @Test
  void shouldWaitBetweenTheRepeatsOfAPictureInsteadOfSpinning() {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), "/page");
    Await.until("the first frame", () -> !this.listener.frames.isEmpty());
    final Thread delivery = session.getThreads().get(1);
    Await.until("the delivery thread waits for the next repeat", () -> delivery.getState() == Thread.State.TIMED_WAITING);
  }

  @Test
  void shouldEndTheThreadsTheConnectionAndThePictureAndRemoveTheFolderOnClose() {
    // the folder is not read from the helper's command line: Linux shows no arguments of a command line longer than a
    // page, which the class path of a test run is
    final HelperSession session = this.open(ScriptedEngine.class.getName(), "/page");
    Await.until("the first frame", () -> !this.listener.frames.isEmpty());
    final List<Thread> threads = session.getThreads();
    final int threadCount = threads.size();
    assertEquals(3, threadCount, "the reader, delivery and output threads");
    for (final Thread thread : threads) {
      final boolean daemon = thread.isDaemon();
      assertTrue(daemon, thread.getName() + " would keep the JVM alive");
    }
    final Path folder = session.getFolder();
    final boolean folderCreated = Files.isDirectory(folder);
    assertTrue(folderCreated, folder.toString());
    final boolean connectedWhileOpen = session.isConnected();
    assertTrue(connectedWhileOpen);
    session.close();
    for (final Thread thread : threads) {
      final boolean threadAlive = thread.isAlive();
      assertFalse(threadAlive, thread.getName() + " still runs after the close");
    }
    final boolean connectedAfterClose = session.isConnected();
    assertFalse(connectedAfterClose);
    final @Nullable ImageBuffer pictureAfterClose = session.getCanvas().snapshot();
    assertNull(pictureAfterClose, "the picture of the page is released");
    final boolean folderLeft = Files.exists(folder);
    assertFalse(folderLeft, folder.toString());
    final boolean sessionAlive = session.isAlive();
    assertFalse(sessionAlive);
  }

  @Test
  void shouldNotKeepTheJvmAliveWithTheThreadThatWritesTheInput() {
    final Thread thread = HelperSession.createInputThread(() -> {});
    final boolean daemon = thread.isDaemon();
    assertTrue(daemon);
    final String threadName = thread.getName();
    assertEquals("mcav-browser-input", threadName);
  }

  @Test
  void shouldGiveEverySessionARandomTokenOfItsOwn() {
    final byte[] first = HelperSession.createToken();
    final byte[] second = HelperSession.createToken();
    assertEquals(HelperProtocol.TOKEN_BYTES, first.length);
    final boolean sameToken = Arrays.equals(first, second);
    assertFalse(sameToken, "two sessions got the same token");
    final boolean allZeroToken = Arrays.equals(new byte[HelperProtocol.TOKEN_BYTES], first);
    assertFalse(allZeroToken, "the token is all zeros");
  }

  @Test
  void shouldGiveTheHelperNoEnvironmentButWhatTheLauncherKeeps() {
    final HelperLauncher launcher = new HelperLauncher(
      NATIVES.resolve("java"),
      ScriptedEngine.class.getName(),
      List.of(),
      List.of(),
      Os.LINUX,
      Map.of("PATH", "/usr/bin", "MCAV_TEST_SECRET", "hidden"),
      1_000L
    );
    final ProcessBuilder builder = HelperSession.createProcessBuilder(launcher, this.directory, null);
    final Map<String, String> helperEnvironment = builder.environment();
    assertEquals(launcher.createEnvironment(this.directory, null), helperEnvironment);
    final File workingDirectory = builder.directory();
    assertEquals(this.directory.toFile(), workingDirectory);
    final boolean errorsMerged = builder.redirectErrorStream();
    assertTrue(errorsMerged);
  }

  @Test
  void shouldReadTheOutputOfARunningHelperWhateverItsSize() {
    final HelperSession session = this.open(RawHelperMain.class.getName(), "/chatty");
    Await.until("the last line of the helper", () -> session.getOutputTail().contains(RawHelperMain.CHATTY_END));
    final boolean alive = session.isAlive();
    assertTrue(alive);
  }

  @Test
  void shouldAcceptARegionAsLargeAsThePage() {
    this.open(RawHelperMain.class.getName(), "/full-page");
    Await.until("the first frame", () -> !this.listener.frames.isEmpty());
    final int firstFrameBlue = blue(this.listener.frames.get(0));
    assertEquals(7, firstFrameBlue);
    assertEquals(List.of(), this.listener.ended);
  }

  @Test
  void shouldEndTheSessionWithTheExitCodeOfAHelperThatExitsWhileItRuns() {
    this.open(RawHelperMain.class.getName(), "/exit-later");
    Await.until("the end of the session", () -> !this.listener.ended.isEmpty());
    assertEquals(List.of("The browser helper exited with code 5 before it was asked to stop!"), this.listener.ended);
  }

  @Test
  void shouldIgnoreInputForAClosedSessionWithoutCallingItDropped() {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), "/page");
    session.close();
    final boolean mouseAccepted = session.sendMouse(new MouseInput(HelperProtocol.MOUSE_MOVE, 1, 1, HelperProtocol.BUTTON_LEFT, 0, 0, 0));
    assertTrue(mouseAccepted);
    final boolean keyAccepted = session.sendKey(HelperProtocol.KEY_TYPE, "late");
    assertTrue(keyAccepted);
    assertEquals(List.of(), this.listener.ended);
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void shouldEndTheSessionWhenInputCannotBeWritten() {
    final HelperSession session = this.open(RawHelperMain.class.getName(), "/deaf");
    Await.until("the end of the session", () -> {
      session.sendKey(HelperProtocol.KEY_TYPE, "lost");
      return !this.listener.ended.isEmpty();
    });
    final String reason = this.listener.ended.get(0);
    final boolean reportsTheInputFailure = reason.startsWith("Input could not be sent to the browser helper (");
    assertTrue(reportsTheInputFailure, reason);
  }

  @Test
  void shouldKillAProcessTheHelperStartedWhenTheSessionCloses() {
    final HelperSession session = this.open(RawHelperMain.class.getName(), "/child");
    final Set<ProcessHandle> started = ConcurrentHashMap.newKeySet();
    Await.until("the process the helper starts", () -> {
      try (final Stream<ProcessHandle> descendants = session.getProcess().descendants()) {
        descendants.forEach(started::add);
      }
      return !started.isEmpty();
    });
    session.close();
    Await.until("no process the helper started is left", () -> started.stream().noneMatch(ProcessHandle::isAlive));
  }

  @Test
  void shouldLeaveNothingBehindWhenTheStartFailsBeforeTheHelperConnects() throws IOException {
    final Set<ProcessHandle> before = liveDescendants();
    final BrowserSource source = BrowserSource.uri(URI.create("https://example.com/silent-stubborn"), 4, 3, 1);
    final HelperLauncher launcher = launcher(RawHelperMain.class.getName(), 1_000L);
    final Path parent = this.shortDirectory();
    final PlayerException failure = assertThrows(PlayerException.class, () ->
      HelperSession.open(launcher, NATIVES, source, BrowserOptions.DEFAULT, this.listener, parent)
    );
    final String message = failure.getMessage();
    assertEquals("The browser helper did not connect in time!", message);
    final Set<ProcessHandle> newProcesses = newSince(before);
    assertEquals(Set.of(), newProcesses);
    try (final Stream<Path> left = Files.list(parent)) {
      final List<Path> remainingFiles = left.toList();
      assertEquals(List.of(), remainingFiles);
    }
  }

  private static Set<ProcessHandle> liveDescendants() {
    try (final Stream<ProcessHandle> descendants = ProcessHandle.current().descendants()) {
      return descendants.filter(ProcessHandle::isAlive).collect(Collectors.toSet());
    }
  }

  private static Set<ProcessHandle> newSince(final Set<ProcessHandle> before) {
    final Set<ProcessHandle> now = new HashSet<>(liveDescendants());
    now.removeAll(before);
    return now;
  }

  @Test
  void shouldFailTheStartWhenThePageFailsToLoad() {
    final PlayerException failure = this.openFails(ScriptedEngine.class.getName(), "/load-error", 60_000L);
    final boolean reportsTheLoadError = failure.getMessage().contains("ERR_NAME_NOT_RESOLVED (-105)");
    assertTrue(reportsTheLoadError, failure.getMessage());
  }

  @Test
  void shouldFailTheStartWhenTheHelperExitsDuringIt() {
    final PlayerException failure = this.openFails(ScriptedEngine.class.getName(), "/exit", 60_000L);
    final boolean reportsTheExit =
      failure.getMessage().contains("exited with code 3") || failure.getMessage().contains("closed the connection");
    assertTrue(reportsTheExit, failure.getMessage());
  }

  @Test
  void shouldFailTheStartWhenTheBrowserCannotStart() {
    final PlayerException failure = this.openFails(ScriptedEngine.class.getName(), "/throw", 60_000L);
    final boolean reportsTheStartFailure = failure.getMessage().contains("scripted start failure");
    assertTrue(reportsTheStartFailure, failure.getMessage());
  }

  @Test
  void shouldTimeOutWhenTheHelperNeverShowsThePage() {
    final PlayerException failure = this.openFails(ScriptedEngine.class.getName(), "/never", 3_000L);
    final boolean reportsTheTimeout = failure.getMessage().contains("did not show https://example.com/never in time");
    assertTrue(reportsTheTimeout, failure.getMessage());
  }

  @Test
  void shouldTimeOutWhenTheHelperNeverConnects() {
    final PlayerException failure = this.openFails(SilentMain.class.getName(), "/page", 2_000L);
    final String message = failure.getMessage();
    assertEquals("The browser helper did not connect in time!", message);
  }

  @Test
  void shouldFailTheStartWhenTheProgramIsNotJava() throws IOException {
    final BrowserSource source = BrowserSource.uri(URI.create("https://example.com/page"), 4, 3, 1);
    final HelperLauncher broken = new HelperLauncher(
      NATIVES.resolve("no-such-java"),
      ScriptedEngine.class.getName(),
      List.of(),
      List.of(),
      OsUtils.getOs(),
      System.getenv(),
      5_000L
    );
    final Path parent = this.shortDirectory();
    final Set<ProcessHandle> before = liveDescendants();
    final PlayerException failure = assertThrows(PlayerException.class, () ->
      HelperSession.open(broken, NATIVES, source, BrowserOptions.DEFAULT, this.listener, parent)
    );
    final boolean reportsTheStartFailure = failure.getMessage().startsWith("The browser helper could not be started");
    assertTrue(reportsTheStartFailure, failure.getMessage());
    final Set<ProcessHandle> newProcesses = newSince(before);
    assertEquals(Set.of(), newProcesses);
    try (final Stream<Path> left = Files.list(parent)) {
      final List<Path> remainingFiles = left.toList();
      assertEquals(List.of(), remainingFiles);
    }
  }

  @Test
  void shouldEndTheSessionWhenTheBrowserFailsLater() {
    this.open(ScriptedEngine.class.getName(), "/fail");
    Await.until("the end of the session", () -> !this.listener.ended.isEmpty());
    assertEquals(List.of("The browser failed (scripted renderer crash)!"), this.listener.ended);
  }

  @Test
  void shouldRefuseAHelperWithAnotherToken() {
    final PlayerException failure = this.openFails(RawHelperMain.class.getName(), "/wrong-token", 60_000L);
    final boolean reportsTheWrongToken = failure.getMessage().contains("presented a wrong session token");
    assertTrue(reportsTheWrongToken, failure.getMessage());
  }

  @Test
  void shouldRefuseAHelperOfAnotherProtocolVersion() {
    final PlayerException failure = this.openFails(RawHelperMain.class.getName(), "/wrong-version", 60_000L);
    final boolean reportsTheProtocolMismatch = failure
      .getMessage()
      .contains("Protocol version 2 of the browser helper differs from version 1 of the server!");
    assertTrue(reportsTheProtocolMismatch, failure.getMessage());
  }

  @Test
  void shouldRefuseAHelperThatDoesNotIntroduceItself() {
    final PlayerException failure = this.openFails(RawHelperMain.class.getName(), "/no-hello", 60_000L);
    final boolean reportsTheMissingIntroduction = failure.getMessage().contains("did not introduce itself");
    assertTrue(reportsTheMissingIntroduction, failure.getMessage());
  }

  @Test
  void shouldRefuseAFrameOfAnotherPage() {
    final PlayerException failure = this.openFails(RawHelperMain.class.getName(), "/wrong-page", 60_000L);
    final boolean reportsTheFrameMismatch = failure.getMessage().contains("A frame of a 5x3 page arrived for a 4x3 page");
    assertTrue(reportsTheFrameMismatch, failure.getMessage());
  }

  @Test
  void shouldEndTheSessionOnAMessageOnlyTheServerSends() {
    this.open(RawHelperMain.class.getName(), "/server-message");
    Await.until("the end of the session", () -> !this.listener.ended.isEmpty());
    final boolean reportsTheServerOnlyMessage = this.listener.ended
      .getFirst()
      .contains("sent message type 18, which only the server sends");
    assertTrue(reportsTheServerOnlyMessage, this.listener.ended.getFirst());
  }

  @Test
  void shouldDropInputForAHelperThatStopsReadingOnceTheQueueIsFull() {
    final HelperSession session = this.open(RawHelperMain.class.getName(), "/stall");
    final String text = "x".repeat(HelperProtocol.MAX_TEXT_BYTES);
    boolean dropped = false;
    for (int count = 0; count < 10_000 && !dropped; count++) {
      dropped = !session.sendKey(HelperProtocol.KEY_TYPE, text);
    }
    assertTrue(dropped, "the bounded queue refuses input once the helper stops reading");
    final boolean mouseAccepted = session.sendMouse(new MouseInput(HelperProtocol.MOUSE_MOVE, 1, 1, HelperProtocol.BUTTON_LEFT, 0, 0, 0));
    assertFalse(mouseAccepted);
  }

  @Test
  void shouldKillAHelperThatIgnoresTheEndOfItsInput() {
    final HelperSession session = this.open(RawHelperMain.class.getName(), "/stubborn");
    final Process process = session.getProcess();
    session.close();
    final boolean alive = process.isAlive();
    assertFalse(alive);
  }

  @Test
  void shouldFailTheStartWhenTheHelperExitsBeforeItConnects() {
    final PlayerException failure = this.openFails(RawHelperMain.class.getName(), "/exit", 60_000L);
    final String message = failure.getMessage();
    assertEquals("The browser helper exited with code 4 before it connected!", message);
  }

  @Test
  void shouldRefuseAHelperThatSendsAFrameBeforeItsHello() {
    final PlayerException failure = this.openFails(RawHelperMain.class.getName(), "/frame-first", 60_000L);
    final boolean reportsTheEarlyFrame = failure.getMessage().contains("arrived where no frame of that size is expected");
    assertTrue(reportsTheEarlyFrame, failure.getMessage());
  }

  @Test
  void shouldGiveAHelperTheLibrariesItsLauncherLinksIntoTheSession() {
    final List<Path> linked = new CopyOnWriteArrayList<>();
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
    final boolean librariesLinked = Files.isDirectory(linked.getFirst());
    assertTrue(librariesLinked);
    session.close();
    final boolean librariesLeft = Files.exists(linked.getFirst());
    assertFalse(librariesLeft);
  }

  @Test
  void shouldFailTheStartAndLeaveNothingBehindWhenLibrariesCannotBeLinked() throws IOException {
    final HelperLauncher launcher = launcher(ScriptedEngine.class.getName(), 60_000L).withLibraries(session -> {
      throw new IOException("no space left");
    });
    final BrowserSource source = BrowserSource.uri(URI.create("https://example.com/page"), 4, 3, 1);
    final Path parent = this.shortDirectory();
    final PlayerException failure = assertThrows(PlayerException.class, () ->
      HelperSession.open(launcher, NATIVES, source, BrowserOptions.DEFAULT, this.listener, parent)
    );
    final String message = failure.getMessage();
    assertEquals("The browser helper could not be started (no space left)!", message);
    try (final Stream<Path> left = Files.list(parent)) {
      final List<Path> remainingFiles = left.toList();
      assertEquals(List.of(), remainingFiles);
    }
  }

  @Test
  void shouldRefuseARegionLargerThanThePageBeforeItsPixelsAreRead() {
    final PlayerException failure = this.openFails(RawHelperMain.class.getName(), "/oversized", 60_000L);
    final boolean reportsTheOversizedFrame = failure
      .getMessage()
      .contains("A frame of 60 bytes arrived where no frame of that size is expected");
    assertTrue(reportsTheOversizedFrame, failure.getMessage());
  }

  @Test
  void shouldDeliverATextLongerThanOneMessageWhole() {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), "/page");
    Await.until("the first frame", () -> !this.listener.blues().isEmpty());
    final boolean textAccepted = session.sendKey(HelperProtocol.KEY_TYPE, "a".repeat(5_000));
    assertTrue(textAccepted);
    Await.until("every character typed", () -> this.listener.inputCounts().contains(10_000));
    final Integer lastInputCount = this.listener.inputCounts().getLast();
    assertEquals(10_000, lastInputCount);
  }

  @Test
  void shouldStopAHelperThatNeverConnectsAtOnceWhenItsStartTimesOut() {
    final long start = System.nanoTime();
    final PlayerException failure = this.openFails(RawHelperMain.class.getName(), "/silent", 1_000L);
    final boolean reportsTheTimeout = failure.getMessage().contains("The browser helper did not connect in time!");
    assertTrue(reportsTheTimeout, failure.getMessage());
    final long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
    final boolean stoppedQuickly = seconds < 8;
    assertTrue(stoppedQuickly, "the failed start took " + seconds + " s");
  }

  @Test
  void shouldKillAProcessTheHelperStartsWhileItDoesNotStopTogetherWithIt() throws InterruptedException {
    final HelperSession session = this.open(RawHelperMain.class.getName(), "/stubborn-child");
    final Process process = session.getProcess();
    // the helper ignores the end of its input, starts a process two seconds later, and is killed after ten seconds;
    // its processes are watched while the session closes, because they are no descendants once the helper is gone
    final Set<ProcessHandle> started = ConcurrentHashMap.newKeySet();
    final AtomicBoolean closing = new AtomicBoolean(true);
    final Thread watcher = new Thread(() -> {
      while (closing.get()) {
        try (final Stream<ProcessHandle> descendants = process.descendants()) {
          descendants.forEach(started::add);
        }
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
      }
    });
    watcher.start();
    try {
      session.close();
    } finally {
      closing.set(false);
      watcher.join();
    }
    final boolean alive = process.isAlive();
    assertFalse(alive);
    final boolean startedNothing = started.isEmpty();
    assertFalse(startedNothing, "the helper started a process while it did not stop");
    Await.until("no process the helper started is left", () -> started.stream().noneMatch(ProcessHandle::isAlive));
  }

  @Test
  void shouldStartNoHelperWhileTheModuleIsStopped() {
    HelperProcesses.closeAll();
    try {
      final PlayerException failure = this.openFails(ScriptedEngine.class.getName(), "/page", 60_000L);
      final String message = failure.getMessage();
      assertEquals("The browser module is stopped!", message);
    } finally {
      HelperProcesses.open();
    }
    final boolean restartedHelperAlive = this.open(ScriptedEngine.class.getName(), "/page").isAlive();
    assertTrue(restartedHelperAlive, "a started module lets helpers start again");
  }

  @Test
  void shouldEndEveryHelperWhenTheModuleStopsEvenWhenTheirListenersFail() {
    final BrowserSource source = BrowserSource.uri(URI.create("https://example.com/page"), 4, 3, 1);
    final BrowserSession.Listener failing = new BrowserSession.Listener() {
      @Override
      public void onFrame(final ImageBuffer frame) {
        frame.close();
      }

      @Override
      public void onAudio(final byte[] samples) {}

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
    final String message = failure.getMessage();
    assertEquals("the listener fails: The browser module was stopped!", message);
    assertEquals(1, failure.getSuppressed().length, "the failure of the other listener goes along");
    final boolean firstAlive = first.isAlive();
    assertFalse(firstAlive, "the first helper ended");
    final boolean secondAlive = second.isAlive();
    assertFalse(secondAlive, "the second helper ended");
    final int helperCount = HelperProcesses.count();
    assertEquals(0, helperCount);
  }

  @Test
  void shouldEndAHelperThatConnectsAfterTheModuleStoppedAndFailItsStart() throws IOException {
    final Path gate = this.directory.resolve("gate");
    final HelperLauncher gated = launcher(
      GatedHelperMain.class.getName(),
      60_000L,
      OsUtils.getOs(),
      List.of("-D" + GatedHelperMain.GATE_PROPERTY + "=" + gate)
    );
    final BrowserSource source = BrowserSource.uri(URI.create("https://example.com/page"), 4, 3, 1);
    final CompletableFuture<HelperSession> opening = CompletableFuture.supplyAsync(() ->
      HelperSession.open(gated, NATIVES, source, BrowserOptions.DEFAULT, this.listener)
    );
    final Path started = gate.resolveSibling(gate.getFileName() + GatedHelperMain.STARTED_SUFFIX);
    try {
      Await.until("the helper runs", () -> Files.exists(started));
      HelperProcesses.closeAll();
      Files.createFile(gate);
      final ExecutionException failure = assertThrows(ExecutionException.class, () -> opening.get(60, TimeUnit.SECONDS));
      final PlayerException cause = assertInstanceOf(PlayerException.class, failure.getCause());
      final String message = cause.getMessage();
      assertEquals("The browser module was stopped while the browser started!", message);
    } finally {
      HelperProcesses.open();
    }
    final int helperCount = HelperProcesses.count();
    assertEquals(0, helperCount);
    Await.until("the helper ended", () ->
      ProcessHandle.current()
        .descendants()
        .noneMatch(process -> process.info().commandLine().orElse("").contains(GatedHelperMain.class.getName()))
    );
  }

  @Test
  void shouldEndAHelperThatConnectsAfterTheModuleStoppedAndStartedAgainAndFailItsStart() throws IOException {
    final Path gate = this.directory.resolve("gate-restart");
    final HelperLauncher gated = launcher(
      GatedHelperMain.class.getName(),
      60_000L,
      OsUtils.getOs(),
      List.of("-D" + GatedHelperMain.GATE_PROPERTY + "=" + gate)
    );
    final BrowserSource source = BrowserSource.uri(URI.create("https://example.com/page"), 4, 3, 1);
    final CompletableFuture<HelperSession> opening = CompletableFuture.supplyAsync(() ->
      HelperSession.open(gated, NATIVES, source, BrowserOptions.DEFAULT, this.listener)
    );
    final Path started = gate.resolveSibling(gate.getFileName() + GatedHelperMain.STARTED_SUFFIX);
    Await.until("the helper runs", () -> Files.exists(started));
    HelperProcesses.closeAll();
    HelperProcesses.open();
    Files.createFile(gate);
    final ExecutionException failure = assertThrows(ExecutionException.class, () -> opening.get(60, TimeUnit.SECONDS));
    final PlayerException cause = assertInstanceOf(PlayerException.class, failure.getCause());
    final String message = cause.getMessage();
    assertEquals("The browser module was stopped while the browser started!", message);
    final int helperCount = HelperProcesses.count();
    assertEquals(0, helperCount);
  }

  @Test
  void shouldFailAnInterruptedStartAndLeaveNothingBehind() throws InterruptedException {
    final BrowserSource source = BrowserSource.uri(URI.create("https://example.com/never"), 4, 3, 1);
    final HelperLauncher launcher = launcher(ScriptedEngine.class.getName(), 60_000L);
    final AtomicReference<Throwable> thrown = new AtomicReference<>();
    final AtomicBoolean stillInterrupted = new AtomicBoolean();
    final Thread starter = new Thread(() -> {
      try {
        HelperSession.open(launcher, NATIVES, source, BrowserOptions.DEFAULT, this.listener);
      } catch (final PlayerException exception) {
        thrown.set(exception);
        stillInterrupted.set(Thread.currentThread().isInterrupted());
      }
    });
    starter.start();
    Await.until("the helper runs", () -> HelperProcesses.count() == 1);
    starter.interrupt();
    starter.join(30_000L);
    final String message = thrown.get().getMessage();
    assertEquals("Interrupted while starting the browser!", message);
    final boolean interruptKept = stillInterrupted.get();
    assertTrue(interruptKept, "the interrupt is kept");
    final int helperCount = HelperProcesses.count();
    assertEquals(0, helperCount);
  }

  @Test
  void shouldEndTheSessionOnceOnInputForAHelperThatHungUp() {
    final HelperSession session = this.open(RawHelperMain.class.getName(), "/hang-up");
    Await.until("the end of the connection", () -> !this.listener.ended.isEmpty());
    final boolean keyAccepted = session.sendKey(HelperProtocol.KEY_TYPE, "late");
    assertTrue(keyAccepted);
    Await.until("the input was tried", () -> session.getHandledInput() == 1);
    assertEquals(List.of("The browser helper closed the connection!"), this.listener.ended);
  }

  @Test
  void shouldNotReportASecondEnd() {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), "/fail");
    Await.until("the end of the session", () -> !this.listener.ended.isEmpty());
    Await.until("the helper exited", () -> !session.isAlive());
    assertEquals(List.of("The browser failed (scripted renderer crash)!"), this.listener.ended);
  }

  @Test
  void shouldLetAListenerCloseTheSessionFromItsOwnThread() {
    final AtomicReference<HelperSession> self = new AtomicReference<>();
    final CountDownLatch closed = new CountDownLatch(1);
    final BrowserSession.Listener closing = new BrowserSession.Listener() {
      @Override
      public void onFrame(final ImageBuffer frame) {
        frame.close();
      }

      @Override
      public void onAudio(final byte[] samples) {}

      @Override
      public void onEnded(final String reason, final Throwable cause) {
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
    final boolean alive = session.isAlive();
    assertFalse(alive);
  }

  @Test
  void shouldEndEverythingAndKeepTheInterruptWhenClosingFromAnInterruptedThread() {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), "/page");
    final Process process = session.getProcess();
    Thread.currentThread().interrupt();
    try {
      session.close();
      final boolean interruptKept = Thread.currentThread().isInterrupted();
      assertTrue(interruptKept);
    } finally {
      Thread.interrupted();
    }
    Await.until("the helper ended", () -> !process.isAlive());
    final int helperCount = HelperProcesses.count();
    assertEquals(0, helperCount);
  }

  @Test
  void shouldEndAnInterruptedDeliveryThreadAndDeliverNothingFromAClosedCanvas() throws InterruptedException {
    final HelperSession first = this.open(ScriptedEngine.class.getName(), "/page");
    final Thread delivery = first.getDeliveryThread();
    delivery.interrupt();
    delivery.join(10_000L);
    final boolean deliveryAlive = delivery.isAlive();
    assertFalse(deliveryAlive);
    first.close();
    final HelperSession second = this.open(ScriptedEngine.class.getName(), "/page");
    second.getCanvas().close();
    second.requestFrame();
    second.getDeliveryThread().join(10_000L);
    final boolean secondDeliveryAlive = second.getDeliveryThread().isAlive();
    assertFalse(secondDeliveryAlive, "a delivery without a picture ends");
  }

  @Test
  void shouldKeepTheOutputOfTheHelperLineByLineBoundedAndUntilItFails() {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), "/page");
    final StringBuilder output = new StringBuilder();
    for (int line = 0; line < 50; line++) {
      output.append("line ").append(line).append("\r\n");
    }
    output.append("x".repeat(3000));
    session.drainOutput(new ByteArrayInputStream(output.toString().getBytes(StandardCharsets.UTF_8)));
    final List<String> tail = List.of(session.getOutputTail().split(System.lineSeparator()));
    final int lineCount = tail.size();
    assertEquals(40, lineCount);
    final String firstLine = tail.getFirst();
    assertEquals("line 11", firstLine);
    final int lastLineLength = tail.getLast().length();
    assertEquals(1024, lastLineLength, "a line is cut at its limit");
    session.drainOutput(
      new InputStream() {
        @Override
        public int read() throws IOException {
          throw new IOException("gone");
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException {
          throw new IOException("gone");
        }
      }
    );
    assertEquals(40, session.getOutputTail().split(System.lineSeparator()).length);
  }

  @Test
  void shouldCreateTheFolderOfASessionOnEveryFileSystem() throws IOException {
    final Path folder = HelperSession.createFolder(this.directory);
    final boolean folderCreated = Files.isDirectory(folder);
    assertTrue(folderCreated);
    if (this.directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      final Set<PosixFilePermission> folderPermissions = Files.getPosixFilePermissions(folder);
      assertEquals(PosixFilePermissions.fromString("rwx------"), folderPermissions);
    }
    final Path zip = this.directory.resolve("folders.zip");
    try (final FileSystem zipped = FileSystems.newFileSystem(zip, Map.of("create", "true"))) {
      final Path root = zipped.getPath("/");
      final Path created = HelperSession.createFolder(root);
      final boolean plainFolderCreated = Files.isDirectory(created);
      assertTrue(plainFolderCreated, "a file system without POSIX permissions gets a plain folder");
    }
    final PlayerException missing = assertThrows(PlayerException.class, () ->
      HelperSession.createFolder(this.directory.resolve("missing"))
    );
    final boolean reportsTheMissingParent = missing.getMessage().startsWith("The folder of the browser session cannot be created");
    assertTrue(reportsTheMissingParent, missing.getMessage());
  }

  @Test
  void shouldGiveASessionFolderAShortRandomNameAndDrawATakenNameAgain() throws IOException {
    final Path folder = HelperSession.createFolder(this.directory);
    final boolean shortRandomName = folder.getFileName().toString().matches("mcavb-[a-z0-9]{8}");
    assertTrue(shortRandomName, folder.toString());
    Files.createDirectory(this.directory.resolve("mcavb-taken"));
    final Iterator<String> names = List.of("mcavb-taken", "mcavb-free").iterator();
    final Path freeFolder = HelperSession.createFolder(this.directory, names::next);
    assertEquals(this.directory.resolve("mcavb-free"), freeFolder);
    final AtomicInteger drawn = new AtomicInteger();
    final Path last = HelperSession.createFolder(this.directory, () -> drawn.incrementAndGet() < 100 ? "mcavb-taken" : "mcavb-last");
    assertEquals(this.directory.resolve("mcavb-last"), last);
    drawn.set(0);
    final PlayerException exhausted = assertThrows(PlayerException.class, () ->
      HelperSession.createFolder(this.directory, () -> {
        drawn.incrementAndGet();
        return "mcavb-taken";
      })
    );
    final boolean reportsEveryNameTaken = exhausted.getMessage().endsWith("(Every name drawn was taken!)!");
    assertTrue(reportsEveryNameTaken, exhausted.getMessage());
    final int drawCount = drawn.get();
    assertEquals(100, drawCount);
  }

  @Test
  void shouldRefuseATemporaryFolderTooLongForChromiumsSocketOnLinuxWithTheReason() {
    // the longest temporary folder, 47 characters: Chromium's socket path has 107, the most a path may have
    HelperSession.requireShortEnough(Os.LINUX, Path.of("/" + "t".repeat(46), "mcavb-12345678"));
    final Path folder = Path.of("/" + "t".repeat(47));
    final Path tooLong = folder.resolve("mcavb-12345678");
    final PlayerException refused = assertThrows(PlayerException.class, () -> HelperSession.requireShortEnough(Os.LINUX, tooLong));
    final String message = refused.getMessage();
    assertEquals(
      "The temporary folder " +
        folder +
        " is too long for the browser: on Linux it may have at most 47 characters, so start the server with a shorter" +
        " java.io.tmpdir (such as /tmp)!",
      message
    );
    HelperSession.requireShortEnough(Os.WINDOWS, tooLong);
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void shouldNotStartAHelperInATemporaryFolderTooLongForChromiumAndRemoveItsFolder() throws IOException {
    final Path temporary = Files.createDirectories(this.directory.resolve("t".repeat(70 - this.directory.toString().length())));
    final BrowserSource source = BrowserSource.uri(URI.create("https://example.com/page"), 4, 3, 1);
    final HelperLauncher launcher = launcher(ScriptedEngine.class.getName(), 60_000L, Os.LINUX);
    final PlayerException refused = assertThrows(PlayerException.class, () ->
      HelperSession.open(launcher, NATIVES, source, BrowserOptions.DEFAULT, this.listener, temporary)
    );
    final boolean reportsTheLongFolder = refused.getMessage().startsWith("The temporary folder " + temporary + " is too long");
    assertTrue(reportsTheLongFolder, refused.getMessage());
    try (final Stream<Path> left = Files.list(temporary)) {
      final List<Path> remainingFiles = left.toList();
      assertEquals(List.of(), remainingFiles, "the folder of the session is removed");
    }
  }

  @Test
  void shouldNotBindASocketPathThatIsTaken() throws IOException {
    final Path taken = Files.createFile(this.directory.resolve("s"));
    assertThrows(IOException.class, () -> HelperSession.bind(taken).close());
    OpenFiles.leaveNoneOpen("a failed bind", () -> assertThrows(IOException.class, () -> HelperSession.bind(taken).close()));
  }

  @Test
  void shouldOnlyLogFailuresWhenClosingAndDeletingQuietly() throws IOException {
    final AtomicInteger calls = new AtomicInteger();
    HelperSession.closeQuietly(() -> {
      calls.incrementAndGet();
      throw new IOException("already closed");
    });
    final int callCount = calls.get();
    assertEquals(1, callCount);
    final Path folder = Files.createDirectories(this.directory.resolve("kept").resolve("inner"));
    Files.writeString(folder.resolve("file"), "x");
    final File inner = folder.toFile();
    assumeTrue(inner.setWritable(false) && !Files.isWritable(folder), "the folder can be made read-only");
    try {
      HelperSession.deleteFolder(this.directory.resolve("kept"));
      final boolean fileKept = Files.exists(folder.resolve("file"));
      assertTrue(fileKept, "a folder that cannot be removed stays");
    } finally {
      inner.setWritable(true);
    }
  }

  @Test
  void shouldRequireTheFirstMessageToBeAHelloWithTheTokenAndVersion() throws ProtocolException {
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
     * @throws IOException if the input fails
     */
    public static void main(final String[] args) throws IOException {
      while (System.in.read() >= 0) {}
    }
  }

  /**
   * Records the frames, the sound and the end of a session.
   */
  static final class RecordingListener implements BrowserSession.Listener {

    final List<ImageBuffer> frames = new CopyOnWriteArrayList<>();
    final List<byte[]> sound = new CopyOnWriteArrayList<>();
    private final List<Long> frameNanos = new CopyOnWriteArrayList<>();
    final List<String> ended = new CopyOnWriteArrayList<>();

    private List<Integer> inputCounts() {
      final List<Integer> counts = new ArrayList<>();
      for (final ImageBuffer frame : this.frames) {
        final ByteBuffer pixels = frame.getData();
        final int low = pixels.get(0) & 0xFF;
        final int high = (pixels.get(1) & 0xFF) ^ ScriptedEngine.GREEN;
        counts.add(low | (high << 8));
      }
      return counts;
    }

    private List<Integer> blues() {
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
