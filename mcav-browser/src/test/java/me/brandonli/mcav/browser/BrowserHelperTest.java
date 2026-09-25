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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.StringReader;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import me.brandonli.mcav.browser.testing.OpenFiles;
import me.brandonli.mcav.browser.testing.StandardError;
import org.cef.browser.McavOffscreenBrowser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrowserHelperTest {

  private static final IntFunction<byte[]> NEW_BUFFER = byte[]::new;

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
    return new HelperConfiguration(
      token(),
      this.folder.resolve("s"),
      this.folder.resolve("natives"),
      this.folder.resolve("profile"),
      URI.create("https://example.com" + path),
      4,
      3,
      1,
      30,
      false,
      false,
      false
    );
  }

  private static SocketChannel connectTo(final Path socket) throws IOException {
    final SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
    channel.connect(UnixDomainSocketAddress.of(socket));
    return channel;
  }

  private CompletableFuture<Integer> run(final BrowserHelper helper) throws IOException {
    this.server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    this.server.bind(UnixDomainSocketAddress.of(this.folder.resolve("s")));
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

    HelperMessage readUntil(final int type) throws IOException {
      HelperMessage message = this.read();
      while (message.getType() != type) {
        message = this.read();
      }
      return message;
    }

    HelperMessage readFrameWithBlue(final int blue) throws IOException {
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

  @Test
  void aHelperIntroducesItselfStreamsThePageForwardsInputAndStopsWhenAsked() throws Exception {
    final ScriptedEngine engine = new ScriptedEngine();
    final BrowserHelper helper = new BrowserHelper(this.configuration("/page"), engine);
    final CompletableFuture<Integer> result = this.run(helper);
    try (final Peer peer = new Peer(this.server.accept())) {
      final HelperMessage hello = peer.read();
      assertEquals(HelperProtocol.HELLO, hello.getType());
      assertArrayEquals(token(), hello.getToken());
      assertEquals("scripted", peer.readUntil(HelperProtocol.READY).getText());
      final HelperMessage first = peer.readFrameWithBlue(0);
      final FrameRegion region = first.getRegion();
      assertEquals(4, region.getWidth());
      assertEquals(3, region.getHeight());
      assertEquals(ScriptedEngine.GREEN, region.getPixels()[1] & 0xFF);
      assertEquals(ScriptedEngine.RED, region.getPixels()[2] & 0xFF);
      peer.send(out -> HelperProtocol.writeKey(out, HelperProtocol.KEY_TYPE, "ab"));
      peer.readFrameWithBlue(4);
      peer.send(out -> HelperProtocol.writeMouse(out, new MouseInput(HelperProtocol.MOUSE_PRESS, 1, 2, HelperProtocol.BUTTON_LEFT, 1, 0, 0))
      );
      peer.readFrameWithBlue(5);
      peer.send(out -> HelperProtocol.writeKey(out, HelperProtocol.KEY_PRESS, "Enter"));
      peer.readFrameWithBlue(7);
      assertTrue(engine.getCalls().get(0).startsWith(DevToolsInput.KEY_METHOD + " {\"type\":\"keyDown\",\"key\":\"a\""));
      assertTrue(engine.getCalls().get(4).startsWith(DevToolsInput.MOUSE_METHOD + " {\"type\":\"mousePressed\",\"x\":1,\"y\":2"));
      assertTrue(engine.getCalls().get(5).contains("\"key\":\"Enter\""));
      assertNull(helper.getStopReason());
      peer.send(HelperProtocol::writeClose);
      assertEquals(0, result.get(10, TimeUnit.SECONDS));
    }
    assertTrue(engine.isStopped());
    assertEquals("the server asked to close", helper.getStopReason());
    assertNotNull(helper.getCompositor());
  }

  @Test
  void theEndOfTheStandardInputStopsTheHelper() throws Exception {
    final BrowserHelper helper = new BrowserHelper(this.configuration("/page"), new ScriptedEngine());
    final CompletableFuture<Integer> result = this.run(helper);
    try (final Peer peer = new Peer(this.server.accept())) {
      peer.readUntil(HelperProtocol.READY);
      this.standardInput.write("ignored after the configuration\n");
      this.standardInput.close();
      assertEquals(0, result.get(10, TimeUnit.SECONDS));
    }
    assertEquals("the standard input ended", helper.getStopReason());
  }

  @Test
  void aConnectionTheServerClosesStopsTheHelper() throws Exception {
    final BrowserHelper helper = new BrowserHelper(this.configuration("/page"), new ScriptedEngine());
    final CompletableFuture<Integer> result = this.run(helper);
    final Peer peer = new Peer(this.server.accept());
    peer.readUntil(HelperProtocol.READY);
    peer.close();
    assertEquals(0, result.get(10, TimeUnit.SECONDS));
    final String reason = helper.getStopReason();
    assertTrue(
      reason.equals("the server closed the connection") || reason.startsWith("the connection failed") || reason.startsWith("a "),
      reason
    );
  }

  @Test
  void aMessageOnlyAHelperSendsStopsTheHelper() throws Exception {
    final BrowserHelper helper = new BrowserHelper(this.configuration("/page"), new ScriptedEngine());
    final CompletableFuture<Integer> result = this.run(helper);
    try (final Peer peer = new Peer(this.server.accept())) {
      peer.readUntil(HelperProtocol.READY);
      peer.send(out -> HelperProtocol.writeText(out, HelperProtocol.NOTICE, "confused server"));
      assertEquals(0, result.get(10, TimeUnit.SECONDS));
    }
    assertEquals("the server sent message type 6, which only a helper sends", helper.getStopReason());
  }

  @Test
  void aMalformedCommandStopsTheHelper() throws Exception {
    final BrowserHelper helper = new BrowserHelper(this.configuration("/page"), new ScriptedEngine());
    final CompletableFuture<Integer> result = this.run(helper);
    try (final Peer peer = new Peer(this.server.accept())) {
      peer.readUntil(HelperProtocol.READY);
      peer.send(out -> {
        out.writeByte(99);
        out.writeInt(0);
      });
      assertEquals(0, result.get(10, TimeUnit.SECONDS));
    }
    assertEquals("the connection failed: Unknown message type 99", helper.getStopReason());
  }

  @Test
  void aFrameFromTheServerStopsTheHelper() throws Exception {
    final BrowserHelper helper = new BrowserHelper(this.configuration("/page"), new ScriptedEngine());
    final CompletableFuture<Integer> result = this.run(helper);
    try (final Peer peer = new Peer(this.server.accept())) {
      peer.readUntil(HelperProtocol.READY);
      peer.send(out -> HelperProtocol.writeFrame(out, new FrameRegion(4, 3, 0, 0, 2, 2, new byte[16])));
      assertEquals(0, result.get(10, TimeUnit.SECONDS));
    }
    assertEquals("the connection failed: A frame of 16 bytes arrived where no frame of that size is expected", helper.getStopReason());
  }

  @Test
  void theHelperRemembersTheHeldButtonsSoAMoveIsADrag() {
    final ScriptedEngine engine = new ScriptedEngine();
    final BrowserHelper helper = new BrowserHelper(this.configuration("/page"), engine);
    assertTrue(
      helper.handleCommand(HelperMessage.mouse(new MouseInput(HelperProtocol.MOUSE_PRESS, 5, 5, HelperProtocol.BUTTON_LEFT, 1, 0, 0)))
    );
    assertTrue(
      helper.handleCommand(HelperMessage.mouse(new MouseInput(HelperProtocol.MOUSE_MOVE, 6, 6, HelperProtocol.BUTTON_LEFT, 0, 0, 0)))
    );
    assertTrue(
      helper.handleCommand(HelperMessage.mouse(new MouseInput(HelperProtocol.MOUSE_RELEASE, 6, 6, HelperProtocol.BUTTON_LEFT, 1, 0, 0)))
    );
    assertTrue(
      helper.handleCommand(HelperMessage.mouse(new MouseInput(HelperProtocol.MOUSE_MOVE, 7, 7, HelperProtocol.BUTTON_LEFT, 0, 0, 0)))
    );
    final List<String> calls = engine.getCalls();
    assertTrue(calls.get(1).contains("\"buttons\":1,\"button\":\"left\""), calls.get(1));
    assertTrue(calls.get(2).contains("\"type\":\"mouseReleased\",\"x\":6,\"y\":6,\"buttons\":0"), calls.get(2));
    assertTrue(calls.get(3).contains("\"buttons\":0,\"button\":\"none\""), calls.get(3));
  }

  @Test
  void aBrokenStandardInputStopsTheHelper() {
    final BrowserHelper helper = new BrowserHelper(this.configuration("/page"), new ScriptedEngine());
    helper.watchInput(
      new java.io.Reader() {
        @Override
        public int read(final char[] buffer, final int offset, final int length) throws IOException {
          throw new IOException("broken pipe");
        }

        @Override
        public void close() {
          // nothing to close
        }
      }
    );
    assertEquals("the standard input ended", helper.getStopReason());
  }

  @Test
  void anInterruptedHelperStillStopsItsEngine() throws Exception {
    final BrowserHelper helper = new BrowserHelper(this.configuration("/page"), new ScriptedEngine());
    this.server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    this.server.bind(UnixDomainSocketAddress.of(this.folder.resolve("s")));
    final PipedReader reader = new PipedReader(this.standardInput);
    final java.util.concurrent.atomic.AtomicInteger status = new java.util.concurrent.atomic.AtomicInteger(-1);
    final java.util.concurrent.atomic.AtomicBoolean interrupted = new java.util.concurrent.atomic.AtomicBoolean();
    final Thread running = new Thread(() -> {
      status.set(helper.run(reader, BrowserHelperTest::connectTo));
      interrupted.set(Thread.currentThread().isInterrupted());
    });
    running.start();
    try (final Peer peer = new Peer(this.server.accept())) {
      peer.readUntil(HelperProtocol.READY);
      running.interrupt();
      running.join(10_000L);
    }
    assertEquals(0, status.get());
    assertTrue(interrupted.get(), "the interrupt is kept");
  }

  @Test
  void framesThatCannotBeSentStopTheHelper() {
    final BrowserHelper helper = new BrowserHelper(this.configuration("/page"), new ScriptedEngine());
    final java.nio.ByteBuffer red = java.nio.ByteBuffer.allocate(4 * 3 * 4);
    helper.getCompositor().onPaint(false, new java.awt.Rectangle[] { new java.awt.Rectangle(0, 0, 4, 3) }, red, 4, 3);
    final DataOutputStream broken = new DataOutputStream(
      new java.io.OutputStream() {
        @Override
        public void write(final int value) throws IOException {
          throw new IOException("connection reset");
        }
      }
    );
    helper.sendFrames(broken);
    assertEquals("a frame could not be sent: connection reset", helper.getStopReason());
  }

  @Test
  void anInterruptedFrameSenderEndsAndKeepsTheInterrupt() throws InterruptedException {
    final BrowserHelper helper = new BrowserHelper(this.configuration("/page"), new ScriptedEngine());
    final java.util.concurrent.atomic.AtomicBoolean interrupted = new java.util.concurrent.atomic.AtomicBoolean();
    final Thread sender = new Thread(() -> {
      helper.sendFrames(new DataOutputStream(java.io.OutputStream.nullOutputStream()));
      interrupted.set(Thread.currentThread().isInterrupted());
    });
    sender.start();
    sender.interrupt();
    sender.join(10_000L);
    assertTrue(interrupted.get());
    assertNull(helper.getStopReason(), "an interrupted sender does not stop the helper by itself");
  }

  @Test
  void anEngineThatCannotStartIsAFailure() throws Exception {
    final BrowserHelper helper = new BrowserHelper(this.configuration("/throw"), new ScriptedEngine());
    final CompletableFuture<Integer> result = this.run(helper);
    try (final Peer peer = new Peer(this.server.accept())) {
      final HelperMessage failure = peer.readUntil(HelperProtocol.FAILURE);
      assertEquals("The browser could not be started: java.lang.IllegalStateException: scripted start failure", failure.getText());
      assertEquals(1, result.get(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void aBrowserThatFailsLaterIsAFailure() throws Exception {
    final BrowserHelper helper = new BrowserHelper(this.configuration("/fail"), new ScriptedEngine());
    final CompletableFuture<Integer> result = this.run(helper);
    try (final Peer peer = new Peer(this.server.accept())) {
      assertEquals("scripted renderer crash", peer.readUntil(HelperProtocol.FAILURE).getText());
      assertEquals(1, result.get(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void aHelperThatCannotReachTheServerFails() {
    final BrowserHelper helper = new BrowserHelper(this.configuration("/page"), new ScriptedEngine());
    final int status = helper.run(new StringReader(""), socket -> {
      throw new IOException("no server");
    });
    assertEquals(1, status);
  }

  private static void neverHalts(final int status) {
    throw new AssertionError("the helper halted with " + status);
  }

  @Test
  void theConfigurationComesFromTheFirstLine() throws Exception {
    assertEquals(
      2,
      BrowserHelper.runFromInput(new BufferedReader(new StringReader("")), new ScriptedEngine(), BrowserHelperTest::neverHalts)
    );
    assertEquals(
      2,
      BrowserHelper.runFromInput(new BufferedReader(new StringReader("garbage\n")), new ScriptedEngine(), BrowserHelperTest::neverHalts)
    );
    final String line = this.configuration("/page").toLine() + "\n";
    // nothing listens on the socket, so the helper cannot connect
    assertEquals(
      1,
      BrowserHelper.runFromInput(new BufferedReader(new StringReader(line)), new ScriptedEngine(), BrowserHelperTest::neverHalts)
    );
  }

  @Test
  void aHelperThatHasNotStoppedByTheDeadlineAfterItsInputEndedHalts() {
    final List<Integer> halts = new java.util.concurrent.CopyOnWriteArrayList<>();
    final BrowserHelper helper = new BrowserHelper(this.configuration("/page"), new ScriptedEngine(), status -> halts.add(status), 100L);
    final long start = System.nanoTime();
    helper.watchInput(new StringReader(""));
    assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(100L), "it waits for the deadline first");
    assertEquals(List.of(1), halts);
    assertEquals("the standard input ended", helper.getStopReason());
  }

  @Test
  void aHelperThatHaltsLogsWhy() {
    final BrowserHelper helper = new BrowserHelper(this.configuration("/page"), new ScriptedEngine(), status -> {}, 10L);
    try (final StandardError errors = new StandardError()) {
      helper.watchInput(new StringReader(""));
      assertTrue(errors.text().contains("The browser helper did not stop within 10 ms and halts"), errors.text());
    }
  }

  @Test
  void anInterruptedDeadlineStillHaltsAndKeepsTheInterrupt() {
    final List<Integer> halts = new java.util.concurrent.CopyOnWriteArrayList<>();
    final BrowserHelper helper = new BrowserHelper(this.configuration("/page"), new ScriptedEngine(), status -> halts.add(status), 60_000L);
    Thread.currentThread().interrupt();
    try {
      helper.watchInput(new StringReader(""));
      assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is kept");
    } finally {
      Thread.interrupted();
    }
    assertEquals(List.of(1), halts);
  }

  @Test
  void aHelperWhoseBrowserHangsInItsStartHaltsOnceTheServerIsGone() throws Exception {
    final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    final java.util.concurrent.CountDownLatch halted = new java.util.concurrent.CountDownLatch(1);
    final HelperEngine hanging = new HelperEngine() {
      @Override
      public void start(
        final HelperConfiguration configuration,
        final McavOffscreenBrowser.PaintListener painter,
        final HelperEvents events
      ) throws InterruptedException {
        // CEF that never finishes its initialization
        release.await();
      }

      @Override
      public void dispatch(final List<DevToolsInput.DevToolsCall> calls) {
        // no page to send input to
      }

      @Override
      public void stop() {
        // nothing started
      }
    };
    final BrowserHelper helper = new BrowserHelper(this.configuration("/page"), hanging, status -> halted.countDown(), 200L);
    final CompletableFuture<Integer> result = this.run(helper);
    try (final Peer peer = new Peer(this.server.accept())) {
      peer.readUntil(HelperProtocol.HELLO);
      // the server dies: its end of the standard input closes
      this.standardInput.close();
      assertTrue(halted.await(30, TimeUnit.SECONDS), "the helper halts although its browser never started");
    } finally {
      release.countDown();
    }
    assertEquals(0, result.get(30, TimeUnit.SECONDS));
  }

  @Test
  void theHelperLogsWhyItCannotRun() {
    final String unreachable = this.configuration("/page").toLine() + "\n";
    try (final StandardError errors = new StandardError()) {
      BrowserHelper.runFromInput(new BufferedReader(new StringReader("")), new ScriptedEngine(), BrowserHelperTest::neverHalts);
      BrowserHelper.runFromInput(new BufferedReader(new StringReader("garbage\n")), new ScriptedEngine(), BrowserHelperTest::neverHalts);
      BrowserHelper.runFromInput(new BufferedReader(new StringReader(unreachable)), new ScriptedEngine(), BrowserHelperTest::neverHalts);
      final String log = errors.text();
      assertTrue(log.contains("The browser helper got no configuration"), log);
      assertTrue(log.contains("The browser helper got an invalid configuration: "), log);
      assertTrue(log.contains("The browser helper cannot reach the server: "), log);
    }
  }

  @Test
  void theHelperLogsWhyItStops() throws Exception {
    try (final StandardError errors = new StandardError()) {
      final BrowserHelper helper = new BrowserHelper(this.configuration("/page"), new ScriptedEngine());
      final CompletableFuture<Integer> result = this.run(helper);
      try (final Peer peer = new Peer(this.server.accept())) {
        peer.readUntil(HelperProtocol.READY);
        peer.send(HelperProtocol::writeClose);
        assertEquals(0, result.get(10, TimeUnit.SECONDS));
      }
      assertTrue(errors.text().contains("The browser helper stops: the server asked to close"), errors.text());
    }
  }

  @Test
  void theHelperConnectsToTheSocketOfTheServer() throws IOException {
    final Path socket = this.folder.resolve("s");
    try (final ServerSocketChannel listening = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
      listening.bind(UnixDomainSocketAddress.of(socket));
      try (final SocketChannel channel = BrowserHelper.connect(socket); final SocketChannel accepted = listening.accept()) {
        assertTrue(channel.isConnected());
        assertTrue(accepted.isConnected());
      }
    }
  }

  @Test
  void aConnectionThatFailsLeavesNoFileOpen() {
    final Path missing = this.folder.resolve("missing");
    OpenFiles.leaveNoneOpen("a failed connection", () -> assertThrows(IOException.class, () -> BrowserHelper.connect(missing)));
  }

  @Test
  void theThreadsOfTheHelperDoNotKeepItsJvmAlive() throws Exception {
    final BrowserHelper helper = new BrowserHelper(this.configuration("/page"), new ScriptedEngine());
    final CompletableFuture<Integer> result = this.run(helper);
    try (final Peer peer = new Peer(this.server.accept())) {
      peer.readUntil(HelperProtocol.READY);
      final List<Thread> threads = Thread.getAllStackTraces()
        .keySet()
        .stream()
        .filter(thread -> thread.getName().startsWith("mcav-browser-helper-"))
        .toList();
      assertFalse(threads.isEmpty());
      for (final Thread thread : threads) {
        assertTrue(thread.isDaemon(), thread.getName());
      }
      peer.send(HelperProtocol::writeClose);
      assertEquals(0, result.get(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void theEndOfTheConnectionStopsTheHelper() {
    final BrowserHelper helper = new BrowserHelper(this.configuration("/page"), new ScriptedEngine());
    helper.readCommands(new DataInputStream(new ByteArrayInputStream(new byte[0])));
    assertEquals("the server closed the connection", helper.getStopReason());
  }

  @Test
  void theHelperReadsNothingMoreAfterACloseOrAMessageOnlyAHelperSends() {
    final BrowserHelper helper = new BrowserHelper(this.configuration("/page"), new ScriptedEngine());
    assertFalse(helper.handleCommand(HelperMessage.close()));
    assertFalse(helper.handleCommand(HelperMessage.text(HelperProtocol.NOTICE, "confused server")));
  }

  @Test
  void aStoppedHelperSendsNoMoreFramesAndClosesThePicture() throws Exception {
    final BrowserHelper helper = new BrowserHelper(this.configuration("/page"), new ScriptedEngine());
    final CompletableFuture<Integer> result = this.run(helper);
    try (final Peer peer = new Peer(this.server.accept())) {
      peer.readUntil(HelperProtocol.READY);
      peer.send(HelperProtocol::writeClose);
      assertEquals(0, result.get(10, TimeUnit.SECONDS));
    }
    final PageCompositor compositor = helper.getCompositor();
    final byte[] buffer = new byte[compositor.getPageBytes()];
    // a closed picture answers at once, with nothing; an open one would wait for damage
    final long start = System.nanoTime();
    assertNull(compositor.takeDamage(buffer, 10_000L));
    assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5), "the picture of the page is still open");
    final DataOutputStream unused = new DataOutputStream(OutputStream.nullOutputStream());
    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> helper.sendFrames(unused), "the frames go on after the stop");
  }

  @Test
  void whatTheBrowserReportsReachesTheServer() throws Exception {
    final BrowserHelper helper = new BrowserHelper(this.configuration("/page"), new ScriptedEngine());
    final CompletableFuture<Integer> result = this.run(helper);
    try (final Peer peer = new Peer(this.server.accept())) {
      assertEquals(1, peer.readUntil(HelperProtocol.LOADING).getNumber());
      assertEquals("scripted notice", peer.readUntil(HelperProtocol.NOTICE).getText());
      assertEquals(0, peer.readUntil(HelperProtocol.LOADING).getNumber());
      peer.send(HelperProtocol::writeClose);
      assertEquals(0, result.get(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void aPageThatFailsToLoadIsReportedToTheServer() throws Exception {
    final BrowserHelper helper = new BrowserHelper(this.configuration("/load-error"), new ScriptedEngine());
    final CompletableFuture<Integer> result = this.run(helper);
    try (final Peer peer = new Peer(this.server.accept())) {
      final HelperMessage error = peer.readUntil(HelperProtocol.LOAD_ERROR);
      assertEquals(-105, error.getNumber());
      assertEquals("ERR_NAME_NOT_RESOLVED", error.getText());
      assertEquals("https://example.com/load-error", error.getUrl());
      peer.send(HelperProtocol::writeClose);
      assertEquals(0, result.get(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void aReportThatCannotBeSentStopsTheHelper() {
    final BrowserHelper helper = new BrowserHelper(this.configuration("/page"), new ScriptedEngine());
    final OutputStream gone = new OutputStream() {
      @Override
      public void write(final int value) throws IOException {
        throw new IOException("the server is gone");
      }
    };
    final BrowserHelper.Reporter reporter = helper.new Reporter(new DataOutputStream(gone));
    reporter.onNotice("lost");
    assertEquals("a message could not be sent: the server is gone", helper.getStopReason());
  }

  @Test
  void anUnreadableStandardInputIsABadConfiguration() {
    final BufferedReader broken = new BufferedReader(new StringReader("x")) {
      @Override
      public String readLine() throws IOException {
        throw new IOException("closed");
      }
    };
    assertEquals(2, BrowserHelper.runFromInput(broken, new ScriptedEngine(), BrowserHelperTest::neverHalts));
  }
}
