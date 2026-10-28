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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.builder.VideoPipelineStepBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.audio.DelayedAudioOutput;
import me.brandonli.mcav.utils.interaction.MouseClick;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.vm.testing.FreePorts;
import me.brandonli.mcav.vnc.VncException;
import me.brandonli.mcav.vnc.VncPlayer;
import me.brandonli.mcav.vnc.VncSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.verification.VerificationMode;

final class QemuVmPlayerTest {

  private static final String PASSWORD = "Pa55word";

  private static final int PORT = 5905;
  private static final int WIDTH = 320;
  private static final int HEIGHT = 240;
  private static final int FRAME_RATE = 15;

  private static final int TINY_WIDTH = 160;
  private static final int TINY_HEIGHT = 120;

  private static final long TIMEOUT_SECONDS = 10L;

  private static final int CHUNK_BYTES = 8;

  private static final long SOUND_HOLD_MILLISECONDS = 70L;

  private static final int MOVE_X = 1;
  private static final int MOVE_Y = 2;
  private static final int CLICK_X = 3;
  private static final int CLICK_Y = 4;
  private static final String KEY = "a";

  private static final String PATH_VARIABLE = "PATH";
  private static final String BIN_FOLDER = "bin";
  private static final String EMPTY_ROOT_FOLDER = "empty-root";

  private static final String AARCH64_COMMAND = "qemu-system-aarch64";
  private static final String RISCV64_COMMAND = "qemu-system-riscv64";

  private static final String REPORT_SEPARATOR = ": ";

  private static final String RELEASE_FAILURE = "VNC release failed!";
  private static final String TERMINATION_FAILURE = "process termination failed!";

  @TempDir
  private Path directory;

  private final List<Path> executables = new CopyOnWriteArrayList<>();
  private final List<VmSettings> createdSettings = new CopyOnWriteArrayList<>();
  private final List<VmConfiguration> createdConfigurations = new CopyOnWriteArrayList<>();

  private final List<VmAudioClient.Sink> sinks = new CopyOnWriteArrayList<>();
  private final List<InetSocketAddress> audioAddresses = new CopyOnWriteArrayList<>();
  private final List<String> audioPasswords = new CopyOnWriteArrayList<>();
  private final VmAudioClient audioClient = mock(VmAudioClient.class);
  private final QemuVmPlayer.AudioConnector audio = (address, password, sink, failures) -> {
    this.audioAddresses.add(address);
    this.audioPasswords.add(password);
    this.sinks.add(sink);
    return this.audioClient;
  };

  private VncPlayer vnc;
  private VmProcess qemu;
  private AtomicBoolean processAlive;
  private ExecutableFinder finder;

  @BeforeEach
  void createParts() throws IOException {
    this.vnc = mock(VncPlayer.class);
    this.qemu = mock(VmProcess.class);
    when(this.qemu.getPassword()).thenReturn(PASSWORD);
    final AtomicBoolean alive = new AtomicBoolean();
    this.processAlive = alive;
    when(this.qemu.isAlive()).thenAnswer(_ -> alive.get());
    doAnswer(_ -> {
      alive.set(true);
      return null;
    })
      .when(this.qemu)
      .start();
    doAnswer(_ -> {
      alive.set(false);
      return null;
    })
      .when(this.qemu)
      .shutdown();

    final Path bin = this.directory.resolve(BIN_FOLDER);
    this.installFakePrograms(bin);

    final String path = bin.toString();
    final Map<String, String> environment = Map.of(PATH_VARIABLE, path);
    final Path root = this.directory.resolve(EMPTY_ROOT_FOLDER);
    this.finder = new ExecutableFinder(Os.LINUX, environment, root);
  }

  private void installFakePrograms(final Path bin) throws IOException {
    Files.createDirectories(bin);
    final VmPlayer.Architecture[] architectures = VmPlayer.Architecture.values();
    for (final VmPlayer.Architecture architecture : architectures) {
      final String command = architecture.getCommand();
      final Path program = bin.resolve(command);
      Files.writeString(program, command);
      final File file = program.toFile();
      final boolean madeExecutable = file.setExecutable(true);
      assertTrue(madeExecutable, command);
    }
  }

  private void useFinderWithoutQemu() {
    final Path root = this.directory.resolve(EMPTY_ROOT_FOLDER);
    final Map<String, String> environment = Map.of(PATH_VARIABLE, "");
    this.finder = new ExecutableFinder(Os.LINUX, environment, root);
  }

  private QemuVmPlayer player() {
    final QemuVmPlayer.ProcessFactory factory = (settings, architecture, executable, configuration) -> {
      this.createdSettings.add(settings);
      this.executables.add(executable);
      this.createdConfigurations.add(configuration);
      return this.qemu;
    };
    return new QemuVmPlayer(this.vnc, this.finder, factory, this.audio);
  }

  private void stubConnectingStream() {
    when(this.vnc.start(any(VncSource.class))).thenReturn(true);
  }

  private boolean startDefaultMachine(final VmPlayer player) {
    final VmSettings settings = VmSettings.of(PORT, WIDTH, HEIGHT, FRAME_RATE);
    final VmConfiguration configuration = VmConfiguration.builder().build();
    return player.start(settings, VmPlayer.Architecture.X86_64, configuration);
  }

  private QemuVmPlayer startedPlayer() {
    this.stubConnectingStream();
    final QemuVmPlayer player = this.player();
    this.startDefaultMachine(player);
    return player;
  }

