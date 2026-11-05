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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class ResourcePackHttpHandlerFuzzTest {

  private static final byte[] PACK = { 'P', 'K', 3, 4, 9 };
  private static final String REQUEST_PATH = "/pack/0123456789abcdef.zip";
  private static final byte[] GET_PREFIX = ("GET " + REQUEST_PATH + " ").getBytes(StandardCharsets.US_ASCII);
  private static final byte[] HEAD_PREFIX = ("HEAD " + REQUEST_PATH + " ").getBytes(StandardCharsets.US_ASCII);
  private static final int MAXIMUM_SPLITS = 8;
  private static final Path PACK_FILE = writePack();

  private static final String PACK_FILE_NAME = "pack.zip";

  private static Path writePack() {
    try {
      final Path directory = Files.createTempDirectory("mcav-pack-fuzz");
      final Path pack = directory.resolve(PACK_FILE_NAME);
      Files.write(pack, PACK);
      pack.toFile().deleteOnExit();
      directory.toFile().deleteOnExit();
      return pack;
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  @FuzzTest(maxDuration = "30s")
  void shouldAnswerItsRequestsAndPassEverythingElseOnUnchanged(final byte[] input) {
    final List<byte[]> reads = this.splitIntoReads(input);
    final byte[] stream = join(reads);
    final ResourcePackFile file = new ResourcePackFile(PACK_FILE);
    final ResourcePackHttpHandler handler = new ResourcePackHttpHandler(file, REQUEST_PATH);
    final EmbeddedChannel channel = new EmbeddedChannel();
    final ChannelPipeline pipeline = channel.pipeline();
    pipeline.addFirst(ResourcePackHttpHandler.NAME, handler);

    final List<ByteBuf> written = new ArrayList<>();
    final ByteArrayOutputStream forwarded = new ByteArrayOutputStream();
    final ByteArrayOutputStream answered = new ByteArrayOutputStream();
    for (final byte[] read : reads) {
      if (!channel.isOpen()) {
        break;
      }
      if (read.length == 0) {
        continue;
      }
      final ByteBuf buffer = Unpooled.copiedBuffer(read);
      written.add(buffer);
      channel.writeInbound(buffer);
      this.drain(channel, forwarded, answered);
    }

    channel.finishAndReleaseAll();

    this.assertBehavior(stream, forwarded.toByteArray(), answered.toByteArray());

    for (final ByteBuf buffer : written) {
      final int references = buffer.refCnt();
      assertEquals(0, references, "a buffer the connection sent was never released");
    }
  }

  private void assertBehavior(final byte[] stream, final byte[] forwarded, final byte[] answered) {
    final boolean isGetRequest = this.startsWith(stream, GET_PREFIX);
    final boolean isHeadRequest = this.startsWith(stream, HEAD_PREFIX);
    final boolean waiting = this.isProperPrefix(stream, GET_PREFIX) || this.isProperPrefix(stream, HEAD_PREFIX);
    if (isGetRequest || isHeadRequest) {
      final int forwardedLength = forwarded.length;
      assertEquals(0, forwardedLength, "nothing of a pack request reaches Minecraft");

      final String response = new String(answered, StandardCharsets.US_ASCII);
      final boolean answeredWithSuccess = response.startsWith("HTTP/1.1 200 OK\r\n");
      assertTrue(answeredWithSuccess, () -> "a pack request is answered: " + response);

      final byte[] tail = Arrays.copyOfRange(answered, Math.max(0, answered.length - PACK.length), answered.length);
      if (isGetRequest) {
        assertArrayEquals(PACK, tail, "a GET request receives the pack");
      } else {
        final int headerEnd = response.indexOf("\r\n\r\n");
        final boolean hasCompleteHeaders = headerEnd >= 0;
        assertTrue(hasCompleteHeaders, "HEAD has complete response headers");

        final int expectedLength = headerEnd + 4;
        final int answeredLength = answered.length;
        assertEquals(expectedLength, answeredLength, "HEAD has no response body");

        final String headers = response.substring(0, headerEnd);
        final long contentLengthCount = headers
          .lines()
          .filter(line -> line.equals("Content-Length: " + PACK.length))
          .count();
        assertEquals(1L, contentLengthCount, headers);
      }

      return;
    }

    if (waiting) {
      final int forwardedLength = forwarded.length;
      assertEquals(0, forwardedLength, "the start of a pack request waits for the rest");

      final int answeredLength = answered.length;
      assertEquals(0, answeredLength, "the start of a pack request is not answered yet");

      return;
    }

    final int answeredLength = answered.length;
    assertEquals(0, answeredLength, "only a request for the pack is answered");
    assertArrayEquals(stream, forwarded, "everything else reaches Minecraft byte for byte");
  }

  private void drain(final EmbeddedChannel channel, final ByteArrayOutputStream forwarded, final ByteArrayOutputStream answered) {
    Object inbound = channel.readInbound();
    while (inbound != null) {
      final ByteBuf buffer = (ByteBuf) inbound;
      final byte[] bytes = ByteBufUtil.getBytes(buffer);
      forwarded.writeBytes(bytes);
      buffer.release();
      inbound = channel.readInbound();
    }

    Object outbound = channel.readOutbound();
    while (outbound != null) {
      final ByteBuf buffer = (ByteBuf) outbound;
      final byte[] bytes = ByteBufUtil.getBytes(buffer);
      answered.writeBytes(bytes);
      buffer.release();
      outbound = channel.readOutbound();
    }
  }

  private List<byte[]> splitIntoReads(final byte[] input) {
    if (input.length == 0) {
      return List.of();
    }

    final int splits = Math.min((input[0] & 0xFF) % (MAXIMUM_SPLITS + 1), input.length - 1);
    final int start = 1 + splits;
    final byte[] stream = Arrays.copyOfRange(input, start, input.length);
    final int[] cuts = new int[splits];
    for (int index = 0; index < splits; index++) {
      cuts[index] = stream.length == 0 ? 0 : (input[1 + index] & 0xFF) % (stream.length + 1);
    }

    Arrays.sort(cuts);
    final List<byte[]> reads = new ArrayList<>();
    int from = 0;
    for (final int cut : cuts) {
      reads.add(Arrays.copyOfRange(stream, from, cut));
      from = cut;
    }

    reads.add(Arrays.copyOfRange(stream, from, stream.length));
    return reads;
  }

  private static byte[] join(final List<byte[]> reads) {
    final ByteArrayOutputStream joined = new ByteArrayOutputStream();
    for (final byte[] read : reads) {
      joined.writeBytes(read);
    }

    return joined.toByteArray();
  }

  private boolean startsWith(final byte[] stream, final byte[] prefix) {
    if (stream.length < prefix.length) {
      return false;
    }

    final byte[] start = Arrays.copyOf(stream, prefix.length);
    return Arrays.equals(start, prefix);
  }

  private boolean isProperPrefix(final byte[] stream, final byte[] prefix) {
    if (stream.length >= prefix.length) {
      return false;
    }

    final byte[] start = Arrays.copyOf(prefix, stream.length);
    return Arrays.equals(start, stream);
  }
}
