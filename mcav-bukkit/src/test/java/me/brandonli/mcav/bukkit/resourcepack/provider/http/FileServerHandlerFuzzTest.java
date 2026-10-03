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
package me.brandonli.mcav.bukkit.resourcepack.provider.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.FileRegion;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Tag;

/**
 * Fuzzes the handler that serves the resource pack on its own port. Its bytes come from anyone who can open a TCP
 * connection, and arrive split into reads wherever the network splits them. Whatever the bytes, the splits and the state
 * of the file: once the headers end within the limit of 8192 bytes, the request is answered exactly once by its method -
 * {@code GET} with the whole file and its length, {@code HEAD} with the headers alone, any other method with 405, a file
 * that cannot be read with 404 - and the read timeout is gone; a stream that passes the limit without ending its headers
 * is refused with 431; a shorter one waits. Every answer closes the connection, and no buffer or file is leaked.
 *
 * <p>The first byte of an input holds the options: how the file opens (bits 0 and 1: normally, failing, or open but
 * unable to tell its size), whether the read timeout is in the pipeline (bit 2), whether the file is empty (bit 3),
 * whether a message that is not bytes arrives after the first read (bit 4), and whether the filler comes before the
 * rest of the stream (bit 5). The second byte is the number of splits, followed by two bytes per split for where it
 * falls, two bytes for the length of the filler and one for its byte; the rest of the input is the stream. The filler
 * lets small inputs reach the limit. The seeds are real requests of a client, a request whose headers fill the limit
 * exactly, headers that pass it, and requests followed by more bytes than the limit.
 */
@Tag("fuzz")
final class FileServerHandlerFuzzTest {

