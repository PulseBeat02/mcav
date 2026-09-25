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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.builder.VideoPipelineStepBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.interaction.MouseClick;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.vnc.VNCPlayer;
import me.brandonli.mcav.vnc.VNCSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.verification.VerificationMode;

/**
 * Tests {@link VMPlayerImpl} and the default methods of {@link VMPlayer}.
 *
 * <p>The unit tests replace QEMU and the VNC connection with mocks. One test starts a real QEMU, which is installed
 * on the development machines; it is skipped where QEMU is missing.
 */
final class VMPlayerImplTest {

  @TempDir
  private Path directory;

  private final List<Path> executables = new CopyOnWriteArrayList<>();
  private final List<VMSettings> createdSettings = new CopyOnWriteArrayList<>();
  private final List<VMConfiguration> createdConfigurations = new CopyOnWriteArrayList<>();

  private final List<VMAudioClient.Sink> sinks = new CopyOnWriteArrayList<>();
  private final List<java.net.InetSocketAddress> audioAddresses = new CopyOnWriteArrayList<>();
  private final VMAudioClient audioClient = mock(VMAudioClient.class);
  // a machine of the mocked QEMU has no sound unless a test says so, and then this connects to it
  private final VMPlayerImpl.AudioConnector audio = (address, sink, failures) -> {
    this.audioAddresses.add(address);
    this.sinks.add(sink);
    return this.audioClient;
  };

  private VNCPlayer vnc;
  private VMProcess qemu;
  private AtomicBoolean processAlive;
  private ExecutableFinder finder;

  @BeforeEach
  void createParts() throws IOException {
    this.vnc = mock(VNCPlayer.class);
    this.qemu = mock(VMProcess.class);
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

    final Path bin = this.directory.resolve("bin");
    installFakePrograms(bin);

    final String path = bin.toString();
    final Map<String, String> environment = Map.of("PATH", path);
    final Path root = this.directory.resolve("empty-root");
    this.finder = new ExecutableFinder(OS.LINUX, environment, root);
  }

  private static void installFakePrograms(final Path bin) throws IOException {
    Files.createDirectories(bin);
    final VMPlayer.Architecture[] architectures = VMPlayer.Architecture.values();
    for (final VMPlayer.Architecture architecture : architectures) {
      final String command = architecture.getCommand();
      final Path program = bin.resolve(command);
      Files.writeString(program, command);
      final File file = program.toFile();
      final boolean madeExecutable = file.setExecutable(true);
      assertTrue(madeExecutable, command);
    }
  }

  private void useFinderWithoutQemu() {
    final Path root = this.directory.resolve("empty-root");
    final Map<String, String> environment = Map.of("PATH", "");
    this.finder = new ExecutableFinder(OS.LINUX, environment, root);
  }

  private VMPlayerImpl player() {
    final VMPlayerImpl.ProcessFactory factory = (settings, architecture, executable, configuration) -> {
      this.createdSettings.add(settings);
      this.executables.add(executable);
      this.createdConfigurations.add(configuration);
      return this.qemu;
    };
    return new VMPlayerImpl(this.vnc, this.finder, factory, this.audio);
  }

  private void stubConnectingStream() {
    when(this.vnc.start(any(VNCSource.class))).thenReturn(true);
  }

  private static boolean startDefaultMachine(final VMPlayer player) {
    final VMSettings settings = VMSettings.of(5905, 320, 240, 15);
    final VMConfiguration configuration = VMConfiguration.builder();
    return player.start(settings, VMPlayer.Architecture.X86_64, configuration);
  }

  private VMPlayerImpl startedPlayer() {
    this.stubConnectingStream();
    final VMPlayerImpl player = this.player();
    startDefaultMachine(player);
    return player;
  }

  private static void sendInput(final VMPlayer player) {
    player.moveMouse(1, 2);
    player.sendMouseEvent(MouseClick.LEFT, 3, 4);
    player.sendKeyEvent("a");
  }

  private void verifyInputForwarded(final VerificationMode mode) {
    verify(this.vnc, mode).moveMouse(1, 2);
    verify(this.vnc, mode).sendMouseEvent(MouseClick.LEFT, 3, 4);
    verify(this.vnc, mode).sendKeyEvent("a");
  }

