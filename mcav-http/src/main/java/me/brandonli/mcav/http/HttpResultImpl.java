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
import io.javalin.websocket.WsContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import me.brandonli.mcav.media.player.metadata.AudioMetadata;
import me.brandonli.mcav.utils.ByteUtils;
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
      throw new HttpException(e.getMessage(), e);
    }
  }

  private final CopyOnWriteArrayList<WsContext> wsClients;
  private final int port;
  private final String html;

  private Javalin app;

  HttpResultImpl(final int port) {
    this(port, HTML_TEMPLATE);
  }

  HttpResultImpl(final int port, final String html) {
    this.wsClients = new CopyOnWriteArrayList<>();
    this.port = port;
    this.html = html.replace("%%PORT%%", String.valueOf(port));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start() {
    this.app = Javalin.create().start(this.port);
    this.app.get("/", ctx -> ctx.html(this.html));
    this.app.ws("/", ws -> {
        ws.onConnect(this.wsClients::add);
        ws.onClose(this.wsClients::remove);
        ws.onError(this.wsClients::remove);
      });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyFilter(final ByteBuffer samples, final AudioMetadata metadata) {
    if (samples == null || metadata == null) {
      return;
    }
    final ByteBuffer clamped = ByteUtils.clampNormalBufferToLittleEndianHttpReads(samples);
    final int position = clamped.position();
    final ByteBuffer copy = ByteBuffer.allocate(clamped.remaining());
    copy.put(clamped);
    copy.flip();
    clamped.position(position);
    this.streamPCMToClients(copy);
  }

  private void streamPCMToClients(final ByteBuffer samples) {
    for (final WsContext client : this.wsClients) {
      try {
        client.send(samples);
      } catch (final Exception e) {
        this.wsClients.remove(client);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getFullUrl() {
    return String.format("http://localhost:%s", this.port);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void stop() {
    if (this.app != null) {
      this.app.stop();
    }
    this.wsClients.clear();
  }
}
