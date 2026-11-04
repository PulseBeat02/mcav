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
package me.brandonli.mcav.bukkit.resourcepack.provider.netty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import me.brandonli.mcav.bukkit.resourcepack.provider.netty.ResourcePackHttpHandler.RequestType;
import me.brandonli.mcav.bukkit.testing.LogCapture;
import org.apache.logging.log4j.Level;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ResourcePackHttpHandlerTest {

  private static final byte[] PACK = { 'P', 'K', 3, 4, 9 };
  private static final byte[] HANDSHAKE = { 0x10, 0x00, (byte) 0xF7, 0x05, 0x09, 'l', 'o', 'c', 'a', 'l' };
  private static final String OK_HEADERS = headers("200 OK", "application/zip", PACK.length);
  private static final String ERROR_HEADERS = headers("500 Internal Server Error", "text/plain", 0);

  private static final String PACK_FILE_NAME = "pack.zip";
  private static final String MISSING_FILE_NAME = "missing.zip";

  private static final String ROOT_PATH = "/";
  private static final String PARTIAL_METHOD = "GE";
  private static final String HEAD_PREFIX = "HEAD /";
  private static final String GET_REQUEST = "GET / HTTP/1.1\r\n\r\n";
  private static final String GET_REQUEST_WITH_HOST = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";

  private static final String TIMEOUT_HANDLER_NAME = "timeout";
  private static final int READ_TIMEOUT_SECONDS = 30;

  @TempDir
  private Path directory;

  private Path packPath;

  @BeforeEach
  void writePack() throws IOException {
    this.packPath = this.directory.resolve(PACK_FILE_NAME);
    Files.write(this.packPath, PACK);
  }

  private EmbeddedChannel createChannel(final Path pack) {
    final ResourcePackFile file = new ResourcePackFile(pack);
    final ResourcePackHttpHandler handler = new ResourcePackHttpHandler(file, ROOT_PATH);
    final EmbeddedChannel channel = new EmbeddedChannel();
    final ChannelPipeline pipeline = channel.pipeline();
    pipeline.addFirst(ResourcePackHttpHandler.NAME, handler);
    return channel;
  }

  private ByteBuf ascii(final String text) {
    return Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII);
  }

  private void writeText(final EmbeddedChannel channel, final String text) {
    final ByteBuf bytes = this.ascii(text);
    channel.writeInbound(bytes);
  }

  private void writeBytes(final EmbeddedChannel channel, final byte[] bytes) {
    final ByteBuf buffer = Unpooled.wrappedBuffer(bytes);
    channel.writeInbound(buffer);
  }

  private RequestType classifyText(final String text) {
    final ByteBuf bytes = this.ascii(text);
    return ResourcePackHttpHandler.classify(bytes);
  }

  private ChannelHandler findHandler(final EmbeddedChannel channel) {
    final ChannelPipeline pipeline = channel.pipeline();
    return pipeline.get(ResourcePackHttpHandler.NAME);
  }

  private String readOutboundText(final EmbeddedChannel channel) {
    final ByteBuf buffer = channel.readOutbound();
    try {
      return buffer.toString(StandardCharsets.US_ASCII);
    } finally {
      buffer.release();
    }
  }

  private byte[] readOutboundBytes(final EmbeddedChannel channel) {
    final ByteBuf buffer = channel.readOutbound();
    try {
      return ByteBufUtil.getBytes(buffer);
    } finally {
      buffer.release();
    }
  }

  private byte[] readInboundBytes(final EmbeddedChannel channel) {
    final ByteBuf buffer = channel.readInbound();
    try {
      return ByteBufUtil.getBytes(buffer);
    } finally {
      buffer.release();
    }
  }

  private static String headers(final String status, final String contentType, final int contentLength) {
    return (
      "HTTP/1.1 " +
      status +
      "\r\nContent-Type: " +
      contentType +
      "\r\nContent-Length: " +
      contentLength +
      "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
    );
  }

  @Test
  void shouldServeThePackToGetRequestsAndCloseTheConnection() {
    final EmbeddedChannel channel = this.createChannel(this.packPath);
    final ByteBuf request = this.ascii("GET / HTTP/1.1\r\nHost: example\r\n\r\n");

    channel.writeInbound(request);
    final String responseHeaders = this.readOutboundText(channel);
    final byte[] body = this.readOutboundBytes(channel);
    final Object passedOn = channel.readInbound();
    final boolean open = channel.isOpen();
    final int requestReferences = request.refCnt();

    assertEquals(OK_HEADERS, responseHeaders);
    assertArrayEquals(PACK, body);
    assertNull(passedOn, "the request never reaches Minecraft");
    assertFalse(open);
    assertEquals(0, requestReferences, "the request is released");
  }

  @Test
  void shouldWaitForTheWholeTargetAndPassAnUnmatchedRequestUnchanged() {
    final EmbeddedChannel channel = this.createChannel(this.packPath);
    this.writeText(channel, HEAD_PREFIX);
    final Object premature = channel.readOutbound();
    assertNull(premature);

    this.writeText(channel, "other HTTP/1.1\r\n\r\n");
    final String passed = new String(this.readInboundBytes(channel), StandardCharsets.US_ASCII);
    final Object response = channel.readOutbound();
    assertEquals("HEAD /other HTTP/1.1\r\n\r\n", passed);
    assertNull(response);

    final ChannelHandler handler = this.findHandler(channel);
    assertNull(handler);

    channel.finishAndReleaseAll();
  }

  @Test
  void shouldAnswerHeadRequestsWithTheHeadersOnly() {
    final EmbeddedChannel channel = this.createChannel(this.packPath);

    this.writeText(channel, "HEAD / HTTP/1.1\r\n\r\n");
    final String responseHeaders = this.readOutboundText(channel);
    final Object body = channel.readOutbound();
    final boolean open = channel.isOpen();

    assertEquals(OK_HEADERS, responseHeaders);
    assertNull(body);
    assertFalse(open);
  }

  @Test
  void shouldServeAGetRequestAsSoonAsItsTargetEnds() {
    final EmbeddedChannel channel = this.createChannel(this.packPath);

    this.writeText(channel, "GET / ");
    final String responseHeaders = this.readOutboundText(channel);

    assertEquals(OK_HEADERS, responseHeaders);
  }

  @Test
  void shouldWaitUntilEnoughBytesArrivedToDecide() {
    final EmbeddedChannel channel = this.createChannel(this.packPath);
    final ByteBuf firstPart = this.ascii(PARTIAL_METHOD);

    channel.writeInbound(firstPart);
    final Object earlyResponse = channel.readOutbound();
    final Object earlyPassedOn = channel.readInbound();
    final boolean openWhileWaiting = channel.isOpen();
    this.writeText(channel, "T / HTTP/1.1\r\n\r\n");
    final String responseHeaders = this.readOutboundText(channel);
    final byte[] body = this.readOutboundBytes(channel);
    final int firstPartReferences = firstPart.refCnt();

    assertNull(earlyResponse);
    assertNull(earlyPassedOn);
    assertTrue(openWhileWaiting);
    assertEquals(OK_HEADERS, responseHeaders);
    assertArrayEquals(PACK, body);
    assertEquals(0, firstPartReferences, "the buffered bytes are released");
  }

  @Test
  void shouldHandMinecraftConnectionsBackUntouchedAndRemoveItself() {
    final EmbeddedChannel channel = this.createChannel(this.packPath);

    this.writeBytes(channel, HANDSHAKE);
    final byte[] passedOn = this.readInboundBytes(channel);
    final ChannelHandler handler = this.findHandler(channel);
    final Object response = channel.readOutbound();
    final boolean open = channel.isOpen();

    assertArrayEquals(HANDSHAKE, passedOn);
    assertNull(handler, "the handler adds no overhead to game connections");
    assertNull(response);
    assertTrue(open);
  }

  @Test
  void shouldHandBackEveryBufferedByteWhenAPartialPrefixTurnsOutToBeMinecraft() {
    final EmbeddedChannel channel = this.createChannel(this.packPath);

    this.writeText(channel, "HE");
    this.writeText(channel, "X!");
    final byte[] passedOn = this.readInboundBytes(channel);
    final String passedOnText = new String(passedOn, StandardCharsets.US_ASCII);
    final ChannelHandler handler = this.findHandler(channel);

    assertEquals("HEX!", passedOnText);
    assertNull(handler);
  }

  @Test
  void shouldHandShortMinecraftPacketsBackImmediately() {
    final EmbeddedChannel channel = this.createChannel(this.packPath);
    final byte[] ping = { 0x01, 0x00 };

    this.writeBytes(channel, ping);
    final byte[] passedOn = this.readInboundBytes(channel);

    assertArrayEquals(ping, passedOn);
  }

  @Test
  void shouldPassMessagesThatAreNotBytesOn() {
    final EmbeddedChannel channel = this.createChannel(this.packPath);
    final Object message = "not a byte buffer";

    channel.writeInbound(message);
    final Object passedOn = channel.readInbound();
    final ChannelHandler handler = this.findHandler(channel);

    assertSame(message, passedOn);
    assertInstanceOf(ResourcePackHttpHandler.class, handler, "the handler still waits for the first bytes");
  }

  @Test
  void shouldAnswerWithAnErrorAndLogItWhenThePackCannotBeRead() {
    final Path missing = this.directory.resolve(MISSING_FILE_NAME);
    final EmbeddedChannel channel = this.createChannel(missing);
    final List<LogCapture.RecordedEvent> events;
    try (final LogCapture logs = LogCapture.capture(ResourcePackHttpHandler.class)) {
      this.writeText(channel, GET_REQUEST);
      events = logs.getEvents();
    }

    final String responseHeaders = this.readOutboundText(channel);
    final Object body = channel.readOutbound();
    final boolean open = channel.isOpen();
    final LogCapture.RecordedEvent event = events.getFirst();
    final Level level = event.getLevel();
    final String message = event.getMessage();
    final Throwable thrown = event.getThrown().orElseThrow();
    assertEquals(ERROR_HEADERS, responseHeaders);
    assertNull(body);
    assertFalse(open);
    assertEquals(Level.WARN, level);
    assertEquals("Could not read the resource pack for an HTTP download.", message);
    assertInstanceOf(NoSuchFileException.class, thrown);
  }

  @Test
  void shouldReleaseBufferedBytesWhenTheConnectionClosesEarly() {
    final EmbeddedChannel channel = this.createChannel(this.packPath);
    final ByteBuf partial = this.ascii(PARTIAL_METHOD);

    channel.writeInbound(partial);
    final ChannelFuture closing = channel.close();
    final boolean closed = closing.isSuccess();
    final int references = partial.refCnt();

    assertTrue(closed);
    assertEquals(0, references);
  }

  @Test
  void shouldReleaseBufferedBytesWhenTheHandlerIsRemoved() {
    final EmbeddedChannel channel = this.createChannel(this.packPath);
    final ChannelPipeline pipeline = channel.pipeline();
    final ByteBuf partial = this.ascii("HEA");

    channel.writeInbound(partial);
    pipeline.remove(ResourcePackHttpHandler.NAME);
    final int references = partial.refCnt();
    final boolean open = channel.isOpen();

    assertEquals(0, references);
    assertTrue(open);
  }

  @Test
  void shouldDropEverythingThatArrivesAfterThePackWasServed() {
    final ResourcePackFile file = new ResourcePackFile(this.packPath);
    final ResourcePackHttpHandler handler = new ResourcePackHttpHandler(file, ROOT_PATH);
    final ChannelHandlerContext context = mock(ChannelHandlerContext.class);
    final ChannelFuture future = mock(ChannelFuture.class);
    when(context.alloc()).thenReturn(ByteBufAllocator.DEFAULT);
    when(context.pipeline()).thenReturn(mock(ChannelPipeline.class));
    when(context.write(any())).thenReturn(future);
    when(context.writeAndFlush(any())).thenReturn(future);
    final ByteBuf request = this.ascii(GET_REQUEST);
    final ByteBuf late = this.ascii("more data");

    handler.channelRead(context, request);
    handler.channelRead(context, late);
    final int references = late.refCnt();

    assertEquals(0, references);
    verify(context, never()).fireChannelRead(any());
  }

  @Test
  void shouldClassifyClientsAsSoonAsTheirMethodIsComplete() {
    final RequestType get = this.classifyText("GET ");
    final RequestType head = this.classifyText("HEAD ");

    assertEquals(RequestType.GET, get, "the shortest complete request line is already a GET");
    assertEquals(RequestType.HEAD, head);
  }

  @Test
  void shouldReleaseBufferedBytesWhenTheConnectionCloses() {
    final EmbeddedChannel channel = this.createChannel(this.packPath);
    final ChannelPipeline pipeline = channel.pipeline();
    final ByteBuf partial = this.ascii(PARTIAL_METHOD);

    channel.writeInbound(partial);
    pipeline.fireChannelInactive();
    final int references = partial.refCnt();

    assertEquals(0, references, "bytes buffered while the client type is unknown are released");
  }

  @Test
  void shouldClassifyClientsByTheirFirstBytes() {
    final RequestType get = this.classifyText("GET /");
    final RequestType head = this.classifyText(HEAD_PREFIX);
    final RequestType partialGet = this.classifyText("GET");
    final RequestType partialHead = this.classifyText("HEAD");
    final RequestType nothing = ResourcePackHttpHandler.classify(Unpooled.EMPTY_BUFFER);
    final RequestType post = this.classifyText("POST / HTTP/1.1");
    final RequestType lowerCase = this.classifyText("get / HTTP/1.1");
    final RequestType noSpace = this.classifyText("GETX");

    assertEquals(RequestType.GET, get);
    assertEquals(RequestType.HEAD, head);
    assertEquals(RequestType.INCOMPLETE, partialGet);
    assertEquals(RequestType.INCOMPLETE, partialHead);
    assertEquals(RequestType.INCOMPLETE, nothing);
    assertEquals(RequestType.MINECRAFT, post);
    assertEquals(RequestType.MINECRAFT, lowerCase);
    assertEquals(RequestType.MINECRAFT, noSpace);
  }

  @Test
  void shouldClassifyFromTheReaderIndexWithoutConsumingBytes() {
    final ByteBuf skipped = this.ascii("xxGET /");
    skipped.skipBytes(2);

    final RequestType afterReaderIndex = ResourcePackHttpHandler.classify(skipped);
    final int readerIndex = skipped.readerIndex();

    assertEquals(RequestType.GET, afterReaderIndex);
    assertEquals(2, readerIndex, "classifying does not consume bytes");
  }

  @Test
  void shouldUseAStablePipelineNamePrefix() {
    assertEquals("mcav_resource_pack_http", ResourcePackHttpHandler.NAME);
  }

  private EmbeddedChannel gameConnection() {
    final EmbeddedChannel channel = new EmbeddedChannel();
    channel.freezeTime();
    final ChannelPipeline pipeline = channel.pipeline();
    pipeline.addLast("client", new SlowClient());
    pipeline.addLast(ResourcePackHttpHandler.NAME, new ResourcePackHttpHandler(new ResourcePackFile(this.packPath), ROOT_PATH));
    pipeline.addLast(TIMEOUT_HANDLER_NAME, new ReadTimeoutHandler(READ_TIMEOUT_SECONDS));
    return channel;
  }

  private EmbeddedChannel downloading() {
    final EmbeddedChannel channel = this.gameConnection();
    this.writeText(channel, GET_REQUEST_WITH_HOST);
    return channel;
  }

  @Test
  void shouldNotCutADownloadLongerThanTheGamesReadTimeout() {
    final EmbeddedChannel channel = this.downloading();
    channel.advanceTimeBy(31, TimeUnit.SECONDS);
    channel.runScheduledPendingTasks();
    final boolean open = channel.isOpen();
    assertTrue(open, "the game counts 30 seconds from the request, and a download sends nothing after it");

    final @Nullable ReadTimeoutHandler readTimeout = channel.pipeline().get(ReadTimeoutHandler.class);
    assertNull(readTimeout);

    final @Nullable ChannelHandler stall = channel.pipeline().get(ResourcePackHttpHandler.STALL_NAME);
    assertInstanceOf(IdleStateHandler.class, stall);

    channel.finishAndReleaseAll();
  }

  @Test
  void shouldCloseADownloadThatTakesNothingForAMinute() {
    final EmbeddedChannel channel = this.downloading();
    channel.advanceTimeBy(ResourcePackHttpHandler.STALL_SECONDS + 1, TimeUnit.SECONDS);
    channel.runScheduledPendingTasks();
    final boolean open = channel.isOpen();
    assertFalse(open, "a client that stopped reading does not hold the game's port forever");

    channel.finishAndReleaseAll();
  }

  @Test
  void shouldNotCutADownloadThatKeepsTakingThePackHoweverLongItTakes() throws IOException {
    final int size = 1 << 20;
    Files.write(this.packPath, new byte[size]);
    final SlowSocket channel = new SlowSocket();
    channel.freezeTime();
    final ChannelPipeline pipeline = channel.pipeline();
    pipeline.addLast(ResourcePackHttpHandler.NAME, new ResourcePackHttpHandler(new ResourcePackFile(this.packPath), ROOT_PATH));
    pipeline.addLast(TIMEOUT_HANDLER_NAME, new ReadTimeoutHandler(READ_TIMEOUT_SECONDS));
    this.writeText(channel, GET_REQUEST_WITH_HOST);
    for (int step = 1; step <= 8; step++) {
      channel.advanceTimeBy(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      channel.runScheduledPendingTasks();
      final boolean openAfterStep = channel.isOpen();
      assertTrue(
        openAfterStep,
        "a download that took some of the pack in the last 30 s is not cut, after " + step * READ_TIMEOUT_SECONDS + " s"
      );

      channel.take(32 * 1024);
    }

    final boolean stillRunning = channel.taken() < size;
    assertTrue(stillRunning, "the download is still running");

    channel.advanceTimeBy(ResourcePackHttpHandler.STALL_SECONDS - 1, TimeUnit.SECONDS);
    channel.runScheduledPendingTasks();
    final boolean openBeforeStall = channel.isOpen();
    assertTrue(openBeforeStall);

    channel.advanceTimeBy(2, TimeUnit.SECONDS);
    channel.runScheduledPendingTasks();
    final boolean openAfterStall = channel.isOpen();
    assertFalse(openAfterStall, "a client that stopped taking the pack does not hold the game's port forever");

    channel.finishAndReleaseAll();
  }

  @Test
  void shouldPassOnTheEventsItDoesNotHandle() {
    final List<Object> events = new ArrayList<>();
    final EmbeddedChannel channel = this.gameConnection();
    channel.pipeline().addLast("events", new EventRecorder(events));
    channel.pipeline().fireUserEventTriggered("before");
    this.writeText(channel, GET_REQUEST_WITH_HOST);
    channel.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT);
    channel.pipeline().fireUserEventTriggered("during");
    final List<Object> expectedEvents = List.of("before", IdleStateEvent.READER_IDLE_STATE_EVENT, "during");
    assertEquals(expectedEvents, events);

    channel.finishAndReleaseAll();
  }

  private static final class SlowClient extends ChannelDuplexHandler {

    private int writes;

    private @Nullable Object content;

    private @Nullable ChannelPromise pending;

    @Override
    public void write(final ChannelHandlerContext context, final Object message, final ChannelPromise promise) {
      if (++this.writes == 1) {
        final ChannelFuture headers = context.write(message, promise);
        headers.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        return;
      }

      this.content = message;
      this.pending = promise;
    }

    @Override
    public void channelInactive(final ChannelHandlerContext context) {
      final ChannelPromise promise = this.pending;
      if (promise != null) {
        ReferenceCountUtil.release(this.content);
        promise.tryFailure(new IOException("Closed!"));
      }

      context.fireChannelInactive();
    }
  }

  private static final class SlowSocket extends EmbeddedChannel {

    private long allowance;

    private long taken;

    private void take(final int bytes) {
      this.allowance += bytes;
      this.flushOutbound();
      this.runPendingTasks();
    }

    private long taken() {
      return this.taken;
    }

    @Override
    protected void doWrite(final ChannelOutboundBuffer outbound) {
      while (this.allowance > 0 && outbound.current() instanceof final ByteBuf buffer) {
        final int bytes = (int) Math.min(this.allowance, buffer.readableBytes());
        this.allowance -= bytes;
        this.taken += bytes;
        outbound.removeBytes(bytes);
      }
    }
  }

  private static final class EventRecorder extends ChannelInboundHandlerAdapter {

    private final List<Object> events;

    EventRecorder(final List<Object> events) {
      this.events = events;
    }

    @Override
    public void userEventTriggered(final ChannelHandlerContext context, final Object event) {
      this.events.add(event);
    }
  }
}
