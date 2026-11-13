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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
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
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.OsUtils;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HelperSessionTest {

  private static final String BROWSER_FAILED_MESSAGE = "Because of \"scripted renderer crash\", the browser failed!";
  private static final String TEMPORARY_FOLDER_MESSAGE_PREFIX = "The temporary folder ";
  private static final String FIRST_FRAME_MESSAGE = "the first frame";
  private static final String MOUSE_MOVE_MESSAGE = "the mouse move";
  private static final String CHILD_PROCESS_MESSAGE = "the process the helper starts";
  private static final String SESSION_ENDED_MESSAGE = "the end of the session";
  private static final String NO_CHILD_PROCESS_MESSAGE = "no process the helper started is left";
  private static final String CONNECT_TIMEOUT_MESSAGE = "The browser helper did not connect in time!";
  private static final String HELPER_RUNNING_MESSAGE = "the helper runs";
  private static final String MODULE_STOPPED_MESSAGE = "The browser module was stopped while the browser started!";
  private static final String HELPER_ENDED_MESSAGE = "the helper ended";

  private static final String STARTED_FILE_TEMPLATE = "%s%s";

  private static final String TEMPORARY_FOLDER_PREFIX = "m";
  private static final String MISSING_JAVA_EXECUTABLE = "no-such-java";
  private static final String RESTART_GATE_FILE = "gate-restart";
  private static final String FREE_SESSION_FOLDER = "mcavb-free";
  private static final String LAST_SESSION_FOLDER = "mcavb-last";
  private static final String BOUNDARY_SESSION_FOLDER = "mcavb-12345678";
  private static final String JAVA_BIN_FOLDER = "bin";
  private static final String JAVA_EXECUTABLE = "java";
  private static final String WINDOWS_JAVA_EXECUTABLE = "java.exe";
  private static final String SYSTEM_PROGRAMS_FOLDER = "/usr/bin";
  private static final String LIBRARIES_FOLDER = "lib";
  private static final String GATE_FILE = "gate";
  private static final String FOLDERS_ARCHIVE_FILE = "folders.zip";
  private static final String MISSING_FOLDER = "missing";
  private static final String ROOT_PATH = "/";
  private static final String SOCKET_FILE = "s";
  private static final String TAKEN_SESSION_FOLDER = "mcavb-taken";

  private static final int CHANNEL_MASK = 0xFF;

  private static final long CONNECTION_FAILURE_TIMEOUT_MILLISECONDS = 1_000L;
  private static final int JOIN_TIMEOUT_SECONDS = 60;

  private static final String INPUT_KEY = "a";
  private static final String LATE_INPUT_KEY = "late";
  private static final String REPEATED_TEXT_CHARACTER = "x";
  private static final String LONG_FOLDER_CHARACTER = "t";

  private static final String SYSTEM_PROPERTY_OPTION = "-D";

  private static final String PROPERTY_VALUE_SEPARATOR = "=";

  private static final int KEEP_TRAILING_EMPTY_PARTS = -1;

  private static final String JAVA_HOME_PROPERTY = "java.home";
  private static final String TEMPORARY_DIRECTORY_PROPERTY = "java.io.tmpdir";
  private static final String CLASS_PATH_PROPERTY = "java.class.path";

  private static final Path NATIVES = Path.of(System.getProperty(TEMPORARY_DIRECTORY_PROPERTY)).toAbsolutePath();

  private static final String PAGE_URL_TEMPLATE = "https://example.com%s";

  private static final String PAGE = "/page";

  private static final long START_TIMEOUT_MILLISECONDS = 60_000L;

  // the helper must connect before this deadline, and a loaded host takes seconds to start it
  private static final long SHOW_TIMEOUT_MILLISECONDS = 15_000L;

  private static final long SOUND_HOLD_MILLISECONDS = 3_000L;

  private static final Path TEMPORARY_FOLDER = Path.of("/tmp");
  private static final Pattern SESSION_FOLDER_NAME = Pattern.compile("mcavb-[a-z0-9]{8}");

  private static final String HELPER_GONE_MESSAGE = "The helper is gone!";

  @TempDir
  private Path directory;

  private final RecordingListener listener = new RecordingListener();
  private final List<Path> shortDirectories = new ArrayList<>();
  private final List<HelperSession> sessions = new ArrayList<>();

  static HelperLauncher launcher(final String mainClass, final long startTimeoutMilliseconds) {
    return launcher(mainClass, startTimeoutMilliseconds, OsUtils.getOs());
  }

  static HelperLauncher launcher(final String mainClass, final long startTimeoutMilliseconds, final Os os) {
    return launcher(mainClass, startTimeoutMilliseconds, os, List.of());
  }

  static HelperLauncher launcher(final String mainClass, final long startTimeoutMilliseconds, final Os os, final List<String> jvmOptions) {
    final String javaHome = System.getProperty(JAVA_HOME_PROPERTY);
    final Path java = Path.of(javaHome, JAVA_BIN_FOLDER, File.separatorChar == '\\' ? WINDOWS_JAVA_EXECUTABLE : JAVA_EXECUTABLE);
    final List<Path> classPath = new ArrayList<>();
    for (final String entry : System.getProperty(CLASS_PATH_PROPERTY).split(Pattern.quote(File.pathSeparator), KEEP_TRAILING_EMPTY_PARTS)) {
      classPath.add(Path.of(entry));
    }

    return new HelperLauncher(java, mainClass, classPath, jvmOptions, os, System.getenv(), startTimeoutMilliseconds);
  }

  private HelperSession open(final String mainClass, final String path) {
    return this.open(mainClass, path, BrowserOptions.DEFAULT);
  }

  private HelperSession open(final String mainClass, final String path, final BrowserOptions options) {
    final BrowserSource source = BrowserSource.uri(URI.create(PAGE_URL_TEMPLATE.formatted(path)), 4, 3, 1);
    final HelperSession session = HelperSession.open(
      launcher(mainClass, START_TIMEOUT_MILLISECONDS),
      NATIVES,
      source,
      options,
      this.listener
    );
    this.sessions.add(session);

    return session;
  }

  private BrowserException openFails(final String mainClass, final String path, final long timeoutMilliseconds) {
    final BrowserSource source = BrowserSource.uri(URI.create(PAGE_URL_TEMPLATE.formatted(path)), 4, 3, 1);
    final int before = HelperProcesses.count();
    final HelperLauncher launcher = launcher(mainClass, timeoutMilliseconds);
    final BrowserException failure = assertThrows(BrowserException.class, () -> this.openSession(launcher, source));
    final int helperCount = HelperProcesses.count();
    assertEquals(before, helperCount, "a failed start leaves no session behind");

    return failure;
  }

  private HelperSession openSession(final HelperLauncher launcher, final BrowserSource source) {
    return HelperSession.open(launcher, NATIVES, source, BrowserOptions.DEFAULT, this.listener);
  }

  private HelperSession openSessionIn(final HelperLauncher launcher, final BrowserSource source, final Path parent) {
    return HelperSession.open(launcher, NATIVES, source, BrowserOptions.DEFAULT, this.listener, parent);
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

  // macOS allows socket paths of at most 104 bytes, which a session folder below /var/folders/…/T/ exceeds
  private Path shortDirectory() throws IOException {
    final Path temporaryDirectory = TEMPORARY_FOLDER;
    final boolean isUsable = Files.isDirectory(temporaryDirectory) && Files.isWritable(temporaryDirectory);
    final Path parent = isUsable
      ? Files.createTempDirectory(temporaryDirectory, TEMPORARY_FOLDER_PREFIX)
      : Files.createTempDirectory(TEMPORARY_FOLDER_PREFIX);
    this.shortDirectories.add(parent);

    return parent;
  }

  private static int blue(final ImageBuffer frame) {
    final ByteBuffer pixels = frame.getData();
    return pixels.get(0) & CHANNEL_MASK;
  }

  @Test
  void shouldStopWaitingForTheHelperAtOnceWhenTheStartIsInterrupted() throws IOException {
    final Process alive = mock(Process.class);
    when(alive.isAlive()).thenReturn(true);
    try (final ServerSocketChannel server = ServerSocketChannel.open()) {
      server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      server.configureBlocking(false);
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(JOIN_TIMEOUT_SECONDS);
      final Duration timeout = Duration.ofSeconds(10);
      final BrowserException interrupted = assertTimeoutPreemptively(timeout, () -> this.acceptWhileInterrupted(server, alive, deadline));
      final String message = interrupted.getMessage();
      assertEquals("Interrupted while the browser helper connected!", message);
    }
  }

  private BrowserException acceptWhileInterrupted(final ServerSocketChannel server, final Process alive, final long deadline) {
    final Thread current = Thread.currentThread();
    current.interrupt();
    try {
      return assertThrows(BrowserException.class, () -> HelperSession.accept(server, alive, deadline));
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void shouldLogAHelperThatReportsInALoopOnlyWithinItsBudget() {
    final String log;
    try (final StandardError errors = new StandardError()) {
      this.open(RawHelperMain.class.getName(), RawHelperMain.NOISY_PATH);
      Await.until("the sound after the reports", () -> !this.listener.sound.isEmpty());
      log = errors.text();
    }

    final long reports = log
      .lines()
      .filter(line -> line.contains("the notice \"noise ") || line.contains("Page https://example.com/noise/"))
      .count();
    final int sent = RawHelperMain.NOISY_NOTICES + RawHelperMain.NOISY_LOAD_ERRORS;
    final boolean loggedWithinBudget = reports >= LogBudget.BURST && reports <= LogBudget.BURST + 5;
    assertTrue(loggedWithinBudget, "logged " + reports + " of " + sent + " reports");

    final boolean logsTheFirstLoadError = log.contains("Page https://example.com/noise/0?<hidden> failed to load");
    assertTrue(logsTheFirstLoadError, log);

    final boolean leaksTheToken = log.contains("token=secret");
    assertFalse(leaksTheToken, log);

    final boolean reportsTheSkippedNotices = log.contains("more notices of the page were not logged");
    assertTrue(reportsTheSkippedNotices, log);

    final boolean logsTheNoiseAfterAPause = log.contains("the notice \"noise after a pause\"");
    assertTrue(logsTheNoiseAfterAPause, log);
  }

  @Test
  void shouldStreamThePageAndCarryInput() throws Exception {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), PAGE);
    final boolean sessionAlive = session.isAlive();
    assertTrue(sessionAlive);

    final int helperCountWhileOpen = HelperProcesses.count();
    assertEquals(1, helperCountWhileOpen);

    Await.until(FIRST_FRAME_MESSAGE, () -> !this.listener.blues().isEmpty());
    final ImageBuffer first = this.listener.frames.getFirst();
    final int width = first.getWidth();
    assertEquals(4, width);

    final int height = first.getHeight();
    assertEquals(3, height);

    final int greenChannel = first.getData().get(1) & CHANNEL_MASK;
    assertEquals(ScriptedEngine.GREEN, greenChannel);

    final int redChannel = first.getData().get(2) & CHANNEL_MASK;
    assertEquals(ScriptedEngine.RED, redChannel);

    final boolean keyAccepted = session.sendKey(HelperProtocol.KEY_TYPE, "ab");
    assertTrue(keyAccepted);

    Await.until("the typed text", () -> this.listener.blues().contains(4));
    final boolean mouseAccepted = session.sendMouse(new MouseInput(HelperProtocol.MOUSE_MOVE, 3, 2, 0, 0, 0, 0));
    assertTrue(mouseAccepted);

    Await.until(MOUSE_MOVE_MESSAGE, () -> this.listener.blues().contains(5));
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
    final HelperSession session = this.open(ScriptedEngine.class.getName(), ScriptedEngine.SOUND_PATH, autoplay);
    Await.until("the sound", () -> this.listener.sound.size() == ScriptedEngine.SOUND_CHUNKS);
    for (int chunk = 1; chunk <= ScriptedEngine.SOUND_CHUNKS; chunk++) {
      final byte[] samples = this.listener.sound.get(chunk - 1);
      final int expectedSampleBytes = ScriptedEngine.SOUND_FRAMES * HelperProtocol.AUDIO_FRAME_BYTES;
      assertEquals(expectedSampleBytes, samples.length);

      for (int index = 0; index < samples.length; index += 2) {
        final byte lowByte = samples[index];
        assertEquals(chunk, lowByte, "the low byte of every sample of chunk " + chunk);

        final byte highByte = samples[index + 1];
        assertEquals(0, highByte);
      }
    }

    Thread.sleep(HelperSession.REPEAT_DELAY_MILLISECONDS * 2);
    final int chunkCount = this.listener.sound.size();
    assertEquals(ScriptedEngine.SOUND_CHUNKS, chunkCount);

    session.close();
    assertEquals(List.of(), this.listener.ended);
  }

  @Test
  void shouldPassTheSoundOfAPageToTheServerOnlyOnceAPlayerPressedAButtonOrAKey() throws Exception {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), ScriptedEngine.SOUND_ON_INPUT_PATH);
    Await.until(FIRST_FRAME_MESSAGE, () -> !this.listener.frames.isEmpty());
    final boolean moveAccepted = session.sendMouse(new MouseInput(HelperProtocol.MOUSE_MOVE, 1, 1, 0, 0, 0, 0));
    assertTrue(moveAccepted);

    Await.until(MOUSE_MOVE_MESSAGE, () -> session.getCompletedInputCount() >= 1);
    final boolean pressAccepted = session.sendMouse(new MouseInput(HelperProtocol.MOUSE_PRESS, 1, 1, 0, 1, 0, 0));
    assertTrue(pressAccepted);

    Await.until("the sound after the press", () -> !this.listener.sound.isEmpty());
    Thread.sleep(HelperSession.REPEAT_DELAY_MILLISECONDS * 2);
    final int chunkCount = this.listener.sound.size();
    assertEquals(1, chunkCount);

    final byte firstSoundByte = this.listener.sound.getFirst()[0];
    assertEquals(2, firstSoundByte);
  }

  @Test
  void shouldHandOverASettledPageAgainABoundedNumberOfTimes() throws Exception {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), PAGE);
    final int expected = 1 + HelperSession.SETTLED_REPEATS;
    Await.until("the repeats of the settled page", () -> this.listener.frames.size() >= expected);
    Thread.sleep(HelperSession.REPEAT_DELAY_MILLISECONDS * 4);
    final int frameCount = this.listener.frames.size();
    assertEquals(expected, frameCount, "the repeats stop");

    final long spanNanoseconds = this.listener.frameNanoseconds.get(expected - 1) - this.listener.frameNanoseconds.get(0);
    final long leastNanoseconds = TimeUnit.MILLISECONDS.toNanos(HelperSession.SETTLED_REPEATS * HelperSession.REPEAT_DELAY_MILLISECONDS);
    final boolean repeatsSpreadOut = spanNanoseconds >= leastNanoseconds;
    assertTrue(repeatsSpreadOut, "the repeats came within " + TimeUnit.NANOSECONDS.toMillis(spanNanoseconds) + " ms");

    session.sendKey(HelperProtocol.KEY_TYPE, INPUT_KEY);
    Await.until("the repeats of the changed page", () -> this.listener.frames.size() >= 2 * expected);
    session.close();
    assertEquals(List.of(), this.listener.ended);
  }

  @Test
  void shouldHandASlowListenerTheLatestPictureInsteadOfThePicturesItMissed() throws InterruptedException {
    final PictureHoldingListener holder = new PictureHoldingListener();
    final HelperLauncher launcher = launcher(ScriptedEngine.class.getName(), START_TIMEOUT_MILLISECONDS);
    final URI page = URI.create(PAGE_URL_TEMPLATE.formatted(PAGE));
    final BrowserSource source = BrowserSource.uri(page, 4, 3, 1);
    final HelperSession session = HelperSession.open(launcher, NATIVES, source, BrowserOptions.DEFAULT, holder);
    this.sessions.add(session);
    final boolean pictureHeld = holder.awaitHolding();
    assertTrue(pictureHeld, "a picture is handed over");

    final FrameCanvas canvas = session.getCanvas();
    try {
      this.typeAndAwaitThePicture(session, canvas, 2);
      this.typeAndAwaitThePicture(session, canvas, 4);
      this.typeAndAwaitThePicture(session, canvas, 6);
    } finally {
      holder.release();
    }

    Await.until("the latest picture", () -> holder.blues.contains(6));
    final List<Integer> missedPictures = holder.blues.stream()
      .filter(deliveredBlue -> deliveredBlue == 2 || deliveredBlue == 4)
      .toList();
    assertEquals(List.of(), missedPictures);
  }

  private void typeAndAwaitThePicture(final HelperSession session, final FrameCanvas canvas, final int expectedBlue) {
    final boolean keyAccepted = session.sendKey(HelperProtocol.KEY_TYPE, INPUT_KEY);
    assertTrue(keyAccepted);

    Await.until("the picture of the typed key", () -> this.showsBlue(canvas, expectedBlue));
  }

  private boolean showsBlue(final FrameCanvas canvas, final int expectedBlue) {
    final @Nullable ImageBuffer picture = canvas.snapshot();
    if (picture == null) {
      return false;
    }

    final int shownBlue = blue(picture);
    picture.close();

    return shownBlue == expectedBlue;
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
          final Thread current = Thread.currentThread();
          current.interrupt();
        }
      }

      @Override
      public void onAudio(final byte[] samples) {}

      @Override
      public void onEnded(final String reason, final Throwable failure) {}
    };
    final BrowserSource source = BrowserSource.uri(URI.create(PAGE_URL_TEMPLATE.formatted(PAGE)), 4, 3, 1);
    final HelperSession session = HelperSession.open(
      launcher(ScriptedEngine.class.getName(), START_TIMEOUT_MILLISECONDS),
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
      Await.until("the close waits for the delivery", () -> this.isWaitingInJoin(closer));
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
  void shouldWaitOnCloseForTheSoundThatIsStillHandedOver() throws InterruptedException {
    final SoundHoldingListener holder = new SoundHoldingListener();
    final BrowserOptions autoplay = BrowserOptions.builder().autoplay(true).build();
    final BrowserSource source = BrowserSource.uri(URI.create(PAGE_URL_TEMPLATE.formatted(ScriptedEngine.SOUND_PATH)), 4, 3, 1);
    final HelperSession session = HelperSession.open(
      launcher(ScriptedEngine.class.getName(), START_TIMEOUT_MILLISECONDS),
      NATIVES,
      source,
      autoplay,
      holder
    );
    this.sessions.add(session);
    final boolean soundHeld = holder.awaitHolding();
    assertTrue(soundHeld, "a sound is handed over");

    final Thread reader = session.getThreads().get(0);
    session.close();
    final boolean readerAlive = reader.isAlive();
    assertFalse(readerAlive, "the close returned only once the sound was handed over");
  }

  @Test
  void shouldWaitOnCloseUntilTheOutputOfTheHelperAndItsProcessesEnded() {
    final HelperSession session = this.open(RawHelperMain.class.getName(), RawHelperMain.OUTPUT_CHILD_PATH);
    final Set<ProcessHandle> started = ConcurrentHashMap.newKeySet();
    Await.until(CHILD_PROCESS_MESSAGE, () -> this.recordDescendantsAndReportAny(session, started));
    final Thread drain = session.getThreads().get(2);
    session.close();
    final boolean drainAlive = drain.isAlive();
    assertFalse(drainAlive, "the close returned only once the output of the helper and its process ended");
  }

  @Test
  @ResourceLock(Resources.SYSTEM_ERR)
  void shouldLogTheOutputOfAHelperWhoseStartFailed() {
    final PrintStream standardError = System.err;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    final BrowserException failure;
    try {
      failure = this.openFails(ScriptedEngine.class.getName(), ScriptedEngine.THROW_AFTER_OUTPUT_PATH, START_TIMEOUT_MILLISECONDS);
    } finally {
      System.setErr(standardError);
    }

    final String message = failure.getMessage();
    final boolean reportsTheStartFailure = message.contains("The scripted start failed after output!");
    assertTrue(reportsTheStartFailure, message);

    final String log = captured.toString(StandardCharsets.UTF_8);
    final boolean outputAnnounced = log.contains("The browser helper wrote these lines before its start failed.");
    final boolean outputLogged = log.contains(ScriptedEngine.OUTPUT_BEFORE_FAILURE);
    assertTrue(outputAnnounced, log);
    assertTrue(outputLogged, log);
  }

  private boolean isWaitingInJoin(final Thread thread) {
    return Arrays.stream(thread.getStackTrace()).anyMatch(this::isJoinFrame);
  }

  private boolean isJoinFrame(final StackTraceElement frame) {
    return frame.getClassName().equals(Thread.class.getName()) && frame.getMethodName().equals("join");
  }

  @Test
  void shouldWaitBetweenTheRepeatsOfAPictureInsteadOfSpinning() {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), PAGE);
    Await.until(FIRST_FRAME_MESSAGE, () -> !this.listener.frames.isEmpty());
    final Thread delivery = session.getThreads().get(1);
    Await.until("the delivery thread waits for the next repeat", () -> delivery.getState() == Thread.State.TIMED_WAITING);
  }

  @Test
  void shouldEndTheThreadsTheConnectionAndThePictureAndRemoveTheFolderOnClose() {
    // the folder does not come from the command line: Linux hides the arguments of one longer than a page, as a test run's class path is
    final HelperSession session = this.open(ScriptedEngine.class.getName(), PAGE);
    Await.until(FIRST_FRAME_MESSAGE, () -> !this.listener.frames.isEmpty());
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
      NATIVES.resolve(JAVA_EXECUTABLE),
      ScriptedEngine.class.getName(),
      List.of(),
      List.of(),
      Os.LINUX,
      Map.of("PATH", SYSTEM_PROGRAMS_FOLDER, "MCAV_TEST_SECRET", "hidden"),
      CONNECTION_FAILURE_TIMEOUT_MILLISECONDS
    );
    final ProcessBuilder builder = HelperSession.createProcessBuilder(launcher, this.directory, null);
    final Map<String, String> helperEnvironment = builder.environment();
    final Map<String, String> expectedHelperEnvironment = launcher.createEnvironment(this.directory, null);
    assertEquals(expectedHelperEnvironment, helperEnvironment);

    final File workingDirectory = builder.directory();
    final File expectedWorkingDirectory = this.directory.toFile();
    assertEquals(expectedWorkingDirectory, workingDirectory);

    final boolean errorsMerged = builder.redirectErrorStream();
    assertTrue(errorsMerged);
  }

  @Test
  void shouldReadTheOutputOfARunningHelperWhateverItsSize() {
    final HelperSession session = this.open(RawHelperMain.class.getName(), RawHelperMain.CHATTY_PATH);
    Await.until("the last line of the helper", () -> session.getOutputTail().contains(RawHelperMain.CHATTY_END));
    final boolean alive = session.isAlive();
    assertTrue(alive);
  }

  @Test
  void shouldAcceptARegionAsLargeAsThePage() {
    this.open(RawHelperMain.class.getName(), RawHelperMain.FULL_PAGE_PATH);
    Await.until(FIRST_FRAME_MESSAGE, () -> !this.listener.frames.isEmpty());
    final int firstFrameBlue = blue(this.listener.frames.get(0));
    assertEquals(7, firstFrameBlue);
    assertEquals(List.of(), this.listener.ended);
  }

  @Test
  void shouldEndTheSessionWithTheExitCodeOfAHelperThatExitsWhileItRuns() {
    this.open(RawHelperMain.class.getName(), RawHelperMain.EXIT_LATER_PATH);
    Await.until(SESSION_ENDED_MESSAGE, () -> !this.listener.ended.isEmpty());
    final List<String> expectedEndReasons = List.of("The browser helper exited with code 5 before it was asked to stop!");
    assertEquals(expectedEndReasons, this.listener.ended);
  }

  @Test
  void shouldIgnoreInputForAClosedSessionWithoutCallingItDropped() {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), PAGE);
    session.close();
    final boolean mouseAccepted = session.sendMouse(new MouseInput(HelperProtocol.MOUSE_MOVE, 1, 1, HelperProtocol.BUTTON_LEFT, 0, 0, 0));
    assertTrue(mouseAccepted);

    final boolean keyAccepted = session.sendKey(HelperProtocol.KEY_TYPE, LATE_INPUT_KEY);
    assertTrue(keyAccepted);
    assertEquals(List.of(), this.listener.ended);
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void shouldEndTheSessionWhenInputCannotBeWritten() {
    final HelperSession session = this.open(RawHelperMain.class.getName(), RawHelperMain.DEAF_PATH);
    Await.until(SESSION_ENDED_MESSAGE, () -> this.sendAKeyAndReportTheEnd(session));
    final String reason = this.listener.ended.get(0);
    final boolean reportsTheInputFailure = reason.startsWith("Input could not be sent to the browser helper!");
    assertTrue(reportsTheInputFailure, reason);

    final Throwable failure = this.listener.failures.get(0);
    final Throwable cause = failure.getCause();
    assertInstanceOf(IOException.class, cause, "the failure carries the failed write");
  }

  private boolean sendAKeyAndReportTheEnd(final HelperSession session) {
    session.sendKey(HelperProtocol.KEY_TYPE, "lost");

    return !this.listener.ended.isEmpty();
  }

  @Test
  void shouldKillAProcessTheHelperStartedWhenTheSessionCloses() {
    final HelperSession session = this.open(RawHelperMain.class.getName(), RawHelperMain.CHILD_PATH);
    final Set<ProcessHandle> started = ConcurrentHashMap.newKeySet();
    Await.until(CHILD_PROCESS_MESSAGE, () -> this.recordDescendantsAndReportAny(session, started));
    session.close();
    Await.until(NO_CHILD_PROCESS_MESSAGE, () -> started.stream().noneMatch(ProcessHandle::isAlive));
  }

  private boolean recordDescendantsAndReportAny(final HelperSession session, final Set<ProcessHandle> started) {
    try (final Stream<ProcessHandle> descendants = session.getProcess().descendants()) {
      descendants.forEach(started::add);
    }

    return !started.isEmpty();
  }

  @Test
  void shouldLeaveNothingBehindWhenTheStartFailsBeforeTheHelperConnects() throws IOException {
    final Set<ProcessHandle> before = this.liveDescendants();
    final BrowserSource source = BrowserSource.uri(URI.create(PAGE_URL_TEMPLATE.formatted(RawHelperMain.SILENT_STUBBORN_PATH)), 4, 3, 1);
    final HelperLauncher launcher = launcher(RawHelperMain.class.getName(), CONNECTION_FAILURE_TIMEOUT_MILLISECONDS);
    final Path parent = this.shortDirectory();
    final BrowserException failure = assertThrows(BrowserException.class, () -> this.openSessionIn(launcher, source, parent));
    final String message = failure.getMessage();
    assertEquals(CONNECT_TIMEOUT_MESSAGE, message);

    final Set<ProcessHandle> newProcesses = this.newSince(before);
    assertEquals(Set.of(), newProcesses);

    try (final Stream<Path> left = Files.list(parent)) {
      final List<Path> remainingFiles = left.toList();
      assertEquals(List.of(), remainingFiles);
    }
  }

  private Set<ProcessHandle> liveDescendants() {
    try (final Stream<ProcessHandle> descendants = ProcessHandle.current().descendants()) {
      return descendants.filter(ProcessHandle::isAlive).collect(Collectors.toSet());
    }
  }

  private Set<ProcessHandle> newSince(final Set<ProcessHandle> before) {
    final Set<ProcessHandle> now = new HashSet<>(this.liveDescendants());
    now.removeAll(before);

    return now;
  }

  @Test
  void shouldFailTheStartWhenThePageFailsToLoad() {
    final BrowserException failure = this.openFails(
      ScriptedEngine.class.getName(),
      ScriptedEngine.LOAD_ERROR_PATH,
      START_TIMEOUT_MILLISECONDS
    );
    final boolean reportsTheLoadError = failure.getMessage().contains("ERR_NAME_NOT_RESOLVED (code -105)");
    assertTrue(reportsTheLoadError, failure.getMessage());
  }

  @Test
  void shouldRefuseAPageWhoseAddressTheGuardRefused() {
    final BrowserException failure = this.openFails(
      ScriptedEngine.class.getName(),
      ScriptedEngine.REFUSED_PATH,
      START_TIMEOUT_MILLISECONDS
    );
    assertInstanceOf(PageRefusedException.class, failure);
  }

  @Test
  void shouldFailAsBeforeWhenTheGuardRefusedAnotherHost() {
    final BrowserException failure = this.openFails(
      ScriptedEngine.class.getName(),
      ScriptedEngine.REFUSED_ELSEWHERE_PATH,
      START_TIMEOUT_MILLISECONDS
    );
    final String message = failure.getMessage();
    final boolean refused = failure instanceof PageRefusedException;
    assertFalse(refused, message);

    final boolean reportsTheLoadError = message.contains("ERR_SOCKS_CONNECTION_FAILED (code -120)");
    assertTrue(reportsTheLoadError, message);
  }

  @Test
  void shouldFailTheStartWhenTheHelperExitsDuringIt() {
    final BrowserException failure = this.openFails(ScriptedEngine.class.getName(), ScriptedEngine.EXIT_PATH, START_TIMEOUT_MILLISECONDS);
    final boolean reportsTheExit =
      failure.getMessage().contains("exited with code 3") || failure.getMessage().contains("closed the connection");
    assertTrue(reportsTheExit, failure.getMessage());
  }

  @Test
  void shouldFailTheStartWhenTheBrowserCannotStart() {
    final BrowserException failure = this.openFails(ScriptedEngine.class.getName(), ScriptedEngine.THROW_PATH, START_TIMEOUT_MILLISECONDS);
    final boolean reportsTheStartFailure = failure.getMessage().contains("The scripted start failed!");
    assertTrue(reportsTheStartFailure, failure.getMessage());
  }

  @Test
  void shouldTimeOutWhenTheHelperNeverShowsThePage() {
    final BrowserException failure = this.openFails(ScriptedEngine.class.getName(), ScriptedEngine.NEVER_PATH, SHOW_TIMEOUT_MILLISECONDS);
    final boolean reportsTheTimeout = failure.getMessage().contains("did not show https://example.com/never in time");
    assertTrue(reportsTheTimeout, failure.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = { RawHelperMain.UNLOADED_PATH, RawHelperMain.UNPAINTED_PATH })
  void shouldTimeOutWhenThePageNeverLoadsOrNeverShowsAFrame(final String path) {
    final BrowserException failure = this.openFails(RawHelperMain.class.getName(), path, SHOW_TIMEOUT_MILLISECONDS);
    final String message = failure.getMessage();
    final String address = PAGE_URL_TEMPLATE.formatted(path);
    final String expected = "did not show %s in time".formatted(address);
    final boolean reportsTheTimeout = message.contains(expected);
    assertTrue(reportsTheTimeout, message);
  }

  @Test
  void shouldTimeOutWhenTheHelperNeverConnects() {
    final BrowserException failure = this.openFails(SilentMain.class.getName(), PAGE, 2_000L);
    final String message = failure.getMessage();
    assertEquals(CONNECT_TIMEOUT_MESSAGE, message);
  }

  @Test
  void shouldFailTheStartWhenTheProgramIsNotJava() throws IOException {
    final BrowserSource source = BrowserSource.uri(URI.create(PAGE_URL_TEMPLATE.formatted(PAGE)), 4, 3, 1);
    final HelperLauncher broken = new HelperLauncher(
      NATIVES.resolve(MISSING_JAVA_EXECUTABLE),
      ScriptedEngine.class.getName(),
      List.of(),
      List.of(),
      OsUtils.getOs(),
      System.getenv(),
      5_000L
    );
    final Path parent = this.shortDirectory();
    final Set<ProcessHandle> before = this.liveDescendants();
    final BrowserException failure = assertThrows(BrowserException.class, () -> this.openSessionIn(broken, source, parent));
    final boolean reportsTheStartFailure = failure.getMessage().endsWith(", the browser helper could not be started!");
    assertTrue(reportsTheStartFailure, failure.getMessage());

    final Set<ProcessHandle> newProcesses = this.newSince(before);
    assertEquals(Set.of(), newProcesses);

    try (final Stream<Path> left = Files.list(parent)) {
      final List<Path> remainingFiles = left.toList();
      assertEquals(List.of(), remainingFiles);
    }
  }

  @Test
  void shouldEndTheSessionWhenTheBrowserFailsLater() {
    this.open(ScriptedEngine.class.getName(), ScriptedEngine.FAIL_PATH);
    Await.until(SESSION_ENDED_MESSAGE, () -> !this.listener.ended.isEmpty());
    final List<String> expectedEndReasons = List.of(BROWSER_FAILED_MESSAGE);
    assertEquals(expectedEndReasons, this.listener.ended);
  }

  @Test
  void shouldRefuseAHelperWithAnotherToken() {
    final BrowserException failure = this.openFails(
      RawHelperMain.class.getName(),
      RawHelperMain.WRONG_TOKEN_PATH,
      START_TIMEOUT_MILLISECONDS
    );
    final boolean reportsTheWrongToken = failure.getMessage().contains("presented a wrong session token");
    assertTrue(reportsTheWrongToken, failure.getMessage());
  }

  @Test
  void shouldRefuseAHelperOfAnotherProtocolVersion() {
    final BrowserException failure = this.openFails(
      RawHelperMain.class.getName(),
      RawHelperMain.WRONG_VERSION_PATH,
      START_TIMEOUT_MILLISECONDS
    );
    final boolean reportsTheProtocolMismatch = failure
      .getMessage()
      .contains("Protocol version 2 of the browser helper differs from version 1 of the server!");
    assertTrue(reportsTheProtocolMismatch, failure.getMessage());
  }

  @Test
  void shouldRefuseAHelperThatDoesNotIntroduceItself() {
    final BrowserException failure = this.openFails(RawHelperMain.class.getName(), RawHelperMain.NO_HELLO_PATH, START_TIMEOUT_MILLISECONDS);
    final boolean reportsTheMissingIntroduction = failure.getMessage().contains("did not introduce itself");
    assertTrue(reportsTheMissingIntroduction, failure.getMessage());
  }

  @Test
  void shouldRefuseAFrameOfAnotherPage() {
    final BrowserException failure = this.openFails(
      RawHelperMain.class.getName(),
      RawHelperMain.WRONG_PAGE_PATH,
      START_TIMEOUT_MILLISECONDS
    );
    final boolean reportsTheFrameMismatch = failure.getMessage().contains("A frame of a 5x3 page arrived for a 4x3 page");
    assertTrue(reportsTheFrameMismatch, failure.getMessage());
  }

  @Test
  void shouldEndTheSessionOnAMessageOnlyTheServerSends() {
    this.open(RawHelperMain.class.getName(), RawHelperMain.SERVER_MESSAGE_PATH);
    Await.until(SESSION_ENDED_MESSAGE, () -> !this.listener.ended.isEmpty());
    final boolean reportsTheServerOnlyMessage = this.listener.ended
      .getFirst()
      .contains("sent message type 18, which only the server sends");
    assertTrue(reportsTheServerOnlyMessage, this.listener.ended.getFirst());
  }

  @Test
  void shouldDropInputForAHelperThatStopsReadingOnceTheQueueIsFull() {
    final HelperSession session = this.open(RawHelperMain.class.getName(), RawHelperMain.STALL_PATH);
    final String text = REPEATED_TEXT_CHARACTER.repeat(HelperProtocol.MAXIMUM_TEXT_BYTES);
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
    final HelperSession session = this.open(RawHelperMain.class.getName(), RawHelperMain.STUBBORN_PATH);
    final Process process = session.getProcess();
    session.close();
    final boolean alive = process.isAlive();
    assertFalse(alive);
  }

  @Test
  void shouldFailTheStartWhenTheHelperExitsBeforeItConnects() {
    final BrowserException failure = this.openFails(RawHelperMain.class.getName(), RawHelperMain.EXIT_PATH, START_TIMEOUT_MILLISECONDS);
    final String message = failure.getMessage();
    assertEquals("The browser helper exited with code 4 before it connected!", message);
  }

  @Test
  void shouldNameWhatTheHelperPrintedWhenItExitsBeforeItConnects() {
    final String missingMainClass = "me.brandonli.mcav.browser.MissingHelperMain";
    final BrowserException failure = this.openFails(missingMainClass, RawHelperMain.EXIT_PATH, START_TIMEOUT_MILLISECONDS);
    final String message = failure.getMessage();
    final List<String> lines = message.lines().toList();
    final String first = lines.getFirst();
    assertEquals("The browser helper exited with code 1 before it connected!", first);

    final String missingClassText = "Could not find or load main class %s".formatted(missingMainClass);
    final boolean namesTheMissingClass = lines.stream().anyMatch(line -> line.contains(missingClassText));
    assertTrue(namesTheMissingClass, message);
  }

  @Test
  void shouldRefuseAHelperThatSendsAFrameBeforeItsHello() {
    final BrowserException failure = this.openFails(
      RawHelperMain.class.getName(),
      RawHelperMain.FRAME_FIRST_PATH,
      START_TIMEOUT_MILLISECONDS
    );
    final boolean reportsTheEarlyFrame = failure.getMessage().contains("arrived where no frame of that size is expected");
    assertTrue(reportsTheEarlyFrame, failure.getMessage());
  }

  @Test
  void shouldGiveAHelperTheLibrariesItsLauncherLinksIntoTheSession() {
    final List<Path> linked = new CopyOnWriteArrayList<>();
    final HelperLauncher base = launcher(ScriptedEngine.class.getName(), START_TIMEOUT_MILLISECONDS);
    final HelperLauncher launcher = base.withLibraries(session -> this.createLibraries(session, linked));
    final BrowserSource source = BrowserSource.uri(URI.create(PAGE_URL_TEMPLATE.formatted(PAGE)), 4, 3, 1);
    final HelperSession session = HelperSession.open(launcher, NATIVES, source, BrowserOptions.DEFAULT, this.listener);
    this.sessions.add(session);
    Await.until(FIRST_FRAME_MESSAGE, () -> !this.listener.frames.isEmpty());
    final List<Path> expectedLinkedFolders = List.of(session.getFolder().resolve(LIBRARIES_FOLDER));
    assertEquals(expectedLinkedFolders, linked);

    final boolean librariesLinked = Files.isDirectory(linked.getFirst());
    assertTrue(librariesLinked);

    session.close();
    final boolean librariesLeft = Files.exists(linked.getFirst());
    assertFalse(librariesLeft);
  }

  private Path createLibraries(final Path session, final List<Path> linked) throws IOException {
    final Path libraries = Files.createDirectory(session.resolve(LIBRARIES_FOLDER));
    linked.add(libraries);

    return libraries;
  }

  @Test
  void shouldFailTheStartAndLeaveNothingBehindWhenLibrariesCannotBeLinked() throws IOException {
    final HelperLauncher base = launcher(ScriptedEngine.class.getName(), START_TIMEOUT_MILLISECONDS);
    final HelperLauncher launcher = base.withLibraries(_ -> this.failToLinkLibraries());
    final BrowserSource source = BrowserSource.uri(URI.create(PAGE_URL_TEMPLATE.formatted(PAGE)), 4, 3, 1);
    final Path parent = this.shortDirectory();
    final BrowserException failure = assertThrows(BrowserException.class, () -> this.openSessionIn(launcher, source, parent));
    final String message = failure.getMessage();
    assertEquals("Because of \"The disk has no space left!\", the browser helper could not be started!", message);

    try (final Stream<Path> left = Files.list(parent)) {
      final List<Path> remainingFiles = left.toList();
      assertEquals(List.of(), remainingFiles);
    }
  }

  private Path failToLinkLibraries() throws IOException {
    final String message = "The disk has no space left!";
    throw new IOException(message);
  }

  @Test
  void shouldRefuseARegionLargerThanThePageBeforeItsPixelsAreRead() {
    final BrowserException failure = this.openFails(
      RawHelperMain.class.getName(),
      RawHelperMain.OVERSIZED_PATH,
      START_TIMEOUT_MILLISECONDS
    );
    final boolean reportsTheOversizedFrame = failure
      .getMessage()
      .contains("A frame of 60 bytes arrived where no frame of that size is expected");
    assertTrue(reportsTheOversizedFrame, failure.getMessage());
  }

  @Test
  void shouldDeliverATextLongerThanOneMessageWhole() {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), PAGE);
    Await.until(FIRST_FRAME_MESSAGE, () -> !this.listener.blues().isEmpty());
    final boolean textAccepted = session.sendKey(HelperProtocol.KEY_TYPE, INPUT_KEY.repeat(5_000));
    assertTrue(textAccepted);

    Await.until("every character typed", () -> this.listener.inputCounts().contains(10_000));
    final Integer lastInputCount = this.listener.inputCounts().getLast();
    assertEquals(10_000, lastInputCount);
  }

  @Test
  void shouldStopAHelperThatNeverConnectsAtOnceWhenItsStartTimesOut() {
    final long start = System.nanoTime();
    final BrowserException failure = this.openFails(
      RawHelperMain.class.getName(),
      RawHelperMain.SILENT_PATH,
      CONNECTION_FAILURE_TIMEOUT_MILLISECONDS
    );
    final boolean reportsTheTimeout = failure.getMessage().contains(CONNECT_TIMEOUT_MESSAGE);
    assertTrue(reportsTheTimeout, failure.getMessage());

    final long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
    final boolean stoppedQuickly = seconds < 8;
    assertTrue(stoppedQuickly, "the failed start took " + seconds + " s");
  }

  @Test
  void shouldKillAProcessTheHelperStartsWhileItDoesNotStopTogetherWithIt() throws InterruptedException {
    final HelperSession session = this.open(RawHelperMain.class.getName(), RawHelperMain.STUBBORN_CHILD_PATH);
    final Process process = session.getProcess();
    // watched while the session closes: once the helper is gone, its processes are no descendants of it
    final Set<ProcessHandle> started = ConcurrentHashMap.newKeySet();
    final AtomicBoolean closing = new AtomicBoolean(true);
    final Thread watcher = new Thread(() -> this.collectDescendantsWhileClosing(process, started, closing));
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

    Await.until(NO_CHILD_PROCESS_MESSAGE, () -> started.stream().noneMatch(ProcessHandle::isAlive));
  }

  private void collectDescendantsWhileClosing(final Process process, final Set<ProcessHandle> started, final AtomicBoolean closing) {
    while (closing.get()) {
      try (final Stream<ProcessHandle> descendants = process.descendants()) {
        descendants.forEach(started::add);
      }
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
    }
  }

  @Test
  void shouldStartNoHelperWhileTheModuleIsStopped() {
    HelperProcesses.closeAll();
    try {
      final BrowserException failure = this.openFails(ScriptedEngine.class.getName(), PAGE, START_TIMEOUT_MILLISECONDS);
      final String message = failure.getMessage();
      assertEquals("The browser module is stopped!", message);
    } finally {
      HelperProcesses.open();
    }

    final boolean restartedHelperAlive = this.open(ScriptedEngine.class.getName(), PAGE).isAlive();
    assertTrue(restartedHelperAlive, "a started module lets helpers start again");
  }

  @Test
  void shouldEndEveryHelperWhenTheModuleStopsEvenWhenTheirListenersFail() {
    final BrowserSource source = BrowserSource.uri(URI.create(PAGE_URL_TEMPLATE.formatted(PAGE)), 4, 3, 1);
    final BrowserSession.Listener failing = new BrowserSession.Listener() {
      @Override
      public void onFrame(final ImageBuffer frame) {
        frame.close();
      }

      @Override
      public void onAudio(final byte[] samples) {}

      @Override
      public void onEnded(final String reason, final Throwable failure) {
        final String message = "Because of \"%s\", the listener failed as the test asked!".formatted(reason);
        throw new IllegalStateException(message);
      }
    };
    final HelperLauncher launcher = launcher(ScriptedEngine.class.getName(), START_TIMEOUT_MILLISECONDS);
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
    final Throwable[] suppressed = failure.getSuppressed();
    assertEquals("Because of \"The browser module was stopped!\", the listener failed as the test asked!", message);
    assertEquals(1, suppressed.length, "the failure of the other listener goes along");

    final boolean firstAlive = first.isAlive();
    assertFalse(firstAlive, "the first helper ended");

    final boolean secondAlive = second.isAlive();
    assertFalse(secondAlive, "the second helper ended");

    final int helperCount = HelperProcesses.count();
    assertEquals(0, helperCount);
  }

  @Test
  void shouldEndAHelperThatConnectsAfterTheModuleStoppedAndFailItsStart() throws IOException {
    final Path gate = this.directory.resolve(GATE_FILE);
    final HelperLauncher gated = launcher(
      GatedHelperMain.class.getName(),
      START_TIMEOUT_MILLISECONDS,
      OsUtils.getOs(),
      List.of(SYSTEM_PROPERTY_OPTION + GatedHelperMain.GATE_PROPERTY + PROPERTY_VALUE_SEPARATOR + gate)
    );
    final BrowserSource source = BrowserSource.uri(URI.create(PAGE_URL_TEMPLATE.formatted(PAGE)), 4, 3, 1);
    final CompletableFuture<HelperSession> opening = CompletableFuture.supplyAsync(() -> this.openSession(gated, source));
    final Path fileName = gate.getFileName();
    final String startedName = STARTED_FILE_TEMPLATE.formatted(fileName, GatedHelperMain.STARTED_SUFFIX);
    final Path started = gate.resolveSibling(startedName);
    try {
      Await.until(HELPER_RUNNING_MESSAGE, () -> Files.exists(started));
      HelperProcesses.closeAll();
      Files.createFile(gate);
      final ExecutionException failure = assertThrows(ExecutionException.class, () -> opening.get(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS));
      final Throwable wrapped = failure.getCause();
      final BrowserException cause = assertInstanceOf(BrowserException.class, wrapped);
      final String message = cause.getMessage();
      assertEquals(MODULE_STOPPED_MESSAGE, message);
    } finally {
      HelperProcesses.open();
    }

    final int helperCount = HelperProcesses.count();
    assertEquals(0, helperCount);

    Await.until(HELPER_ENDED_MESSAGE, this::isGatedHelperGone);
  }

  private boolean isGatedHelperGone() {
    return ProcessHandle.current().descendants().noneMatch(this::isGatedHelper);
  }

  private boolean isGatedHelper(final ProcessHandle process) {
    return process.info().commandLine().orElse("").contains(GatedHelperMain.class.getName());
  }

  @Test
  void shouldEndAHelperThatConnectsAfterTheModuleStoppedAndStartedAgainAndFailItsStart() throws IOException {
    final Path gate = this.directory.resolve(RESTART_GATE_FILE);
    final HelperLauncher gated = launcher(
      GatedHelperMain.class.getName(),
      START_TIMEOUT_MILLISECONDS,
      OsUtils.getOs(),
      List.of(SYSTEM_PROPERTY_OPTION + GatedHelperMain.GATE_PROPERTY + PROPERTY_VALUE_SEPARATOR + gate)
    );
    final BrowserSource source = BrowserSource.uri(URI.create(PAGE_URL_TEMPLATE.formatted(PAGE)), 4, 3, 1);
    final CompletableFuture<HelperSession> opening = CompletableFuture.supplyAsync(() -> this.openSession(gated, source));
    final Path fileName = gate.getFileName();
    final String startedName = STARTED_FILE_TEMPLATE.formatted(fileName, GatedHelperMain.STARTED_SUFFIX);
    final Path started = gate.resolveSibling(startedName);
    Await.until(HELPER_RUNNING_MESSAGE, () -> Files.exists(started));
    HelperProcesses.closeAll();
    HelperProcesses.open();
    Files.createFile(gate);
    final ExecutionException failure = assertThrows(ExecutionException.class, () -> opening.get(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    final Throwable wrapped = failure.getCause();
    final BrowserException cause = assertInstanceOf(BrowserException.class, wrapped);
    final String message = cause.getMessage();
    assertEquals(MODULE_STOPPED_MESSAGE, message);

    final int helperCount = HelperProcesses.count();
    assertEquals(0, helperCount);
  }

  @Test
  void shouldFailAnInterruptedStartAndLeaveNothingBehind() throws InterruptedException, IOException {
    final Set<ProcessHandle> before = this.liveDescendants();
    final BrowserSource source = BrowserSource.uri(URI.create(PAGE_URL_TEMPLATE.formatted(ScriptedEngine.NEVER_PATH)), 4, 3, 1);
    final HelperLauncher launcher = launcher(ScriptedEngine.class.getName(), START_TIMEOUT_MILLISECONDS);
    final Path parent = this.shortDirectory();
    final AtomicReference<Throwable> thrown = new AtomicReference<>();
    final AtomicBoolean stillInterrupted = new AtomicBoolean();
    final Thread starter = new Thread(() -> this.openAndRecordTheFailure(launcher, source, parent, thrown, stillInterrupted));
    starter.start();
    Await.until(HELPER_RUNNING_MESSAGE, () -> HelperProcesses.count() == 1);
    starter.interrupt();
    starter.join(30_000L);
    final String message = thrown.get().getMessage();
    assertEquals("Interrupted while starting the browser!", message);

    final boolean interruptKept = stillInterrupted.get();
    assertTrue(interruptKept, "the interrupt is kept");

    final int helperCount = HelperProcesses.count();
    assertEquals(0, helperCount);

    try (final Stream<Path> left = Files.list(parent)) {
      final List<Path> remainingFiles = left.toList();
      assertEquals(List.of(), remainingFiles);
    }

    Await.until(HELPER_ENDED_MESSAGE, () -> this.newSince(before).isEmpty());
  }

  private void openAndRecordTheFailure(
    final HelperLauncher launcher,
    final BrowserSource source,
    final Path parent,
    final AtomicReference<Throwable> thrown,
    final AtomicBoolean stillInterrupted
  ) {
    try {
      this.openSessionIn(launcher, source, parent);
    } catch (final BrowserException exception) {
      thrown.set(exception);
      stillInterrupted.set(Thread.currentThread().isInterrupted());
    }
  }

  @Test
  void shouldEndTheSessionOnceOnInputForAHelperThatHungUp() {
    final HelperSession session = this.open(RawHelperMain.class.getName(), RawHelperMain.HANG_UP_PATH);
    Await.until("the end of the connection", () -> !this.listener.ended.isEmpty());
    final boolean keyAccepted = session.sendKey(HelperProtocol.KEY_TYPE, LATE_INPUT_KEY);
    assertTrue(keyAccepted);

    Await.until("the input was tried", () -> session.getCompletedInputCount() == 1);
    final List<String> expectedEndReasons = List.of("The browser helper closed the connection!");
    assertEquals(expectedEndReasons, this.listener.ended);
  }

  @Test
  void shouldWaitForAHelperThatHungUpToExitInsteadOfSpinning() {
    final HelperSession session = this.open(RawHelperMain.class.getName(), RawHelperMain.HANG_UP_PATH);
    final List<Thread> threads = session.getThreads();
    final Thread reader = threads.getFirst();
    Await.until("the reader waits for the helper to exit", () -> reader.getState() == Thread.State.TIMED_WAITING);
  }

  @Test
  void shouldNotReportASecondEnd() {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), ScriptedEngine.FAIL_PATH);
    Await.until(SESSION_ENDED_MESSAGE, () -> !this.listener.ended.isEmpty());
    Await.until("the helper exited", () -> !session.isAlive());
    final List<String> expectedEndReasons = List.of(BROWSER_FAILED_MESSAGE);
    assertEquals(expectedEndReasons, this.listener.ended);
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
      public void onEnded(final String reason, final Throwable failure) {
        self.get().close();
        closed.countDown();
      }
    };
    final BrowserSource source = BrowserSource.uri(URI.create(PAGE_URL_TEMPLATE.formatted(ScriptedEngine.FAIL_PATH)), 4, 3, 1);
    final HelperSession session = HelperSession.open(
      launcher(ScriptedEngine.class.getName(), START_TIMEOUT_MILLISECONDS),
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
    final HelperSession session = this.open(ScriptedEngine.class.getName(), PAGE);
    final Process process = session.getProcess();
    final Thread current = Thread.currentThread();
    current.interrupt();
    try {
      session.close();
      final boolean interruptKept = current.isInterrupted();
      assertTrue(interruptKept);
    } finally {
      Thread.interrupted();
    }

    Await.until(HELPER_ENDED_MESSAGE, () -> !process.isAlive());
    final int helperCount = HelperProcesses.count();
    assertEquals(0, helperCount);
  }

  @Test
  void shouldEndAnInterruptedDeliveryThreadAndDeliverNothingFromAClosedCanvas() throws InterruptedException {
    final HelperSession first = this.open(ScriptedEngine.class.getName(), PAGE);
    final Thread delivery = first.getDeliveryThread();
    delivery.interrupt();
    delivery.join(10_000L);
    final boolean deliveryAlive = delivery.isAlive();
    assertFalse(deliveryAlive);

    final boolean deliveryInterrupted = delivery.isInterrupted();
    assertTrue(deliveryInterrupted);

    first.close();
    final HelperSession second = this.open(ScriptedEngine.class.getName(), PAGE);
    second.getCanvas().close();
    second.requestFrame();
    second.getDeliveryThread().join(10_000L);
    final boolean secondDeliveryAlive = second.getDeliveryThread().isAlive();
    assertFalse(secondDeliveryAlive, "a delivery without a picture ends");
  }

  @Test
  void shouldKeepTheOutputOfTheHelperLineByLineBoundedAndUntilItFails() {
    final HelperSession session = this.open(ScriptedEngine.class.getName(), PAGE);
    final StringBuilder output = new StringBuilder();
    for (int line = 0; line < 50; line++) {
      output.append("line ").append(line).append("\r\n");
    }

    output.append(REPEATED_TEXT_CHARACTER.repeat(3000));
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
          throw new IOException(HELPER_GONE_MESSAGE);
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException {
          throw new IOException(HELPER_GONE_MESSAGE);
        }
      }
    );
    final String outputTail = session.getOutputTail();
    final int tailLineCount = outputTail.split(System.lineSeparator()).length;
    assertEquals(40, tailLineCount);
  }

  @Test
  void shouldCreateTheFolderOfASessionOnEveryFileSystem() throws IOException {
    final Path folder = HelperSession.createFolder(this.directory);
    final boolean folderCreated = Files.isDirectory(folder);
    assertTrue(folderCreated);

    if (this.directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      final Set<PosixFilePermission> folderPermissions = Files.getPosixFilePermissions(folder);
      final Set<PosixFilePermission> expectedFolderPermissions = PosixFilePermissions.fromString("rwx------");
      assertEquals(expectedFolderPermissions, folderPermissions);
    }

    final Path zip = this.directory.resolve(FOLDERS_ARCHIVE_FILE);
    try (final FileSystem zipped = FileSystems.newFileSystem(zip, Map.of("create", "true"))) {
      final Path root = zipped.getPath(ROOT_PATH);
      final Path created = HelperSession.createFolder(root);
      final boolean plainFolderCreated = Files.isDirectory(created);
      assertTrue(plainFolderCreated, "a file system without POSIX permissions gets a plain folder");
    }

    final Path missingParent = this.directory.resolve(MISSING_FOLDER);
    final BrowserException missing = assertThrows(BrowserException.class, () -> HelperSession.createFolder(missingParent));
    final boolean reportsTheMissingParent = missing.getMessage().endsWith(", the folder of the browser session cannot be created!");
    assertTrue(reportsTheMissingParent, missing.getMessage());
  }

  @Test
  void shouldGiveASessionFolderAShortRandomNameAndDrawATakenNameAgain() throws IOException {
    final Path folder = HelperSession.createFolder(this.directory);
    final boolean shortRandomName = SESSION_FOLDER_NAME.matcher(folder.getFileName().toString()).matches();
    assertTrue(shortRandomName, folder.toString());

    Files.createDirectory(this.directory.resolve(TAKEN_SESSION_FOLDER));
    final Iterator<String> names = List.of(TAKEN_SESSION_FOLDER, FREE_SESSION_FOLDER).iterator();
    final Path freeFolder = HelperSession.createFolder(this.directory, names::next);
    final Path expectedFreeFolder = this.directory.resolve(FREE_SESSION_FOLDER);
    assertEquals(expectedFreeFolder, freeFolder);

    final AtomicInteger drawn = new AtomicInteger();
    final Path last = HelperSession.createFolder(this.directory, () ->
      drawn.incrementAndGet() < 100 ? TAKEN_SESSION_FOLDER : LAST_SESSION_FOLDER
    );
    final Path expectedLastFolder = this.directory.resolve(LAST_SESSION_FOLDER);
    assertEquals(expectedLastFolder, last);

    drawn.set(0);
    final BrowserException exhausted = assertThrows(BrowserException.class, () -> this.createFolderFromTakenNames(drawn));
    final boolean reportsEveryNameTaken = exhausted.getMessage().startsWith("Because of \"Every name drawn was taken!\", ");
    assertTrue(reportsEveryNameTaken, exhausted.getMessage());

    final int drawCount = drawn.get();
    assertEquals(100, drawCount);
  }

  private Path createFolderFromTakenNames(final AtomicInteger drawn) {
    return HelperSession.createFolder(this.directory, () -> this.drawATakenName(drawn));
  }

  private String drawATakenName(final AtomicInteger drawn) {
    drawn.incrementAndGet();

    return TAKEN_SESSION_FOLDER;
  }

  @Test
  void shouldRefuseATemporaryFolderTooLongForChromiumsSocketOnLinuxWithTheReason() {
    // the longest temporary folder, 47 characters: Chromium's socket path has 107, the most a path may have
    HelperSession.requireShortEnough(Os.LINUX, Path.of(ROOT_PATH + LONG_FOLDER_CHARACTER.repeat(46), BOUNDARY_SESSION_FOLDER));
    final Path folder = Path.of(ROOT_PATH + LONG_FOLDER_CHARACTER.repeat(47));
    final Path tooLong = folder.resolve(BOUNDARY_SESSION_FOLDER);
    final BrowserException refused = assertThrows(BrowserException.class, () -> HelperSession.requireShortEnough(Os.LINUX, tooLong));
    final String message = refused.getMessage();
    final String expectedMessage =
      TEMPORARY_FOLDER_MESSAGE_PREFIX +
      folder +
      " is too long for the browser: on Linux it may have at most 47 characters, so start the server with a shorter" +
      " java.io.tmpdir (such as /tmp)!";
    assertEquals(expectedMessage, message);

    HelperSession.requireShortEnough(Os.WINDOWS, tooLong);
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void shouldNotStartAHelperInATemporaryFolderTooLongForChromiumAndRemoveItsFolder() throws IOException {
    final Path temporary = Files.createDirectories(
      this.directory.resolve(LONG_FOLDER_CHARACTER.repeat(70 - this.directory.toString().length()))
    );
    final BrowserSource source = BrowserSource.uri(URI.create(PAGE_URL_TEMPLATE.formatted(PAGE)), 4, 3, 1);
    final HelperLauncher launcher = launcher(ScriptedEngine.class.getName(), START_TIMEOUT_MILLISECONDS, Os.LINUX);
    final BrowserException refused = assertThrows(BrowserException.class, () -> this.openSessionIn(launcher, source, temporary));
    final boolean reportsTheLongFolder = refused.getMessage().startsWith(TEMPORARY_FOLDER_MESSAGE_PREFIX + temporary + " is too long");
    assertTrue(reportsTheLongFolder, refused.getMessage());

    try (final Stream<Path> left = Files.list(temporary)) {
      final List<Path> remainingFiles = left.toList();
      assertEquals(List.of(), remainingFiles, "the folder of the session is removed");
    }
  }

  @Test
  void shouldNotBindASocketPathThatIsTaken() throws IOException {
    final Path taken = Files.createFile(this.directory.resolve(SOCKET_FILE));
    assertThrows(IOException.class, () -> HelperSession.bind(taken).close());

    OpenFiles.assertLeavesNoneOpen("a failed bind", () -> assertThrows(IOException.class, () -> HelperSession.bind(taken).close()));
  }

  @Test
  void shouldOnlyLogFailuresWhenClosingAndDeletingQuietly() throws IOException {
    final AtomicInteger calls = new AtomicInteger();
    HelperSession.closeQuietly(() -> this.countAndFailClosing(calls));
    final int callCount = calls.get();
    assertEquals(1, callCount);

    try (final FileSystem memory = Jimfs.newFileSystem(Configuration.unix())) {
      final Path unremovable = memory.getPath(ROOT_PATH);
      HelperSession.deleteFolder(unremovable);
      final boolean folderKept = Files.isDirectory(unremovable);
      assertTrue(folderKept, "a folder that cannot be removed stays");
    }
  }

  private void countAndFailClosing(final AtomicInteger calls) throws IOException {
    calls.incrementAndGet();
    final String message = "The connection is already closed!";
    throw new IOException(message);
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

  public static final class SilentMain {

    private SilentMain() {}

    public static void main(final String[] args) throws IOException {
      while (System.in.read() >= 0) {}
    }
  }

  static final class RecordingListener implements BrowserSession.Listener {

    private final List<ImageBuffer> frames = new CopyOnWriteArrayList<>();
    private final List<byte[]> sound = new CopyOnWriteArrayList<>();
    private final List<Long> frameNanoseconds = new CopyOnWriteArrayList<>();
    private final List<String> ended = new CopyOnWriteArrayList<>();
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();

    private List<Integer> inputCounts() {
      final List<Integer> counts = new ArrayList<>();
      for (final ImageBuffer frame : this.frames) {
        final ByteBuffer pixels = frame.getData();
        final int low = pixels.get(0) & CHANNEL_MASK;
        final int high = (pixels.get(1) & CHANNEL_MASK) ^ ScriptedEngine.GREEN;
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
      this.frameNanoseconds.add(System.nanoTime());
      this.frames.add(frame);
    }

    @Override
    public void onAudio(final byte[] samples) {
      this.sound.add(samples);
    }

    @Override
    public void onEnded(final String reason, final Throwable failure) {
      this.ended.add(reason);
      this.failures.add(failure);
    }
  }

  // holds the first sound like a slow server
  private static final class SoundHoldingListener implements BrowserSession.Listener {

    private final CountDownLatch holding;

    SoundHoldingListener() {
      this.holding = new CountDownLatch(1);
    }

    boolean awaitHolding() throws InterruptedException {
      return this.holding.await(START_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onFrame(final ImageBuffer frame) {
      frame.close();
    }

    @Override
    public void onAudio(final byte[] samples) {
      if (this.holding.getCount() == 0) {
        return;
      }

      this.holding.countDown();
      this.hold();
    }

    private void hold() {
      try {
        Thread.sleep(SOUND_HOLD_MILLISECONDS);
      } catch (final InterruptedException exception) {
        final Thread current = Thread.currentThread();
        current.interrupt();
      }
    }

    @Override
    public void onEnded(final String reason, final Throwable failure) {}
  }

  // holds the first picture like a slow server
  private static final class PictureHoldingListener implements BrowserSession.Listener {

    private final CountDownLatch holding;
    private final CountDownLatch released;
    private final List<Integer> blues;

    PictureHoldingListener() {
      this.holding = new CountDownLatch(1);
      this.released = new CountDownLatch(1);
      this.blues = new CopyOnWriteArrayList<>();
    }

    boolean awaitHolding() throws InterruptedException {
      return this.holding.await(START_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
    }

    void release() {
      this.released.countDown();
    }

    @Override
    public void onFrame(final ImageBuffer frame) {
      final int frameBlue = blue(frame);
      frame.close();
      this.blues.add(frameBlue);

      if (this.holding.getCount() == 0) {
        return;
      }

      this.holding.countDown();
      this.hold();
    }

    private void hold() {
      try {
        this.released.await();
      } catch (final InterruptedException exception) {
        final Thread current = Thread.currentThread();
        current.interrupt();
      }
    }

    @Override
    public void onAudio(final byte[] samples) {}

    @Override
    public void onEnded(final String reason, final Throwable failure) {}
  }
}