  private void assertStreamedWith(final VMSettings settings) {
    final ArgumentCaptor<VNCSource> captor = ArgumentCaptor.forClass(VNCSource.class);
    verify(this.vnc).start(captor.capture());
    final VNCSource source = captor.getValue();
    final String sourceHost = source.getHost();
    final int sourcePort = source.getPort();
    final int sourceWidth = source.getScreenWidth();
    final int sourceHeight = source.getScreenHeight();
    final int sourceFrameRate = source.getTargetFrameRate();

    final int port = settings.getPort();
    final int width = settings.getWidth();
    final int height = settings.getHeight();
    final int frameRate = settings.getTargetFps();
    assertEquals("127.0.0.1", sourceHost);
    assertEquals(port, sourcePort);
    assertEquals(width, sourceWidth);
    assertEquals(height, sourceHeight);
    assertEquals(frameRate, sourceFrameRate);
  }

  @Test
  void theSoundOfAMachineWithSoundReachesTheAudioPipelineAndStopsWhilePaused() {
    when(this.qemu.hasAudio()).thenReturn(true);
    when(this.vnc.pause()).thenReturn(true);
    when(this.vnc.resume()).thenReturn(true);
    when(this.vnc.isPlaying()).thenReturn(true);
    final List<Integer> heard = new CopyOnWriteArrayList<>();
    final VMPlayerImpl player = this.startedPlayer();
    player.getAudioAttachableCallback().attach(AudioPipelineStep.of((samples, metadata) -> heard.add(samples.remaining())));
    assertEquals(List.of(new java.net.InetSocketAddress(VMProcess.LOOPBACK, 5905)), this.audioAddresses);
    final VMAudioClient.Sink sink = this.sinks.getFirst();
    sink.accept(new byte[8], 8);
    waitUntil(() -> heard.size() == 1);
    assertTrue(player.pause());
    sink.accept(new byte[8], 8);
    assertTrue(player.resume());
    sink.accept(new byte[4], 4);
    waitUntil(() -> heard.size() == 2);
    assertEquals(List.of(8, 4), heard, "the sound of the paused machine was dropped");
    player.release();
    verify(this.audioClient).close();
  }

  @Test
  void aResumeDuringAPauseLeavesPictureAndSoundTogether() throws Exception {
    when(this.qemu.hasAudio()).thenReturn(true);
    final java.util.concurrent.CountDownLatch insidePause = new java.util.concurrent.CountDownLatch(1);
    final java.util.concurrent.CountDownLatch finishPause = new java.util.concurrent.CountDownLatch(1);
    when(this.vnc.pause()).thenAnswer(invocation -> {
      insidePause.countDown();
      finishPause.await(10, java.util.concurrent.TimeUnit.SECONDS);
      return true;
    });
    when(this.vnc.resume()).thenReturn(true);
    when(this.vnc.isPlaying()).thenReturn(true);
    final List<Integer> heard = new CopyOnWriteArrayList<>();
    final VMPlayerImpl player = this.startedPlayer();
    player.getAudioAttachableCallback().attach(AudioPipelineStep.of((samples, metadata) -> heard.add(samples.remaining())));
    final java.util.concurrent.CompletableFuture<Boolean> pausing = java.util.concurrent.CompletableFuture.supplyAsync(player::pause);
    assertTrue(insidePause.await(10, java.util.concurrent.TimeUnit.SECONDS));
    // the picture is being paused while another thread resumes; without one lock the sound would end up paused
    final java.util.concurrent.CompletableFuture<Boolean> resuming = java.util.concurrent.CompletableFuture.supplyAsync(player::resume);
    Thread.sleep(200L);
    finishPause.countDown();
    assertTrue(pausing.get(10, java.util.concurrent.TimeUnit.SECONDS));
    assertTrue(resuming.get(10, java.util.concurrent.TimeUnit.SECONDS));
    this.sinks.getFirst().accept(new byte[8], 8);
    waitUntil(() -> heard.size() == 1);
    player.release();
  }

