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

@Tag("fuzz")
final class FileServerHandlerFuzzTest {

  private static final int UNSIGNED_BYTE_MASK = 0xFF;

  private static final int MAXIMUM_HEADER_BYTES = 8192;
  private static final int MAXIMUM_SPLITS = 8;
  private static final int MAXIMUM_FILLER = 12_000;
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
  void shouldAnswerOnceByTheMethodWithinTheHeaderLimit(final byte[] input) throws IOException {
    if (input.length < 2) {
      return;
    }

    final int options = input[0] & UNSIGNED_BYTE_MASK;
    final OpenMode mode = OpenMode.values()[(options & 0b11) % OpenMode.values().length];
    final ReadTimeout readTimeout = (options & 0b100) != 0 ? ReadTimeout.INSTALLED : ReadTimeout.ABSENT;
    final byte[] content = (options & 0b1000) != 0 ? new byte[0] : PACK;
    final boolean sendsOtherMessage = (options & 0b1_0000) != 0;
    final FillerPlace fillerPlace = (options & 0b10_0000) != 0 ? FillerPlace.FIRST : FillerPlace.LAST;
    final Request request = Request.of(input, fillerPlace);

    final List<FileChannel> opened = new ArrayList<>();
    final Path file = content.length == 0 ? EMPTY_FILE : PACK_FILE;
    final FileServerHandler handler = new FileServerHandler(file, path -> this.open(path, mode, opened));
    final RecordingAllocator allocator = new RecordingAllocator();
    final EmbeddedChannel channel = new EmbeddedChannel();
    channel.config().setAllocator(allocator);
    final ChannelPipeline pipeline = channel.pipeline();
    if (readTimeout == ReadTimeout.INSTALLED) {
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
      if (read.length > 0) {
        final ByteBuf buffer = Unpooled.copiedBuffer(read);
        sent.add(buffer);
        channel.writeInbound(buffer);
      }
      if (index == 0 && sendsOtherMessage && channel.isOpen()) {
        channel.writeInbound(NOT_BYTES);
      }
      this.drain(channel, answered);
    }

    channel.checkException();
    final boolean openAfterReads = channel.isOpen();
    final boolean timeoutLeft = pipeline.get(FileHttpChannelInitializer.READ_TIMEOUT_NAME) != null;
    final Outcome outcome = new Outcome(answered, openAfterReads, timeoutLeft);

    try {
      this.assertBehavior(request.stream, mode, content, readTimeout, outcome);
    } finally {
      for (final Object message : answered) {
        this.releaseAnswer(message);
      }

      channel.finishAndReleaseAll();
    }

    for (final ByteBuf buffer : sent) {
      final int referenceCount = buffer.refCnt();
      assertEquals(0, referenceCount, "a buffer the connection sent was never released");
    }

    for (final ByteBuf buffer : allocator.allocated) {
      final int referenceCount = buffer.refCnt();
      assertEquals(0, referenceCount, "a buffer of the handler outlived the connection");
    }

    for (final FileChannel fileChannel : opened) {
      final boolean fileOpen = fileChannel.isOpen();
      assertFalse(fileOpen, "the served file outlived the connection");
    }
  }

  private void releaseAnswer(final Object message) {
    if (message instanceof final FileRegion region) {
      region.release();
      return;
    }

    if (message instanceof final ByteBuf buffer) {
      buffer.release();
    }
  }

  private FileChannel open(final Path path, final OpenMode mode, final List<FileChannel> opened) throws IOException {
    if (mode == OpenMode.FAILS) {
      throw new NoSuchFileException(path.toString());
    }

    final FileChannel fileChannel = FileChannel.open(path, StandardOpenOption.READ);
    opened.add(fileChannel);

    if (mode == OpenMode.CANNOT_TELL_SIZE) {
      fileChannel.close();
    }

    return fileChannel;
  }