  private void sendInput(final VmPlayer player) {
    player.moveMouse(MOVE_X, MOVE_Y);
    player.sendMouseEvent(MouseClick.LEFT, CLICK_X, CLICK_Y);
    player.sendKeyEvent(KEY);
  }

  private void verifyInputForwarded(final VerificationMode mode) {
    verify(this.vnc, mode).moveMouse(MOVE_X, MOVE_Y);
    verify(this.vnc, mode).sendMouseEvent(MouseClick.LEFT, CLICK_X, CLICK_Y);
    verify(this.vnc, mode).sendKeyEvent(KEY);
  }

  @Test
  void shouldHandAPauseOfTheGuestsSoundToTheAudioPipelineAsSilence() {
    when(this.qemu.hasAudio()).thenReturn(true);
    final List<Integer> heard = new CopyOnWriteArrayList<>();
    final QemuVmPlayer player = this.startedPlayer();
    final AudioAttachableCallback audio = player.getAudioAttachableCallback();
    audio.attach(AudioPipelineStep.of((samples, metadata) -> heard.add(samples.remaining())));
    final VmAudioClient.Sink sink = this.sinks.getFirst();
    sink.accept(new byte[CHUNK_BYTES], CHUNK_BYTES);
    this.waitUntil(() -> heard.size() == 1);
    sink.quiet();
    this.waitUntil(() -> heard.size() >= 2);
    final int silenceLength = heard.get(1);
    assertEquals(3840, silenceLength, "20 ms of silence after the sound");

    player.release();
  }

  @Test
  void shouldHoldTheSoundOfTheGuestSoItWaitsForThePicture() {
    when(this.qemu.hasAudio()).thenReturn(true);
    final List<Long> handedOver = new CopyOnWriteArrayList<>();
    final QemuVmPlayer player = this.startedPlayer();
    final AudioAttachableCallback audio = player.getAudioAttachableCallback();
    audio.attach(AudioPipelineStep.of((samples, metadata) -> handedOver.add(System.nanoTime())));
    final VmAudioClient.Sink sink = this.sinks.getFirst();
    final long arrived = System.nanoTime();
    sink.accept(new byte[CHUNK_BYTES], CHUNK_BYTES);
    this.waitUntil(() -> !handedOver.isEmpty());

    final long handedOverAt = handedOver.getFirst();
    final long heldNanoseconds = handedOverAt - arrived;
    final long heldMilliseconds = TimeUnit.NANOSECONDS.toMillis(heldNanoseconds);
    assertTrue(heldMilliseconds >= SOUND_HOLD_MILLISECONDS, "the sound was held for %s ms".formatted(heldMilliseconds));

    player.release();
  }

  @Test
  void shouldHandTheSoundOfAMachineToTheAudioPipelineAndStopWhilePaused() {
    when(this.qemu.hasAudio()).thenReturn(true);
    when(this.vnc.pause()).thenReturn(true);
    when(this.vnc.resume()).thenReturn(true);
    when(this.vnc.isPlaying()).thenReturn(true);
    final List<Integer> heard = new CopyOnWriteArrayList<>();
    final QemuVmPlayer player = this.startedPlayer();
    final AudioAttachableCallback audio = player.getAudioAttachableCallback();
    audio.attach(AudioPipelineStep.of((samples, metadata) -> heard.add(samples.remaining())));
    final List<InetSocketAddress> expectedAddresses = List.of(new InetSocketAddress(VmProcess.LOOPBACK, PORT));
    final List<String> expectedPasswords = List.of(PASSWORD);
    assertEquals(expectedAddresses, this.audioAddresses);
    assertEquals(expectedPasswords, this.audioPasswords, "the sound connection sends the password of its machine");

    final VmAudioClient.Sink sink = this.sinks.getFirst();
    sink.accept(new byte[CHUNK_BYTES], CHUNK_BYTES);
    this.waitUntil(() -> heard.size() == 1);
    final boolean paused = player.pause();
    assertTrue(paused);

    sink.accept(new byte[CHUNK_BYTES], CHUNK_BYTES);
    final boolean resumed = player.resume();
    assertTrue(resumed);

    sink.accept(new byte[4], 4);
    this.waitUntil(() -> heard.size() == 2);
    final List<Integer> expected = List.of(CHUNK_BYTES, 4);
    assertEquals(expected, heard, "the sound of the paused machine was dropped");

    player.release();
    verify(this.audioClient).close();
  }

  @Test
  void shouldKeepPictureAndSoundTogetherWhenResumedDuringAPause() throws Exception {
    when(this.qemu.hasAudio()).thenReturn(true);
    final CountDownLatch insidePause = new CountDownLatch(1);
    final CountDownLatch finishPause = new CountDownLatch(1);
    when(this.vnc.pause()).thenAnswer(invocation -> {
      insidePause.countDown();
      finishPause.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      return true;
    });
    when(this.vnc.resume()).thenReturn(true);
    when(this.vnc.isPlaying()).thenReturn(true);
    final List<Integer> heard = new CopyOnWriteArrayList<>();
    final QemuVmPlayer player = this.startedPlayer();
    final AudioAttachableCallback audio = player.getAudioAttachableCallback();
    audio.attach(AudioPipelineStep.of((samples, metadata) -> heard.add(samples.remaining())));
    final CompletableFuture<Boolean> pausing = CompletableFuture.supplyAsync(player::pause);
    final boolean pauseEntered = insidePause.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(pauseEntered);

    final CompletableFuture<Boolean> resuming = CompletableFuture.supplyAsync(player::resume);
    Thread.sleep(200L);
    finishPause.countDown();
    final boolean paused = pausing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    final boolean resumed = resuming.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(paused);
    assertTrue(resumed);

    final VmAudioClient.Sink sink = this.sinks.getFirst();
    sink.accept(new byte[CHUNK_BYTES], CHUNK_BYTES);
    this.waitUntil(() -> heard.size() == 1);
    player.release();
  }

