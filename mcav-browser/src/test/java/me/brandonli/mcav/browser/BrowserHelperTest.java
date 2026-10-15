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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.Reader;
import java.io.StringReader;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import me.brandonli.mcav.browser.testing.OpenFiles;
import me.brandonli.mcav.browser.testing.StandardError;
import org.cef.browser.McavOffscreenBrowser;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrowserHelperTest {

  private static final IntFunction<byte[]> NEW_BUFFER = byte[]::new;

  private static final String PAGE = "/page";
  private static final String SOCKET_NAME = "s";

  private static final String STANDARD_INPUT_ENDED = "the standard input ended";

  private static final long JOIN_TIMEOUT_MILLISECONDS = 10_000L;
  private static final long RESULT_TIMEOUT_SECONDS = 10L;

  @TempDir
  Path folder;

  private final PipedWriter standardInput = new PipedWriter();
  private ServerSocketChannel server;

  private static byte[] token() {
    final byte[] token = new byte[HelperProtocol.TOKEN_BYTES];
    token[5] = 5;
    return token;
  }

  private HelperConfiguration configuration(final String path) {
    return this.configuration(path, false);
  }

  private HelperConfiguration configuration(final String path, final boolean allowsAutoplay) {
    return new HelperConfiguration(
      token(),
      this.folder.resolve(SOCKET_NAME),
      this.folder.resolve("natives"),
      this.folder.resolve("profile"),
      URI.create("https://example.com" + path),
      4,
      3,
      1,
      30,
      false,
      false,
      allowsAutoplay,
      false,
      this.folder,
      List.of()
    );
  }

  @Test
  void shouldPassPageSoundToTheServerOnlyAfterAPressOrAKeyUnlessAutoplayIsOn() throws IOException {
    final byte[] chunk = new byte[HelperProtocol.AUDIO_FRAME_BYTES];
    final int message = 1 + 4 + chunk.length;
    final BrowserHelper helper = new BrowserHelper(this.configuration(PAGE), new ScriptedEngine());
    final ByteArrayOutputStream sent = new ByteArrayOutputStream();
    final BrowserHelper.Reporter reporter = helper.new Reporter(new DataOutputStream(sent));
    reporter.onAudio(chunk);
    final int soundBytesBeforeAnyPress = sent.size();
    assertEquals(0, soundBytesBeforeAnyPress, "no sound before anyone pressed a button or a key");
    helper.executeCommand(HelperMessage.mouse(new MouseInput(HelperProtocol.MOUSE_MOVE, 1, 1, HelperProtocol.BUTTON_LEFT, 0, 0, 0)));
    reporter.onAudio(chunk);
    final int soundBytesAfterAMove = sent.size();
    assertEquals(0, soundBytesAfterAMove, "a move presses nothing");
    helper.executeCommand(HelperMessage.key(HelperProtocol.KEY_TYPE, "a"));
    reporter.onAudio(chunk);
    final int soundBytesAfterAKey = sent.size();
    assertEquals(message, soundBytesAfterAKey, "a key lets the sound pass");
    final BrowserHelper autoplayHelper = new BrowserHelper(this.configuration(PAGE, true), new ScriptedEngine());
    final ByteArrayOutputStream played = new ByteArrayOutputStream();
    autoplayHelper.new Reporter(new DataOutputStream(played)).onAudio(chunk);
    final int autoplaySoundBytes = played.size();
    assertEquals(message, autoplaySoundBytes, "with autoplay the sound passes at once");
  }

  @Test
  void shouldWaitForTheOwnPressOfANewPageBeforeItsSoundReachesTheServer() {
    final byte[] chunk = new byte[HelperProtocol.AUDIO_FRAME_BYTES];
    final int message = 1 + 4 + chunk.length;
    final BrowserHelper helper = new BrowserHelper(this.configuration(PAGE), new ScriptedEngine());
    final ByteArrayOutputStream sent = new ByteArrayOutputStream();
    final BrowserHelper.Reporter reporter = helper.new Reporter(new DataOutputStream(sent));
    helper.executeCommand(HelperMessage.mouse(new MouseInput(HelperProtocol.MOUSE_PRESS, 1, 1, HelperProtocol.BUTTON_LEFT, 1, 0, 0)));
    reporter.onAudio(chunk);
    final int soundBytesAfterAPress = sent.size();
    assertEquals(message, soundBytesAfterAPress, "a press lets the sound of the page pass");
    reporter.onPageStart();
    reporter.onAudio(chunk);
    final int soundBytesOnTheNewPage = sent.size();
    assertEquals(message, soundBytesOnTheNewPage, "the new page plays nothing before a press on it");
    helper.executeCommand(HelperMessage.key(HelperProtocol.KEY_PRESS, "Enter"));
    reporter.onAudio(chunk);
    final int soundBytesAfterAPressOnTheNewPage = sent.size();
    assertEquals(2 * message, soundBytesAfterAPressOnTheNewPage);
  }

  private static SocketChannel connectTo(final Path socket) throws IOException {
    final SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
    channel.connect(UnixDomainSocketAddress.of(socket));
    return channel;
  }

  private CompletableFuture<Integer> run(final BrowserHelper helper) throws IOException {
    this.server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    this.server.bind(UnixDomainSocketAddress.of(this.folder.resolve(SOCKET_NAME)));
    final PipedReader reader = new PipedReader(this.standardInput);
    return CompletableFuture.supplyAsync(() -> helper.run(reader, BrowserHelperTest::connectTo));
  }

  @AfterEach
  void closeServer() throws IOException {
    this.standardInput.close();
    if (this.server != null) {
      this.server.close();
    }
  }

  @Test
  void shouldIntroduceItselfStreamThePageForwardInputAndStopWhenAsked() throws Exception {
    final ScriptedEngine engine = new ScriptedEngine();
    final BrowserHelper helper = new BrowserHelper(this.configuration(PAGE), engine);
    final CompletableFuture<Integer> result = this.run(helper);
    try (final Peer peer = new Peer(this.server.accept())) {
      final HelperMessage hello = peer.read();
      final int helloType = hello.getType();
      assertEquals(HelperProtocol.HELLO, helloType);
      final byte[] helloToken = hello.getToken();
      assertArrayEquals(token(), helloToken);
      final String readyText = peer.readUntil(HelperProtocol.READY).getText();
      assertEquals("scripted", readyText);
      final HelperMessage first = peer.readFrameWithBlue(0);
      final FrameRegion region = first.getRegion();
      final int width = region.getWidth();
      assertEquals(4, width);
      final int height = region.getHeight();
      assertEquals(3, height);
      final int greenChannel = region.getPixels()[1] & 0xFF;
      assertEquals(ScriptedEngine.GREEN, greenChannel);
      final int redChannel = region.getPixels()[2] & 0xFF;
      assertEquals(ScriptedEngine.RED, redChannel);
      peer.send(out -> HelperProtocol.writeKey(out, HelperProtocol.KEY_TYPE, "ab"));
      peer.readFrameWithBlue(4);
      peer.send(out ->
        HelperProtocol.writeMouse(out, new MouseInput(HelperProtocol.MOUSE_PRESS, 1, 2, HelperProtocol.BUTTON_LEFT, 1, 0, 0))
      );
      peer.readFrameWithBlue(5);
      peer.send(out -> HelperProtocol.writeKey(out, HelperProtocol.KEY_PRESS, "Enter"));
      peer.readFrameWithBlue(7);
      final boolean firstCallPressesA = engine
        .getCalls()
        .get(0)
        .startsWith(DevToolsInput.KEY_METHOD + " {\"type\":\"keyDown\",\"key\":\"a\"");
      assertTrue(firstCallPressesA);
      final boolean fifthCallPressesTheMouse = engine
        .getCalls()
        .get(4)
        .startsWith(DevToolsInput.MOUSE_METHOD + " {\"type\":\"mousePressed\",\"x\":1,\"y\":2");
      assertTrue(fifthCallPressesTheMouse);
      final boolean sixthCallPressesEnter = engine.getCalls().get(5).contains("\"key\":\"Enter\"");
      assertTrue(sixthCallPressesEnter);
      final @Nullable String stopReasonWhileRunning = helper.getStopReason();
      assertNull(stopReasonWhileRunning);
      peer.send(HelperProtocol::writeClose);
      final Integer exitStatus = result.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals(0, exitStatus);
    }
    final boolean stopped = engine.isStopped();
    assertTrue(stopped);
    final @Nullable String stopReason = helper.getStopReason();
    assertEquals("the server asked to close", stopReason);
    final PageCompositor compositor = helper.getCompositor();
    assertNotNull(compositor);
  }

  @Test
  void shouldStopTheHelperAtTheEndOfTheStandardInput() throws Exception {
    final BrowserHelper helper = new BrowserHelper(this.configuration(PAGE), new ScriptedEngine());
    final CompletableFuture<Integer> result = this.run(helper);
    try (final Peer peer = new Peer(this.server.accept())) {
      peer.readUntil(HelperProtocol.READY);
      this.standardInput.write("ignored after the configuration\n");
      this.standardInput.close();
      final Integer exitStatus = result.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals(0, exitStatus);
    }
    final @Nullable String stopReason = helper.getStopReason();
    assertEquals(STANDARD_INPUT_ENDED, stopReason);
  }

  @Test
  void shouldStopTheHelperWhenTheServerClosesTheConnection() throws Exception {
    final BrowserHelper helper = new BrowserHelper(this.configuration(PAGE), new ScriptedEngine());
    final CompletableFuture<Integer> result = this.run(helper);
    final Peer peer = new Peer(this.server.accept());
    peer.readUntil(HelperProtocol.READY);
    peer.close();
    final Integer exitStatus = result.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertEquals(0, exitStatus);
    final String reason = helper.getStopReason();
    final boolean knownReason =
      reason.equals("the server closed the connection") || reason.startsWith("the connection failed") || reason.startsWith("a ");
    assertTrue(knownReason, reason);
  }

  @Test
  void shouldStopTheHelperOnAMessageOnlyAHelperSends() throws Exception {
    final BrowserHelper helper = new BrowserHelper(this.configuration(PAGE), new ScriptedEngine());
    final CompletableFuture<Integer> result = this.run(helper);
    try (final Peer peer = new Peer(this.server.accept())) {
      peer.readUntil(HelperProtocol.READY);
      peer.send(out -> HelperProtocol.writeText(out, HelperProtocol.NOTICE, "confused server"));
      final Integer exitStatus = result.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals(0, exitStatus);
    }
    final @Nullable String stopReason = helper.getStopReason();
    assertEquals("the server sent message type 6, which only a helper sends", stopReason);
  }

  @Test
  void shouldStopTheHelperOnAMalformedCommand() throws Exception {
    final BrowserHelper helper = new BrowserHelper(this.configuration(PAGE), new ScriptedEngine());
    final CompletableFuture<Integer> result = this.run(helper);
    try (final Peer peer = new Peer(this.server.accept())) {
      peer.readUntil(HelperProtocol.READY);
      peer.send(out -> {
        out.writeByte(99);
        out.writeInt(0);
      });
      final Integer exitStatus = result.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals(0, exitStatus);
    }
    final @Nullable String stopReason = helper.getStopReason();
    assertEquals("the connection failed (Message type 99 is unknown!)", stopReason);
  }

  @Test
  void shouldStopTheHelperOnAFrameFromTheServer() throws Exception {
    final BrowserHelper helper = new BrowserHelper(this.configuration(PAGE), new ScriptedEngine());
    final CompletableFuture<Integer> result = this.run(helper);
    try (final Peer peer = new Peer(this.server.accept())) {
      peer.readUntil(HelperProtocol.READY);
      peer.send(out -> HelperProtocol.writeFrame(out, new FrameRegion(4, 3, 0, 0, 2, 2, new byte[16])));
      final Integer exitStatus = result.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals(0, exitStatus);
    }
    final @Nullable String stopReason = helper.getStopReason();
    assertEquals("the connection failed (A frame of 16 bytes arrived where no frame of that size is expected!)", stopReason);
  }

  @Test
  void shouldRememberTheHeldButtonsSoAMoveIsADrag() {
    final ScriptedEngine engine = new ScriptedEngine();
    final BrowserHelper helper = new BrowserHelper(this.configuration(PAGE), engine);
    final HelperMessage press = HelperMessage.mouse(new MouseInput(HelperProtocol.MOUSE_PRESS, 5, 5, HelperProtocol.BUTTON_LEFT, 1, 0, 0));
    helper.executeCommand(press);
    final boolean readsOnAfterThePress = helper.isInput(press);
    assertTrue(readsOnAfterThePress);
    final HelperMessage drag = HelperMessage.mouse(new MouseInput(HelperProtocol.MOUSE_MOVE, 6, 6, HelperProtocol.BUTTON_LEFT, 0, 0, 0));
    helper.executeCommand(drag);
    final boolean readsOnAfterTheDrag = helper.isInput(drag);
    assertTrue(readsOnAfterTheDrag);
    final HelperMessage release = HelperMessage.mouse(
      new MouseInput(HelperProtocol.MOUSE_RELEASE, 6, 6, HelperProtocol.BUTTON_LEFT, 1, 0, 0)
    );
    helper.executeCommand(release);
    final boolean readsOnAfterTheRelease = helper.isInput(release);
    assertTrue(readsOnAfterTheRelease);
    final HelperMessage move = HelperMessage.mouse(new MouseInput(HelperProtocol.MOUSE_MOVE, 7, 7, HelperProtocol.BUTTON_LEFT, 0, 0, 0));
    helper.executeCommand(move);
    final boolean readsOnAfterTheMove = helper.isInput(move);
    assertTrue(readsOnAfterTheMove);
    final List<String> calls = engine.getCalls();
    final boolean dragHoldsTheLeftButton = calls.get(1).contains("\"buttons\":1,\"button\":\"left\"");
    assertTrue(dragHoldsTheLeftButton, calls.get(1));
    final boolean releaseHoldsNoButton = calls.get(2).contains("\"type\":\"mouseReleased\",\"x\":6,\"y\":6,\"buttons\":0");
    assertTrue(releaseHoldsNoButton, calls.get(2));
    final boolean moveHoldsNoButton = calls.get(3).contains("\"buttons\":0,\"button\":\"none\"");
    assertTrue(moveHoldsNoButton, calls.get(3));
  }

  @Test
  void shouldStopTheHelperOnABrokenStandardInput() {
    final BrowserHelper helper = new BrowserHelper(this.configuration(PAGE), new ScriptedEngine());
    helper.watchInput(
      new Reader() {
        @Override
        public int read(final char[] buffer, final int offset, final int length) throws IOException {
          throw new IOException("broken pipe");
        }

        @Override
        public void close() {}
      }
    );
    final @Nullable String stopReason = helper.getStopReason();
    assertEquals(STANDARD_INPUT_ENDED, stopReason);
  }

  @Test
  void shouldStillStopTheEngineOfAnInterruptedHelper() throws Exception {
    final ScriptedEngine engine = new ScriptedEngine();
    final BrowserHelper helper = new BrowserHelper(this.configuration(PAGE), engine);
    this.server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    this.server.bind(UnixDomainSocketAddress.of(this.folder.resolve(SOCKET_NAME)));
    final PipedReader reader = new PipedReader(this.standardInput);
    final AtomicInteger status = new AtomicInteger(-1);
    final AtomicBoolean interrupted = new AtomicBoolean();
    final Thread running = new Thread(() -> {
      status.set(helper.run(reader, BrowserHelperTest::connectTo));
      interrupted.set(Thread.currentThread().isInterrupted());
    });
    running.start();
    try (final Peer peer = new Peer(this.server.accept())) {
      peer.readUntil(HelperProtocol.READY);
      running.interrupt();
      running.join(JOIN_TIMEOUT_MILLISECONDS);
    }
    final boolean alive = running.isAlive();
    assertFalse(alive, "the interrupted helper has terminated");
    final boolean stopped = engine.isStopped();
    assertTrue(stopped, "the interrupted helper stops its engine");
    final int exitStatus = status.get();
    assertEquals(0, exitStatus);
    final boolean interruptKept = interrupted.get();
    assertTrue(interruptKept, "the interrupt is kept");
  }

  @Test
  void shouldStopTheHelperWhenFramesCannotBeSent() {
    final BrowserHelper helper = new BrowserHelper(this.configuration(PAGE), new ScriptedEngine());
    final ByteBuffer red = ByteBuffer.allocate(4 * 3 * 4);
    helper.getCompositor().onPaint(false, new Rectangle[] { new Rectangle(0, 0, 4, 3) }, red, 4, 3);
    final DataOutputStream broken = new DataOutputStream(
      new OutputStream() {
        @Override
        public void write(final int value) throws IOException {
          throw new IOException("connection reset");
        }
      }
    );
    helper.sendFrames(broken);
    final @Nullable String stopReason = helper.getStopReason();
    assertEquals("a frame could not be sent (connection reset)", stopReason);
  }

  @Test
  void shouldEndAnInterruptedFrameSenderAndKeepTheInterrupt() throws InterruptedException {
    final BrowserHelper helper = new BrowserHelper(this.configuration(PAGE), new ScriptedEngine());
    final AtomicBoolean interrupted = new AtomicBoolean();
    final Thread sender = new Thread(() -> {
      helper.sendFrames(new DataOutputStream(OutputStream.nullOutputStream()));
      interrupted.set(Thread.currentThread().isInterrupted());
    });
    sender.start();
    sender.interrupt();
    sender.join(JOIN_TIMEOUT_MILLISECONDS);
    final boolean interruptKept = interrupted.get();
    assertTrue(interruptKept);
    final @Nullable String stopReason = helper.getStopReason();
    assertNull(stopReason, "an interrupted sender does not stop the helper by itself");
  }

  @Test
  void shouldFailWhenTheEngineCannotStart() throws Exception {
    final BrowserHelper helper = new BrowserHelper(this.configuration("/throw"), new ScriptedEngine());
    final CompletableFuture<Integer> result = this.run(helper);
    try (final Peer peer = new Peer(this.server.accept())) {
      final HelperMessage failure = peer.readUntil(HelperProtocol.FAILURE);
      final String failureText = failure.getText();
      assertEquals("The browser could not be started (java.lang.IllegalStateException: scripted start failure)", failureText);
      final Integer exitStatus = result.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals(1, exitStatus);
    }
  }

  @Test
  void shouldFailWhenTheNativesOfTheEngineCannotLoad() throws Exception {
    final BrowserHelper helper = new BrowserHelper(this.configuration("/unlinked"), new ScriptedEngine());
    final CompletableFuture<Integer> result = this.run(helper);
    try (final Peer peer = new Peer(this.server.accept())) {
      final HelperMessage failure = peer.readUntil(HelperProtocol.FAILURE);
      final String failureText = failure.getText();
      assertEquals("The browser could not be started (java.lang.UnsatisfiedLinkError: scripted missing native library)", failureText);
      final Integer exitStatus = result.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals(1, exitStatus);
    }
  }

  @Test
  void shouldFailWhenTheBrowserFailsLater() throws Exception {
    final BrowserHelper helper = new BrowserHelper(this.configuration("/fail"), new ScriptedEngine());
    final CompletableFuture<Integer> result = this.run(helper);
    try (final Peer peer = new Peer(this.server.accept())) {
      final String failureText = peer.readUntil(HelperProtocol.FAILURE).getText();
      assertEquals("scripted renderer crash", failureText);
      final Integer exitStatus = result.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals(1, exitStatus);
    }
  }

  @Test
  void shouldFailWhenTheHelperCannotReachTheServer() {
    final BrowserHelper helper = new BrowserHelper(this.configuration(PAGE), new ScriptedEngine());
    final int status = helper.run(new StringReader(""), socket -> {
      throw new IOException("no server");
    });
    assertEquals(1, status);
  }

  private static void neverHalts(final int status) {
    throw new AssertionError("the helper halted with " + status);
  }

  @Test
  void shouldReadTheConfigurationFromTheFirstLine() throws Exception {
    final int statusWithoutInput = BrowserHelper.runFromInput(
      new BufferedReader(new StringReader("")),
      new ScriptedEngine(),
      BrowserHelperTest::neverHalts
    );
    assertEquals(2, statusWithoutInput);
    final int statusForGarbage = BrowserHelper.runFromInput(
      new BufferedReader(new StringReader("garbage\n")),
      new ScriptedEngine(),
      BrowserHelperTest::neverHalts
    );
    assertEquals(2, statusForGarbage);
    final String line = this.configuration(PAGE).toLine() + "\n";
    final int statusForAConfiguration = BrowserHelper.runFromInput(
      new BufferedReader(new StringReader(line)),
      new ScriptedEngine(),
      BrowserHelperTest::neverHalts
    );
    assertEquals(1, statusForAConfiguration);
  }

  @Test
  void shouldHaltAHelperThatHasNotStoppedByTheDeadlineAfterItsInputEnded() {
    final List<Integer> halts = new CopyOnWriteArrayList<>();
    final BrowserHelper helper = new BrowserHelper(this.configuration(PAGE), new ScriptedEngine(), status -> halts.add(status), 100L);
    final long start = System.nanoTime();
    helper.watchInput(new StringReader(""));
    final boolean waitedForTheDeadline = System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(100L);
    assertTrue(waitedForTheDeadline, "it waits for the deadline first");
    assertEquals(List.of(1), halts);
    final @Nullable String stopReason = helper.getStopReason();
    assertEquals(STANDARD_INPUT_ENDED, stopReason);
  }

  @Test
  void shouldLogWhyAHelperHalts() {
    final BrowserHelper helper = new BrowserHelper(this.configuration(PAGE), new ScriptedEngine(), status -> {}, 10L);
    try (final StandardError errors = new StandardError()) {
      helper.watchInput(new StringReader(""));
      final boolean reportsTheHalt = errors.text().contains("The browser helper did not stop within 10 ms and halts");
      assertTrue(reportsTheHalt, errors.text());
    }
  }

  @Test
  void shouldStillHaltAndKeepTheInterruptWhenTheDeadlineIsInterrupted() {
    final List<Integer> halts = new CopyOnWriteArrayList<>();
    final BrowserHelper helper = new BrowserHelper(this.configuration(PAGE), new ScriptedEngine(), status -> halts.add(status), 60_000L);
    Thread.currentThread().interrupt();
    try {
      helper.watchInput(new StringReader(""));
      final boolean interruptKept = Thread.currentThread().isInterrupted();
      assertTrue(interruptKept, "the interrupt is kept");
    } finally {
      Thread.interrupted();
    }
    assertEquals(List.of(1), halts);
  }

  @Test
  void shouldHaltAHelperWhoseBrowserHangsInItsStartOnceTheServerIsGone() throws Exception {
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch halted = new CountDownLatch(1);
    final HelperEngine hanging = new HelperEngine() {
      @Override
      public void start(
        final HelperConfiguration configuration,
        final McavOffscreenBrowser.PaintListener painter,
        final HelperEvents events
      ) throws InterruptedException {
        release.await();
      }

      @Override
      public void dispatch(final List<DevToolsInput.DevToolsCall> calls) {}

      @Override
      public void stop() {}
    };
    final BrowserHelper helper = new BrowserHelper(this.configuration(PAGE), hanging, status -> halted.countDown(), 200L);
    final CompletableFuture<Integer> result = this.run(helper);
    try (final Peer peer = new Peer(this.server.accept())) {
      peer.readUntil(HelperProtocol.HELLO);
      this.standardInput.close();
      final boolean haltedInTime = halted.await(30, TimeUnit.SECONDS);
      assertTrue(haltedInTime, "the helper halts although its browser never started");
    } finally {
      release.countDown();
    }
    final Integer exitStatus = result.get(30, TimeUnit.SECONDS);
    assertEquals(0, exitStatus);
  }

  @Test
  void shouldLogWhyTheHelperCannotRun() {
    final String unreachable = this.configuration(PAGE).toLine() + "\n";
    try (final StandardError errors = new StandardError()) {
      BrowserHelper.runFromInput(new BufferedReader(new StringReader("")), new ScriptedEngine(), BrowserHelperTest::neverHalts);
      BrowserHelper.runFromInput(new BufferedReader(new StringReader("garbage\n")), new ScriptedEngine(), BrowserHelperTest::neverHalts);
      BrowserHelper.runFromInput(new BufferedReader(new StringReader(unreachable)), new ScriptedEngine(), BrowserHelperTest::neverHalts);
      final String log = errors.text();
      final boolean reportsTheMissingConfiguration = log.contains("The browser helper got no configuration");
      assertTrue(reportsTheMissingConfiguration, log);
      final boolean reportsTheInvalidConfiguration = log.contains("The browser helper got an invalid configuration (");
      assertTrue(reportsTheInvalidConfiguration, log);
      final boolean reportsTheUnreachableServer = log.contains("The browser helper cannot reach the server (");
      assertTrue(reportsTheUnreachableServer, log);
    }
  }

  @Test
  void shouldLogWhyTheHelperStops() throws Exception {
    try (final StandardError errors = new StandardError()) {
      final BrowserHelper helper = new BrowserHelper(this.configuration(PAGE), new ScriptedEngine());
      final CompletableFuture<Integer> result = this.run(helper);
      try (final Peer peer = new Peer(this.server.accept())) {
        peer.readUntil(HelperProtocol.READY);
        peer.send(HelperProtocol::writeClose);
        final Integer exitStatus = result.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(0, exitStatus);
      }
      final boolean reportsTheStop = errors.text().contains("The browser helper stops (the server asked to close).");
      assertTrue(reportsTheStop, errors.text());
    }
  }

  @Test
  void shouldConnectToTheSocketOfTheServer() throws IOException {
    final Path socket = this.folder.resolve(SOCKET_NAME);
    try (final ServerSocketChannel listening = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
      listening.bind(UnixDomainSocketAddress.of(socket));
      try (final SocketChannel channel = BrowserHelper.connect(socket); final SocketChannel accepted = listening.accept()) {
        final boolean channelConnected = channel.isConnected();
        assertTrue(channelConnected);
        final boolean acceptedConnected = accepted.isConnected();
        assertTrue(acceptedConnected);
      }
    }
  }

  @Test
  void shouldLeaveNoFileOpenWhenTheConnectionFails() {
    final Path missing = this.folder.resolve("missing");
    OpenFiles.leaveNoneOpen("a failed connection", () -> assertThrows(IOException.class, () -> BrowserHelper.connect(missing)));
  }

  /**
   * Runs a helper as its main method does, on a thread that is no daemon: the threads it starts are daemons only if it
   * makes them so, while those a daemon thread starts are daemons anyway.
   */
  private CompletableFuture<Integer> runOnMainLikeThread(final BrowserHelper helper) throws IOException {
    this.server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    this.server.bind(UnixDomainSocketAddress.of(this.folder.resolve(SOCKET_NAME)));
    final PipedReader reader = new PipedReader(this.standardInput);
    final CompletableFuture<Integer> result = new CompletableFuture<>();
    Thread.ofPlatform()
      .daemon(false)
      .name("helper-main")
      .start(() -> result.complete(helper.run(reader, BrowserHelperTest::connectTo)));
    return result;
  }

  @Test
  void shouldNotKeepTheJvmAliveWithTheThreadsOfTheHelper() throws Exception {
    final BrowserHelper helper = new BrowserHelper(this.configuration(PAGE), new ScriptedEngine());
    final CompletableFuture<Integer> result = this.runOnMainLikeThread(helper);
    try (final Peer peer = new Peer(this.server.accept())) {
      peer.readUntil(HelperProtocol.READY);
      final List<Thread> threads = Thread.getAllStackTraces()
        .keySet()
        .stream()
        .filter(thread -> thread.getName().startsWith("mcav-browser-helper-"))
        .toList();
      final boolean noHelperThreads = threads.isEmpty();
      assertFalse(noHelperThreads);
      for (final Thread thread : threads) {
        final boolean daemon = thread.isDaemon();
        assertTrue(daemon, thread.getName());
      }
      peer.send(HelperProtocol::writeClose);
      final Integer exitStatus = result.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals(0, exitStatus);
    }
  }

  @Test
  void shouldStopTheHelperAtTheEndOfTheConnection() {
    final BrowserHelper helper = new BrowserHelper(this.configuration(PAGE), new ScriptedEngine());
    helper.readCommands(new DataInputStream(new ByteArrayInputStream(new byte[0])));
    final @Nullable String stopReason = helper.getStopReason();
    assertEquals("the server closed the connection", stopReason);
  }

  @Test
  void shouldReadNothingMoreAfterACloseOrAMessageOnlyAHelperSends() {
    final BrowserHelper helper = new BrowserHelper(this.configuration(PAGE), new ScriptedEngine());
    final HelperMessage close = HelperMessage.close();
    helper.executeCommand(close);
    final boolean readsOnAfterTheClose = helper.isInput(close);
    assertFalse(readsOnAfterTheClose);
    final HelperMessage helperMessage = HelperMessage.text(HelperProtocol.NOTICE, "confused server");
    helper.executeCommand(helperMessage);
    final boolean readsOnAfterTheHelperMessage = helper.isInput(helperMessage);
    assertFalse(readsOnAfterTheHelperMessage);
  }

  @Test
  void shouldSendNoMoreFramesAndCloseThePictureOnceStopped() throws Exception {
    final BrowserHelper helper = new BrowserHelper(this.configuration(PAGE), new ScriptedEngine());
    final CompletableFuture<Integer> result = this.run(helper);
    try (final Peer peer = new Peer(this.server.accept())) {
      peer.readUntil(HelperProtocol.READY);
      peer.send(HelperProtocol::writeClose);
      final Integer exitStatus = result.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals(0, exitStatus);
    }
    final PageCompositor compositor = helper.getCompositor();
    final byte[] buffer = new byte[compositor.getPageBytes()];
    final long start = System.nanoTime();
    final @Nullable FrameRegion damage = compositor.takeDamage(buffer, 10_000L);
    assertNull(damage);
    final boolean returnedAtOnce = System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5);
    assertTrue(returnedAtOnce, "the picture of the page is still open");
    final DataOutputStream unused = new DataOutputStream(OutputStream.nullOutputStream());
    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> helper.sendFrames(unused), "the frames go on after the stop");
  }

  @Test
  void shouldPassWhatTheBrowserReportsToTheServer() throws Exception {
    final BrowserHelper helper = new BrowserHelper(this.configuration(PAGE), new ScriptedEngine());
    final CompletableFuture<Integer> result = this.run(helper);
    try (final Peer peer = new Peer(this.server.accept())) {
      final int loadingAtTheStart = peer.readUntil(HelperProtocol.LOADING).getNumber();
      assertEquals(1, loadingAtTheStart);
      final String noticeText = peer.readUntil(HelperProtocol.NOTICE).getText();
      assertEquals("scripted notice", noticeText);
      final int loadingAtTheEnd = peer.readUntil(HelperProtocol.LOADING).getNumber();
      assertEquals(0, loadingAtTheEnd);
      peer.send(HelperProtocol::writeClose);
      final Integer exitStatus = result.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals(0, exitStatus);
    }
  }

  @Test
  void shouldReportAPageThatFailsToLoadToTheServer() throws Exception {
    final BrowserHelper helper = new BrowserHelper(this.configuration("/load-error"), new ScriptedEngine());
    final CompletableFuture<Integer> result = this.run(helper);
    try (final Peer peer = new Peer(this.server.accept())) {
      final HelperMessage error = peer.readUntil(HelperProtocol.LOAD_ERROR);
      final int errorCode = error.getNumber();
      assertEquals(-105, errorCode);
      final String errorText = error.getText();
      assertEquals("ERR_NAME_NOT_RESOLVED", errorText);
      final String url = error.getUrl();
      assertEquals("https://example.com/load-error", url);
      peer.send(HelperProtocol::writeClose);
      final Integer exitStatus = result.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertEquals(0, exitStatus);
    }
  }

  @Test
  void shouldStopTheHelperWhenAReportCannotBeSent() {
    final BrowserHelper helper = new BrowserHelper(this.configuration(PAGE), new ScriptedEngine());
    final OutputStream gone = new OutputStream() {
      @Override
      public void write(final int value) throws IOException {
        throw new IOException("the server is gone");
      }
    };
    final BrowserHelper.Reporter reporter = helper.new Reporter(new DataOutputStream(gone));
    reporter.onNotice("lost");
    final @Nullable String stopReason = helper.getStopReason();
    assertEquals("a message could not be sent (the server is gone)", stopReason);
  }

  @Test
  void shouldTreatAnUnreadableStandardInputAsABadConfiguration() {
    final BufferedReader broken = new BufferedReader(new StringReader("x")) {
      @Override
      public String readLine() throws IOException {
        throw new IOException("closed");
      }
    };
    final int statusForABrokenInput = BrowserHelper.runFromInput(broken, new ScriptedEngine(), BrowserHelperTest::neverHalts);
    assertEquals(2, statusForABrokenInput);
  }

  /**
   * The server side of a connection.
   */
  private static final class Peer implements AutoCloseable {

    private final SocketChannel channel;
    private final DataInputStream in;
    private final DataOutputStream out;

    Peer(final SocketChannel channel) {
      this.channel = channel;
      this.in = new DataInputStream(new BufferedInputStream(Channels.newInputStream(channel)));
      this.out = new DataOutputStream(new BufferedOutputStream(Channels.newOutputStream(channel)));
    }

    HelperMessage read() throws IOException {
      return HelperProtocol.read(this.in, NEW_BUFFER);
    }

    private HelperMessage readUntil(final int type) throws IOException {
      HelperMessage message = this.read();
      while (message.getType() != type) {
        message = this.read();
      }
      return message;
    }

    private HelperMessage readFrameWithBlue(final int blue) throws IOException {
      while (true) {
        final HelperMessage message = this.readUntil(HelperProtocol.FRAME);
        final byte[] pixels = message.getRegion().getPixels();
        if ((pixels[0] & 0xFF) == blue) {
          return message;
        }
      }
    }

    void send(final Writer writer) throws IOException {
      writer.write(this.out);
      this.out.flush();
    }

    @Override
    public void close() throws IOException {
      this.channel.close();
    }
  }

  @FunctionalInterface
  private interface Writer {
    void write(DataOutputStream out) throws IOException;
  }
}
