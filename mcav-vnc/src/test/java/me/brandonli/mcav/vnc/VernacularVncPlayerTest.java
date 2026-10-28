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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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

import com.google.common.util.concurrent.Uninterruptibles;
import com.shinyhut.vernacular.client.VernacularClient;
import com.shinyhut.vernacular.client.VernacularConfig;
import com.shinyhut.vernacular.client.exceptions.AuthenticationFailedException;
import com.shinyhut.vernacular.client.exceptions.NoSupportedSecurityTypesException;
import com.shinyhut.vernacular.client.exceptions.UnexpectedVncException;
import com.shinyhut.vernacular.client.exceptions.UnknownMessageTypeException;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import me.brandonli.mcav.media.image.ImageBuffer;
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

// Tests VernacularVncPlayer and the default methods of VncPlayer. Most tests talk to a RfbTestServer on the loopback
// interface, so the whole path from the socket to the pipeline is exercised. Tests of failures the real client cannot
// produce on demand use a mocked VernacularClient whose listeners are driven by the test.
final class VernacularVncPlayerTest {

  private static final int RED = 0xFF0000;
  private static final int GREEN = 0x00FF00;
  private static final int BLUE = 0x0000FF;
  private static final String RENDER_THREAD = "mcav-vnc-render";

  private static final long TIMEOUT_SECONDS = 10;

  private static final int SQUARE_SIDE = 4;

  private static final int SERVER_SIDE = 16;

  private static final int SERVER_WIDTH = 64;
  private static final int SERVER_HEIGHT = 48;

  private static final int SCREEN_WIDTH = 200;
  private static final int SCREEN_HEIGHT = 100;

  private static final int SCALED_WIDTH = 100;
  private static final int SCALED_HEIGHT = 50;

  private static final String PASSWORD = "secret";

  private static final String RETURN_KEY = "Return";
  private static final int RETURN_KEYSYM = 0xff0d;

  private static final int UNKNOWN_MESSAGE_TYPE = 9;

  private static final long SHORT_IDLE_NANOSECONDS = TimeUnit.MILLISECONDS.toNanos(10);
  private static final long HANDSHAKE_DEADLINE_MILLISECONDS = 500;

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

  // Loads the OpenCV libraries that every frame goes through before any test runs. JavaCV loads them on first use,
  // which takes seconds on a busy machine, and that one-time cost would otherwise count against the timeout of
  // whichever test receives the first frame.
  @BeforeAll
  static void loadImageLibraries() {
    final BufferedImage frame = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
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
    final RfbTestServer server = RfbTestServer.start(SERVER_WIDTH, SERVER_HEIGHT, security, password);
    this.resources.add(server);
    return server;
  }

  private ServerSocket listeningSocket() throws IOException {
    final InetAddress loopback = InetAddress.getLoopbackAddress();
    final ServerSocket socket = new ServerSocket(0, 5, loopback);
    this.resources.add(socket);
    return socket;
  }

  private VernacularVncPlayer track(final VernacularVncPlayer player) {
    this.resources.add(player::release);
    final BiConsumer<String, Throwable> handler = (message, error) -> {
      this.errorMessages.add(message);
      this.errors.add(error);
    };
    player.setExceptionHandler(handler);
    final VideoFilter recorder = this::record;
    this.attach(player, recorder);
    return player;
  }

  private void attach(final VncPlayer player, final VideoFilter filter) {
    final VideoPipelineStepBuilder builder = PipelineBuilder.video();
    builder.then(filter);
    final VideoPipelineStep pipeline = builder.build();
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    callback.attach(pipeline);
  }

  private VernacularVncPlayer player() {
    final VernacularVncPlayer player = new VernacularVncPlayer();
    return this.track(player);
  }

  private VernacularVncPlayer mockedPlayer(final VernacularClient client, final AtomicReference<VernacularConfig> capturedConfiguration) {
    final VernacularVncPlayer player = new VernacularVncPlayer(configuration -> {
      capturedConfiguration.set(configuration);
      return client;
    }, Socket::new);
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
    return false;
  }

  private VncSourceBuilder hostBuilder(final int port) {
    final VncSourceBuilder builder = VncSource.builder();
    builder.host("127.0.0.1");
    builder.port(port);
    return builder;
  }

  private VncSourceBuilder hostBuilder(final ServerSocket socket) {
    final int port = socket.getLocalPort();
    return this.hostBuilder(port);
  }

  private VncSourceBuilder hostBuilder(final RfbTestServer server) {
    final int port = server.getPort();
    return this.hostBuilder(port);
  }

  private VncSource source(final VncSourceBuilder builder, final int width, final int height) {
    builder.screenWidth(width);
    builder.screenHeight(height);
    builder.targetFrameRate(50);
    return builder.build();
  }

  private VncSource source(final ServerSocket socket, final int width, final int height) {
    final VncSourceBuilder builder = this.hostBuilder(socket);
    return this.source(builder, width, height);
  }

  private VncSource source(final RfbTestServer server, final int width, final int height) {
    final VncSourceBuilder builder = this.hostBuilder(server);
    return this.source(builder, width, height);
  }

  private RecordedFrame awaitFrame(final String description, final Predicate<RecordedFrame> condition) {
    final AtomicReference<RecordedFrame> found = new AtomicReference<>();
    Await.until(description, () -> this.findFrame(condition, found));
    return found.get();
  }

  private boolean findFrame(final Predicate<RecordedFrame> condition, final AtomicReference<RecordedFrame> found) {
    for (final RecordedFrame frame : this.frames) {
      if (condition.test(frame)) {
        found.set(frame);
        return true;
      }
    }

    return false;
  }

  private boolean containsBlueFrame() {
    for (final RecordedFrame frame : this.frames) {
      if (frame.rgb == BLUE) {
        return true;
      }
    }
    return false;
  }

  private BufferedImage image(final int width, final int height, final int rgb) {
    final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    for (int row = 0; row < height; row++) {
      for (int column = 0; column < width; column++) {
        image.setRGB(column, row, rgb);
      }
    }

    return image;
  }

  private void pushScreen(final AtomicReference<VernacularConfig> capturedConfiguration, final Image image) {
    final VernacularConfig configuration = capturedConfiguration.get();
    final Consumer<Image> listener = configuration.getScreenUpdateListener();
    listener.accept(image);
  }

  private void pushError(
    final AtomicReference<VernacularConfig> capturedConfiguration,
    final com.shinyhut.vernacular.client.exceptions.VncException error // fqn: clashes with the VncException of this module
  ) {
    final VernacularConfig configuration = capturedConfiguration.get();
    final Consumer<com.shinyhut.vernacular.client.exceptions.VncException> listener = configuration.getErrorListener(); // fqn: clashes with the VncException of this module
    listener.accept(error);
  }

  private Socket startedSocket(final VernacularClient client) {
    final ArgumentCaptor<Socket> captor = ArgumentCaptor.forClass(Socket.class);
    verify(client).start(captor.capture());
    return captor.getValue();
  }