  private static final int MAX_HEADER_BYTES = 8192;
  private static final int MAX_SPLITS = 8;
  private static final int MAX_FILLER = 12_000;
  private static final byte[] PACK = { 'P', 'K', 3, 4, 9 };
  private static final byte[] HEADER_TERMINATOR = { '\r', '\n', '\r', '\n' };
  private static final byte[] GET_PREFIX = "GET ".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] HEAD_PREFIX = "HEAD ".getBytes(StandardCharsets.US_ASCII);
  private static final String NOT_BYTES = "not bytes";
  private static final Path PACK_FILE = writeFile("pack.zip", PACK);
  private static final Path EMPTY_FILE = writeFile("empty.zip", new byte[0]);

  private static Path writeFile(final String name, final byte[] content) {
    try {
      final Path directory = Files.createTempDirectory("mcav-file-server-fuzz");
      final Path file = directory.resolve(name);
      Files.write(file, content);
      file.toFile().deleteOnExit();
      directory.toFile().deleteOnExit();
      return file;
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  @FuzzTest(maxDuration = "30s")
  void answersOnceByTheMethodWithinTheHeaderLimit(final byte[] input) throws IOException {
    if (input.length < 2) {
      return;
    }
    final int options = input[0] & 0xFF;
    final OpenMode mode = OpenMode.values()[(options & 0b11) % OpenMode.values().length];
    final boolean hasReadTimeout = (options & 0b100) != 0;
    final byte[] content = (options & 0b1000) != 0 ? new byte[0] : PACK;
    final boolean sendsOtherMessage = (options & 0b1_0000) != 0;
    final boolean fillerFirst = (options & 0b10_0000) != 0;
    final Request request = Request.of(input, fillerFirst);

    final List<FileChannel> opened = new ArrayList<>();
    final Path file = content.length == 0 ? EMPTY_FILE : PACK_FILE;
    final FileServerHandler handler = new FileServerHandler(file, path -> open(path, mode, opened));
    final RecordingAllocator allocator = new RecordingAllocator();
    final EmbeddedChannel channel = new EmbeddedChannel();
    channel.config().setAllocator(allocator);
    final ChannelPipeline pipeline = channel.pipeline();
    if (hasReadTimeout) {
      pipeline.addLast(
        FileHttpChannelInitializer.READ_TIMEOUT_NAME,
        new ReadTimeoutHandler(FileHttpChannelInitializer.READ_TIMEOUT_SECONDS)
      );
    }
    pipeline.addLast(handler);

    final List<ByteBuf> sent = new ArrayList<>();
    final List<Object> answered = new ArrayList<>();
    for (int index = 0; index < request.reads.size() && channel.isOpen(); index++) {
      final byte[] read = request.reads.get(index);
      // a read of a connection always carries bytes
      if (read.length > 0) {
        final ByteBuf buffer = Unpooled.copiedBuffer(read);
        sent.add(buffer);
        channel.writeInbound(buffer);
      }
      if (index == 0 && sendsOtherMessage && channel.isOpen()) {
        channel.writeInbound(NOT_BYTES);
      }
      drain(channel, answered);
    }
    channel.checkException();
    final boolean openAfterReads = channel.isOpen();
    final boolean timeoutLeft = pipeline.get(FileHttpChannelInitializer.READ_TIMEOUT_NAME) != null;

    try {
      assertBehaviour(request.stream, mode, content, hasReadTimeout, answered, openAfterReads, timeoutLeft);
    } finally {
      for (final Object message : answered) {
        if (message instanceof final FileRegion region) {
          region.release();
        } else if (message instanceof final ByteBuf buffer) {
          buffer.release();
        }
      }
      channel.finishAndReleaseAll();
    }
    for (final ByteBuf buffer : sent) {
      assertEquals(0, buffer.refCnt(), "a buffer the connection sent was never released");
    }
    for (final ByteBuf buffer : allocator.allocated) {
      assertEquals(0, buffer.refCnt(), "a buffer of the handler outlived the connection");
    }
    for (final FileChannel fileChannel : opened) {
      assertFalse(fileChannel.isOpen(), "the served file outlived the connection");
    }
  }

  private static FileChannel open(final Path path, final OpenMode mode, final List<FileChannel> opened) throws IOException {
    if (mode == OpenMode.FAILS) {
      throw new NoSuchFileException(path.toString());
    }
    final FileChannel fileChannel = FileChannel.open(path, StandardOpenOption.READ);
    opened.add(fileChannel);
    if (mode == OpenMode.CANNOT_TELL_SIZE) {
      // a closed channel throws on size()
      fileChannel.close();
    }
    return fileChannel;
  }

  private static void assertBehaviour(
    final byte[] stream,
    final OpenMode mode,
    final byte[] content,
    final boolean hasReadTimeout,
    final List<Object> answered,
    final boolean openAfterReads,
    final boolean timeoutLeft
  ) throws IOException {
    final int headerEnd = indexOf(stream, HEADER_TERMINATOR);
    final boolean complete = headerEnd >= 0 && headerEnd + HEADER_TERMINATOR.length <= MAX_HEADER_BYTES;
    if (!complete) {
      if (stream.length > MAX_HEADER_BYTES) {
        assertEquals(List.of(status("431 Request Header Fields Too Large")), texts(answered), "headers past the limit are refused");
        assertFalse(openAfterReads, "a refused connection is closed");
        return;
      }
      assertEquals(List.of(), answered, "a request whose headers have not ended yet waits");
      assertTrue(openAfterReads, "a request whose headers have not ended yet keeps its connection");
      assertEquals(hasReadTimeout, timeoutLeft, "a request that waits keeps its read timeout");
      return;
    }

    assertFalse(timeoutLeft, "a complete request no longer has a read timeout");
    assertFalse(openAfterReads, "every answer closes the connection");
    final boolean isGet = startsWith(stream, GET_PREFIX);
    final boolean isHead = startsWith(stream, HEAD_PREFIX);
    if (!isGet && !isHead) {
      assertEquals(List.of(status("405 Method Not Allowed")), texts(answered), "only GET and HEAD are served");
      return;
    }
    if (mode != OpenMode.OPENS) {
      assertEquals(List.of(status("404 Not Found")), texts(answered), "a file that cannot be read is not found");
      return;
    }
    assertEquals(isGet ? 2 : 1, answered.size(), "a request is answered once: the headers, and for GET the file");
    final String headers = text(answered.getFirst());
    assertTrue(headers.startsWith("HTTP/1.1 200 OK\r\n"), () -> "the file is served: " + headers);
    assertTrue(headers.contains("\r\nContent-Length: " + content.length + "\r\n"), () -> "the length is the file's: " + headers);
    assertTrue(headers.endsWith("\r\n\r\n"), () -> "the headers end: " + headers);
    if (isGet) {
      final FileRegion region = assertInstanceOf(FileRegion.class, answered.get(1));
      assertEquals(content.length, region.count(), "the body is the whole file");
      assertArrayEquals(content, transfer(region), "the body is the file");
    }
  }

  private static byte[] transfer(final FileRegion region) throws IOException {
    final ByteArrayOutputStream body = new ByteArrayOutputStream();
    final WritableByteChannel target = Channels.newChannel(body);
    long position = 0;
    while (position < region.count()) {
      final long written = region.transferTo(target, position);
      if (written <= 0) {
        break;
      }
      position += written;
    }
    return body.toByteArray();
  }

  private static void drain(final EmbeddedChannel channel, final List<Object> answered) {
    Object outbound = channel.readOutbound();
    while (outbound != null) {
      answered.add(outbound);
      outbound = channel.readOutbound();
    }
    final Object inbound = channel.readInbound();
    assertNull(inbound, "the handler passes nothing on");
  }

  private static List<String> texts(final List<Object> answered) {
    final List<String> texts = new ArrayList<>();
    for (final Object message : answered) {
      texts.add(text(message));
    }
    return texts;
  }

  private static String text(final Object message) {
    final ByteBuf buffer = assertInstanceOf(ByteBuf.class, message);
    return new String(ByteBufUtil.getBytes(buffer), StandardCharsets.US_ASCII);
  }

  private static String status(final String status) {
    return "HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
  }

  private static int indexOf(final byte[] stream, final byte[] part) {
    for (int index = 0; index + part.length <= stream.length; index++) {
      if (Arrays.equals(stream, index, index + part.length, part, 0, part.length)) {
        return index;
      }
    }
    return -1;
  }

  private static boolean startsWith(final byte[] stream, final byte[] prefix) {
    return stream.length >= prefix.length && Arrays.equals(stream, 0, prefix.length, prefix, 0, prefix.length);
  }

  /**
   * How the served file opens.
   */
  private enum OpenMode {
    OPENS,
    FAILS,
    CANNOT_TELL_SIZE,
  }

  /**
   * The bytes a connection sends and the reads they arrive in.
   */
  private static final class Request {

    private final byte[] stream;
    private final List<byte[]> reads;

    private Request(final byte[] stream, final List<byte[]> reads) {
      this.stream = stream;
      this.reads = reads;
    }

    static Request of(final byte[] input, final boolean fillerFirst) {
      final int splits = (input[1] & 0xFF) % (MAX_SPLITS + 1);
      final int header = 2 + 2 * splits + 3;
      final int fillerLength = ((byteAt(input, header - 3) << 8) | byteAt(input, header - 2)) % (MAX_FILLER + 1);
      final byte[] filler = new byte[fillerLength];
      Arrays.fill(filler, (byte) byteAt(input, header - 1));
      final byte[] rest = header < input.length ? Arrays.copyOfRange(input, header, input.length) : new byte[0];
      final ByteArrayOutputStream joined = new ByteArrayOutputStream();
      joined.writeBytes(fillerFirst ? filler : rest);
      joined.writeBytes(fillerFirst ? rest : filler);
      final byte[] stream = joined.toByteArray();
      final int[] cuts = new int[splits];
      for (int index = 0; index < splits; index++) {
        final int value = (byteAt(input, 2 + 2 * index) << 8) | byteAt(input, 3 + 2 * index);
        cuts[index] = value % (stream.length + 1);
      }
      Arrays.sort(cuts);
      final List<byte[]> reads = new ArrayList<>();
      int from = 0;
      for (final int cut : cuts) {
        reads.add(Arrays.copyOfRange(stream, from, cut));
        from = cut;
      }
      reads.add(Arrays.copyOfRange(stream, from, stream.length));
      return new Request(stream, reads);
    }

    // an input too short for its header reads as zeros there, so every input is a request
    private static int byteAt(final byte[] input, final int index) {
      return index >= 2 && index < input.length ? input[index] & 0xFF : 0;
    }
  }

  /**
   * Hands out unpooled heap buffers and remembers each, so the test sees whether the handler released its own.
   */
  private static final class RecordingAllocator extends AbstractByteBufAllocator {

    private final List<ByteBuf> allocated = new ArrayList<>();

    RecordingAllocator() {
      super(false);
    }

    @Override
    protected ByteBuf newHeapBuffer(final int initialCapacity, final int maxCapacity) {
      final ByteBuf buffer = Unpooled.buffer(initialCapacity, maxCapacity);
      this.allocated.add(buffer);
      return buffer;
    }

    @Override
    protected ByteBuf newDirectBuffer(final int initialCapacity, final int maxCapacity) {
      final ByteBuf buffer = Unpooled.directBuffer(initialCapacity, maxCapacity);
      this.allocated.add(buffer);
      return buffer;
    }

    @Override
    public boolean isDirectBufferPooled() {
      return false;
    }
  }
}
