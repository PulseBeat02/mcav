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

/**
 * Fuzzes the handler mcav puts in front of Minecraft on its own port. Its bytes come from anyone who can open a TCP
 * connection, before any Minecraft handshake, and arrive split into reads wherever the network splits them. Whatever the
 * bytes and the splits: a request for the pack of this handler is answered and nothing of it reaches Minecraft; the
 * start of such a request waits for more; everything else reaches Minecraft byte for byte, in order; and no buffer is
 * leaked. The first byte of an input is the number of splits, the next bytes are where they fall, and the rest is what
 * the connection sends. The seeds are a real handshake of a client, real pack downloads, and their prefixes.
 */
@Tag("fuzz")
final class ResourcePackHttpHandlerFuzzTest {

  private static final byte[] PACK = { 'P', 'K', 3, 4, 9 };
  private static final String REQUEST_PATH = "/pack/0123456789abcdef.zip";
  private static final byte[] GET_PREFIX = ("GET " + REQUEST_PATH + " ").getBytes(StandardCharsets.US_ASCII);
  private static final byte[] HEAD_PREFIX = ("HEAD " + REQUEST_PATH + " ").getBytes(StandardCharsets.US_ASCII);
  private static final int MAX_SPLITS = 8;
  private static final Path PACK_FILE = writePack();

  private static Path writePack() {
    try {
      final Path directory = Files.createTempDirectory("mcav-pack-fuzz");
      final Path pack = directory.resolve("pack.zip");
      Files.write(pack, PACK);
      pack.toFile().deleteOnExit();
      directory.toFile().deleteOnExit();
      return pack;
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  @FuzzTest(maxDuration = "30s")
  void answersItsRequestsAndPassesEverythingElseOnUnchanged(final byte[] input) {
    final List<byte[]> reads = splitIntoReads(input);
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
      // a read of a connection always carries bytes
      if (read.length == 0) {
        continue;
      }
      final ByteBuf buffer = Unpooled.copiedBuffer(read);
      written.add(buffer);
      channel.writeInbound(buffer);
      drain(channel, forwarded, answered);
    }
    channel.finishAndReleaseAll();

    assertBehaviour(stream, forwarded.toByteArray(), answered.toByteArray());
    for (final ByteBuf buffer : written) {
      final int references = buffer.refCnt();
      assertEquals(0, references, "a buffer the connection sent was never released");
    }
  }

  private static void assertBehaviour(final byte[] stream, final byte[] forwarded, final byte[] answered) {
    final boolean get = startsWith(stream, GET_PREFIX);
    final boolean head = startsWith(stream, HEAD_PREFIX);
    final boolean waiting = isProperPrefix(stream, GET_PREFIX) || isProperPrefix(stream, HEAD_PREFIX);
    if (get || head) {
      assertEquals(0, forwarded.length, "nothing of a pack request reaches Minecraft");
      final String response = new String(answered, StandardCharsets.US_ASCII);
      assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"), () -> "a pack request is answered: " + response);
      final byte[] tail = Arrays.copyOfRange(answered, Math.max(0, answered.length - PACK.length), answered.length);
      if (get) {
        assertArrayEquals(PACK, tail, "a GET request receives the pack");
      }
      return;
    }
    if (waiting) {
      assertEquals(0, forwarded.length, "the start of a pack request waits for the rest");
      assertEquals(0, answered.length, "the start of a pack request is not answered yet");
      return;
    }
    assertEquals(0, answered.length, "only a request for the pack is answered");
    assertArrayEquals(stream, forwarded, "everything else reaches Minecraft byte for byte");
  }

  /**
   * Moves what the handler passed on and what it answered out of the channel, releasing the buffers.
   */
  private static void drain(final EmbeddedChannel channel, final ByteArrayOutputStream forwarded, final ByteArrayOutputStream answered) {
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

  private static List<byte[]> splitIntoReads(final byte[] input) {
    if (input.length == 0) {
      return List.of();
    }
    final int splits = Math.min((input[0] & 0xFF) % (MAX_SPLITS + 1), input.length - 1);
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

  private static boolean startsWith(final byte[] stream, final byte[] prefix) {
    if (stream.length < prefix.length) {
      return false;
    }
    final byte[] start = Arrays.copyOf(stream, prefix.length);
    return Arrays.equals(start, prefix);
  }

  private static boolean isProperPrefix(final byte[] stream, final byte[] prefix) {
    if (stream.length >= prefix.length) {
      return false;
    }
    final byte[] start = Arrays.copyOf(prefix, stream.length);
    return Arrays.equals(start, stream);
  }
}