  @Test
  void shouldRunWithoutSoundWhenTheSoundCannotBeConnected() {
    when(this.qemu.hasAudio()).thenReturn(true);
    this.stubConnectingStream();
    final List<String> reports = new CopyOnWriteArrayList<>();
    when(this.vnc.getExceptionHandler()).thenReturn((message, failure) -> reports.add(message + REPORT_SEPARATOR + failure.getMessage()));
    final QemuVmPlayer player = new QemuVmPlayer(
      this.vnc,
      this.finder,
      (settings, architecture, executable, configuration) -> this.qemu,
      (address, password, sink, failures) -> {
        throw new IOException("connection refused!");
      }
    );
    final long outputsBefore = this.countOutputThreads();
    final boolean started = this.startDefaultMachine(player);
    final List<String> expectedReports = List.of(
      "The sound of the virtual machine could not be connected, it runs without sound: connection refused!"
    );
    final long outputsAfter = this.countOutputThreads();
    assertTrue(started);
    assertEquals(expectedReports, reports);
    assertEquals(outputsBefore, outputsAfter, "the output of the sound that never came is closed");

    when(this.vnc.pause()).thenReturn(true);
    final boolean paused = player.pause();
    assertTrue(paused, "a machine without sound pauses all the same");

    when(this.vnc.resume()).thenReturn(true);
    final boolean resumed = player.resume();
    assertTrue(resumed);

    player.release();
  }

  @Test
  void shouldNeverConnectToTheSoundOfAMachineWithoutSound() {
    final QemuVmPlayer player = this.startedPlayer();
    assertEquals(List.of(), this.sinks);

    final AudioAttachableCallback first = player.getAudioAttachableCallback();
    final AudioAttachableCallback second = player.getAudioAttachableCallback();
    assertSame(first, second);

    player.release();
  }

  private long countOutputThreads() {
    final Map<Thread, StackTraceElement[]> stackTraces = Thread.getAllStackTraces();
    final Set<Thread> threads = stackTraces.keySet();
    return threads
      .stream()
      .filter(thread -> thread.isAlive() && DelayedAudioOutput.THREAD_NAME.equals(thread.getName()))
      .count();
  }