  private void assertBehavior(
    final byte[] stream,
    final OpenMode mode,
    final byte[] content,
    final ReadTimeout readTimeout,
    final Outcome outcome
  ) throws IOException {
    final List<Object> answered = outcome.answered();
    final boolean openAfterReads = outcome.openAfterReads();
    final boolean timeoutLeft = outcome.timeoutLeft();
    final int headerEnd = this.indexOf(stream, HEADER_TERMINATOR);
    final boolean complete = headerEnd >= 0 && headerEnd + HEADER_TERMINATOR.length <= MAXIMUM_HEADER_BYTES;
    if (!complete) {
      if (stream.length > MAXIMUM_HEADER_BYTES) {
        final List<String> expectedAnswers = List.of(this.status("431 Request Header Fields Too Large"));
        final List<String> answeredTexts = this.texts(answered);
        assertEquals(expectedAnswers, answeredTexts, "headers past the limit are refused");
        assertFalse(openAfterReads, "a refused connection is closed");

        return;
      }

      assertEquals(List.of(), answered, "a request whose headers have not ended yet waits");
      assertTrue(openAfterReads, "a request whose headers have not ended yet keeps its connection");
      final boolean timeoutInstalled = readTimeout == ReadTimeout.INSTALLED;
      assertEquals(timeoutInstalled, timeoutLeft, "a request that waits keeps its read timeout");

      return;
    }

    assertFalse(timeoutLeft, "a complete request no longer has a read timeout");
    assertFalse(openAfterReads, "every answer closes the connection");

    final boolean isGet = this.startsWith(stream, GET_PREFIX);
    final boolean isHead = this.startsWith(stream, HEAD_PREFIX);
    if (!isGet && !isHead) {
      final List<String> expectedAnswers = List.of(this.status("405 Method Not Allowed"));
      final List<String> answeredTexts = this.texts(answered);
      assertEquals(expectedAnswers, answeredTexts, "only GET and HEAD are served");

      return;
    }

    if (mode != OpenMode.OPENS) {
      final List<String> expectedAnswers = List.of(this.status("404 Not Found"));
      final List<String> answeredTexts = this.texts(answered);
      assertEquals(expectedAnswers, answeredTexts, "a file that cannot be read is not found");

      return;
    }

    final int expectedAnswerCount = isGet ? 2 : 1;
    final int answerCount = answered.size();
    assertEquals(expectedAnswerCount, answerCount, "a request is answered once: the headers, and for GET the file");

    final String headers = text(answered.getFirst());
    final boolean served = headers.startsWith("HTTP/1.1 200 OK\r\n");
    assertTrue(served, () -> "the file is served: " + headers);

    final boolean statesTheLength = headers.contains("\r\nContent-Length: " + content.length + "\r\n");
    assertTrue(statesTheLength, () -> "the length is the file's: " + headers);

    final boolean headersEnd = headers.endsWith("\r\n\r\n");
    assertTrue(headersEnd, () -> "the headers end: " + headers);

    if (isGet) {
      final Object bodyMessage = answered.get(1);
      final FileRegion region = assertInstanceOf(FileRegion.class, bodyMessage);

      final int expectedLength = content.length;
      final long regionCount = region.count();
      assertEquals(expectedLength, regionCount, "the body is the whole file");

      final byte[] body = this.transfer(region);
      assertArrayEquals(content, body, "the body is the file");
    }
  }

  private byte[] transfer(final FileRegion region) throws IOException {
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

  private void drain(final EmbeddedChannel channel, final List<Object> answered) {
    Object outbound = channel.readOutbound();
    while (outbound != null) {
      answered.add(outbound);
      outbound = channel.readOutbound();
    }

    final Object inbound = channel.readInbound();
    assertNull(inbound, "the handler passes nothing on");
  }

  private List<String> texts(final List<Object> answered) {
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

  private String status(final String status) {
    return "HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
  }

  private int indexOf(final byte[] stream, final byte[] part) {
    for (int index = 0; index + part.length <= stream.length; index++) {
      if (Arrays.equals(stream, index, index + part.length, part, 0, part.length)) {
        return index;
      }
    }

    return -1;
  }

  private boolean startsWith(final byte[] stream, final byte[] prefix) {
    return stream.length >= prefix.length && Arrays.equals(stream, 0, prefix.length, prefix, 0, prefix.length);
  }

  private enum OpenMode {
    OPENS,
    FAILS,
    CANNOT_TELL_SIZE,
  }

  private enum ReadTimeout {
    INSTALLED,
    ABSENT,
  }

  private enum FillerPlace {
    FIRST,
    LAST,
  }

  private record Outcome(List<Object> answered, boolean openAfterReads, boolean timeoutLeft) {}

  private static final class Request {

    private final byte[] stream;
    private final List<byte[]> reads;

    private Request(final byte[] stream, final List<byte[]> reads) {
      this.stream = stream;
      this.reads = reads;
    }

    private static Request of(final byte[] input, final FillerPlace fillerPlace) {
      final int splits = (input[1] & UNSIGNED_BYTE_MASK) % (MAXIMUM_SPLITS + 1);
      final int header = 2 + 2 * splits + 3;
      final int fillerLength = ((byteAt(input, header - 3) << 8) | byteAt(input, header - 2)) % (MAXIMUM_FILLER + 1);
      final byte[] filler = new byte[fillerLength];
      Arrays.fill(filler, (byte) byteAt(input, header - 1));
      final byte[] rest = header < input.length ? Arrays.copyOfRange(input, header, input.length) : new byte[0];
      final ByteArrayOutputStream joined = new ByteArrayOutputStream();
      final boolean fillerFirst = fillerPlace == FillerPlace.FIRST;
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

    private static int byteAt(final byte[] input, final int index) {
      return index >= 2 && index < input.length ? input[index] & UNSIGNED_BYTE_MASK : 0;
    }
  }

  private static final class RecordingAllocator extends AbstractByteBufAllocator {

    private final List<ByteBuf> allocated = new ArrayList<>();

    RecordingAllocator() {
      super(false);
    }

    @Override
    protected ByteBuf newHeapBuffer(final int initialCapacity, final int maximumCapacity) {
      final ByteBuf buffer = Unpooled.buffer(initialCapacity, maximumCapacity);
      this.allocated.add(buffer);
      return buffer;
    }

    @Override
    protected ByteBuf newDirectBuffer(final int initialCapacity, final int maximumCapacity) {
      final ByteBuf buffer = Unpooled.directBuffer(initialCapacity, maximumCapacity);
      this.allocated.add(buffer);
      return buffer;
    }

    @Override
    public boolean isDirectBufferPooled() {
      return false;
    }
  }
}
