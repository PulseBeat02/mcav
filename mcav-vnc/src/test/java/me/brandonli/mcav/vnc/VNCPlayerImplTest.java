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
package me.brandonli.mcav.vnc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.shinyhut.vernacular.client.VernacularClient;
import com.shinyhut.vernacular.client.VernacularConfig;
import com.shinyhut.vernacular.client.exceptions.AuthenticationFailedException;
import com.shinyhut.vernacular.client.exceptions.NoSupportedSecurityTypesException;
import com.shinyhut.vernacular.client.exceptions.UnknownMessageTypeException;
import com.shinyhut.vernacular.client.exceptions.VncException;
import com.shinyhut.vernacular.client.rendering.ColorDepth;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.builder.VideoPipelineStepBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.interaction.MouseClick;
import me.brandonli.mcav.vnc.testing.Await;
import me.brandonli.mcav.vnc.testing.RfbTestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Tests {@link VNCPlayerImpl} and the default methods of {@link VNCPlayer}.
 *
 * <p>Most tests talk to a {@link RfbTestServer} on the loopback interface, so the whole path from the socket to the
 * pipeline is exercised. Tests of failures the real client cannot produce on demand use a mocked
 * {@link VernacularClient} whose listeners are driven by the test.
 */
final class VNCPlayerImplTest {

  private static final int RED = 0xFF0000;
  private static final int GREEN = 0x00FF00;
  private static final int BLUE = 0x0000FF;
  private static final String RENDER_THREAD = "mcav-vnc-render";

  private final List<AutoCloseable> resources = new CopyOnWriteArrayList<>();
  private final List<RecordedFrame> frames = new CopyOnWriteArrayList<>();
  private final List<String> errorMessages = new CopyOnWriteArrayList<>();
  private final List<Throwable> errors = new CopyOnWriteArrayList<>();

  @AfterEach
  void closeResources() throws Exception {
    // newest first, so a player is released before the server it is connected to; a server closed first would
    // wait for the still connected client until its join timeout runs out
    final List<AutoCloseable> newestFirst = this.resources.reversed();
    for (final AutoCloseable resource : newestFirst) {
      resource.close();
    }
  }

  /**
   * Loads the OpenCV libraries that every frame goes through before any test runs. JavaCV loads them on first use,
   * which takes seconds on a busy machine, and that one-time cost would otherwise count against the timeout of
   * whichever test receives the first frame.
   */
  @BeforeAll
  static void loadImageLibraries() {
    final BufferedImage frame = image(2, 2, RED);
    final ResizeFilter resizeFilter = new ResizeFilter(1, 1);
    final OriginalVideoMetadata metadata = OriginalVideoMetadata.of(1, 1, 50);
    try (final ImageBuffer buffer = ImageBuffer.image(frame)) {
      resizeFilter.applyFilter(buffer, metadata);
    }
  }

  private RfbTestServer server(final int width, final int height) {
    final RfbTestServer server = RfbTestServer.start(width, height);
    this.resources.add(server);
    return server;
  }

  private RfbTestServer server(final RfbTestServer.Security security, final String password) {
    final RfbTestServer server = RfbTestServer.start(64, 48, security, password);
    this.resources.add(server);
    return server;
  }

  private ServerSocket listeningSocket() throws IOException {
    final InetAddress loopback = InetAddress.getLoopbackAddress();
    final ServerSocket socket = new ServerSocket(0, 5, loopback);
    this.resources.add(socket);
    return socket;
  }

  private VNCPlayerImpl track(final VNCPlayerImpl player) {
    this.resources.add(player::release);
    final BiConsumer<String, Throwable> handler = (message, error) -> {
      this.errorMessages.add(message);
      this.errors.add(error);
    };
    player.setExceptionHandler(handler);
    final VideoFilter recorder = this::record;
    attach(player, recorder);
    return player;
  }

  private static void attach(final VNCPlayer player, final VideoFilter filter) {
    final VideoPipelineStepBuilder builder = PipelineBuilder.video();
    builder.then(filter);
    final VideoPipelineStep pipeline = builder.build();
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    callback.attach(pipeline);
  }

  private VNCPlayerImpl player() {
    final VNCPlayerImpl player = new VNCPlayerImpl();
    return this.track(player);
  }

  private VNCPlayerImpl mockedPlayer(final VernacularClient client, final AtomicReference<VernacularConfig> config) {
    final VNCPlayerImpl player = new VNCPlayerImpl(
      configuration -> {
        config.set(configuration);
        return client;
      },
      Socket::new
    );
    return this.track(player);
  }

  private boolean record(final ImageBuffer image, final OriginalVideoMetadata metadata) {
    final int width = image.getWidth();
    final int height = image.getHeight();
    final int[] pixels = image.getPixels();
    final int rgb = pixels[0] & 0xFFFFFF;
    final int metadataWidth = metadata.getVideoWidth();
    final int metadataHeight = metadata.getVideoHeight();
    final float frameRate = metadata.getVideoFrameRate();
    final RecordedFrame frame = new RecordedFrame(width, height, rgb, metadataWidth, metadataHeight, frameRate);
    this.frames.add(frame);
    return true;
  }

  private static VNCSource.Builder hostBuilder(final int port) {
    final VNCSource.Builder builder = VNCSource.builder();
    builder.host("127.0.0.1");
    builder.port(port);
    return builder;
  }

  private static VNCSource.Builder hostBuilder(final ServerSocket socket) {
    final int port = socket.getLocalPort();
    return hostBuilder(port);
  }

  private static VNCSource.Builder hostBuilder(final RfbTestServer server) {
    final int port = server.getPort();
    return hostBuilder(port);
  }

  private static VNCSource source(final VNCSource.Builder builder, final int width, final int height) {
    builder.screenWidth(width);
    builder.screenHeight(height);
    builder.targetFrameRate(50);
    return builder.build();
  }

  private static VNCSource source(final ServerSocket socket, final int width, final int height) {
    final VNCSource.Builder builder = hostBuilder(socket);
    return source(builder, width, height);
  }

  private static VNCSource source(final RfbTestServer server, final int width, final int height) {
    final VNCSource.Builder builder = hostBuilder(server);
    return source(builder, width, height);
  }

  private RecordedFrame awaitFrame(final String description, final Predicate<RecordedFrame> condition) {
    final AtomicReference<RecordedFrame> found = new AtomicReference<>();
    Await.until(description, () -> {
      for (final RecordedFrame frame : this.frames) {
        if (condition.test(frame)) {
          found.set(frame);
          return true;
        }
      }
      return false;
    });
    return found.get();
  }