  private void waitUntil(final BooleanSupplier condition) {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        fail("Condition did not hold in time!");
      }
      Thread.onSpinWait();
    }
  }

  @Test
  void shouldStartQemuAndStreamItsDisplay() {
    this.stubConnectingStream();
    final QemuVmPlayer player = this.player();
    final VmSettings settings = VmSettings.of(PORT, WIDTH, HEIGHT, FRAME_RATE);
    final VmConfiguration configuration = VmConfiguration.builder().memory(128).build();

    final boolean started = player.start(settings, VmPlayer.Architecture.AARCH64, configuration);

    assertTrue(started);
    verify(this.qemu).start();

    final Path executable = this.executables.getFirst();
    final Path fileName = executable.getFileName();
    final String name = fileName.toString();
    final VmSettings passedSettings = this.createdSettings.getFirst();
    final VmConfiguration passedConfiguration = this.createdConfigurations.getFirst();
    assertEquals(AARCH64_COMMAND, name);
    assertSame(settings, passedSettings);
    assertSame(configuration, passedConfiguration);
    this.assertStreamedWith(settings);
  }

  private void assertStreamedWith(final VmSettings settings) {
    final ArgumentCaptor<VncSource> captor = ArgumentCaptor.forClass(VncSource.class);
    verify(this.vnc).start(captor.capture());

    final VncSource source = captor.getValue();
    final String sourceHost = source.getHost();
    final int sourcePort = source.getPort();
    final int sourceWidth = source.getScreenWidth();
    final int sourceHeight = source.getScreenHeight();
    final int sourceFrameRate = source.getTargetFrameRate();
    final Optional<String> sourcePassword = source.getPassword();

    final int port = settings.getPort();
    final int width = settings.getWidth();
    final int height = settings.getHeight();
    final int frameRate = settings.getTargetFrameRate();
    final Optional<String> expectedPassword = Optional.of(PASSWORD);
    assertEquals("127.0.0.1", sourceHost);
    assertEquals(port, sourcePort);
    assertEquals(width, sourceWidth);
    assertEquals(height, sourceHeight);
    assertEquals(frameRate, sourceFrameRate);
    assertEquals(expectedPassword, sourcePassword, "the display asks for the password of its machine");
  }

  @Test
  void shouldPlayOnlyWhileQemuAndTheStreamAreRunning() {
    final QemuVmPlayer player = this.player();
    when(this.qemu.isAlive()).thenReturn(true);
    when(this.vnc.isPlaying()).thenReturn(true);
    final boolean beforeStart = player.isPlaying();
    assertFalse(beforeStart);

    this.stubConnectingStream();
    this.startDefaultMachine(player);
    final boolean running = player.isPlaying();
    when(this.vnc.isPlaying()).thenReturn(false);
    final boolean streamPaused = player.isPlaying();
    when(this.vnc.isPlaying()).thenReturn(true);
    when(this.qemu.isAlive()).thenReturn(false);
    final boolean qemuExited = player.isPlaying();
    assertTrue(running);
    assertFalse(streamPaused);
    assertFalse(qemuExited);

    player.release();
    when(this.qemu.isAlive()).thenReturn(true);
    final boolean playingAfterRelease = player.isPlaying();
    assertFalse(playingAfterRelease);
  }

  @Test
  void shouldRefuseToStartTwiceOrAfterRelease() {
    final QemuVmPlayer player = this.startedPlayer();
    final VmSettings settings = VmSettings.of(PORT + 1, WIDTH, HEIGHT, FRAME_RATE);
    final VmConfiguration configuration = VmConfiguration.builder().build();
    final boolean startedAgain = player.start(settings, VmPlayer.Architecture.X86_64, configuration);
    assertFalse(startedAgain);

    final boolean released = player.release();
    final boolean releasedAgain = player.release();
    final boolean startedAfterRelease = player.start(settings, VmPlayer.Architecture.X86_64, configuration);
    assertTrue(released);
    assertFalse(releasedAgain);
    assertFalse(startedAfterRelease);
    verify(this.qemu, times(1)).start();
    verify(this.vnc, times(1)).release();
    verify(this.qemu, times(1)).shutdown();
  }

  @Test
  void shouldFailWhenQemuIsNotInstalled() {
    this.useFinderWithoutQemu();
    final QemuVmPlayer player = this.player();
    final VmSettings settings = VmSettings.of(PORT, WIDTH, HEIGHT, FRAME_RATE);
    final VmConfiguration configuration = VmConfiguration.builder().build();

    final ExecutableNotInPathException exception = assertThrows(ExecutableNotInPathException.class, () ->
      player.start(settings, VmPlayer.Architecture.RISCV64, configuration)
    );

    final String message = exception.getMessage();
    final boolean nothingCreated = this.executables.isEmpty();
    final boolean messageContains = message.contains(RISCV64_COMMAND);
    assertTrue(messageContains, message);
    assertTrue(nothingCreated);
    verify(this.vnc, never()).start(any(VncSource.class));
  }

  @Test
  void shouldReportAMissingQemuAsAnExceptionThatCatchAllHandlersSee() {
    this.useFinderWithoutQemu();
    final QemuVmPlayer player = this.player();
    Exception caught = null;
    try {
      this.startDefaultMachine(player);
    } catch (final Exception exception) {
      caught = exception;
    }

    assertInstanceOf(ExecutableNotInPathException.class, caught);
  }

  @Test
  void shouldStopWhenQemuExitsAndStartAgain() {
    final VmProcess exited = this.qemu;
    final VmProcess next = mock(VmProcess.class);
    when(next.getPassword()).thenReturn(PASSWORD);
    when(next.isAlive()).thenReturn(true);
    final List<VmProcess> initialProcesses = List.of(exited, next);
    final List<VmProcess> processes = new CopyOnWriteArrayList<>(initialProcesses);
    final QemuVmPlayer.ProcessFactory factory = (_, _, _, _) -> processes.removeFirst();
    this.stubRunningStream();
    final QemuVmPlayer player = new QemuVmPlayer(this.vnc, this.finder, factory, this.audio);
    final boolean started = this.startDefaultMachine(player);
    final boolean playingBefore = player.isPlaying();
    assertTrue(started);
    assertTrue(playingBefore);

    when(exited.isAlive()).thenReturn(false);
    this.assertIgnoresInputAndPlayback(player);

    doAnswer(_ -> {
      this.assertIgnoresInputAndPlayback(player);
      return null;
    })
      .when(next)
      .start();
    final boolean restarted = this.startDefaultMachine(player);
    final boolean playingAgain = player.isPlaying();
    assertTrue(restarted);
    assertTrue(playingAgain);

    final InOrder order = inOrder(exited, next);
    order.verify(exited).start();
    order.verify(exited).shutdown();
    order.verify(next).start();
  }

  private void stubRunningStream() {
    this.stubConnectingStream();
    when(this.vnc.isPlaying()).thenReturn(true);
    when(this.vnc.pause()).thenReturn(true);
    when(this.vnc.resume()).thenReturn(true);
  }

  private void assertIgnoresInputAndPlayback(final QemuVmPlayer player) {
    final boolean playing = player.isPlaying();
    final boolean paused = player.pause();
    final boolean resumed = player.resume();
    this.sendInput(player);

    assertFalse(playing);
    assertFalse(paused);
    assertFalse(resumed);

    final VerificationMode none = never();
    this.verifyInputForwarded(none);
    verify(this.vnc, never()).pause();
  }

  @Test
  void shouldPassOnQemuStartFailures() {
    final VmException failure = new VmException("QEMU exited with code 1!");
    doThrow(failure).when(this.qemu).start();
    final QemuVmPlayer player = this.player();

    final VmException exception = assertThrows(VmException.class, () -> this.startDefaultMachine(player));

    final boolean playing = player.isPlaying();
    assertSame(failure, exception);
    assertFalse(playing);
    verify(this.vnc, never()).start(any(VncSource.class));
  }

  @Test
  void shouldShutQemuDownWhenTheStreamCannotStart() {
    when(this.vnc.start(any(VncSource.class))).thenReturn(false);
    final QemuVmPlayer player = this.player();

    final VmException exception = assertThrows(VmException.class, () -> this.startDefaultMachine(player));

    final String message = exception.getMessage();
    assertEquals("VNC player could not be started!", message);
    verify(this.qemu).shutdown();

    player.release();
    verify(this.qemu, times(1)).shutdown();
  }

  @Test
  void shouldShutQemuDownAndStartAgainWhenTheConnectionFails() {
    final VncException failure = new VncException("Failed to connect!");
    when(this.vnc.start(any(VncSource.class)))
      .thenThrow(failure)
      .thenReturn(true);
    final QemuVmPlayer player = this.player();

    final VncException exception = assertThrows(VncException.class, () -> this.startDefaultMachine(player));

    assertSame(failure, exception);
    verify(this.qemu).shutdown();

    final boolean started = this.startDefaultMachine(player);
    assertTrue(started);
    verify(this.qemu, times(2)).start();
  }

  @Test
  void shouldForwardInputOnlyWhileRunning() {
    final QemuVmPlayer player = this.player();
    this.sendInput(player);
    final VerificationMode none = never();
    this.verifyInputForwarded(none);

    this.stubConnectingStream();
    this.startDefaultMachine(player);
    this.sendInput(player);
    final VerificationMode once = times(1);
    this.verifyInputForwarded(once);

    player.release();
    this.sendInput(player);
    this.verifyInputForwarded(once);
  }

  @Test
  void shouldPauseAndResumeTheStreamWhileRunning() {
    final QemuVmPlayer player = this.player();
    when(this.vnc.pause()).thenReturn(true);
    when(this.vnc.resume()).thenReturn(true);
    final boolean pausedBeforeStart = player.pause();
    final boolean resumedBeforeStart = player.resume();
    assertFalse(pausedBeforeStart);
    assertFalse(resumedBeforeStart);
    verify(this.vnc, never()).pause();
    verify(this.vnc, never()).resume();

    this.stubConnectingStream();
    this.startDefaultMachine(player);
    final boolean paused = player.pause();
    final boolean resumed = player.resume();
    when(this.vnc.pause()).thenReturn(false);
    when(this.vnc.resume()).thenReturn(false);
    final boolean pausedAgain = player.pause();
    final boolean resumedAgain = player.resume();
    assertTrue(paused);
    assertTrue(resumed);
    assertFalse(pausedAgain);
    assertFalse(resumedAgain);
  }

  @Test
  void shouldReleaseTheStreamEvenIfNeverStarted() {
    final QemuVmPlayer player = this.player();
    final boolean released = player.release();
    assertTrue(released);
    verify(this.vnc).release();
    verify(this.qemu, never()).shutdown();
  }

  @Test
  void shouldShutQemuDownEvenWhenReleasingTheStreamFails() {
    final QemuVmPlayer player = this.startedPlayer();
    final IllegalStateException failure = new IllegalStateException("VNC cleanup failed!");
    doThrow(failure).when(this.vnc).release();
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, player::release);
    final boolean releasedAgain = player.release();
    final boolean playing = player.isPlaying();
    assertSame(failure, thrown);
    assertFalse(releasedAgain);
    assertFalse(playing);
    verify(this.qemu).shutdown();
    this.assertDoesNotBlock("a failed VNC release unlocks the player", () -> player.release());
  }

  @Test
  void shouldRetryAStillLiveMachineOnRepeatedReleaseWithoutChangingTheReturnContract() {
    final QemuVmPlayer player = this.startedPlayer();
    final AtomicBoolean alive = new AtomicBoolean(true);
    final AtomicInteger attempts = new AtomicInteger();
    when(this.qemu.isAlive()).thenAnswer(_ -> alive.get());
    doAnswer(_ -> {
      if (attempts.incrementAndGet() == 2) {
        alive.set(false);
      }
      return null;
    })
      .when(this.qemu)
      .shutdown();
    final boolean firstRelease = player.release();
    this.assertIgnoresInputAndPlayback(player);

    final boolean secondRelease = player.release();
    final boolean thirdRelease = player.release();
    assertTrue(firstRelease);
    assertFalse(secondRelease);
    assertFalse(thirdRelease);
    verify(this.qemu, times(2)).shutdown();
    verify(this.vnc).release();
  }

  @Test
  void shouldRefuseToReplaceAnUnstoppableFailedStartAndRetryBeforeLaunchingItsReplacement() {
    final VmProcess previous = this.qemu;
    final VmProcess next = mock(VmProcess.class);
    when(next.getPassword()).thenReturn(PASSWORD);
    when(next.isAlive()).thenReturn(true);
    final AtomicBoolean alive = new AtomicBoolean(true);
    final AtomicInteger attempts = new AtomicInteger();
    final AtomicInteger created = new AtomicInteger();
    when(previous.isAlive()).thenAnswer(_ -> alive.get());
    doAnswer(_ -> {
      if (attempts.incrementAndGet() == 3) {
        alive.set(false);
      }
      return null;
    })
      .when(previous)
      .shutdown();
    final QemuVmPlayer.ProcessFactory factory = (_, _, _, _) -> created.getAndIncrement() == 0 ? previous : next;
    final QemuVmPlayer player = new QemuVmPlayer(this.vnc, this.finder, factory, this.audio);
    final IllegalStateException connectionFailure = new IllegalStateException("VNC initialization failed!");
    when(this.vnc.start(any(VncSource.class)))
      .thenThrow(connectionFailure)
      .thenReturn(true);
    final IllegalStateException original = assertThrows(IllegalStateException.class, () -> this.startDefaultMachine(player));
    assertSame(connectionFailure, original);
    this.assertIgnoresInputAndPlayback(player);

    final VmException replacement = assertThrows(VmException.class, () -> this.startDefaultMachine(player));
    final String message = replacement.getMessage();
    final int owners = created.get();
    assertEquals("Previous QEMU process is still alive after its shutdown!", message);
    assertEquals(1, owners, "replacement cannot allocate a new owner while the old child survives");
    verify(next, never()).start();

    final boolean restarted = this.startDefaultMachine(player);
    final int ownersAfterRestart = created.get();
    assertTrue(restarted);
    assertEquals(2, ownersAfterRestart);

    final InOrder order = inOrder(previous, next);
    order.verify(previous).start();
    order.verify(previous, times(3)).shutdown();
    order.verify(next).start();
  }

  @Test
  void shouldRetainAChildThatSurvivesAPartiallyFailedQemuStart() {
    final QemuVmPlayer player = this.player();
    final VmException startFailure = new VmException("display startup timed out!");
    final AtomicBoolean alive = new AtomicBoolean(true);
    final AtomicInteger attempts = new AtomicInteger();
    when(this.qemu.isAlive()).thenAnswer(_ -> alive.get());
    doThrow(startFailure).when(this.qemu).start();
    doAnswer(_ -> {
      if (attempts.incrementAndGet() == 2) {
        alive.set(false);
      }
      return null;
    })
      .when(this.qemu)
      .shutdown();
    final VmException thrown = assertThrows(VmException.class, () -> this.startDefaultMachine(player));
    assertSame(startFailure, thrown);
    verify(this.qemu).shutdown();

    final boolean released = player.release();
    final boolean releasedAgain = player.release();
    assertTrue(released);
    assertFalse(releasedAgain);
    verify(this.qemu, times(2)).shutdown();
    verify(this.vnc, never()).start(any(VncSource.class));
  }

  @Test
  void shouldPreserveTheVncFailureAndRetryFailedProcessCleanupOnRelease() {
    final QemuVmPlayer player = this.startedPlayer();
    final IllegalStateException primary = new IllegalStateException(RELEASE_FAILURE);
    final IllegalArgumentException cleanup = new IllegalArgumentException(TERMINATION_FAILURE);
    doThrow(primary).when(this.vnc).release();
    doThrow(cleanup).when(this.qemu).shutdown();
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, player::release);
    assertSame(primary, thrown);

    final Throwable[] expectedSuppressed = new Throwable[] { cleanup };
    final Throwable[] suppressed = thrown.getSuppressed();
    assertArrayEquals(expectedSuppressed, suppressed);

    when(this.qemu.isAlive()).thenReturn(false);
    doAnswer(_ -> null)
      .when(this.qemu)
      .shutdown();
    final boolean repeated = player.release();
    final boolean finished = player.release();
    assertFalse(repeated);
    assertFalse(finished);
    verify(this.qemu, times(2)).shutdown();
    verify(this.vnc).release();
  }

  @Test
  void shouldPreserveAStartFailureWhenItsProcessCleanupAlsoFails() {
    final QemuVmPlayer player = this.player();
    final VncException primary = new VncException("VNC start failed!");
    final IllegalStateException cleanup = new IllegalStateException(TERMINATION_FAILURE);
    when(this.vnc.start(any(VncSource.class))).thenThrow(primary);
    doThrow(cleanup).when(this.qemu).shutdown();
    final VncException thrown = assertThrows(VncException.class, () -> this.startDefaultMachine(player));
    assertSame(primary, thrown);

    final Throwable[] expectedSuppressed = new Throwable[] { cleanup };
    final Throwable[] suppressed = thrown.getSuppressed();
    assertArrayEquals(expectedSuppressed, suppressed);

    when(this.qemu.isAlive()).thenReturn(false);
    doAnswer(_ -> null)
      .when(this.qemu)
      .shutdown();
    final boolean released = player.release();
    assertTrue(released);
    verify(this.qemu, times(2)).shutdown();
  }

  @Test
  void shouldNotTryToSuppressTheSameFailureInstance() {
    final QemuVmPlayer player = this.startedPlayer();
    final IllegalStateException shared = new IllegalStateException("shared cleanup failure!");
    doThrow(shared).when(this.vnc).release();
    doThrow(shared).when(this.qemu).shutdown();
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, player::release);
    assertSame(shared, thrown);

    final Throwable[] suppressed = thrown.getSuppressed();
    assertArrayEquals(new Throwable[0], suppressed);
    verify(this.qemu).shutdown();
  }

  @Test
  void shouldNotHideFatalProcessCleanupBehindAnOrdinaryVncFailure() {
    final QemuVmPlayer player = this.startedPlayer();
    final IllegalStateException primary = new IllegalStateException(RELEASE_FAILURE);
    final InternalError fatal = new InternalError("VM failure during cleanup!");
    doThrow(primary).when(this.vnc).release();
    doThrow(fatal).when(this.qemu).shutdown();
    final InternalError thrown = assertThrows(InternalError.class, player::release);
    assertSame(fatal, thrown);

    final boolean playing = player.isPlaying();
    assertFalse(playing);
  }

  @Test
  void shouldRetainTheMachineForExplicitReleaseAfterAFatalDisplayStartFailure() {
    final QemuVmPlayer player = this.player();
    final InternalError fatal = new InternalError("VM failure during VNC startup!");
    when(this.vnc.start(any(VncSource.class))).thenThrow(fatal);
    final InternalError thrown = assertThrows(InternalError.class, () -> this.startDefaultMachine(player));
    assertSame(fatal, thrown);

    final boolean playing = player.isPlaying();
    assertFalse(playing);
    verify(this.qemu, never()).shutdown();

    final boolean childAlive = this.processAlive.get();
    assertTrue(childAlive, "fatal startup must not run more foreign cleanup code");

    final boolean released = player.release();
    final boolean repeated = player.release();
    assertTrue(released);
    assertFalse(repeated);
    verify(this.qemu).shutdown();

    final boolean childAliveAfterRelease = this.processAlive.get();
    assertFalse(childAliveAfterRelease, "a later explicit release still owns the child left by fatal startup");
  }

  @Test
  void shouldRetryOwnedProcessCleanupAfterAFatalVncReleaseWithoutReleasingVncTwice() {
    final QemuVmPlayer player = this.startedPlayer();
    final InternalError fatal = new InternalError("VM failure during VNC release!");
    doThrow(fatal).when(this.vnc).release();
    final InternalError thrown = assertThrows(InternalError.class, player::release);
    assertSame(fatal, thrown);

    final boolean playing = player.isPlaying();
    assertFalse(playing);
    verify(this.qemu, never()).shutdown();

    final boolean childAlive = this.processAlive.get();
    assertTrue(childAlive);

    final boolean repeated = player.release();
    final boolean finished = player.release();
    assertFalse(repeated);
    assertFalse(finished);
    verify(this.qemu).shutdown();
    verify(this.vnc).release();

    final boolean childAliveAfterRelease = this.processAlive.get();
    assertFalse(childAliveAfterRelease);
  }

  @Test
  void shouldReleaseItsLockWhenTheMachineWasReleased() {
    final QemuVmPlayer player = this.startedPlayer();
    final boolean released = player.release();
    assertTrue(released);
    this.assertDoesNotBlock("another thread releases after a release", () -> player.release());
  }

  // the action takes the lock of the player, so it only finishes while no lock is left held
  private void assertDoesNotBlock(final String description, final Runnable action) {
    final CountDownLatch finished = new CountDownLatch(1);
    final Runnable probe = () -> {
      action.run();
      finished.countDown();
    };
    final Thread probing = new Thread(probe, "lock-probe");
    probing.setDaemon(true);
    probing.start();
    final boolean completed = this.awaitLatch(finished);
    assertTrue(completed, description);
  }

  private boolean awaitLatch(final CountDownLatch latch) {
    try {
      return latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
      return false;
    }
  }

  @Test
  void shouldUseTheVideoCallbackOfTheStream() {
    final VideoAttachableCallback callback = VideoAttachableCallback.create();
    when(this.vnc.getVideoAttachableCallback()).thenReturn(callback);
    final QemuVmPlayer player = this.player();
    final VideoAttachableCallback returned = player.getVideoAttachableCallback();
    assertSame(callback, returned);
  }

  @Test
  void shouldDelegateTheExceptionHandlerToTheStream() {
    final BiConsumer<String, Throwable> handler = (_, _) -> {};
    when(this.vnc.getExceptionHandler()).thenReturn(handler);
    final QemuVmPlayer player = this.player();
    player.setExceptionHandler(handler);
    final BiConsumer<String, Throwable> current = player.getExceptionHandler();
    verify(this.vnc).setExceptionHandler(handler);
    assertSame(handler, current);
  }

  @Test
  void shouldRejectNullArguments() {
    final QemuVmPlayer player = this.player();
    final VmSettings settings = VmSettings.of(PORT, WIDTH, HEIGHT, FRAME_RATE);
    final VmConfiguration configuration = VmConfiguration.builder().build();
    assertThrows(NullPointerException.class, () -> player.start(null, VmPlayer.Architecture.X86_64, configuration));
    assertThrows(NullPointerException.class, () -> player.start(settings, null, configuration));
    assertThrows(NullPointerException.class, () -> player.start(settings, VmPlayer.Architecture.X86_64, null));
    assertThrows(NullPointerException.class, () -> player.sendMouseEvent(null, 0, 0));
    assertThrows(NullPointerException.class, () -> player.sendKeyEvent(null));
    assertThrows(NullPointerException.class, () -> player.setExceptionHandler(null));
  }

  @Test
  void shouldStartAsynchronously() throws Exception {
    this.stubConnectingStream();
    final AtomicReference<Thread> startupThread = new AtomicReference<>();
    doAnswer(_ -> {
      startupThread.set(Thread.currentThread());
      this.processAlive.set(true);
      return null;
    })
      .when(this.qemu)
      .start();
    final VmPlayer player = this.player();
    final VmSettings settings = VmSettings.of(PORT, WIDTH, HEIGHT, FRAME_RATE);
    final VmConfiguration configuration = VmConfiguration.builder().build();
    try {
      final CompletableFuture<Boolean> first = player.startAsync(settings, VmPlayer.Architecture.X86_64, configuration);
      final boolean started = first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(started);

      final Thread current = Thread.currentThread();
      final Thread startup = startupThread.get();
      assertNotSame(current, startup, "the default executor must perform startup");

      final ExecutorService executor = mock(ExecutorService.class);
      final List<Runnable> queued = new CopyOnWriteArrayList<>();
      doAnswer(invocation -> {
        queued.add(invocation.getArgument(0));
        return null;
      })
        .when(executor)
        .execute(any());
      try (executor) {
        final CompletableFuture<Boolean> second = player.startAsync(settings, VmPlayer.Architecture.X86_64, configuration, executor);
        final boolean done = second.isDone();
        final int queuedTasks = queued.size();
        assertFalse(done, "the supplied executor owns the second startup");
        assertEquals(1, queuedTasks);

        final Runnable task = queued.getFirst();
        task.run();
        final boolean startedAgain = second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertFalse(startedAgain);
        assertThrows(NullPointerException.class, () -> player.startAsync(null, VmPlayer.Architecture.X86_64, configuration, executor));
        assertThrows(NullPointerException.class, () -> player.startAsync(settings, null, configuration, executor));
        assertThrows(NullPointerException.class, () -> player.startAsync(settings, VmPlayer.Architecture.X86_64, null, executor));
        assertThrows(NullPointerException.class, () -> player.startAsync(settings, VmPlayer.Architecture.X86_64, configuration, null));
        assertThrows(NullPointerException.class, () -> player.startAsync(null, VmPlayer.Architecture.X86_64, configuration));
      }
    } finally {
      player.release();
    }
  }

  @Test
  void shouldNameTheQemuProgramOfEveryArchitecture() {
    final String x86 = VmPlayer.Architecture.X86_64.getCommand();
    final String arm = VmPlayer.Architecture.ARM.getCommand();
    final String aarch64 = VmPlayer.Architecture.AARCH64.getCommand();
    final String riscv = VmPlayer.Architecture.RISCV64.getCommand();
    assertEquals("qemu-system-x86_64", x86);
    assertEquals("qemu-system-arm", arm);
    assertEquals(AARCH64_COMMAND, aarch64);
    assertEquals(RISCV64_COMMAND, riscv);
  }

  @Test
  void shouldCreatePlayersThatAreNotPlaying() {
    final VmPlayer player = VmPlayer.create();
    final boolean playing = player.isPlaying();
    final boolean paused = player.pause();
    assertInstanceOf(QemuVmPlayer.class, player);
    assertFalse(playing);
    assertFalse(paused);

    final boolean released = player.release();
    assertTrue(released);
  }

  @Test
  void shouldStreamTheScreenOfARealVirtualMachine() {
    this.assumeQemuInstalled();

    final List<String> errors = new CopyOnWriteArrayList<>();
    final List<int[]> sizes = new CopyOnWriteArrayList<>();
    final VmPlayer player = VmPlayer.create();
    try {
      player.setExceptionHandler((message, error) -> errors.add(message + REPORT_SEPARATOR + error));
      this.startTinyMachine(player);
      this.attachRecorder(player, sizes);
      this.awaitFirstFrame(sizes, errors);

      final int[] size = sizes.getFirst();
      final int width = size[0];
      final int height = size[1];
      final boolean playing = player.isPlaying();
      assertEquals(TINY_WIDTH, width);
      assertEquals(TINY_HEIGHT, height);
      assertTrue(playing);

      player.sendKeyEvent("Return");
      player.sendMouseEvent(MouseClick.LEFT, 80, 60);
      final boolean noErrors = errors.isEmpty();
      assertTrue(noErrors, errors::toString);
    } finally {
      final boolean released = player.release();
      assertTrue(released);
    }

    final boolean playingAfterRelease = player.isPlaying();
    assertFalse(playingAfterRelease);
  }

  private void assumeQemuInstalled() {
    final ExecutableFinder realFinder = new ExecutableFinder();
    final String command = VmPlayer.Architecture.X86_64.getCommand();
    final Optional<Path> installed = realFinder.find(command);
    final boolean installedIsPresent = installed.isPresent();
    assumeTrue(installedIsPresent, "QEMU is not installed!");
  }

  private void startTinyMachine(final VmPlayer player) {
    final int port = FreePorts.find();
    final VmSettings settings = VmSettings.of(port, TINY_WIDTH, TINY_HEIGHT, 10);
    final VmConfiguration configuration = VmConfiguration.builder().memory(64).build();
    final boolean started = player.start(settings, VmPlayer.Architecture.X86_64, configuration);
    assertTrue(started);
  }

  private void attachRecorder(final VmPlayer player, final List<int[]> sizes) {
    final VideoFilter recorder = (image, _) -> {
      final int width = image.getWidth();
      final int height = image.getHeight();
      final int[] size = { width, height };
      sizes.add(size);
      return false;
    };
    final VideoPipelineStepBuilder builder = PipelineBuilder.video();
    builder.then(recorder);
    final VideoPipelineStep pipeline = builder.build();
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    callback.attach(pipeline);
  }

  private void awaitFirstFrame(final List<int[]> sizes, final List<String> errors) {
    final long timeout = TimeUnit.SECONDS.toNanos(20);
    final long pause = TimeUnit.MILLISECONDS.toNanos(20);
    final long deadline = System.nanoTime() + timeout;
    while (sizes.isEmpty()) {
      final long now = System.nanoTime();
      if (now - deadline > 0) {
        fail("QEMU sent no frame in time, and the errors %s were reported!".formatted(errors));
      }
      LockSupport.parkNanos(pause);
    }
  }
}