  @Test
  void shouldStreamTheRemoteScreenScaledToTheConfiguredSize() {
    final RfbTestServer server = this.server(SERVER_WIDTH, SERVER_HEIGHT);
    final VernacularVncPlayer player = this.player();
    final VncSource source = this.source(server, 32, 24);
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
  void shouldKeepTheSizeOfTheRemoteScreenWhenNoSizeIsConfigured() {
    final RfbTestServer server = this.server(40, 30);
    final VernacularVncPlayer player = this.player();
    final VncSource source = this.source(server, 0, 0);
    player.start(source);
    final RecordedFrame frame = this.awaitFrame("a frame arrives", _ -> true);
    assertEquals(40, frame.width);
    assertEquals(30, frame.height);
    assertEquals(RED, frame.rgb);
  }

  @Test
  void shouldForwardKeysAndTypedTextToTheServer() {
    final RfbTestServer server = this.server(SERVER_WIDTH, SERVER_HEIGHT);
    final VernacularVncPlayer player = this.player();
    final VncSource source = this.source(server, 0, 0);
    player.start(source);
    player.sendKeyEvent(RETURN_KEY);
    player.sendKeyEvent("hi");
    Await.until("six key events arrive", () -> server.getKeyCount() == 6);

    final List<RfbTestServer.ReceivedKey> keys = server.getKeys();
    final int[] expectedSymbols = { RETURN_KEYSYM, RETURN_KEYSYM, 'h', 'h', 'i', 'i' };
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
  void shouldTypeNothingForEmptyText() {
    final RfbTestServer server = this.server(SERVER_WIDTH, SERVER_HEIGHT);
    final VernacularVncPlayer player = this.player();
    final VncSource source = this.source(server, 0, 0);
    player.start(source);
    player.sendKeyEvent("");
    player.sendKeyEvent(RETURN_KEY);
    Await.until("the press and release of the return key arrive", () -> server.getKeyCount() == 2);

    final List<RfbTestServer.ReceivedKey> keys = server.getKeys();
    final List<Integer> keysyms = keys.stream().map(RfbTestServer.ReceivedKey::getKeysym).toList();
    assertEquals(List.of(RETURN_KEYSYM, RETURN_KEYSYM), keysyms);
  }

  @Test
  void shouldTranslatePointerInputToTheRemoteScreen() {
    final RfbTestServer server = this.server(SCREEN_WIDTH, SCREEN_HEIGHT);
    final VernacularVncPlayer player = this.player();
    final VncSource source = this.source(server, SCALED_WIDTH, SCALED_HEIGHT);
    player.start(source);
    this.awaitFrame("the first frame arrives", _ -> true);
    player.moveMouse(10, 20);
    player.sendMouseEvent(MouseClick.LEFT, 50, 25);
    player.sendMouseEvent(MouseClick.RIGHT, 1000, -5);

    Await.until("seven pointer events arrive", () -> server.getPointerCount() >= 7);
    final List<RfbTestServer.ReceivedPointer> pointers = server.getPointers();
    final int count = pointers.size();
    assertEquals(7, count);
    final int[][] expected = { { 0, 20, 40 }, { 0, 100, 50 }, { 1, 100, 50 }, { 0, 100, 50 }, { 0, 199, 0 }, { 4, 199, 0 }, { 0, 199, 0 } };
    for (int index = 0; index < expected.length; index++) {
      final RfbTestServer.ReceivedPointer pointer = pointers.get(index);
      final int mask = pointer.getButtonMask();
      final int screenX = pointer.getX();
      final int screenY = pointer.getY();
      assertEquals(expected[index][0], mask, "mask " + index);
      assertEquals(expected[index][1], screenX, "x " + index);
      assertEquals(expected[index][2], screenY, "y " + index);
    }
  }

  @Test
  void shouldSendUntranslatedCoordinatesBeforeTheFirstFrame() {
    final RfbTestServer server = this.server(SCREEN_WIDTH, SCREEN_HEIGHT);
    server.setAnswering(false);
    final VernacularVncPlayer player = this.player();
    final VncSource source = this.source(server, SCALED_WIDTH, SCALED_HEIGHT);
    player.start(source);
    player.moveMouse(30, -7);
    Await.until("the pointer event arrives", () -> server.getPointerCount() == 1);

    final List<RfbTestServer.ReceivedPointer> pointers = server.getPointers();
    final RfbTestServer.ReceivedPointer pointer = pointers.getFirst();
    final int screenX = pointer.getX();
    final int screenY = pointer.getY();
    final boolean noFrames = this.frames.isEmpty();
    assertEquals(30, screenX);
    assertEquals(0, screenY);
    assertTrue(noFrames);

    server.setAnswering(true);
    this.awaitFrame("a frame arrives once the server answers", _ -> true);
  }

  @Test
  void shouldPauseAndResumeTheStream() {
    final RfbTestServer server = this.server(SERVER_SIDE, SERVER_SIDE);
    final VernacularVncPlayer player = this.player();
    final VncSource source = this.source(server, 0, 0);
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
    server.setColor(BLUE);
    final int sentBefore = server.getUpdatesSent();
    Await.until("the server sends more updates", () -> server.getUpdatesSent() > sentBefore + 5);
    final boolean blueWhilePaused = this.containsBlueFrame();
    assertFalse(blueWhilePaused);
  }

  @Test
  void shouldRefuseToStartTwiceOrAfterRelease() throws InterruptedException {
    final RfbTestServer server = this.server(SERVER_SIDE, SERVER_SIDE);
    final VernacularVncPlayer player = this.player();
    final VncSource source = this.source(server, 0, 0);
    final boolean started = player.start(source);
    final boolean startedAgain = player.start(source);
    assertTrue(started);
    assertFalse(startedAgain);

    player.pause();
    final boolean startedWhilePaused = player.start(source);
    assertFalse(startedWhilePaused);

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
    this.assertDisconnectedOnce(server);
  }

  private void assertDisconnectedOnce(final RfbTestServer server) throws InterruptedException {
    final CountDownLatch disconnected = server.getDisconnected();
    final boolean closed = disconnected.await(5, TimeUnit.SECONDS);
    final int connections = server.getConnections();
    assertTrue(closed);
    assertEquals(1, connections);
  }

  @Test
  void shouldIgnoreInputAfterRelease() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final VncSource source = this.source(listening, 0, 0);
    player.start(source);

    player.moveMouse(2, 3);
    verify(client).moveMouse(2, 3);

    player.release();
    player.moveMouse(1, 1);
    player.sendMouseEvent(MouseClick.LEFT, 1, 1);
    player.sendKeyEvent(RETURN_KEY);
    player.sendKeyEvent("text");
    verify(client, times(1)).moveMouse(anyInt(), anyInt());
    verify(client, never()).click(anyInt());
    verify(client, never()).updateKey(anyInt(), anyBoolean());
    verify(client, never()).type(any());
  }

  @Test
  void shouldAuthenticateWithThePassword() {
    final RfbTestServer server = this.server(RfbTestServer.Security.PASSWORD, PASSWORD);
    final VernacularVncPlayer player = this.player();
    final VncSourceBuilder builder = this.hostBuilder(server);
    builder.username("user");
    builder.password(PASSWORD);
    final VncSource source = builder.build();
    final boolean started = player.start(source);
    final boolean authenticated = server.isAuthenticated();
    assertTrue(started);
    assertTrue(authenticated);
    this.awaitFrame("a frame arrives", _ -> true);
  }

  @Test
  void shouldFailToStartWhenThePasswordIsRejected() {
    final RfbTestServer server = this.server(RfbTestServer.Security.PASSWORD, PASSWORD);
    final VernacularVncPlayer player = this.player();
    final VncSourceBuilder builder = this.hostBuilder(server);
    builder.password("wrong");
    final VncSource source = builder.build();
    final VncException exception = assertThrows(VncException.class, () -> player.start(source));

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
  void shouldFailToStartWhenNoSecurityTypeIsSupported() {
    final RfbTestServer server = this.server(RfbTestServer.Security.UNSUPPORTED, "");
    final VernacularVncPlayer player = this.player();
    final VncSource source = this.source(server, 0, 0);
    final VncException exception = assertThrows(VncException.class, () -> player.start(source));
    final Throwable cause = exception.getCause();
    assertInstanceOf(NoSupportedSecurityTypesException.class, cause);
    final boolean playing = player.isPlaying();
    assertFalse(playing);
  }

  @Test
  void shouldStartAgainAfterAFailedStart() {
    final RfbTestServer refusing = this.server(RfbTestServer.Security.UNSUPPORTED, "");
    final RfbTestServer working = this.server(SERVER_SIDE, SERVER_SIDE);
    final VernacularVncPlayer player = this.player();
    final VncSource refusingSource = this.source(refusing, 0, 0);
    final VncSource workingSource = this.source(working, 0, 0);
    assertThrows(VncException.class, () -> player.start(refusingSource));
    final boolean started = player.start(workingSource);
    assertTrue(started);
    this.awaitFrame("a frame arrives", _ -> true);
  }

  @Test
  void shouldFailToStartWhenTheServerHangsUpDuringTheHandshake() {
    final RfbTestServer server = this.server(RfbTestServer.Security.HANG_UP, "");
    final VernacularVncPlayer player = this.player();
    final VncSource source = this.source(server, 0, 0);
    final VncException exception = assertThrows(VncException.class, () -> player.start(source));
    final String message = exception.getMessage();
    final boolean messageStartsWith = message.startsWith("VNC session with vnc://127.0.0.1:");
    assertTrue(messageStartsWith, message);
    final boolean playing = player.isPlaying();
    assertFalse(playing);
  }

  @Test
  void shouldFailToStartWhenNothingListens() throws IOException {
    final ServerSocket closed = this.listeningSocket();
    final VncSource source = this.source(closed, 0, 0);
    closed.close();
    final VernacularVncPlayer player = this.player();
    final VncException exception = assertThrows(VncException.class, () -> player.start(source));
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    final boolean messageStartsWith = message.startsWith("VNC server vnc://127.0.0.1:");
    assertTrue(messageStartsWith, message);
    assertInstanceOf(IOException.class, cause);
  }

  @Test
  void shouldCloseSocketsThatFailToConnectEvenIfClosingFails() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VncSource source = this.source(listening, 0, 0);
    final List<FailingSocket> createdSockets = new CopyOnWriteArrayList<>();
    final Supplier<Socket> sockets = () -> {
      final FailingSocket socket = new FailingSocket();
      createdSockets.add(socket);
      return socket;
    };
    final VernacularClient client = mock(VernacularClient.class);
    final VernacularVncPlayer created = new VernacularVncPlayer(_ -> client, sockets);
    final VernacularVncPlayer player = this.track(created);
    final VncException exception = assertThrows(VncException.class, () -> player.start(source));

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
  void shouldReportConnectionFailuresWhileStreaming() {
    final RfbTestServer server = this.server(SERVER_SIDE, SERVER_SIDE);
    final VernacularVncPlayer player = this.player();
    final VncSource source = this.source(server, 0, 0);
    player.start(source);
    this.awaitFrame("a frame arrives", _ -> true);
    server.sendUnknownMessage();
    Await.until("the failure is reported", () -> !this.errorMessages.isEmpty());
    final String message = this.errorMessages.getFirst();
    final Throwable error = this.errors.getFirst();
    assertEquals("The VNC connection failed", message);
    final UnexpectedVncException failure = assertInstanceOf(UnexpectedVncException.class, error);
    final Throwable cause = failure.getCause();
    final IOException refused = assertInstanceOf(IOException.class, cause);
    final String reason = refused.getMessage();
    final boolean named = reason.contains("message type 127");
    assertTrue(named, reason);
  }

  @Test
  void shouldReportPipelineFailuresAndKeepStreaming() {
    final RfbTestServer server = this.server(SERVER_SIDE, SERVER_SIDE);
    final VernacularVncPlayer player = this.player();
    final IllegalStateException failure = new IllegalStateException("filter failed!");
    final VideoFilter failing = (_, _) -> {
      throw failure;
    };
    this.attach(player, failing);
    final VncSource source = this.source(server, 0, 0);
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
  void shouldRestoreTheInterruptFlagWhenReleasedFromAnInterruptedThread() {
    final RfbTestServer server = this.server(SERVER_SIDE, SERVER_SIDE);
    final VernacularVncPlayer player = this.player();
    final VncSource source = this.source(server, 0, 0);
    player.start(source);
    final Thread current = Thread.currentThread();
    current.interrupt();
    final boolean released = player.release();
    final boolean interrupted = Thread.interrupted();
    assertTrue(released);
    assertTrue(interrupted);
  }

  @Test
  void shouldStopWaitingForABusyFilterWhenTheReleasingThreadIsInterrupted() throws Exception {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    final VideoFilter busy = this.blockingFilter(entered, proceed);
    this.attach(player, busy);

    final VncSource source = this.source(listening, 0, 0);
    player.start(source);
    final BufferedImage screen = this.image(SQUARE_SIDE, SQUARE_SIDE, RED);
    this.pushScreen(capturedConfiguration, screen);
    final boolean filtering = entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(filtering);

    final long elapsedMilliseconds = this.releaseWhileInterrupted(player, proceed);
    final boolean interrupted = Thread.interrupted();
    final boolean playing = player.isPlaying();
    assertTrue(interrupted);
    assertFalse(playing);
    assertTrue(elapsedMilliseconds < 1_500L, () -> "release took " + elapsedMilliseconds + " ms");
  }

  private VideoFilter blockingFilter(final CountDownLatch entered, final CountDownLatch proceed) {
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

  private long releaseWhileInterrupted(final VernacularVncPlayer player, final CountDownLatch proceed) {
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
  void shouldStartAsynchronously() throws Exception {
    final RfbTestServer server = this.server(SERVER_SIDE, SERVER_SIDE);
    final VncPlayer player = this.player();
    final VncSource source = this.source(server, 0, 0);
    final CompletableFuture<Boolean> first = player.startAsync(source);
    final boolean started = first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      final CompletableFuture<Boolean> second = player.startAsync(source, executor);
      final boolean startedAgain = second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
  void shouldCreatePlayersThatAreNotPlaying() {
    final VncPlayer player = VncPlayer.create();
    final boolean playing = player.isPlaying();
    final boolean paused = player.pause();
    final boolean resumed = player.resume();
    assertInstanceOf(VernacularVncPlayer.class, player);
    assertFalse(playing);
    assertFalse(paused);
    assertFalse(resumed);
    final boolean released = player.release();
    assertTrue(released);
  }

  @Test
  void shouldRejectNullArguments() {
    final VernacularVncPlayer player = this.player();
    assertThrows(NullPointerException.class, () -> player.start(null));
    assertThrows(NullPointerException.class, () -> player.sendMouseEvent(null, 0, 0));
    assertThrows(NullPointerException.class, () -> player.sendKeyEvent(null));
    assertThrows(NullPointerException.class, () -> player.setExceptionHandler(null));
  }

  @Test
  void shouldReplaceTheExceptionHandler() {
    final VernacularVncPlayer player = new VernacularVncPlayer();
    final BiConsumer<String, Throwable> handler = (_, _) -> {};
    player.setExceptionHandler(handler);
    final BiConsumer<String, Throwable> current = player.getExceptionHandler();
    assertSame(handler, current);
  }

  @Test
  void shouldConfigureTheClientFromTheSource() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final VncSourceBuilder builder = this.hostBuilder(listening);
    builder.username("user");
    builder.password(PASSWORD);
    builder.targetFrameRate(12);
    final VncSource source = builder.build();
    player.start(source);

    final VernacularConfig configuration = capturedConfiguration.get();
    this.assertConfiguredFromTheSource(configuration);
    final Socket connected = this.startedSocket(client);
    this.assertConnectedWithoutDelay(connected);
  }

  private void assertConnectedWithoutDelay(final Socket socket) throws IOException {
    final boolean isConnected = socket.isConnected();
    final boolean noDelay = socket.getTcpNoDelay();
    assertTrue(isConnected);
    assertTrue(noDelay);
  }

  private void assertConfiguredFromTheSource(final VernacularConfig configuration) {
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
    assertEquals(PASSWORD, password);
  }

  @Test
  void shouldSendNoCredentialsWhenTheyAreEmpty() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final VncSourceBuilder builder = this.hostBuilder(listening);
    builder.username("");
    builder.password("");
    final VncSource source = builder.build();
    player.start(source);
    final VernacularConfig configuration = capturedConfiguration.get();
    final Supplier<String> usernames = configuration.getUsernameSupplier();
    final Supplier<String> passwords = configuration.getPasswordSupplier();
    assertNull(usernames);
    assertNull(passwords);
  }

  @Test
  void shouldCloseTheSocketWhenTheClientFailsToStart() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final IllegalStateException failure = new IllegalStateException("client is already running!");
    doThrow(failure).when(client).start(any(Socket.class));
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final VncSource source = this.source(listening, 0, 0);
    final VncException exception = assertThrows(VncException.class, () -> player.start(source));
    final Throwable cause = exception.getCause();
    assertSame(failure, cause);

    final Socket used = this.startedSocket(client);
    final boolean closed = used.isClosed();
    assertTrue(closed);
    try (final Socket accepted = listening.accept()) {
      final InputStream input = accepted.getInputStream();
      final int read = input.read();
      assertEquals(-1, read);
    }
  }

  @Test
  void shouldStopTheClientAndCloseTheSocketWhenTheHandshakeFails() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final AuthenticationFailedException failure = new AuthenticationFailedException("denied!");
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    doAnswer(_ -> {
      final BufferedImage early = this.image(SQUARE_SIDE, SQUARE_SIDE, RED);
      this.pushScreen(capturedConfiguration, early);
      final boolean pending = player.hasPendingFrame();
      assertTrue(pending, "the handshake can deliver an image before reporting its failure");

      this.pushError(capturedConfiguration, failure);
      return null;
    })
      .when(client)
      .start(any(Socket.class));
    final VncSource source = this.source(listening, 0, 0);
    final VncException exception = assertThrows(VncException.class, () -> player.start(source));
    final Throwable cause = exception.getCause();
    final boolean pending = player.hasPendingFrame();
    assertSame(failure, cause);
    assertFalse(pending, "a failed handshake must not retain its undelivered image");

    verify(client).stop();
    final Socket used = this.startedSocket(client);
    final boolean closed = used.isClosed();
    assertTrue(closed);
    final boolean noReports = this.errorMessages.isEmpty();
    assertTrue(noReports);
  }

  @Test
  void shouldRenderEveryScreenUpdateAtItsOwnSize() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final VncSource source = this.source(listening, 0, 0);
    player.start(source);
    final BufferedImage small = this.image(8, 4, RED);
    this.pushScreen(capturedConfiguration, small);
    final RecordedFrame first = this.awaitFrame("the first frame arrives", frame -> frame.rgb == RED);
    final BufferedImage wide = this.image(6, 2, BLUE);
    this.pushScreen(capturedConfiguration, wide);
    final RecordedFrame second = this.awaitFrame("the second frame arrives", frame -> frame.rgb == BLUE);
    assertEquals(8, first.width);
    assertEquals(4, first.height);
    assertEquals(6, second.width);
    assertEquals(2, second.height);
    assertEquals(6, second.metadataWidth);
    assertEquals(2, second.metadataHeight);
  }

  @Test
  void shouldMapCoordinatesOneToOneWhenFramesKeepTheRemoteSize() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final VncSource source = this.source(listening, 0, 0);
    player.start(source);
    final BufferedImage screen = this.image(30, 20, RED);
    this.pushScreen(capturedConfiguration, screen);
    player.moveMouse(12, 7);
    player.moveMouse(30, 20);
    final InOrder order = inOrder(client);
    order.verify(client).moveMouse(12, 7);
    order.verify(client).moveMouse(29, 19);
  }