  private boolean containsBlueFrame() {
    for (final RecordedFrame frame : this.frames) {
      if (frame.rgb == BLUE) {
        return true;
      }
    }
    return false;
  }

  private static BufferedImage image(final int width, final int height, final int rgb) {
    final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        image.setRGB(x, y, rgb);
      }
    }
    return image;
  }

  private static void pushScreen(final AtomicReference<VernacularConfig> config, final Image image) {
    final VernacularConfig configuration = config.get();
    final Consumer<Image> listener = configuration.getScreenUpdateListener();
    listener.accept(image);
  }

  private static void pushError(final AtomicReference<VernacularConfig> config, final VncException error) {
    final VernacularConfig configuration = config.get();
    final Consumer<VncException> listener = configuration.getErrorListener();
    listener.accept(error);
  }

  private static Socket startedSocket(final VernacularClient client) {
    final ArgumentCaptor<Socket> captor = ArgumentCaptor.forClass(Socket.class);
    verify(client).start(captor.capture());
    return captor.getValue();
  }

  @Test
  void streamsTheRemoteScreenScaledToTheConfiguredSize() {
    final RfbTestServer server = this.server(64, 48);
    final VNCPlayerImpl player = this.player();
    final VNCSource source = source(server, 32, 24);
    final boolean started = player.start(source);
    final boolean playing = player.isPlaying();
    assertTrue(started);
    assertTrue(playing);

    final RecordedFrame red = this.awaitFrame("a red frame arrives", frame -> frame.rgb == RED);
    assertEquals(32, red.width);
    assertEquals(24, red.height);
    assertEquals(32, red.metadataWidth);
    assertEquals(24, red.metadataHeight);
    assertEquals(50.0F, red.frameRate);

    server.setColor(BLUE);
    final RecordedFrame blue = this.awaitFrame("the screen turns blue", frame -> frame.rgb == BLUE);
    assertEquals(32, blue.width);
    final boolean empty = this.errorMessages.isEmpty();
    assertTrue(empty, this.errorMessages::toString);
  }

  @Test
  void keepsTheSizeOfTheRemoteScreenWhenNoSizeIsConfigured() {
    final RfbTestServer server = this.server(40, 30);
    final VNCPlayerImpl player = this.player();
    final VNCSource source = source(server, 0, 0);
    player.start(source);
    final RecordedFrame frame = this.awaitFrame("a frame arrives", _ -> true);
    assertEquals(40, frame.width);
    assertEquals(30, frame.height);
    assertEquals(RED, frame.rgb);
  }

  @Test
  void forwardsKeysAndTypedTextToTheServer() {
    final RfbTestServer server = this.server(64, 48);
    final VNCPlayerImpl player = this.player();
    final VNCSource source = source(server, 0, 0);
    player.start(source);
    player.sendKeyEvent("Return");
    player.sendKeyEvent("hi");
    Await.until("six key events arrive", () -> server.getKeyCount() == 6);

    final List<RfbTestServer.ReceivedKey> keys = server.getKeys();
    final int[] expectedSymbols = { 0xff0d, 0xff0d, 'h', 'h', 'i', 'i' };
    final boolean[] expectedDown = { true, false, true, false, true, false };
    for (int index = 0; index < expectedSymbols.length; index++) {
      final RfbTestServer.ReceivedKey key = keys.get(index);
      final int keysym = key.getKeysym();
      final boolean down = key.isDown();
      assertEquals(expectedSymbols[index], keysym, "keysym " + index);
      assertEquals(expectedDown[index], down, "state " + index);
    }
  }

  @Test
  void translatesPointerInputToTheRemoteScreen() {
    final RfbTestServer server = this.server(200, 100);
    final VNCPlayerImpl player = this.player();
    final VNCSource source = source(server, 100, 50);
    player.start(source);
    this.awaitFrame("the first frame arrives", _ -> true);
    player.moveMouse(10, 20);
    player.sendMouseEvent(MouseClick.LEFT, 50, 25);
    player.sendMouseEvent(MouseClick.RIGHT, 1000, -5);

    // a move is one event, and each click is a move, a press and a release
    Await.until("seven pointer events arrive", () -> server.getPointerCount() >= 7);
    final List<RfbTestServer.ReceivedPointer> pointers = server.getPointers();
    final int count = pointers.size();
    assertEquals(7, count);
    final int[][] expected = { { 0, 20, 40 }, { 0, 100, 50 }, { 1, 100, 50 }, { 0, 100, 50 }, { 0, 199, 0 }, { 4, 199, 0 }, { 0, 199, 0 } };
    for (int index = 0; index < expected.length; index++) {
      final RfbTestServer.ReceivedPointer pointer = pointers.get(index);
      final int mask = pointer.getButtonMask();
      final int x = pointer.getX();
      final int y = pointer.getY();
      assertEquals(expected[index][0], mask, "mask " + index);
      assertEquals(expected[index][1], x, "x " + index);
      assertEquals(expected[index][2], y, "y " + index);
    }
  }

  @Test
  void sendsUntranslatedCoordinatesBeforeTheFirstFrame() {
    final RfbTestServer server = this.server(200, 100);
    server.setAnswering(false);
    final VNCPlayerImpl player = this.player();
    final VNCSource source = source(server, 100, 50);
    player.start(source);
    player.moveMouse(30, -7);
    Await.until("the pointer event arrives", () -> server.getPointerCount() == 1);

    final List<RfbTestServer.ReceivedPointer> pointers = server.getPointers();
    final RfbTestServer.ReceivedPointer pointer = pointers.getFirst();
    final int x = pointer.getX();
    final int y = pointer.getY();
    final boolean noFrames = this.frames.isEmpty();
    assertEquals(30, x);
    assertEquals(0, y);
    assertTrue(noFrames);

    server.setAnswering(true);
    this.awaitFrame("a frame arrives once the server answers", _ -> true);
  }

  @Test
  void pausesAndResumesTheStream() {
    final RfbTestServer server = this.server(16, 16);
    final VNCPlayerImpl player = this.player();
    final VNCSource source = source(server, 0, 0);
    player.start(source);
    this.awaitFrame("a red frame arrives", frame -> frame.rgb == RED);

    final boolean paused = player.pause();
    final boolean pausedAgain = player.pause();
    final boolean playingWhilePaused = player.isPlaying();
    assertTrue(paused);
    assertFalse(pausedAgain);
    assertFalse(playingWhilePaused);
    this.assertNoBlueFramesArrive(server);

    final boolean resumed = player.resume();
    final boolean resumedAgain = player.resume();
    final boolean playing = player.isPlaying();
    assertTrue(resumed);
    assertFalse(resumedAgain);
    assertTrue(playing);
    this.awaitFrame("a blue frame arrives after resuming", frame -> frame.rgb == BLUE);
  }

  private void assertNoBlueFramesArrive(final RfbTestServer server) {
    // frames already handed to the render thread may still arrive, so the color change marks the pause
    server.setColor(BLUE);
    final int sentBefore = server.getUpdatesSent();
    Await.until("the server sends more updates", () -> server.getUpdatesSent() > sentBefore + 5);
    final boolean blueWhilePaused = this.containsBlueFrame();
    assertFalse(blueWhilePaused);
  }

  @Test
  void refusesToStartTwiceOrAfterRelease() throws InterruptedException {
    final RfbTestServer server = this.server(16, 16);
    final VNCPlayerImpl player = this.player();
    final VNCSource source = source(server, 0, 0);
    final boolean started = player.start(source);
    final boolean startedAgain = player.start(source);
    assertTrue(started);
    assertFalse(startedAgain);

    final boolean released = player.release();
    final boolean releasedAgain = player.release();
    final boolean playing = player.isPlaying();
    final boolean startedAfterRelease = player.start(source);
    final boolean pausedAfterRelease = player.pause();
    final boolean resumedAfterRelease = player.resume();
    assertTrue(released);
    assertFalse(releasedAgain);
    assertFalse(playing);
    assertFalse(startedAfterRelease);
    assertFalse(pausedAfterRelease);
    assertFalse(resumedAfterRelease);
    assertDisconnectedOnce(server);
  }

  private static void assertDisconnectedOnce(final RfbTestServer server) throws InterruptedException {
    final CountDownLatch disconnected = server.getDisconnected();
    final boolean closed = disconnected.await(5, TimeUnit.SECONDS);
    final int connections = server.getConnections();
    assertTrue(closed);
    assertEquals(1, connections);
  }

  @Test
  void ignoresInputAfterRelease() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final VNCSource source = source(listening, 0, 0);
    player.start(source);

    // the positive control: input reaches the client while the player plays
    player.moveMouse(2, 3);
    verify(client).moveMouse(2, 3);

    player.release();
    player.moveMouse(1, 1);
    player.sendMouseEvent(MouseClick.LEFT, 1, 1);
    player.sendKeyEvent("Return");
    player.sendKeyEvent("text");
    verify(client, times(1)).moveMouse(anyInt(), anyInt());
    verify(client, never()).click(anyInt());
    verify(client, never()).updateKey(anyInt(), anyBoolean());
    verify(client, never()).type(any());
  }

  @Test
  void authenticatesWithThePassword() {
    final RfbTestServer server = this.server(RfbTestServer.Security.PASSWORD, "secret");
    final VNCPlayerImpl player = this.player();
    final VNCSource.Builder builder = hostBuilder(server);
    builder.username("user");
    builder.password("secret");
    final VNCSource source = builder.build();
    final boolean started = player.start(source);
    final boolean authenticated = server.isAuthenticated();
    assertTrue(started);
    assertTrue(authenticated);
    this.awaitFrame("a frame arrives", _ -> true);
  }

  @Test
  void failsToStartWhenThePasswordIsRejected() {
    final RfbTestServer server = this.server(RfbTestServer.Security.PASSWORD, "secret");
    final VNCPlayerImpl player = this.player();
    final VNCSource.Builder builder = hostBuilder(server);
    builder.password("wrong");
    final VNCSource source = builder.build();
    final PlayerException exception = assertThrows(PlayerException.class, () -> player.start(source));

    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    final boolean playing = player.isPlaying();
    final boolean messageContains = message.contains("Wrong password");
    assertTrue(messageContains, message);
    assertInstanceOf(AuthenticationFailedException.class, cause);
    assertFalse(playing);
    final boolean noReports = this.errorMessages.isEmpty();
    assertTrue(noReports, this.errorMessages::toString);
  }

  @Test
  void failsToStartWhenNoSecurityTypeIsSupported() {
    final RfbTestServer server = this.server(RfbTestServer.Security.UNSUPPORTED, "");
    final VNCPlayerImpl player = this.player();
    final VNCSource source = source(server, 0, 0);
    final PlayerException exception = assertThrows(PlayerException.class, () -> player.start(source));
    final Throwable cause = exception.getCause();
    assertInstanceOf(NoSupportedSecurityTypesException.class, cause);
    final boolean playing = player.isPlaying();
    assertFalse(playing);
  }

  @Test
  void canStartAgainAfterAFailedStart() {
    final RfbTestServer refusing = this.server(RfbTestServer.Security.UNSUPPORTED, "");
    final RfbTestServer working = this.server(16, 16);
    final VNCPlayerImpl player = this.player();
    final VNCSource refusingSource = source(refusing, 0, 0);
    final VNCSource workingSource = source(working, 0, 0);
    assertThrows(PlayerException.class, () -> player.start(refusingSource));
    final boolean started = player.start(workingSource);
    assertTrue(started);
    this.awaitFrame("a frame arrives", _ -> true);
  }

  @Test
  void failsToStartWhenTheServerHangsUpDuringTheHandshake() {
    final RfbTestServer server = this.server(RfbTestServer.Security.HANG_UP, "");
    final VNCPlayerImpl player = this.player();
    final VNCSource source = source(server, 0, 0);
    final PlayerException exception = assertThrows(PlayerException.class, () -> player.start(source));
    final String message = exception.getMessage();
    final boolean messageStartsWith = message.startsWith("Failed to start the VNC session with VNCSource[vnc://127.0.0.1:");
    assertTrue(messageStartsWith, message);
    final boolean playing = player.isPlaying();
    assertFalse(playing);
  }

  @Test
  void failsToStartWhenNothingListens() throws IOException {
    final ServerSocket closed = this.listeningSocket();
    final VNCSource source = source(closed, 0, 0);
    closed.close();
    final VNCPlayerImpl player = this.player();
    final PlayerException exception = assertThrows(PlayerException.class, () -> player.start(source));
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    final boolean messageStartsWith = message.startsWith("Failed to connect to ");
    assertTrue(messageStartsWith, message);
    assertInstanceOf(IOException.class, cause);
  }

  @Test
  void closesSocketsThatFailToConnectEvenIfClosingFails() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VNCSource source = source(listening, 0, 0);
    final List<FailingSocket> createdSockets = new CopyOnWriteArrayList<>();
    final Supplier<Socket> sockets = () -> {
      final FailingSocket socket = new FailingSocket();
      createdSockets.add(socket);
      return socket;
    };
    final VernacularClient client = mock(VernacularClient.class);
    final VNCPlayerImpl created = new VNCPlayerImpl(_ -> client, sockets);
    final VNCPlayerImpl player = this.track(created);
    final PlayerException exception = assertThrows(PlayerException.class, () -> player.start(source));

    final String message = exception.getMessage();
    final boolean messageContains = message.contains("refused on purpose");
    assertTrue(messageContains, message);
    final int socketCount = createdSockets.size();
    final FailingSocket socket = createdSockets.getFirst();
    assertEquals(1, socketCount);
    assertTrue(socket.closeAttempted);
    verify(client, never()).start(any(Socket.class));
  }

  @Test
  void reportsConnectionFailuresWhileStreaming() {
    final RfbTestServer server = this.server(16, 16);
    final VNCPlayerImpl player = this.player();
    final VNCSource source = source(server, 0, 0);
    player.start(source);
    this.awaitFrame("a frame arrives", _ -> true);
    server.sendUnknownMessage();
    Await.until("the failure is reported", () -> !this.errorMessages.isEmpty());
    final String message = this.errorMessages.getFirst();
    final Throwable error = this.errors.getFirst();
    assertEquals("The VNC connection failed", message);
    assertInstanceOf(UnknownMessageTypeException.class, error);
  }

  @Test
  void reportsPipelineFailuresAndKeepsStreaming() {
    final RfbTestServer server = this.server(16, 16);
    final VNCPlayerImpl player = this.player();
    final IllegalStateException failure = new IllegalStateException("filter failed");
    final VideoFilter failing = (_, _) -> {
      throw failure;
    };
    attach(player, failing);
    final VNCSource source = source(server, 0, 0);
    player.start(source);

    Await.until("two failures are reported", () -> this.errorMessages.size() >= 2);
    final String message = this.errorMessages.getFirst();
    final Throwable error = this.errors.getFirst();
    final boolean playing = player.isPlaying();
    assertEquals("Failed to process a VNC frame", message);
    assertSame(failure, error);
    assertTrue(playing);
  }

  @Test
  void restoresTheInterruptFlagWhenReleasedFromAnInterruptedThread() {
    final RfbTestServer server = this.server(16, 16);
    final VNCPlayerImpl player = this.player();
    final VNCSource source = source(server, 0, 0);
    player.start(source);
    final Thread current = Thread.currentThread();
    current.interrupt();
    final boolean released = player.release();
    final boolean interrupted = Thread.interrupted();
    assertTrue(released);
    assertTrue(interrupted);
  }

  @Test
  void stopsWaitingForABusyFilterWhenTheReleasingThreadIsInterrupted() throws Exception {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    final VideoFilter busy = blockingFilter(entered, proceed);
    attach(player, busy);

    final VNCSource source = source(listening, 0, 0);
    player.start(source);
    final BufferedImage screen = image(4, 4, RED);
    pushScreen(config, screen);
    final boolean filtering = entered.await(10, TimeUnit.SECONDS);
    assertTrue(filtering);

    final long elapsedMillis = releaseWhileInterrupted(player, proceed);
    final boolean interrupted = Thread.interrupted();
    final boolean playing = player.isPlaying();
    assertTrue(interrupted);
    assertFalse(playing);
    // the release must not sit out the two second join timeout once the caller is interrupted
    assertTrue(elapsedMillis < 1_500L, () -> "release took " + elapsedMillis + " ms");
  }

  private static VideoFilter blockingFilter(final CountDownLatch entered, final CountDownLatch proceed) {
    return (_, _) -> {
      entered.countDown();
      try {
        proceed.await();
      } catch (final InterruptedException exception) {
        final Thread current = Thread.currentThread();
        current.interrupt();
      }
      return true;
    };
  }

  private static long releaseWhileInterrupted(final VNCPlayerImpl player, final CountDownLatch proceed) {
    final Thread current = Thread.currentThread();
    current.interrupt();
    final long before = System.nanoTime();
    final boolean released;
    try {
      released = player.release();
    } finally {
      proceed.countDown();
    }
    final long after = System.nanoTime();
    assertTrue(released);
    return TimeUnit.NANOSECONDS.toMillis(after - before);
  }

  @Test
  void startsAsynchronously() throws Exception {
    final RfbTestServer server = this.server(16, 16);
    final VNCPlayer player = this.player();
    final VNCSource source = source(server, 0, 0);
    final CompletableFuture<Boolean> first = player.startAsync(source);
    final boolean started = first.get(10, TimeUnit.SECONDS);
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      final CompletableFuture<Boolean> second = player.startAsync(source, executor);
      final boolean startedAgain = second.get(10, TimeUnit.SECONDS);
      assertTrue(started);
      assertFalse(startedAgain);
    } finally {
      executor.shutdownNow();
    }
    assertThrows(NullPointerException.class, () -> player.startAsync(null));
    assertThrows(NullPointerException.class, () -> player.startAsync(source, null));
    assertThrows(NullPointerException.class, () -> player.startAsync(null, executor));
  }

  @Test
  void createsPlayersThatAreNotPlaying() {
    final VNCPlayer player = VNCPlayer.create();
    final boolean playing = player.isPlaying();
    final boolean paused = player.pause();
    final boolean resumed = player.resume();
    assertInstanceOf(VNCPlayerImpl.class, player);
    assertFalse(playing);
    assertFalse(paused);
    assertFalse(resumed);
    final boolean released = player.release();
    assertTrue(released);
  }

  @Test
  void rejectsNullArguments() {
    final VNCPlayerImpl player = this.player();
    assertThrows(NullPointerException.class, () -> player.start(null));
    assertThrows(NullPointerException.class, () -> player.sendMouseEvent(null, 0, 0));
    assertThrows(NullPointerException.class, () -> player.sendKeyEvent(null));
    assertThrows(NullPointerException.class, () -> player.setExceptionHandler(null));
  }

  @Test
  void replacesTheExceptionHandler() {
    final VNCPlayerImpl player = new VNCPlayerImpl();
    final BiConsumer<String, Throwable> handler = (_, _) -> {};
    player.setExceptionHandler(handler);
    final BiConsumer<String, Throwable> current = player.getExceptionHandler();
    assertSame(handler, current);
  }

  @Test
  void configuresTheClientFromTheSource() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final VNCSource.Builder builder = hostBuilder(listening);
    builder.username("user");
    builder.password("secret");
    builder.targetFrameRate(12);
    final VNCSource source = builder.build();
    player.start(source);

    final VernacularConfig configuration = config.get();
    assertConfiguredFromTheSource(configuration);
    final Socket connected = startedSocket(client);
    assertConnectedWithoutDelay(connected);
  }

  private static void assertConnectedWithoutDelay(final Socket socket) throws IOException {
    final boolean isConnected = socket.isConnected();
    final boolean noDelay = socket.getTcpNoDelay();
    assertTrue(isConnected);
    assertTrue(noDelay);
  }

  private static void assertConfiguredFromTheSource(final VernacularConfig configuration) {
    final ColorDepth depth = configuration.getColorDepth();
    final boolean shared = configuration.isShared();
    final boolean localPointer = configuration.isUseLocalMousePointer();
    final int frameRate = configuration.getTargetFramesPerSecond();
    final Supplier<String> usernames = configuration.getUsernameSupplier();
    final Supplier<String> passwords = configuration.getPasswordSupplier();
    final String username = usernames.get();
    final String password = passwords.get();
    assertEquals(ColorDepth.BPP_24_TRUE, depth);
    assertTrue(shared);
    assertFalse(localPointer);
    assertEquals(12, frameRate);
    assertEquals("user", username);
    assertEquals("secret", password);
  }

  @Test
  void sendsNoCredentialsWhenTheyAreEmpty() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final VNCSource.Builder builder = hostBuilder(listening);
    builder.username("");
    builder.password("");
    final VNCSource source = builder.build();
    player.start(source);
    final VernacularConfig configuration = config.get();
    final Supplier<String> usernames = configuration.getUsernameSupplier();
    final Supplier<String> passwords = configuration.getPasswordSupplier();
    assertNull(usernames);
    assertNull(passwords);
  }

  @Test
  void closesTheSocketWhenTheClientFailsToStart() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final IllegalStateException failure = new IllegalStateException("client is already running");
    doThrow(failure).when(client).start(any(Socket.class));
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final VNCSource source = source(listening, 0, 0);
    final PlayerException exception = assertThrows(PlayerException.class, () -> player.start(source));
    final Throwable cause = exception.getCause();
    assertSame(failure, cause);

    final Socket used = startedSocket(client);
    final boolean closed = used.isClosed();
    assertTrue(closed);
    try (final Socket accepted = listening.accept()) {
      final InputStream input = accepted.getInputStream();
      final int read = input.read();
      assertEquals(-1, read);
    }
  }

  @Test
  void stopsTheClientAndClosesTheSocketWhenTheHandshakeFails() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final AuthenticationFailedException failure = new AuthenticationFailedException("denied");
    doAnswer(_ -> {
      pushError(config, failure);
      return null;
    })
      .when(client)
      .start(any(Socket.class));
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final VNCSource source = source(listening, 0, 0);
    final PlayerException exception = assertThrows(PlayerException.class, () -> player.start(source));
    final Throwable cause = exception.getCause();
    assertSame(failure, cause);

    verify(client).stop();
    final Socket used = startedSocket(client);
    final boolean closed = used.isClosed();
    assertTrue(closed);
    final boolean noReports = this.errorMessages.isEmpty();
    assertTrue(noReports);
  }

  @Test
  void rendersEveryScreenUpdateAtItsOwnSize() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final VNCSource source = source(listening, 0, 0);
    player.start(source);
    final BufferedImage small = image(8, 4, RED);
    pushScreen(config, small);
    final RecordedFrame first = this.awaitFrame("the first frame arrives", frame -> frame.rgb == RED);
    final BufferedImage wide = image(6, 2, BLUE);
    pushScreen(config, wide);
    final RecordedFrame second = this.awaitFrame("the second frame arrives", frame -> frame.rgb == BLUE);
    assertEquals(8, first.width);
    assertEquals(4, first.height);
    assertEquals(6, second.width);
    assertEquals(2, second.height);
    assertEquals(6, second.metadataWidth);
    assertEquals(2, second.metadataHeight);
  }

  @Test
  void mapsCoordinatesOneToOneWhenFramesKeepTheRemoteSize() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final VNCSource source = source(listening, 0, 0);
    player.start(source);
    final BufferedImage screen = image(30, 20, RED);
    pushScreen(config, screen);
    player.moveMouse(12, 7);
    player.moveMouse(30, 20);
    final InOrder order = inOrder(client);
    order.verify(client).moveMouse(12, 7);
    order.verify(client).moveMouse(29, 19);
  }

  @Test
  void ignoresScreenUpdatesThatAreNotBufferedImages() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final VNCSource source = source(listening, 10, 10);
    player.start(source);
    final Image other = mock(Image.class);
    pushScreen(config, other);
    player.moveMouse(5, 6);
    verify(client).moveMouse(5, 6);

    final BufferedImage frame = image(20, 20, RED);
    pushScreen(config, frame);
    this.awaitFrame("the buffered frame arrives", _ -> true);
    final int count = this.frames.size();
    assertEquals(1, count);
    verifyNoInteractions(other);
  }

  @Test
  void learnsTheRemoteSizeButDropsFramesWhilePaused() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final VNCSource source = source(listening, 10, 5);
    player.start(source);
    player.pause();
    final BufferedImage paused = image(20, 10, RED);
    pushScreen(config, paused);
    player.moveMouse(5, 2);
    verify(client).moveMouse(10, 4);
    player.moveMouse(1000, -5);
    verify(client).moveMouse(19, 0);

    player.resume();
    final BufferedImage resumed = image(20, 10, BLUE);
    pushScreen(config, resumed);
    final RecordedFrame frame = this.awaitFrame("a frame arrives after resuming", _ -> true);
    final int count = this.frames.size();
    assertEquals(BLUE, frame.rgb);
    assertEquals(10, frame.width);
    assertEquals(5, frame.height);
    assertEquals(1, count);
  }

  @Test
  void translatesEveryKindOfClick() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final VNCSource source = source(listening, 0, 0);
    player.start(source);
    player.sendMouseEvent(MouseClick.LEFT, 1, 2);
    player.sendMouseEvent(MouseClick.RIGHT, 3, 4);
    player.sendMouseEvent(MouseClick.DOUBLE, 5, 6);
    player.sendMouseEvent(MouseClick.HOLD, 7, 8);
    player.sendMouseEvent(MouseClick.RELEASE, 9, 10);

    final InOrder order = inOrder(client);
    order.verify(client).moveMouse(1, 2);
    order.verify(client).click(1);
    order.verify(client).moveMouse(3, 4);
    order.verify(client).click(3);
    order.verify(client).moveMouse(5, 6);
    order.verify(client, times(2)).click(1);
    order.verify(client).moveMouse(7, 8);
    order.verify(client).updateMouseButton(1, true);
    order.verify(client).moveMouse(9, 10);
    order.verify(client).updateMouseButton(1, false);
  }

  @Test
  void pressesNamedKeysAndTypesOtherText() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final VNCSource source = source(listening, 0, 0);
    player.start(source);
    player.sendKeyEvent("Escape");
    player.sendKeyEvent("hello");
    final InOrder order = inOrder(client);
    order.verify(client).updateKey(0xff1b, true);
    order.verify(client).updateKey(0xff1b, false);
    order.verify(client).type("hello");
  }

  @Test
  void doesNotTouchTheClientBeforeStarting() {
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    player.moveMouse(1, 1);
    player.sendMouseEvent(MouseClick.LEFT, 1, 1);
    player.sendKeyEvent("Return");
    final boolean released = player.release();
    assertTrue(released);
    verifyNoInteractions(client);
    final VernacularConfig configuration = config.get();
    assertNull(configuration);
  }

  @Test
  void reportsInputThatFailsToForward() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final IllegalStateException failure = new IllegalStateException("broken pipe");
    doThrow(failure).when(client).moveMouse(anyInt(), anyInt());
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final VNCSource source = source(listening, 0, 0);
    player.start(source);
    player.moveMouse(1, 1);
    final String message = this.errorMessages.getFirst();
    final Throwable error = this.errors.getFirst();
    assertEquals("Failed to forward input to the VNC server", message);
    assertSame(failure, error);
  }

  @Test
  void ignoresInputFailuresCausedByARelease() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final AtomicReference<VNCPlayerImpl> reference = new AtomicReference<>();
    doAnswer(_ -> {
      final VNCPlayerImpl current = reference.get();
      current.release();
      throw new IllegalStateException("closed while typing");
    })
      .when(client)
      .updateKey(anyInt(), anyBoolean());
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    reference.set(player);
    final VNCSource source = source(listening, 0, 0);
    player.start(source);
    player.sendKeyEvent("Return");
    final boolean noReports = this.errorMessages.isEmpty();
    final boolean playing = player.isPlaying();
    assertTrue(noReports, this.errorMessages::toString);
    assertFalse(playing);
  }

  @Test
  void reportsErrorsOfAStartedConnection() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final VNCSource source = source(listening, 0, 0);
    player.start(source);
    final UnknownMessageTypeException failure = new UnknownMessageTypeException(9);
    pushError(config, failure);
    final String message = this.errorMessages.getFirst();
    final Throwable error = this.errors.getFirst();
    assertEquals("The VNC connection failed", message);
    assertSame(failure, error);
  }

  @Test
  void stopsPlayingWhenTheConnectionFailsAndCanStartAgain() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final VNCSource source = source(listening, 0, 0);
    player.start(source);
    final UnknownMessageTypeException failure = new UnknownMessageTypeException(9);
    pushError(config, failure);
    pushError(config, failure);
    this.assertEndedSessionIgnoresControl(player, client);

    final boolean restarted = player.start(source);
    final boolean playingAgain = player.isPlaying();
    assertTrue(restarted);
    assertTrue(playingAgain);
    // the failed client is stopped before the new session starts
    final InOrder order = inOrder(client);
    order.verify(client).start(any(Socket.class));
    order.verify(client).stop();
    order.verify(client).start(any(Socket.class));
  }

  private void assertEndedSessionIgnoresControl(final VNCPlayerImpl player, final VernacularClient client) {
    final boolean playing = player.isPlaying();
    final boolean paused = player.pause();
    final boolean resumed = player.resume();
    player.moveMouse(1, 1);
    player.sendKeyEvent("Return");
    final int reports = this.errorMessages.size();
    assertFalse(playing);
    assertFalse(paused);
    assertFalse(resumed);
    assertEquals(1, reports, this.errorMessages::toString);
    verify(client, never()).moveMouse(anyInt(), anyInt());
    verify(client, never()).updateKey(anyInt(), anyBoolean());
  }

  @Test
  void failsTheStartWithTheFirstErrorOfTheHandshake() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final AuthenticationFailedException first = new AuthenticationFailedException("denied");
    final UnknownMessageTypeException second = new UnknownMessageTypeException(3);
    doAnswer(_ -> {
      pushError(config, first);
      pushError(config, second);
      return null;
    })
      .when(client)
      .start(any(Socket.class));
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final VNCSource source = source(listening, 0, 0);
    final PlayerException exception = assertThrows(PlayerException.class, () -> player.start(source));
    final Throwable cause = exception.getCause();
    final boolean noReports = this.errorMessages.isEmpty();
    assertSame(first, cause);
    assertTrue(noReports, this.errorMessages::toString);
  }

  @Test
  void showsAScreenUpdateThatArrivesDuringTheHandshake() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    // a screen that never changes sends a single update, which may come before the start returns
    doAnswer(_ -> {
      final BufferedImage screen = image(4, 4, RED);
      pushScreen(config, screen);
      return null;
    })
      .when(client)
      .start(any(Socket.class));
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final VNCSource source = source(listening, 0, 0);
    player.start(source);
    final RecordedFrame frame = this.awaitFrame("the update of the handshake is shown", _ -> true);
    assertEquals(RED, frame.rgb);
  }

  @Test
  void neverLosesAnErrorThatArrivesAroundTheEndOfTheHandshake() throws IOException {
    final InetAddress loopback = InetAddress.getLoopbackAddress();
    final ServerSocket listening = new ServerSocket(0, 200, loopback);
    this.resources.add(listening);
    final VNCSource source = source(listening, 0, 0);
    int started = 0;
    for (int attempt = 0; attempt < 40; attempt++) {
      final boolean running = this.startWithLateError(source, attempt);
      if (running) {
        started++;
      }
    }

    final int expectedReports = started;
    Await.until("every error of a started session is reported", () -> this.errorMessages.size() >= expectedReports);
    final int reports = this.errorMessages.size();
    assertEquals(started, reports, this.errorMessages::toString);
  }

  private boolean startWithLateError(final VNCSource source, final int attempt) {
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    // the error arrives a little later on every attempt, so some fall right after the handshake finishes
    final long delayNanos = TimeUnit.MICROSECONDS.toNanos(attempt * 25L);
    final UnknownMessageTypeException failure = new UnknownMessageTypeException(attempt);
    doAnswer(_ -> {
      final Thread sender = new Thread(() -> {
        LockSupport.parkNanos(delayNanos);
        pushError(config, failure);
      });
      sender.start();
      return null;
    })
      .when(client)
      .start(any(Socket.class));

    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    boolean running;
    try {
      running = player.start(source);
    } catch (final PlayerException exception) {
      running = false;
    }
    if (running) {
      Await.until("the error of attempt " + attempt + " ends the session", () -> !player.isPlaying());
    }
    return running;
  }

  @Test
  void throwsErrorsOfTheVirtualMachineOnTheRenderThread() throws Exception {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final InternalError fatal = new InternalError("out of native memory");
    final VideoFilter failing = (_, _) -> {
      throw fatal;
    };
    attach(player, failing);

    final VNCSource source = source(listening, 0, 0);
    final Throwable error = awaitRenderThreadDeath(() -> {
      player.start(source);
      final BufferedImage screen = image(4, 4, RED);
      pushScreen(config, screen);
    });
    final boolean noReports = this.errorMessages.isEmpty();
    assertSame(fatal, error);
    assertTrue(noReports, this.errorMessages::toString);
  }

  private static Throwable awaitRenderThreadDeath(final Runnable trigger) throws InterruptedException {
    final AtomicReference<Throwable> uncaught = new AtomicReference<>();
    final CountDownLatch died = new CountDownLatch(1);
    final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
      final String name = thread.getName();
      if (name.equals("mcav-vnc-render")) {
        uncaught.set(error);
        died.countDown();
      }
    });
    try {
      trigger.run();
      final boolean threadDied = died.await(10, TimeUnit.SECONDS);
      assertTrue(threadDied);
    } finally {
      Thread.setDefaultUncaughtExceptionHandler(previous);
    }
    return uncaught.get();
  }

  @Test
  void reportsClientsThatFailToStop() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final IllegalStateException failure = new IllegalStateException("stuck");
    doThrow(failure).when(client).stop();
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final VNCSource source = source(listening, 0, 0);
    player.start(source);
    final boolean released = player.release();
    final String message = this.errorMessages.getFirst();
    final Throwable error = this.errors.getFirst();
    assertTrue(released);
    assertEquals("Failed to close the VNC connection", message);
    assertSame(failure, error);
  }

  @Test
  void rendersOnDaemonThreadsSoTheyNeverKeepTheJvmAlive() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final VNCSource source = source(listening, 0, 0);
    player.start(source);

    Await.until("the render thread runs", () -> !renderThreads().isEmpty());
    final List<Thread> threads = renderThreads();
    for (final Thread thread : threads) {
      final boolean daemon = thread.isDaemon();
      final String name = thread.getName();
      assertTrue(daemon, name);
    }
  }

  /**
   * Gets the render threads of every player that is alive, which all carry the same name.
   *
   * @return the live render threads
   */
  private static List<Thread> renderThreads() {
    final Map<Thread, StackTraceElement[]> stackTraces = Thread.getAllStackTraces();
    final Set<Thread> threads = stackTraces.keySet();
    final List<Thread> running = new ArrayList<>();
    for (final Thread thread : threads) {
      final String name = thread.getName();
      final boolean render = name.equals(RENDER_THREAD);
      final boolean alive = thread.isAlive();
      if (render && alive) {
        running.add(thread);
      }
    }
    return running;
  }

  @Test
  void waitsForTheRenderThreadToFinishItsFrameBeforeAReleaseReturns() throws Exception {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    final VideoFilter busy = blockingFilter(entered, proceed);
    attach(player, busy);
    final VNCSource source = source(listening, 0, 0);
    player.start(source);
    final BufferedImage screen = image(4, 4, RED);
    pushScreen(config, screen);
    final boolean filtering = entered.await(10, TimeUnit.SECONDS);
    assertTrue(filtering);

    final CountDownLatch released = releaseOnAnotherThread(player);
    final boolean returnedWhileBusy = released.await(500, TimeUnit.MILLISECONDS);
    proceed.countDown();
    final boolean returnedAfterwards = released.await(10, TimeUnit.SECONDS);
    assertFalse(returnedWhileBusy, "the release waits for the render thread to finish its frame");
    assertTrue(returnedAfterwards);
  }

  private static CountDownLatch releaseOnAnotherThread(final VNCPlayerImpl player) {
    final CountDownLatch released = new CountDownLatch(1);
    final Runnable release = () -> {
      player.release();
      released.countDown();
    };
    final Thread releasing = new Thread(release, "release-probe");
    releasing.setDaemon(true);
    releasing.start();
    return released;
  }

  @Test
  void dropsTheFrameThatWasStillWaitingWhenThePlayerPaused() throws Exception {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    final VideoFilter busy = this.blockingThenRecording(entered, proceed);
    attach(player, busy);
    final VNCSource source = source(listening, 0, 0);
    player.start(source);
    this.pushAndAwaitBusyRenderThread(config, entered);

    final BufferedImage waiting = image(4, 4, GREEN);
    pushScreen(config, waiting);
    final boolean paused = player.pause();
    proceed.countDown();
    player.resume();
    final BufferedImage afterResume = image(4, 4, BLUE);
    pushScreen(config, afterResume);
    this.awaitFrame("a frame arrives after resuming", frame -> frame.rgb == BLUE);
    final boolean stale = this.containsFrame(GREEN);
    assertTrue(paused);
    assertFalse(stale, "the frame that was waiting when the player paused is dropped");
  }

  @Test
  void neverShowsAFrameThatWasWaitingWhenTheSessionEnded() throws Exception {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    final VideoFilter busy = this.blockingThenRecording(entered, proceed);
    attach(player, busy);
    final VNCSource source = source(listening, 0, 0);
    player.start(source);
    this.pushAndAwaitBusyRenderThread(config, entered);

    final BufferedImage waiting = image(4, 4, GREEN);
    pushScreen(config, waiting);
    final UnknownMessageTypeException failure = new UnknownMessageTypeException(9);
    pushError(config, failure);
    final boolean restarted = player.start(source);
    proceed.countDown();
    final BufferedImage afterRestart = image(4, 4, BLUE);
    pushScreen(config, afterRestart);
    this.awaitFrame("a frame of the new session arrives", frame -> frame.rgb == BLUE);
    final boolean stale = this.containsFrame(GREEN);
    assertTrue(restarted);
    assertFalse(stale, "a frame of the session that ended is never shown by the next one");
  }

  @Test
  void dropsThePendingFrameWhenPausing() throws Exception {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    final VideoFilter busy = this.blockingThenRecording(entered, proceed);
    attach(player, busy);
    final VNCSource source = source(listening, 0, 0);
    player.start(source);
    this.pushAndAwaitBusyRenderThread(config, entered);

    // the render thread is held inside the first frame, so it cannot take this update before the pause
    final BufferedImage waiting = image(4, 4, GREEN);
    pushScreen(config, waiting);
    final boolean pendingBeforePause = player.hasPendingFrame();
    final boolean paused = player.pause();
    final boolean pendingAfterPause = player.hasPendingFrame();
    proceed.countDown();

    assertTrue(pendingBeforePause, "the update waits while the render thread is busy");
    assertTrue(paused);
    assertFalse(pendingAfterPause, "pausing drops the update that was still waiting");
  }

  @Test
  void dropsThePendingFrameWhenTheNextSessionStarts() throws Exception {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    final VideoFilter busy = this.blockingThenRecording(entered, proceed);
    attach(player, busy);
    final VNCSource source = source(listening, 0, 0);
    player.start(source);
    this.pushAndAwaitBusyRenderThread(config, entered);
    final Thread endingRenderThread = onlyRenderThread();

    final BufferedImage waiting = image(4, 4, GREEN);
    pushScreen(config, waiting);
    final UnknownMessageTypeException failure = new UnknownMessageTypeException(9);
    pushError(config, failure);
    // the render thread of the ended session leaves the update alone, because it checks the session before it takes
    // the next one; waiting for it to stop leaves the new session with the only render thread
    proceed.countDown();
    Await.until("the render thread of the session that ended stops", () -> !endingRenderThread.isAlive());
    final boolean pendingAfterTheSessionEnded = player.hasPendingFrame();

    final boolean restarted = player.start(source);
    // the render thread of the new session parks as soon as it finds nothing to render, so an update left over from
    // the session that ended would have been rendered before it parks
    Await.until("the render thread of the new session waits for an update", VNCPlayerImplTest::anyRenderThreadWaitsForAnUpdate);
    final boolean pending = player.hasPendingFrame();
    final boolean stale = this.containsFrame(GREEN);

    assertTrue(pendingAfterTheSessionEnded, "the update of the session that ended waits until the next start");
    assertTrue(restarted);
    assertFalse(pending, "the update of the session that ended is dropped when the next one starts");
    assertFalse(stale, "and it is never rendered");
  }

  /**
   * Gets the one render thread that is alive, failing the test when a thread of an earlier session is still running.
   *
   * @return the render thread
   */
  private static Thread onlyRenderThread() {
    final List<Thread> threads = renderThreads();
    final int count = threads.size();
    assertEquals(1, count, "exactly one render thread runs");
    return threads.getFirst();
  }

  /**
   * Checks whether a render thread parked because it found no update, which the render loop does with a timed park.
   * A render thread that is held inside a filter waits with a timeout as well, so this only tells the two apart once
   * the threads of earlier sessions have stopped.
   *
   * @return true once a render thread waits for the next update
   */
  private static boolean anyRenderThreadWaitsForAnUpdate() {
    final List<Thread> threads = renderThreads();
    for (final Thread thread : threads) {
      final Thread.State state = thread.getState();
      if (state == Thread.State.TIMED_WAITING) {
        return true;
      }
    }
    return false;
  }

  /**
   * Pushes a first screen update and waits until the render thread is busy with it, so the next update stays waiting.
   *
   * @param config  the configuration of the client, whose listener receives the update
   * @param entered opens once the filter of the render thread runs
   * @throws InterruptedException if the test is interrupted
   */
  private void pushAndAwaitBusyRenderThread(final AtomicReference<VernacularConfig> config, final CountDownLatch entered)
    throws InterruptedException {
    final BufferedImage first = image(4, 4, RED);
    pushScreen(config, first);
    final boolean filtering = entered.await(10, TimeUnit.SECONDS);
    assertTrue(filtering);
  }

  /**
   * Creates a filter that holds the render thread inside its first frame and records every frame as usual.
   *
   * @param entered opens once the first frame is being filtered
   * @param proceed lets the first frame finish
   * @return the filter
   */
  private VideoFilter blockingThenRecording(final CountDownLatch entered, final CountDownLatch proceed) {
    final AtomicBoolean firstFrame = new AtomicBoolean(true);
    return (image, metadata) -> {
      final boolean first = firstFrame.compareAndSet(true, false);
      if (first) {
        entered.countDown();
        awaitLatch(proceed);
      }
      return this.record(image, metadata);
    };
  }

  private boolean containsFrame(final int rgb) {
    for (final RecordedFrame frame : this.frames) {
      if (frame.rgb == rgb) {
        return true;
      }
    }
    return false;
  }

  @Test
  void startsPlayingAgainAfterTheSessionThatWasPausedFailed() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final VNCSource source = source(listening, 0, 0);
    player.start(source);
    final boolean paused = player.pause();

    final UnknownMessageTypeException failure = new UnknownMessageTypeException(9);
    pushError(config, failure);
    final boolean restarted = player.start(source);
    final boolean playing = player.isPlaying();
    assertTrue(paused);
    assertTrue(restarted);
    assertTrue(playing, "a session that starts again is never paused");

    final BufferedImage screen = image(4, 4, BLUE);
    pushScreen(config, screen);
    this.awaitFrame("a frame of the new session arrives", frame -> frame.rgb == BLUE);
  }

  @Test
  void releasesItsLockAfterEveryControlCall() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> config = new AtomicReference<>();
    final VNCPlayerImpl player = this.mockedPlayer(client, config);
    final VNCSource source = source(listening, 0, 0);
    player.start(source);

    player.pause();
    assertDoesNotBlock("another thread pauses after a pause", () -> player.pause());
    player.resume();
    assertDoesNotBlock("another thread resumes after a resume", () -> player.resume());
    player.release();
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

  /**
   * A frame the pipeline received.
   */
  private static final class RecordedFrame {

    private final int width;
    private final int height;
    private final int rgb;
    private final int metadataWidth;
    private final int metadataHeight;
    private final float frameRate;

    RecordedFrame(
      final int width,
      final int height,
      final int rgb,
      final int metadataWidth,
      final int metadataHeight,
      final float frameRate
    ) {
      this.width = width;
      this.height = height;
      this.rgb = rgb;
      this.metadataWidth = metadataWidth;
      this.metadataHeight = metadataHeight;
      this.frameRate = frameRate;
    }
  }

  /**
   * A socket that refuses to connect and fails to close.
   */
  private static final class FailingSocket extends Socket {

    private volatile boolean closeAttempted;

    @Override
    public void connect(final SocketAddress endpoint, final int timeout) throws IOException {
      throw new ConnectException("refused on purpose");
    }

    @Override
    public synchronized void close() throws IOException {
      this.closeAttempted = true;
      throw new IOException("close failed on purpose");
    }
  }
}
