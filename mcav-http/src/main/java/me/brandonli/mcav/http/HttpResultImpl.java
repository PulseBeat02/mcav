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
package me.brandonli.mcav.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.utils.IOUtils;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

/**
 * The concrete implementation of the {@link HttpResult} interface, providing a default HTML template
 * if none isn't specified. Opens multiple web socket connections and sends PCM audio data, which the
 * JavaScript client can encode and play in the browser.
 */
public class HttpResultImpl implements HttpResult {

  private static final String HTML_TEMPLATE = loadHtmlFromResource();

  private static String loadHtmlFromResource() {
    try (
      final InputStream is = IOUtils.getResourceAsInputStream("player.html");
      final InputStreamReader isr = new InputStreamReader(is);
      final BufferedReader reader = new BufferedReader(isr)
    ) {
      final Stream<String> lines = reader.lines();
      return lines.collect(Collectors.joining("\n"));
    } catch (final IOException e) {
      final String msg = e.getMessage();
      throw new HttpException(msg, e);
    }
  }

  private final CopyOnWriteArrayList<WebSocketSession> wsClients;
  private final String domain;
  private final int port;
  private final String html;

  private URLParseDump current;
  private ConfigurableApplicationContext context;

  HttpResultImpl(final String domain, final int port) {
    this(domain, port, HTML_TEMPLATE);
  }

  HttpResultImpl(final String domain, final int port, final String html) {
    final String portValue = String.valueOf(port);
    this.wsClients = new CopyOnWriteArrayList<>();
    this.domain = domain;
    this.port = port;
    this.html = html.replace("%%PORT%%", portValue);
    this.current = new URLParseDump();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start() {
    System.setProperty("org.springframework.boot.logging.LoggingSystem", "none");
    final SpringApplicationBuilder builder = new SpringApplicationBuilder()
      .sources(HttpServerApplication.class)
      .web(WebApplicationType.SERVLET)
      .properties(
        "server.port=" + this.port,
        "spring.application.name=mcav-http-" + this.port,
        "spring.jmx.enabled=false",
        "spring.main.banner-mode=off"
      )
      .logStartupInfo(true);
    try {
      this.context = builder.run();
      final HttpServerConfiguration config = this.context.getBean(HttpServerConfiguration.class);
      config.setHttpResultInstance(this);
    } finally {
      System.clearProperty("org.springframework.boot.logging.LoggingSystem");
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyFilter(final ByteBuffer samples, final OriginalAudioMetadata metadata) {
    if (samples == null || metadata == null) {
      return;
    }

    if (this.wsClients.isEmpty()) {
      return;
    }

    final ByteBuffer clamped = samples.order(ByteOrder.BIG_ENDIAN);
    final int position = clamped.position();
    final int remaining = clamped.remaining();
    final ByteBuffer copy = ByteBuffer.allocate(remaining);
    copy.put(clamped);
    copy.flip();
    clamped.position(position);

    final Iterator<WebSocketSession> iterator = this.wsClients.iterator();
    while (iterator.hasNext()) {
      final WebSocketSession session = iterator.next();
      if (!session.isOpen()) {
        iterator.remove();
        continue;
      }
      try {
        session.sendMessage(new BinaryMessage(copy));
      } catch (final Exception e) {
        iterator.remove();
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getFullUrl() {
    return String.format("http://%s:%s", this.domain, this.port);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setCurrentMedia(final URLParseDump dump) {
    this.current = dump;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void stop() {
    if (this.context != null) {
      this.wsClients.forEach(session -> {
          try {
            session.close();
          } catch (final IOException ignored) {}
        });
      this.wsClients.clear();
      this.context.close();
    }
  }

  @SpringBootApplication
  @EnableWebSocket
  static class HttpServerApplication {

    @Bean
    public HttpServerConfiguration httpServerConfiguration() {
      return new HttpServerConfiguration();
    }

    @Bean
    public WebSocketConfigurer webSocketConfigurer(final HttpServerConfiguration config) {
      return registry -> registry.addHandler(config.createWebSocketHandler(), "/audio").setAllowedOrigins("*");
    }
  }

  static class HttpServerConfiguration {

    private HttpResultImpl httpResultInstance;

    void setHttpResultInstance(final HttpResultImpl instance) {
      this.httpResultInstance = instance;
    }

    HttpResultImpl getHttpResultInstance() {
      return this.httpResultInstance;
    }

    AudioWebSocketHandler createWebSocketHandler() {
      return new AudioWebSocketHandler(this);
    }
  }

  static class AudioWebSocketHandler extends BinaryWebSocketHandler {

    private final HttpServerConfiguration config;

    AudioWebSocketHandler(final HttpServerConfiguration config) {
      this.config = config;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void afterConnectionEstablished(final WebSocketSession session) throws Exception {
      final HttpResultImpl instance = this.config.getHttpResultInstance();
      if (instance != null) {
        instance.wsClients.add(session);
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void afterConnectionClosed(final WebSocketSession session, final CloseStatus status) throws Exception {
      final HttpResultImpl instance = this.config.getHttpResultInstance();
      if (instance != null) {
        instance.wsClients.remove(session);
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void handleTransportError(final WebSocketSession session, final Throwable exception) throws Exception {
      final HttpResultImpl instance = this.config.getHttpResultInstance();
      if (instance != null) {
        instance.wsClients.remove(session);
      }
    }
  }

  @RestController
  static class HttpController {

    private final HttpServerConfiguration config;

    HttpController(final HttpServerConfiguration config) {
      this.config = config;
    }

    /**
     * Handles the root path and returns the HTML template.
     *
     * @return The HTML template as a string.
     */
    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String index() {
      final HttpResultImpl instance = this.config.getHttpResultInstance();
      return instance != null ? instance.html : "";
    }

    /**
     * Handles the "/media" path and returns the current media URL parse dump.
     *
     * @return The current URL parse dump as JSON.
     */
    @GetMapping(value = "/media", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public URLParseDump media() {
      final HttpResultImpl instance = this.config.getHttpResultInstance();
      return instance != null ? instance.current : new URLParseDump();
    }
  }
}