  @Test
  void aMachineWhoseSoundCannotBeConnectedRunsWithoutSound() {
    when(this.qemu.hasAudio()).thenReturn(true);
    this.stubConnectingStream();
    final List<String> reports = new CopyOnWriteArrayList<>();
    when(this.vnc.getExceptionHandler()).thenReturn((message, failure) -> reports.add(message + ": " + failure.getMessage()));
    final VMPlayerImpl player = new VMPlayerImpl(
      this.vnc,
      this.finder,
      (settings, architecture, executable, configuration) -> this.qemu,
      (address, sink, failures) -> {
        throw new IOException("connection refused");
      }
    );
    final long outputsBefore = countOutputThreads();
    assertTrue(startDefaultMachine(player));
    assertEquals(List.of("The sound of the virtual machine could not be connected, it runs without sound: connection refused"), reports);
    assertEquals(outputsBefore, countOutputThreads(), "the output of the sound that never came is closed");
    when(this.vnc.pause()).thenReturn(true);
    assertTrue(player.pause(), "a machine without sound pauses all the same");
    when(this.vnc.resume()).thenReturn(true);
    assertTrue(player.resume());
    player.release();
  }

  @Test
  void aMachineWithoutSoundNeverConnectsToIt() {
    final VMPlayerImpl player = this.startedPlayer();
    assertEquals(List.of(), this.sinks);
    assertSame(player.getAudioAttachableCallback(), player.getAudioAttachableCallback());
    player.release();
  }

  private static long countOutputThreads() {
    return Thread.getAllStackTraces()
      .keySet()
      .stream()
      .filter(thread -> thread.isAlive() && thread.getName().equals("mcav-vm-audio-output"))
      .count();
  }

