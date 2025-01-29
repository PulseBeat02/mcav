/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.http;

import io.javalin.Javalin;
import io.javalin.http.Context;
import jakarta.servlet.ServletOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.util.stream.Collectors;
import me.brandonli.mcav.media.player.metadata.AudioMetadata;
import me.brandonli.mcav.utils.IOUtils;

public class HttpResultImpl implements HttpResult {

  private static final String HTML_TEMPLATE = loadHtmlFromResource();

  private static String loadHtmlFromResource() {
    try (
      final InputStream is = IOUtils.getResourceAsInputStream("player.html");
      final InputStreamReader isr = new InputStreamReader(is);
      final BufferedReader reader = new BufferedReader(isr)
    ) {
      return reader.lines().collect(Collectors.joining("\n"));
    } catch (final IOException e) {
      throw new HttpException(e.getMessage());
    }
  }

  private final int port;
  private final String html;

  private ByteBuffer currentSamples;
  private Javalin app;

  HttpResultImpl(final int port) {
    this(port, HTML_TEMPLATE);
  }

  HttpResultImpl(final int port, final String html) {
    this.port = port;
    this.html = html;
  }

  private void handlePCMPacket(final Context ctx) {
    if (this.currentSamples != null) {
      ctx.contentType("audio/wav");
      ctx.header("Transfer-Encoding", "chunked");
      try {
        final ServletOutputStream outputStream = ctx.res().getOutputStream();
        final byte[] header = this.createWavHeader(this.currentSamples.array().length);
        outputStream.write(header);
        outputStream.flush();
        outputStream.write(this.currentSamples.array());
        outputStream.flush();
      } catch (final IOException e) {
        ctx.status(500).result("Error streaming audio: " + e.getMessage());
      }
    } else {
      ctx.status(404).result("No audio data available");
    }
  }

  private byte[] createWavHeader(final int pcmDataLength) {
    final int sampleRate = 44100;
    final int channels = 2;
    final int bitsPerSample = 16;

    final byte[] header = new byte[44];

    header[0] = 'R';
    header[1] = 'I';
    header[2] = 'F';
    header[3] = 'F';
    final int fileSize = pcmDataLength + 36;
    header[4] = (byte) (fileSize & 0xff);
    header[5] = (byte) ((fileSize >> 8) & 0xff);
    header[6] = (byte) ((fileSize >> 16) & 0xff);
    header[7] = (byte) ((fileSize >> 24) & 0xff);
    header[8] = 'W';
    header[9] = 'A';
    header[10] = 'V';
    header[11] = 'E';

    header[12] = 'f';
    header[13] = 'm';
    header[14] = 't';
    header[15] = ' ';
    header[16] = 16;
    header[17] = 0;
    header[18] = 0;
    header[19] = 0;
    header[20] = 1;
    header[21] = 0;
    header[22] = (byte) channels;
    header[23] = 0;
    header[24] = (byte) (sampleRate & 0xff);
    header[25] = (byte) ((sampleRate >> 8) & 0xff);
    header[26] = (byte) ((sampleRate >> 16) & 0xff);
    header[27] = (byte) ((sampleRate >> 24) & 0xff);

    final int byteRate = (sampleRate * channels * bitsPerSample) / 8;
    header[28] = (byte) (byteRate & 0xff);
    header[29] = (byte) ((byteRate >> 8) & 0xff);
    header[30] = (byte) ((byteRate >> 16) & 0xff);
    header[31] = (byte) ((byteRate >> 24) & 0xff);
    header[32] = (byte) ((channels * bitsPerSample) / 8);
    header[33] = 0;
    header[34] = (byte) bitsPerSample;
    header[35] = 0;
    header[36] = 'd';
    header[37] = 'a';
    header[38] = 't';
    header[39] = 'a';
    header[40] = (byte) (pcmDataLength & 0xff);
    header[41] = (byte) ((pcmDataLength >> 8) & 0xff);
    header[42] = (byte) ((pcmDataLength >> 16) & 0xff);
    header[43] = (byte) ((pcmDataLength >> 24) & 0xff);

    return header;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start() {
    this.app = Javalin.create().start(this.port);
    this.app.get("/audio", this::handlePCMPacket);
    this.app.get("/", ctx -> ctx.html(this.html));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyFilter(final ByteBuffer samples, final AudioMetadata metadata) {
    if (samples == null || metadata == null) {
      return;
    }
    final int position = samples.position();
    final ByteBuffer copy = ByteBuffer.allocate(samples.remaining());
    copy.put(samples);
    copy.flip();
    samples.position(position);
    this.currentSamples = copy;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void stop() {
    if (this.app != null) {
      this.app.stop();
    }
  }
}