  @Test
  void shouldClampExtremeCoordinatesBeforeNarrowingToIntegers() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final VncSource source = this.source(listening, SCALED_WIDTH, SCALED_HEIGHT);
    player.start(source);
    final BufferedImage screen = this.image(SCREEN_WIDTH, SCREEN_HEIGHT, RED);
    this.pushScreen(capturedConfiguration, screen);

    player.moveMouse(Integer.MAX_VALUE, Integer.MAX_VALUE);
    player.moveMouse(Integer.MIN_VALUE + 1, Integer.MIN_VALUE + 1);
    player.sendMouseEvent(MouseClick.LEFT, Integer.MAX_VALUE, Integer.MIN_VALUE + 1);
    player.sendMouseEvent(MouseClick.RIGHT, Integer.MIN_VALUE + 1, Integer.MAX_VALUE);

    final InOrder order = inOrder(client);
    order.verify(client).moveMouse(199, 99);
    order.verify(client).moveMouse(0, 0);
    order.verify(client).moveMouse(199, 0);
    order.verify(client).click(1);
    order.verify(client).moveMouse(0, 99);
    order.verify(client).click(3);
  }

  @Test
  void shouldIgnoreScreenUpdatesThatAreNotBufferedImages() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final VncSource source = this.source(listening, 10, 10);
    player.start(source);
    final Image other = mock(Image.class);
    this.pushScreen(capturedConfiguration, other);
    player.moveMouse(5, 6);
    verify(client).moveMouse(5, 6);

    final BufferedImage frame = this.image(20, 20, RED);
    this.pushScreen(capturedConfiguration, frame);
    this.awaitFrame("the buffered frame arrives", _ -> true);
    final int count = this.frames.size();
    assertEquals(1, count);
    verifyNoInteractions(other);
  }

  @Test
  void shouldLearnTheRemoteSizeButDropFramesWhilePaused() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final VncSource source = this.source(listening, 10, 5);
    player.start(source);
    player.pause();
    final BufferedImage paused = this.image(20, 10, RED);
    this.pushScreen(capturedConfiguration, paused);
    player.moveMouse(5, 2);
    verify(client).moveMouse(10, 4);
    player.moveMouse(1000, -5);
    verify(client).moveMouse(19, 0);

    player.resume();
    final BufferedImage resumed = this.image(20, 10, BLUE);
    this.pushScreen(capturedConfiguration, resumed);
    final RecordedFrame frame = this.awaitFrame("a frame arrives after resuming", _ -> true);
    final int count = this.frames.size();
    assertEquals(BLUE, frame.rgb);
    assertEquals(10, frame.width);
    assertEquals(5, frame.height);
    assertEquals(1, count);
  }

  @Test
  void shouldTranslateEveryKindOfClick() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final VncSource source = this.source(listening, 0, 0);
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
  void shouldPressNamedKeysAndTypeOtherText() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final VncSource source = this.source(listening, 0, 0);
    player.start(source);
    player.sendKeyEvent("Escape");
    player.sendKeyEvent("hello");
    final InOrder order = inOrder(client);
    order.verify(client).updateKey(0xff1b, true);
    order.verify(client).updateKey(0xff1b, false);
    order.verify(client).type("hello");
  }

  @Test
  void shouldNotTouchTheClientBeforeStarting() {
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    player.moveMouse(1, 1);
    player.sendMouseEvent(MouseClick.LEFT, 1, 1);
    player.sendKeyEvent(RETURN_KEY);
    final boolean released = player.release();
    assertTrue(released);
    verifyNoInteractions(client);
    final VernacularConfig configuration = capturedConfiguration.get();
    assertNull(configuration);
  }

  @Test
  void shouldReportInputThatFailsToForward() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final IllegalStateException failure = new IllegalStateException("broken pipe!");
    doThrow(failure).when(client).moveMouse(anyInt(), anyInt());
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final VncSource source = this.source(listening, 0, 0);
    player.start(source);
    player.moveMouse(1, 1);
    final String message = this.errorMessages.getFirst();
    final Throwable error = this.errors.getFirst();
    assertEquals("Failed to forward input to the VNC server", message);
    assertSame(failure, error);
  }

  @Test
  void shouldIgnoreInputFailuresCausedByARelease() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final AtomicReference<VernacularVncPlayer> reference = new AtomicReference<>();
    doAnswer(_ -> {
      final VernacularVncPlayer current = reference.get();
      current.release();
      throw new IllegalStateException("closed while typing!");
    })
      .when(client)
      .updateKey(anyInt(), anyBoolean());
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    reference.set(player);
    final VncSource source = this.source(listening, 0, 0);
    player.start(source);
    player.sendKeyEvent(RETURN_KEY);
    final boolean noReports = this.errorMessages.isEmpty();
    final boolean playing = player.isPlaying();
    assertTrue(noReports, this.errorMessages::toString);
    assertFalse(playing);
  }

  @Test
  void shouldReportErrorsOfAStartedConnection() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final VncSource source = this.source(listening, 0, 0);
    player.start(source);
    final UnknownMessageTypeException failure = new UnknownMessageTypeException(UNKNOWN_MESSAGE_TYPE);
    this.pushError(capturedConfiguration, failure);
    final String message = this.errorMessages.getFirst();
    final Throwable error = this.errors.getFirst();
    assertEquals("The VNC connection failed", message);
    assertSame(failure, error);
  }

  @Test
  void shouldStopPlayingWhenTheConnectionFailsAndCanStartAgain() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final VncSource source = this.source(listening, 0, 0);
    player.start(source);
    final UnknownMessageTypeException failure = new UnknownMessageTypeException(UNKNOWN_MESSAGE_TYPE);
    this.pushError(capturedConfiguration, failure);
    this.pushError(capturedConfiguration, failure);
    this.assertEndedSessionIgnoresControl(player, client);

    final boolean restarted = player.start(source);
    final boolean playingAgain = player.isPlaying();
    assertTrue(restarted);
    assertTrue(playingAgain);
    final InOrder order = inOrder(client);
    order.verify(client).start(any(Socket.class));
    order.verify(client).stop();
    order.verify(client).start(any(Socket.class));
  }

  private void assertEndedSessionIgnoresControl(final VernacularVncPlayer player, final VernacularClient client) {
    final boolean playing = player.isPlaying();
    final boolean paused = player.pause();
    final boolean resumed = player.resume();
    player.moveMouse(1, 1);
    player.sendKeyEvent(RETURN_KEY);
    final int reports = this.errorMessages.size();
    assertFalse(playing);
    assertFalse(paused);
    assertFalse(resumed);
    assertEquals(1, reports, this.errorMessages::toString);
    verify(client, never()).moveMouse(anyInt(), anyInt());
    verify(client, never()).updateKey(anyInt(), anyBoolean());
  }

  @Test
  void shouldFailTheStartWithTheFirstErrorOfTheHandshake() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final AuthenticationFailedException first = new AuthenticationFailedException("denied!");
    final UnknownMessageTypeException second = new UnknownMessageTypeException(3);
    doAnswer(_ -> {
      this.pushError(capturedConfiguration, first);
      this.pushError(capturedConfiguration, second);
      return null;
    })
      .when(client)
      .start(any(Socket.class));
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final VncSource source = this.source(listening, 0, 0);
    final VncException exception = assertThrows(VncException.class, () -> player.start(source));
    final Throwable cause = exception.getCause();
    final boolean noReports = this.errorMessages.isEmpty();
    assertSame(first, cause);
    assertTrue(noReports, this.errorMessages::toString);
  }

  @Test
  void shouldShowAScreenUpdateThatArrivesDuringTheHandshake() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    doAnswer(_ -> {
      final BufferedImage screen = this.image(SQUARE_SIDE, SQUARE_SIDE, RED);
      this.pushScreen(capturedConfiguration, screen);
      return null;
    })
      .when(client)
      .start(any(Socket.class));
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final VncSource source = this.source(listening, 0, 0);
    player.start(source);
    final RecordedFrame frame = this.awaitFrame("the update of the handshake is shown", _ -> true);
    assertEquals(RED, frame.rgb);
  }

  @Test
  void shouldNeverLoseAnErrorThatArrivesAroundTheEndOfTheHandshake() throws IOException {
    final InetAddress loopback = InetAddress.getLoopbackAddress();
    final ServerSocket listening = new ServerSocket(0, 200, loopback);
    this.resources.add(listening);
    final VncSource source = this.source(listening, 0, 0);
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

  private boolean startWithLateError(final VncSource source, final int attempt) {
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final long delayNanoseconds = TimeUnit.MICROSECONDS.toNanos(attempt * 25L);
    final UnknownMessageTypeException failure = new UnknownMessageTypeException(attempt);
    doAnswer(_ -> {
      if (attempt == 0) {
        this.pushError(capturedConfiguration, failure);
        return null;
      }
      final Thread sender = new Thread(() -> {
        LockSupport.parkNanos(delayNanoseconds);
        this.pushError(capturedConfiguration, failure);
      });
      sender.start();
      return null;
    })
      .when(client)
      .start(any(Socket.class));

    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    boolean running;
    try {
      running = player.start(source);
    } catch (final VncException exception) {
      final Throwable cause = exception.getCause();
      assertSame(failure, cause, "a refused handshake must carry its injected error");

      running = false;
    }
    if (running) {
      Await.until("the error of attempt " + attempt + " ends the session", () -> !player.isPlaying());
    }
    return running;
  }

  @Test
  void shouldThrowErrorsOfTheVirtualMachineOnTheRenderThread() throws Exception {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final InternalError fatal = new InternalError("out of native memory!");
    final VideoFilter failing = (_, _) -> {
      throw fatal;
    };
    this.attach(player, failing);

    final VncSource source = this.source(listening, 0, 0);
    final Throwable error = this.awaitRenderThreadDeath(() -> {
      player.start(source);
      final BufferedImage screen = this.image(SQUARE_SIDE, SQUARE_SIDE, RED);
      this.pushScreen(capturedConfiguration, screen);
    });
    final boolean noReports = this.errorMessages.isEmpty();
    assertSame(fatal, error);
    assertTrue(noReports, this.errorMessages::toString);
  }

  private Throwable awaitRenderThreadDeath(final Runnable trigger) throws InterruptedException {
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
      final boolean threadDied = died.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(threadDied);
    } finally {
      Thread.setDefaultUncaughtExceptionHandler(previous);
    }
    return uncaught.get();
  }

  @Test
  void shouldReportClientsThatFailToStop() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final IllegalStateException failure = new IllegalStateException("stuck!");
    doThrow(failure).when(client).stop();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final VncSource source = this.source(listening, 0, 0);
    player.start(source);
    final boolean released = player.release();
    final String message = this.errorMessages.getFirst();
    final Throwable error = this.errors.getFirst();
    assertTrue(released);
    assertEquals("Failed to close the VNC connection", message);
    assertSame(failure, error);
  }

  @Test
  void shouldRenderOnDaemonThreadsSoTheyNeverKeepTheJvmAlive() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final VncSource source = this.source(listening, 0, 0);
    player.start(source);

    Await.until("the render thread runs", () -> !this.renderThreads().isEmpty());
    final List<Thread> threads = this.renderThreads();
    for (final Thread thread : threads) {
      final boolean isDaemon = thread.isDaemon();
      final String name = thread.getName();
      assertTrue(isDaemon, name);
    }
  }

  // Gets the render threads of every player that is alive, which all carry the same name.
  private List<Thread> renderThreads() {
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
  void shouldWaitForTheRenderThreadToFinishItsFrameBeforeAReleaseReturns() throws Exception {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    final VideoFilter busy = this.blockingFilter(entered, proceed);
    this.attach(player, busy);
    final VncSource source = this.source(listening, 0, 0);
    player.start(source);
    final BufferedImage screen = this.image(SQUARE_SIDE, SQUARE_SIDE, RED);
    this.pushScreen(capturedConfiguration, screen);
    final boolean filtering = entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(filtering);

    final CountDownLatch released = this.releaseOnAnotherThread(player);
    final boolean returnedWhileBusy = released.await(500, TimeUnit.MILLISECONDS);
    proceed.countDown();
    final boolean returnedAfterwards = released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertFalse(returnedWhileBusy, "the release waits for the render thread to finish its frame");
    assertTrue(returnedAfterwards);
  }

  private CountDownLatch releaseOnAnotherThread(final VernacularVncPlayer player) {
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
  void shouldDropTheFrameThatWasStillWaitingWhenThePlayerPaused() throws Exception {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    final VideoFilter busy = this.blockingThenRecording(entered, proceed);
    this.attach(player, busy);
    final VncSource source = this.source(listening, 0, 0);
    player.start(source);
    this.pushAndAwaitBusyRenderThread(capturedConfiguration, entered);

    final BufferedImage waiting = this.image(SQUARE_SIDE, SQUARE_SIDE, GREEN);
    this.pushScreen(capturedConfiguration, waiting);
    final boolean paused = player.pause();
    proceed.countDown();
    player.resume();
    final BufferedImage afterResume = this.image(SQUARE_SIDE, SQUARE_SIDE, BLUE);
    this.pushScreen(capturedConfiguration, afterResume);
    this.awaitFrame("a frame arrives after resuming", frame -> frame.rgb == BLUE);
    final boolean stale = this.containsFrame(GREEN);
    assertTrue(paused);
    assertFalse(stale, "the frame that was waiting when the player paused is dropped");
  }

  @Test
  void shouldNeverShowAFrameThatWasWaitingWhenTheSessionEnded() throws Exception {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    final VideoFilter busy = this.blockingThenRecording(entered, proceed);
    this.attach(player, busy);
    final VncSource source = this.source(listening, 0, 0);
    player.start(source);
    this.pushAndAwaitBusyRenderThread(capturedConfiguration, entered);

    final BufferedImage waiting = this.image(SQUARE_SIDE, SQUARE_SIDE, GREEN);
    this.pushScreen(capturedConfiguration, waiting);
    final UnknownMessageTypeException failure = new UnknownMessageTypeException(UNKNOWN_MESSAGE_TYPE);
    this.pushError(capturedConfiguration, failure);
    final boolean restarted = player.start(source);
    proceed.countDown();
    final BufferedImage afterRestart = this.image(SQUARE_SIDE, SQUARE_SIDE, BLUE);
    this.pushScreen(capturedConfiguration, afterRestart);
    this.awaitFrame("a frame of the new session arrives", frame -> frame.rgb == BLUE);
    final boolean stale = this.containsFrame(GREEN);
    assertTrue(restarted);
    assertFalse(stale, "a frame of the session that ended is never shown by the next one");
  }

  @Test
  void shouldDropThePendingFrameWhenPausing() throws Exception {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    final VideoFilter busy = this.blockingThenRecording(entered, proceed);
    this.attach(player, busy);
    final VncSource source = this.source(listening, 0, 0);
    player.start(source);
    this.pushAndAwaitBusyRenderThread(capturedConfiguration, entered);

    final BufferedImage waiting = this.image(SQUARE_SIDE, SQUARE_SIDE, GREEN);
    this.pushScreen(capturedConfiguration, waiting);
    final boolean pendingBeforePause = player.hasPendingFrame();
    final boolean paused = player.pause();
    final boolean pendingAfterPause = player.hasPendingFrame();
    proceed.countDown();

    assertTrue(pendingBeforePause, "the update waits while the render thread is busy");
    assertTrue(paused);
    assertFalse(pendingAfterPause, "pausing drops the update that was still waiting");
  }

  @Test
  void shouldDropThePendingFrameWhenTheNextSessionStarts() throws Exception {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    final VideoFilter busy = this.blockingThenRecording(entered, proceed);
    this.attach(player, busy);
    final VncSource source = this.source(listening, 0, 0);
    player.start(source);
    this.pushAndAwaitBusyRenderThread(capturedConfiguration, entered);
    final Thread endingRenderThread = this.onlyRenderThread();

    final BufferedImage waiting = this.image(SQUARE_SIDE, SQUARE_SIDE, GREEN);
    this.pushScreen(capturedConfiguration, waiting);
    final UnknownMessageTypeException failure = new UnknownMessageTypeException(UNKNOWN_MESSAGE_TYPE);
    this.pushError(capturedConfiguration, failure);
    proceed.countDown();
    Await.until("the render thread of the session that ended stops", () -> !endingRenderThread.isAlive());
    final boolean pendingAfterTheSessionEnded = player.hasPendingFrame();

    final boolean restarted = player.start(source);
    Await.until("the render thread of the new session waits for an update", this::anyRenderThreadWaitsForAnUpdate);
    final boolean pending = player.hasPendingFrame();
    final boolean stale = this.containsFrame(GREEN);

    assertTrue(pendingAfterTheSessionEnded, "the update of the session that ended waits until the next start");
    assertTrue(restarted);
    assertFalse(pending, "the update of the session that ended is dropped when the next one starts");
    assertFalse(stale, "and it is never rendered");
  }

  // Gets the one render thread that is alive, failing the test when a thread of an earlier session is still running.
  private Thread onlyRenderThread() {
    final List<Thread> threads = this.renderThreads();
    final int count = threads.size();
    assertEquals(1, count, "exactly one render thread runs");
    return threads.getFirst();
  }

  // Checks whether a render thread parked because it found no update, which the render loop does with a timed park. A
  // render thread that is held inside a filter waits with a timeout as well, so this only tells the two apart once the
  // threads of earlier sessions have stopped.
  private boolean anyRenderThreadWaitsForAnUpdate() {
    final List<Thread> threads = this.renderThreads();
    for (final Thread thread : threads) {
      final Thread.State state = thread.getState();
      if (state == Thread.State.TIMED_WAITING) {
        return true;
      }
    }
    return false;
  }

  // Pushes a first screen update and waits until the render thread is busy with it, so the next update stays waiting.
  private void pushAndAwaitBusyRenderThread(final AtomicReference<VernacularConfig> capturedConfiguration, final CountDownLatch entered)
    throws InterruptedException {
    final BufferedImage first = this.image(SQUARE_SIDE, SQUARE_SIDE, RED);
    this.pushScreen(capturedConfiguration, first);
    final boolean filtering = entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(filtering);
  }

  // Creates a filter that holds the render thread inside its first frame and records every frame as usual.
  private VideoFilter blockingThenRecording(final CountDownLatch entered, final CountDownLatch proceed) {
    final AtomicBoolean firstFrame = new AtomicBoolean(true);
    return (image, metadata) -> {
      final boolean first = firstFrame.compareAndSet(true, false);
      if (first) {
        entered.countDown();
        this.awaitLatch(proceed);
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
  void shouldIgnoreScreensFromAnEndedSessionAfterRestartAndRelease() throws Exception {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    final VideoFilter busy = this.blockingThenRecording(entered, proceed);
    this.attach(player, busy);
    final VncSource source = this.source(listening, SCALED_WIDTH, SCALED_HEIGHT);
    player.start(source);
    final VernacularConfig oldConfiguration = capturedConfiguration.get();
    final Consumer<Image> oldListener = oldConfiguration.getScreenUpdateListener();
    final UnknownMessageTypeException failure = new UnknownMessageTypeException(UNKNOWN_MESSAGE_TYPE);
    this.pushError(capturedConfiguration, failure);
    player.start(source);
    final VernacularConfig newConfiguration = capturedConfiguration.get();
    final Consumer<Image> newListener = newConfiguration.getScreenUpdateListener();
    final BufferedImage current = this.image(SCREEN_WIDTH, SCREEN_HEIGHT, RED);
    this.pushScreen(capturedConfiguration, current);
    final boolean rendering = entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(rendering);
    try {
      final BufferedImage stale = this.image(3, 9, GREEN);
      oldListener.accept(stale);
      final boolean staleQueued = player.hasPendingFrame();
      player.moveMouse(50, 25);
      assertFalse(staleQueued, "a late callback must not replace the next session's pending image");
      verify(client).moveMouse(100, 50);
      proceed.countDown();
      player.release();
      newListener.accept(stale);
      final boolean queuedAfterRelease = player.hasPendingFrame();
      assertFalse(queuedAfterRelease, "a released session must reject late image callbacks");
    } finally {
      proceed.countDown();
    }
  }

  @Test
  void shouldDropThePendingFrameOnReleaseAfterTheRendererStops() throws Exception {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    final VideoFilter busy = this.blockingThenRecording(entered, proceed);
    this.attach(player, busy);
    final VncSource source = this.source(listening, 0, 0);
    player.start(source);
    this.pushAndAwaitBusyRenderThread(capturedConfiguration, entered);
    final Thread renderer = this.onlyRenderThread();
    try {
      final BufferedImage pending = this.image(SQUARE_SIDE, SQUARE_SIDE, GREEN);
      this.pushScreen(capturedConfiguration, pending);
      final UnknownMessageTypeException failure = new UnknownMessageTypeException(UNKNOWN_MESSAGE_TYPE);
      this.pushError(capturedConfiguration, failure);
      proceed.countDown();
      renderer.join(2_000L);
      final boolean pendingBeforeRelease = player.hasPendingFrame();
      assertTrue(pendingBeforeRelease);
      player.release();
      final boolean pendingAfterRelease = player.hasPendingFrame();
      assertFalse(pendingAfterRelease, "release must discard the retained image even when rendering already stopped");
    } finally {
      proceed.countDown();
    }
  }

  @Test
  void shouldWaitForThePreviousRendererToFinishOnRestart() throws Exception {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    final VideoFilter busy = this.blockingThenRecording(entered, proceed);
    this.attach(player, busy);
    final VncSource source = this.source(listening, 0, 0);
    player.start(source);
    this.pushAndAwaitBusyRenderThread(capturedConfiguration, entered);
    final Thread oldRenderer = this.onlyRenderThread();
    final UnknownMessageTypeException failure = new UnknownMessageTypeException(UNKNOWN_MESSAGE_TYPE);
    this.pushError(capturedConfiguration, failure);
    final CountDownLatch restarted = new CountDownLatch(1);
    final AtomicBoolean result = new AtomicBoolean();
    final Thread starter = new Thread(() -> {
      result.set(player.start(source));
      restarted.countDown();
    }, "restart-vnc-probe");
    try {
      starter.start();
      final boolean restartedWhileBusy = restarted.await(250, TimeUnit.MILLISECONDS);
      assertFalse(restartedWhileBusy, "restart must join the previous render worker before installing a new one");
      proceed.countDown();
      final boolean completed = restarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      final boolean oldAlive = oldRenderer.isAlive();
      final boolean started = result.get();
      assertTrue(completed);
      assertTrue(started);
      assertFalse(oldAlive);
    } finally {
      proceed.countDown();
      starter.join(10_000L);
    }
  }

  @Test
  void shouldWakeAnIdleRendererForFramesAndShutdown() throws Exception {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final long longIdleWait = TimeUnit.SECONDS.toNanos(30);
    final VernacularVncPlayer player = new VernacularVncPlayer(
      configuration -> {
        capturedConfiguration.set(configuration);
        return client;
      },
      Socket::new,
      longIdleWait
    );
    this.track(player);
    final CountDownLatch received = new CountDownLatch(1);
    final VideoFilter recorder = (_, _) -> {
      received.countDown();
      return false;
    };
    this.attach(player, recorder);
    final VncSource source = this.source(listening, 0, 0);
    player.start(source);
    Await.until("the renderer starts", () -> !this.renderThreads().isEmpty());
    final Thread renderer = this.onlyRenderThread();
    try {
      Await.until("the renderer enters its long idle park", this::anyRenderThreadWaitsForAnUpdate);
      final BufferedImage screen = this.image(SQUARE_SIDE, SQUARE_SIDE, RED);
      this.pushScreen(capturedConfiguration, screen);
      final boolean delivered = received.await(2, TimeUnit.SECONDS);
      assertTrue(delivered, "a screen callback must wake the renderer instead of waiting for its idle timeout");
      Await.until("the renderer parks again after the frame", this::anyRenderThreadWaitsForAnUpdate);
      player.release();
      final boolean aliveAfterRelease = renderer.isAlive();
      assertFalse(aliveAfterRelease, "release must wake the idle renderer so it can observe the ended session");
    } finally {
      player.release();
      LockSupport.unpark(renderer);
      renderer.join(2_000L);
    }
  }

  @Test
  void shouldRejectNonPositiveIdleWaits() {
    assertThrows(IllegalArgumentException.class, () -> new VernacularVncPlayer(VernacularClient::new, Socket::new, 0));
    assertThrows(IllegalArgumentException.class, () -> new VernacularVncPlayer(VernacularClient::new, Socket::new, -1));
  }

  @Test
  void shouldRejectNonPositiveHandshakeTimeouts() {
    final IllegalArgumentException zero = assertThrows(IllegalArgumentException.class, () ->
      new VernacularVncPlayer(VernacularClient::new, Socket::new, 1, 0)
    );
    final String message = zero.getMessage();
    assertEquals("Handshake timeout 0 must be positive!", message);

    assertThrows(IllegalArgumentException.class, () -> new VernacularVncPlayer(VernacularClient::new, Socket::new, 1, -1));
  }

  @Test
  void shouldReleaseFromARenderCallbackWithoutWaitingForItself() throws Exception {
    this.assertControlFromRenderCallback(false);
  }

  @Test
  void shouldRestartAnEndedSessionFromARenderCallbackWithoutWaitingForItself() throws Exception {
    this.assertControlFromRenderCallback(true);
  }

  @Test
  void shouldKeepPipelineDeliverySerialAcrossRestartedSessions() throws Exception {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final VncSource source = this.source(listening, 0, 0);
    final CountDownLatch restarted = new CountDownLatch(1);
    final CountDownLatch releaseFirst = new CountDownLatch(1);
    final CountDownLatch secondEntered = new CountDownLatch(1);
    final CountDownLatch delivered = new CountDownLatch(2);
    final AtomicInteger active = new AtomicInteger();
    final AtomicInteger maximum = new AtomicInteger();
    final List<Integer> colors = new CopyOnWriteArrayList<>();
    final UnknownMessageTypeException failure = new UnknownMessageTypeException(UNKNOWN_MESSAGE_TYPE);
    final VideoPipelineStep recorder = VideoPipelineStep.of((image, _) -> {
      colors.add(image.getPixels()[0] & 0xFFFFFF);
      active.decrementAndGet();
      delivered.countDown();
      return false;
    });
    final VideoPipelineStep controller = VideoPipelineStep.of(recorder, (image, _) -> {
      maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
      if ((image.getPixels()[0] & 0xFFFFFF) == RED) {
        this.pushError(capturedConfiguration, failure);
        final boolean started = player.start(source);
        assertTrue(started);

        this.pushScreen(capturedConfiguration, this.image(SQUARE_SIDE, SQUARE_SIDE, BLUE));
        restarted.countDown();
        this.awaitLatch(releaseFirst);
      } else {
        secondEntered.countDown();
      }
      return false;
    });
    player.getVideoAttachableCallback().attach(controller);
    try {
      final boolean started = player.start(source);
      assertTrue(started);

      this.pushScreen(capturedConfiguration, this.image(SQUARE_SIDE, SQUARE_SIDE, RED));
      final boolean restartedInCallback = restarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      final boolean replacementEntered = secondEntered.await(200, TimeUnit.MILLISECONDS);
      assertTrue(restartedInCallback);
      assertFalse(replacementEntered, "the replacement entered a pipeline still processing the old session");

      releaseFirst.countDown();
      final boolean replacementDelivered = delivered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      final int mostAtOnce = maximum.get();
      final List<Integer> expectedColors = List.of(RED, BLUE);
      final List<com.shinyhut.vernacular.client.exceptions.VncException> expectedErrors = List.of(failure); // fqn: clashes with the VncException of this module
      assertTrue(replacementDelivered);
      assertEquals(1, mostAtOnce);
      assertEquals(expectedColors, colors, "the replacement's frame must remain the last displayed frame");
      assertEquals(expectedErrors, this.errors);
    } finally {
      releaseFirst.countDown();
      player.release();
    }
  }

  @Test
  void shouldDropTheFrameOfAnEndedReplacementWhileWaitingForAnOldCallback() throws Exception {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final VncSource source = this.source(listening, 0, 0);
    final CountDownLatch restarted = new CountDownLatch(1);
    final CountDownLatch releaseFirst = new CountDownLatch(1);
    final AtomicReference<Thread> originalRenderer = new AtomicReference<>();
    final List<Integer> colors = new CopyOnWriteArrayList<>();
    final UnknownMessageTypeException originalFailure = new UnknownMessageTypeException(UNKNOWN_MESSAGE_TYPE);
    final UnknownMessageTypeException replacementFailure = new UnknownMessageTypeException(10);
    player.getVideoAttachableCallback().attach(
      VideoPipelineStep.of((frame, _) -> {
        final int color = frame.getPixels()[0] & 0xFFFFFF;
        if (color == RED) {
          originalRenderer.set(Thread.currentThread());
          this.pushError(capturedConfiguration, originalFailure);
          final boolean started = player.start(source);
          assertTrue(started);

          this.pushScreen(capturedConfiguration, this.image(SQUARE_SIDE, SQUARE_SIDE, BLUE));
          restarted.countDown();
          final boolean releasedFirst = this.awaitLatch(releaseFirst);
          assertTrue(releasedFirst);
        }
        colors.add(color);
        return false;
      })
    );
    try {
      final boolean started = player.start(source);
      assertTrue(started);

      this.pushScreen(capturedConfiguration, this.image(SQUARE_SIDE, SQUARE_SIDE, RED));
      final boolean restartedInCallback = restarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(restartedInCallback);

      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (player.hasPendingFrame() && System.nanoTime() < deadline) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
      }
      final boolean pending = player.hasPendingFrame();
      assertFalse(pending, "the replacement takes its queued frame");

      this.pushError(capturedConfiguration, replacementFailure);
      releaseFirst.countDown();
    } finally {
      releaseFirst.countDown();
      player.release();
      final Thread original = originalRenderer.get();
      if (original != null) {
        original.join(10_000);
        final boolean originalAlive = original.isAlive();
        assertFalse(originalAlive, "the original renderer must finish");
      }
    }

    final List<Integer> expectedColors = List.of(RED);
    final List<com.shinyhut.vernacular.client.exceptions.VncException> expectedErrors = List.of(originalFailure, replacementFailure); // fqn: clashes with the VncException of this module
    assertEquals(expectedColors, colors, "the ended replacement cannot show its waiting frame");
    assertEquals(expectedErrors, this.errors);
  }

  private void assertControlFromRenderCallback(final boolean restarts) throws Exception {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final VncSource source = this.source(listening, 0, 0);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch returned = new CountDownLatch(1);
    final AtomicBoolean changed = new AtomicBoolean();
    final AtomicReference<Thread> worker = new AtomicReference<>();
    final VideoFilter controller = (_, _) -> {
      worker.set(Thread.currentThread());
      entered.countDown();
      if (restarts) {
        final UnknownMessageTypeException failure = new UnknownMessageTypeException(UNKNOWN_MESSAGE_TYPE);
        this.pushError(capturedConfiguration, failure);
        changed.set(player.start(source));
      } else {
        changed.set(player.release());
      }
      returned.countDown();
      return false;
    };
    this.attach(player, controller);
    player.start(source);
    try {
      final BufferedImage screen = this.image(SQUARE_SIDE, SQUARE_SIDE, RED);
      this.pushScreen(capturedConfiguration, screen);
      final boolean controlling = entered.await(2, TimeUnit.SECONDS);
      assertTrue(controlling);
      final boolean completed = returned.await(1, TimeUnit.SECONDS);
      final boolean restartedInCallback = changed.get();
      assertTrue(completed, "the render thread must not spend the two-second join timeout waiting for itself");
      assertTrue(restartedInCallback);

      final Thread oldRenderer = worker.get();
      oldRenderer.join(2_000L);
      final boolean oldAlive = oldRenderer.isAlive();
      final boolean playing = player.isPlaying();
      assertFalse(oldAlive);
      assertEquals(restarts, playing);
    } finally {
      player.release();
    }
  }

  @Test
  void shouldStartPlayingAgainAfterTheSessionThatWasPausedFailed() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final VncSource source = this.source(listening, 0, 0);
    player.start(source);
    final boolean paused = player.pause();

    final UnknownMessageTypeException failure = new UnknownMessageTypeException(UNKNOWN_MESSAGE_TYPE);
    this.pushError(capturedConfiguration, failure);
    final boolean restarted = player.start(source);
    final boolean playing = player.isPlaying();
    assertTrue(paused);
    assertTrue(restarted);
    assertTrue(playing, "a session that starts again is never paused");

    final BufferedImage screen = this.image(SQUARE_SIDE, SQUARE_SIDE, BLUE);
    this.pushScreen(capturedConfiguration, screen);
    this.awaitFrame("a frame of the new session arrives", frame -> frame.rgb == BLUE);
  }

  @Test
  void shouldReleaseItsLockAfterEveryControlCall() throws IOException {
    final ServerSocket listening = this.listeningSocket();
    final VernacularClient client = mock(VernacularClient.class);
    final AtomicReference<VernacularConfig> capturedConfiguration = new AtomicReference<>();
    final VernacularVncPlayer player = this.mockedPlayer(client, capturedConfiguration);
    final VncSource source = this.source(listening, 0, 0);
    player.start(source);

    player.pause();
    this.assertDoesNotBlock("another thread pauses after a pause", () -> player.pause());
    player.resume();
    this.assertDoesNotBlock("another thread resumes after a resume", () -> player.resume());
    player.release();
    this.assertDoesNotBlock("another thread releases after a release", () -> player.release());
  }

  // Asserts that an action which takes the lock of the player finishes on another thread, which it only does while no
  // lock is left held.
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

  private static final class FailingSocket extends Socket {

    private volatile boolean closeAttempted;

    @Override
    public void connect(final SocketAddress endpoint, final int timeout) throws IOException {
      throw new ConnectException("refused on purpose!");
    }

    @Override
    public synchronized void close() throws IOException {
      this.closeAttempted = true;
      throw new IOException("close failed on purpose!");
    }
  }

  // Keeps every worker of the common pool busy until the returned latch opens, as the tasks of other plugins can.
  private CountDownLatch occupyTheCommonPool() throws InterruptedException {
    final int workers = ForkJoinPool.getCommonPoolParallelism();
    final CountDownLatch busy = new CountDownLatch(workers);
    final CountDownLatch release = new CountDownLatch(1);
    final ForkJoinPool pool = ForkJoinPool.commonPool();
    for (int worker = 0; worker < workers; worker++) {
      pool.execute(() -> this.occupyWorker(busy, release));
    }

    final boolean allBusy = busy.await(30, TimeUnit.SECONDS);
    assertTrue(allBusy, "every worker of the common pool is busy");

    return release;
  }

  private void occupyWorker(final CountDownLatch busy, final CountDownLatch release) {
    busy.countDown();
    try {
      release.await();
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  @Test
  void shouldGiveUpOnAServerThatNeverAnswersItsHandshakeWhileTheCommonPoolIsBusy() throws Exception {
    final CountDownLatch release = this.occupyTheCommonPool();
    try {
      final ServerSocket silent = this.listeningSocket();
      final VernacularVncPlayer player = this.track(
        new VernacularVncPlayer(VernacularClient::new, Socket::new, SHORT_IDLE_NANOSECONDS, HANDSHAKE_DEADLINE_MILLISECONDS)
      );
      final VncSource source = this.source(silent, 0, 0);
      final VncException failure = assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
        assertThrows(VncException.class, () -> player.start(source))
      );
      final String message = failure.getMessage();
      final boolean blamesTheDeadline = message.endsWith(" could not start because the server did not finish the handshake within 500 ms!");
      assertTrue(blamesTheDeadline, message);
    } finally {
      release.countDown();
    }
  }

  @Test
  void shouldGiveUpOnAServerThatNeverAnswersItsHandshake() throws IOException {
    final ServerSocket silent = this.listeningSocket();
    final VernacularVncPlayer player = this.track(
      new VernacularVncPlayer(VernacularClient::new, Socket::new, SHORT_IDLE_NANOSECONDS, HANDSHAKE_DEADLINE_MILLISECONDS)
    );
    final VncSource source = this.source(silent, 0, 0);
    final VncException failure = assertTimeoutPreemptively(Duration.ofSeconds(15), () ->
      assertThrows(VncException.class, () -> player.start(source))
    );
    final String message = failure.getMessage();
    final boolean blamesTheDeadline = message.endsWith(" could not start because the server did not finish the handshake within 500 ms!");
    assertTrue(blamesTheDeadline, message);

    assertTimeoutPreemptively(Duration.ofSeconds(5), player::release);
  }

  // A socket whose first close, the deadline's, takes a second to return once the socket is closed, as a close can.
  private static final class SlowToClose extends Socket {

    private final AtomicBoolean first = new AtomicBoolean(true);

    @Override
    public void close() throws IOException {
      super.close();
      if (this.first.getAndSet(false)) {
        Uninterruptibles.sleepUninterruptibly(1, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void shouldBlameTheDeadlineWhenTheHandshakeFailsBeforeTheDeadlinesCloseReturns() throws IOException {
    final ServerSocket silent = this.listeningSocket();
    final VernacularVncPlayer player = this.track(
      new VernacularVncPlayer(VernacularClient::new, SlowToClose::new, SHORT_IDLE_NANOSECONDS, HANDSHAKE_DEADLINE_MILLISECONDS)
    );
    final VncSource source = this.source(silent, 0, 0);
    final VncException failure = assertTimeoutPreemptively(Duration.ofSeconds(15), () ->
      assertThrows(VncException.class, () -> player.start(source))
    );
    final String message = failure.getMessage();
    final boolean blamesTheDeadline = message.endsWith(" could not start because the server did not finish the handshake within 500 ms!");
    assertTrue(blamesTheDeadline, message);
  }

  @Test
  void shouldKeepTheConnectionOfAHandshakeThatEndsInTime() throws InterruptedException {
    final RfbTestServer answering = this.server(SERVER_WIDTH, SERVER_HEIGHT);
    final VernacularVncPlayer player = this.track(new VernacularVncPlayer(VernacularClient::new, Socket::new, SHORT_IDLE_NANOSECONDS, 300));
    final VncSource source = this.source(answering, SERVER_WIDTH, SERVER_HEIGHT);
    final boolean started = player.start(source);
    assertTrue(started);

    final CountDownLatch disconnected = answering.getDisconnected();
    final boolean closedByTheDeadline = disconnected.await(900, TimeUnit.MILLISECONDS);
    final int connections = answering.getConnections();
    assertFalse(closedByTheDeadline, "the deadline closed an established session");
    assertEquals(1, connections);
  }
}