  private static void waitUntil(final java.util.function.BooleanSupplier condition) {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        fail("timed out");
      }
      Thread.onSpinWait();
    }
  }

  @Test
  void startsQemuAndStreamsItsDisplay() {
    this.stubConnectingStream();
    final VMPlayerImpl player = this.player();
    final VMSettings settings = VMSettings.of(5905, 320, 240, 15);
    final VMConfiguration configuration = VMConfiguration.builder();
    configuration.memory(128);

    final boolean started = player.start(settings, VMPlayer.Architecture.AARCH64, configuration);

    assertTrue(started);
    verify(this.qemu).start();
    final Path executable = this.executables.getFirst();
    final Path fileName = executable.getFileName();
    final String name = fileName.toString();
    final VMSettings passedSettings = this.createdSettings.getFirst();
    final VMConfiguration passedConfiguration = this.createdConfigurations.getFirst();
    assertEquals("qemu-system-aarch64", name);
    assertSame(settings, passedSettings);
    assertSame(configuration, passedConfiguration);
    this.assertStreamedWith(settings);
  }

  @Test
  void playsOnlyWhileQemuAndTheStreamAreRunning() {
    final VMPlayerImpl player = this.player();
    when(this.qemu.isAlive()).thenReturn(true);
    when(this.vnc.isPlaying()).thenReturn(true);
    final boolean beforeStart = player.isPlaying();
    assertFalse(beforeStart);

    this.stubConnectingStream();
    startDefaultMachine(player);
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
    final boolean afterRelease = player.isPlaying();
    assertFalse(afterRelease);
  }

  @Test
  void refusesToStartTwiceOrAfterRelease() {
    final VMPlayerImpl player = this.startedPlayer();
    final VMSettings settings = VMSettings.of(5906, 320, 240, 15);
    final VMConfiguration configuration = VMConfiguration.builder();
    final boolean startedAgain = player.start(settings, VMPlayer.Architecture.X86_64, configuration);
    assertFalse(startedAgain);

    final boolean released = player.release();
    final boolean releasedAgain = player.release();
    final boolean startedAfterRelease = player.start(settings, VMPlayer.Architecture.X86_64, configuration);
    assertTrue(released);
    assertFalse(releasedAgain);
    assertFalse(startedAfterRelease);
    verify(this.qemu, times(1)).start();
    verify(this.vnc, times(1)).release();
    verify(this.qemu, times(1)).shutdown();
  }

  @Test
  void failsWhenQemuIsNotInstalled() {
    this.useFinderWithoutQemu();
    final VMPlayerImpl player = this.player();
    final VMSettings settings = VMSettings.of(5905, 320, 240, 15);
    final VMConfiguration configuration = VMConfiguration.builder();

    final ExecutableNotInPathException exception = assertThrows(ExecutableNotInPathException.class, () ->
      player.start(settings, VMPlayer.Architecture.RISCV64, configuration)
    );

    final String message = exception.getMessage();
    final boolean nothingCreated = this.executables.isEmpty();
    final boolean messageContains = message.contains("qemu-system-riscv64");
    assertTrue(messageContains, message);
    assertTrue(nothingCreated);
    verify(this.vnc, never()).start(any(VNCSource.class));
  }

  @Test
  void reportsAMissingQemuAsAnExceptionThatCatchAllHandlersSee() {
    this.useFinderWithoutQemu();
    final VMPlayerImpl player = this.player();
    Exception caught = null;
    try {
      startDefaultMachine(player);
    } catch (final Exception exception) {
      caught = exception;
    }
    assertInstanceOf(ExecutableNotInPathException.class, caught);
  }

  @Test
  void stopsWhenQemuExitsAndCanStartAgain() {
    final VMProcess exited = this.qemu;
    final VMProcess next = mock(VMProcess.class);
    when(next.isAlive()).thenReturn(true);
    final List<VMProcess> initialProcesses = List.of(exited, next);
    final List<VMProcess> processes = new CopyOnWriteArrayList<>(initialProcesses);
    final VMPlayerImpl.ProcessFactory factory = (_, _, _, _) -> processes.removeFirst();
    this.stubRunningStream();
    final VMPlayerImpl player = new VMPlayerImpl(this.vnc, this.finder, factory, this.audio);
    final boolean started = startDefaultMachine(player);
    final boolean playingBefore = player.isPlaying();
    assertTrue(started);
    assertTrue(playingBefore);

    // the guest shut down, so QEMU exited on its own
    when(exited.isAlive()).thenReturn(false);
    this.assertIgnoresInputAndPlayback(player);

    doAnswer(_ -> {
      this.assertIgnoresInputAndPlayback(player);
      return null;
    })
      .when(next)
      .start();
    final boolean restarted = startDefaultMachine(player);
    final boolean playingAgain = player.isPlaying();
    assertTrue(restarted);
    assertTrue(playingAgain);
    // the exited process is cleaned up before the new one starts
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

  private void assertIgnoresInputAndPlayback(final VMPlayerImpl player) {
    final boolean playing = player.isPlaying();
    final boolean paused = player.pause();
    final boolean resumed = player.resume();
    sendInput(player);

    assertFalse(playing);
    assertFalse(paused);
    assertFalse(resumed);
    final VerificationMode none = never();
    this.verifyInputForwarded(none);
    verify(this.vnc, never()).pause();
  }

  @Test
  void passesOnQemuStartFailures() {
    final PlayerException failure = new PlayerException("QEMU exited with code 1");
    doThrow(failure).when(this.qemu).start();
    final VMPlayerImpl player = this.player();

    final PlayerException exception = assertThrows(PlayerException.class, () -> startDefaultMachine(player));

    final boolean playing = player.isPlaying();
    assertSame(failure, exception);
    assertFalse(playing);
    verify(this.vnc, never()).start(any(VNCSource.class));
  }

  @Test
  void shutsQemuDownWhenTheStreamCannotStart() {
    when(this.vnc.start(any(VNCSource.class))).thenReturn(false);
    final VMPlayerImpl player = this.player();

    final PlayerException exception = assertThrows(PlayerException.class, () -> startDefaultMachine(player));

    final String message = exception.getMessage();
    assertEquals("The VNC player could not be started", message);
    verify(this.qemu).shutdown();
    player.release();
    verify(this.qemu, times(1)).shutdown();
  }

  @Test
  void shutsQemuDownAndCanStartAgainWhenTheConnectionFails() {
    final PlayerException failure = new PlayerException("Failed to connect");
    when(this.vnc.start(any(VNCSource.class))).thenThrow(failure).thenReturn(true);
    final VMPlayerImpl player = this.player();

    final PlayerException exception = assertThrows(PlayerException.class, () -> startDefaultMachine(player));

    assertSame(failure, exception);
    verify(this.qemu).shutdown();
    final boolean started = startDefaultMachine(player);
    assertTrue(started);
    verify(this.qemu, times(2)).start();
  }

  @Test
  void forwardsInputOnlyWhileRunning() {
    final VMPlayerImpl player = this.player();
    sendInput(player);
    final VerificationMode none = never();
    this.verifyInputForwarded(none);

    this.stubConnectingStream();
    startDefaultMachine(player);
    sendInput(player);
    final VerificationMode once = times(1);
    this.verifyInputForwarded(once);

    player.release();
    sendInput(player);
    this.verifyInputForwarded(once);
  }

  @Test
  void pausesAndResumesTheStreamWhileRunning() {
    final VMPlayerImpl player = this.player();
    when(this.vnc.pause()).thenReturn(true);
    when(this.vnc.resume()).thenReturn(true);
    final boolean pausedBeforeStart = player.pause();
    final boolean resumedBeforeStart = player.resume();
    assertFalse(pausedBeforeStart);
    assertFalse(resumedBeforeStart);
    verify(this.vnc, never()).pause();
    verify(this.vnc, never()).resume();

    this.stubConnectingStream();
    startDefaultMachine(player);
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
  void releasesTheStreamEvenIfNeverStarted() {
    final VMPlayerImpl player = this.player();
    final boolean released = player.release();
    assertTrue(released);
    verify(this.vnc).release();
    verify(this.qemu, never()).shutdown();
  }

  @Test
  void shutsQemuDownEvenWhenReleasingTheStreamFails() {
    final VMPlayerImpl player = this.startedPlayer();
    final IllegalStateException failure = new IllegalStateException("VNC cleanup failed");
    doThrow(failure).when(this.vnc).release();
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, player::release);
    final boolean releasedAgain = player.release();
    final boolean playing = player.isPlaying();
    assertSame(failure, thrown);
    assertFalse(releasedAgain);
    assertFalse(playing);
    verify(this.qemu).shutdown();
    assertDoesNotBlock("a failed VNC release unlocks the player", () -> player.release());
  }

  @Test
  void retriesAStillLiveMachineOnRepeatedReleaseWithoutChangingTheReturnContract() {
    final VMPlayerImpl player = this.startedPlayer();
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
    final boolean first = player.release();
    this.assertIgnoresInputAndPlayback(player);
    final boolean second = player.release();
    final boolean third = player.release();
    assertTrue(first);
    assertFalse(second);
    assertFalse(third);
    verify(this.qemu, times(2)).shutdown();
    verify(this.vnc).release();
  }

  @Test
  void refusesToReplaceAnUnstoppableFailedStartAndRetriesBeforeLaunchingItsReplacement() {
    final VMProcess previous = this.qemu;
    final VMProcess next = mock(VMProcess.class);
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
    final VMPlayerImpl.ProcessFactory factory = (_, _, _, _) -> created.getAndIncrement() == 0 ? previous : next;
    final VMPlayerImpl player = new VMPlayerImpl(this.vnc, this.finder, factory, this.audio);
    final IllegalStateException connectionFailure = new IllegalStateException("VNC initialization failed");
    when(this.vnc.start(any(VNCSource.class))).thenThrow(connectionFailure).thenReturn(true);
    final IllegalStateException original = assertThrows(IllegalStateException.class, () -> startDefaultMachine(player));
    assertSame(connectionFailure, original);
    this.assertIgnoresInputAndPlayback(player);
    final PlayerException replacement = assertThrows(PlayerException.class, () -> startDefaultMachine(player));
    assertEquals("The previous QEMU process is still alive after shutdown", replacement.getMessage());
    assertEquals(1, created.get(), "replacement cannot allocate a new owner while the old child survives");
    verify(next, never()).start();

    final boolean restarted = startDefaultMachine(player);
    assertTrue(restarted);
    assertEquals(2, created.get());
    final InOrder order = inOrder(previous, next);
    order.verify(previous).start();
    order.verify(previous, times(3)).shutdown();
    order.verify(next).start();
  }

  @Test
  void retainsAChildThatSurvivesAPartiallyFailedQemuStart() {
    final VMPlayerImpl player = this.player();
    final PlayerException startFailure = new PlayerException("display startup timed out");
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
    final PlayerException thrown = assertThrows(PlayerException.class, () -> startDefaultMachine(player));
    assertSame(startFailure, thrown);
    verify(this.qemu).shutdown();
    final boolean released = player.release();
    final boolean releasedAgain = player.release();
    assertTrue(released);
    assertFalse(releasedAgain);
    verify(this.qemu, times(2)).shutdown();
    verify(this.vnc, never()).start(any(VNCSource.class));
  }

  @Test
  void preservesTheVncFailureAndRetriesFailedProcessCleanupOnRelease() {
    final VMPlayerImpl player = this.startedPlayer();
    final IllegalStateException primary = new IllegalStateException("VNC release failed");
    final IllegalArgumentException cleanup = new IllegalArgumentException("process termination failed");
    doThrow(primary).when(this.vnc).release();
    doThrow(cleanup).when(this.qemu).shutdown();
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, player::release);
    assertSame(primary, thrown);
    assertArrayEquals(new Throwable[] { cleanup }, thrown.getSuppressed());
    when(this.qemu.isAlive()).thenReturn(false);
    doAnswer(_ -> null).when(this.qemu).shutdown();
    final boolean repeated = player.release();
    final boolean finished = player.release();
    assertFalse(repeated);
    assertFalse(finished);
    verify(this.qemu, times(2)).shutdown();
    verify(this.vnc).release();
  }

  @Test
  void preservesAStartFailureWhenItsProcessCleanupAlsoFails() {
    final VMPlayerImpl player = this.player();
    final PlayerException primary = new PlayerException("VNC start failed");
    final IllegalStateException cleanup = new IllegalStateException("process termination failed");
    when(this.vnc.start(any(VNCSource.class))).thenThrow(primary);
    doThrow(cleanup).when(this.qemu).shutdown();
    final PlayerException thrown = assertThrows(PlayerException.class, () -> startDefaultMachine(player));
    assertSame(primary, thrown);
    assertArrayEquals(new Throwable[] { cleanup }, thrown.getSuppressed());
    when(this.qemu.isAlive()).thenReturn(false);
    doAnswer(_ -> null).when(this.qemu).shutdown();
    assertTrue(player.release());
    verify(this.qemu, times(2)).shutdown();
  }

  @Test
  void doesNotTryToSuppressTheSameFailureInstance() {
    final VMPlayerImpl player = this.startedPlayer();
    final IllegalStateException shared = new IllegalStateException("shared cleanup failure");
    doThrow(shared).when(this.vnc).release();
    doThrow(shared).when(this.qemu).shutdown();
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, player::release);
    assertSame(shared, thrown);
    assertArrayEquals(new Throwable[0], thrown.getSuppressed());
    verify(this.qemu).shutdown();
  }

  @Test
  void doesNotHideFatalProcessCleanupBehindAnOrdinaryVncFailure() {
    final VMPlayerImpl player = this.startedPlayer();
    final IllegalStateException primary = new IllegalStateException("VNC release failed");
    final InternalError fatal = new InternalError("VM failure during cleanup");
    doThrow(primary).when(this.vnc).release();
    doThrow(fatal).when(this.qemu).shutdown();
    final InternalError thrown = assertThrows(InternalError.class, player::release);
    assertSame(fatal, thrown);
    assertFalse(player.isPlaying());
  }

  @Test
  void retainsTheMachineForExplicitReleaseAfterAFatalDisplayStartFailure() {
    final VMPlayerImpl player = this.player();
    final InternalError fatal = new InternalError("VM failure during VNC startup");
    when(this.vnc.start(any(VNCSource.class))).thenThrow(fatal);
    final InternalError thrown = assertThrows(InternalError.class, () -> startDefaultMachine(player));
    assertSame(fatal, thrown);
    assertFalse(player.isPlaying());
    verify(this.qemu, never()).shutdown();
    assertTrue(this.processAlive.get(), "fatal startup must not run more foreign cleanup code");
    final boolean released = player.release();
    final boolean repeated = player.release();
    assertTrue(released);
    assertFalse(repeated);
    verify(this.qemu).shutdown();
    assertFalse(this.processAlive.get(), "a later explicit release still owns the child left by fatal startup");
  }

  @Test
  void retriesOwnedProcessCleanupAfterAFatalVncReleaseWithoutReleasingVncTwice() {
    final VMPlayerImpl player = this.startedPlayer();
    final InternalError fatal = new InternalError("VM failure during VNC release");
    doThrow(fatal).when(this.vnc).release();
    final InternalError thrown = assertThrows(InternalError.class, player::release);
    assertSame(fatal, thrown);
    assertFalse(player.isPlaying());
    verify(this.qemu, never()).shutdown();
    assertTrue(this.processAlive.get());
    final boolean repeated = player.release();
    final boolean finished = player.release();
    assertFalse(repeated);
    assertFalse(finished);
    verify(this.qemu).shutdown();
    verify(this.vnc).release();
    assertFalse(this.processAlive.get());
  }

  @Test
  void releasesItsLockWhenTheMachineWasReleased() {
    final VMPlayerImpl player = this.startedPlayer();
    final boolean released = player.release();
    assertTrue(released);
    assertDoesNotBlock("another thread releases after a release", () -> player.release());
  }

  /**
   * Asserts that an action which takes the lock of the player finishes on another thread, which it only does while
   * no lock is left held.
   *
   * @param description what must not block
   * @param action      the action
   */
  private static void assertDoesNotBlock(final String description, final Runnable action) {
    final CountDownLatch finished = new CountDownLatch(1);
    final Runnable probe = () -> {
      action.run();
      finished.countDown();
    };
    final Thread probing = new Thread(probe, "lock-probe");
    probing.setDaemon(true);
    probing.start();
    final boolean completed = awaitLatch(finished);
    assertTrue(completed, description);
  }

  private static boolean awaitLatch(final CountDownLatch latch) {
    try {
      return latch.await(10, TimeUnit.SECONDS);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
      return false;
    }
  }

  @Test
  void usesTheVideoCallbackOfTheStream() {
    final VideoAttachableCallback callback = VideoAttachableCallback.create();
    when(this.vnc.getVideoAttachableCallback()).thenReturn(callback);
    final VMPlayerImpl player = this.player();
    final VideoAttachableCallback returned = player.getVideoAttachableCallback();
    assertSame(callback, returned);
  }

  @Test
  void delegatesTheExceptionHandlerToTheStream() {
    final BiConsumer<String, Throwable> handler = (_, _) -> {};
    when(this.vnc.getExceptionHandler()).thenReturn(handler);
    final VMPlayerImpl player = this.player();
    player.setExceptionHandler(handler);
    final BiConsumer<String, Throwable> current = player.getExceptionHandler();
    verify(this.vnc).setExceptionHandler(handler);
    assertSame(handler, current);
  }

  @Test
  void rejectsNullArguments() {
    final VMPlayerImpl player = this.player();
    final VMSettings settings = VMSettings.of(5905, 320, 240, 15);
    final VMConfiguration configuration = VMConfiguration.builder();
    assertThrows(NullPointerException.class, () -> player.start(null, VMPlayer.Architecture.X86_64, configuration));
    assertThrows(NullPointerException.class, () -> player.start(settings, null, configuration));
    assertThrows(NullPointerException.class, () -> player.start(settings, VMPlayer.Architecture.X86_64, null));
    assertThrows(NullPointerException.class, () -> player.sendMouseEvent(null, 0, 0));
    assertThrows(NullPointerException.class, () -> player.sendKeyEvent(null));
    assertThrows(NullPointerException.class, () -> player.setExceptionHandler(null));
  }

  @Test
  void startsAsynchronously() throws Exception {
    this.stubConnectingStream();
    final VMPlayer player = this.player();
    final VMSettings settings = VMSettings.of(5905, 320, 240, 15);
    final VMConfiguration configuration = VMConfiguration.builder();
    final CompletableFuture<Boolean> first = player.startAsync(settings, VMPlayer.Architecture.X86_64, configuration);
    final boolean started = first.get(10, TimeUnit.SECONDS);
    assertTrue(started);

    try (final ExecutorService executor = Executors.newSingleThreadExecutor()) {
      final CompletableFuture<Boolean> second = player.startAsync(settings, VMPlayer.Architecture.X86_64, configuration, executor);
      final boolean startedAgain = second.get(10, TimeUnit.SECONDS);
      assertFalse(startedAgain);
      assertThrows(NullPointerException.class, () -> player.startAsync(null, VMPlayer.Architecture.X86_64, configuration, executor));
      assertThrows(NullPointerException.class, () -> player.startAsync(settings, null, configuration, executor));
      assertThrows(NullPointerException.class, () -> player.startAsync(settings, VMPlayer.Architecture.X86_64, null, executor));
      assertThrows(NullPointerException.class, () -> player.startAsync(settings, VMPlayer.Architecture.X86_64, configuration, null));
      assertThrows(NullPointerException.class, () -> player.startAsync(null, VMPlayer.Architecture.X86_64, configuration));
    }
  }

  @Test
  void namesTheQemuProgramOfEveryArchitecture() {
    final String x86 = VMPlayer.Architecture.X86_64.getCommand();
    final String arm = VMPlayer.Architecture.ARM.getCommand();
    final String aarch64 = VMPlayer.Architecture.AARCH64.getCommand();
    final String riscv = VMPlayer.Architecture.RISCV64.getCommand();
    assertEquals("qemu-system-x86_64", x86);
    assertEquals("qemu-system-arm", arm);
    assertEquals("qemu-system-aarch64", aarch64);
    assertEquals("qemu-system-riscv64", riscv);
  }

  @Test
  void createsPlayersThatAreNotPlaying() {
    final VMPlayer player = VMPlayer.create();
    final boolean playing = player.isPlaying();
    final boolean paused = player.pause();
    assertInstanceOf(VMPlayerImpl.class, player);
    assertFalse(playing);
    assertFalse(paused);
    final boolean released = player.release();
    assertTrue(released);
  }

  @Test
  void streamsTheScreenOfARealVirtualMachine() {
    assumeQemuInstalled();
    final List<String> errors = new CopyOnWriteArrayList<>();
    final List<int[]> sizes = new CopyOnWriteArrayList<>();
    final VMPlayer player = VMPlayer.create();
    try {
      player.setExceptionHandler((message, error) -> errors.add(message + ": " + error));
      startTinyMachine(player);
      // a pipeline attached while the machine runs must receive its frames
      attachRecorder(player, sizes);
      awaitFirstFrame(sizes, errors);

      final int[] size = sizes.getFirst();
      final boolean playing = player.isPlaying();
      assertEquals(160, size[0]);
      assertEquals(120, size[1]);
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

  private static void assumeQemuInstalled() {
    final ExecutableFinder realFinder = new ExecutableFinder();
    final String command = VMPlayer.Architecture.X86_64.getCommand();
    final Optional<Path> installed = realFinder.find(command);
    final boolean installedIsPresent = installed.isPresent();
    assumeTrue(installedIsPresent, "QEMU is not installed");
  }

  private static void startTinyMachine(final VMPlayer player) {
    final VMSettings settings = VMSettings.of(160, 120, 10);
    final VMConfiguration configuration = VMConfiguration.builder();
    configuration.memory(64);
    final boolean started = player.start(settings, VMPlayer.Architecture.X86_64, configuration);
    assertTrue(started);
  }

  private static void attachRecorder(final VMPlayer player, final List<int[]> sizes) {
    final VideoFilter recorder = (image, _) -> {
      final int width = image.getWidth();
      final int height = image.getHeight();
      final int[] size = { width, height };
      sizes.add(size);
      // Displaying or recording the frame leaves its pixels unchanged.
      return false;
    };
    final VideoPipelineStepBuilder builder = PipelineBuilder.video();
    builder.then(recorder);
    final VideoPipelineStep pipeline = builder.build();
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    callback.attach(pipeline);
  }

  private static void awaitFirstFrame(final List<int[]> sizes, final List<String> errors) {
    final long timeout = TimeUnit.SECONDS.toNanos(20);
    final long pause = TimeUnit.MILLISECONDS.toNanos(20);
    final long deadline = System.nanoTime() + timeout;
    while (sizes.isEmpty()) {
      final long now = System.nanoTime();
      if (now - deadline > 0) {
        fail("No frame arrived from QEMU; errors: " + errors);
      }
      LockSupport.parkNanos(pause);
    }
  }
}
