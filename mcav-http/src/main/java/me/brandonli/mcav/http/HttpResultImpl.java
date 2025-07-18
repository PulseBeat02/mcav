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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
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

  private final CopyOnWriteArrayList<WebSocketSession> wsClients;
  private final String domain;
  private final int port;
  private final String directory;

  private URLParseDump current;
  private ConfigurableApplicationContext context;

  HttpResultImpl(final String domain, final int port) {
    this(domain, port, "static");
  }

  HttpResultImpl(final String domain, final int port, final String directory) {
    this.wsClients = new CopyOnWriteArrayList<>();
    this.domain = domain;
    this.port = port;
    this.directory = directory;
    this.current = new URLParseDump();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start() {
    final Runnable runnable = () -> {
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
        .logStartupInfo(true)
        .registerShutdownHook(false); // Bukkit classloader is closed before Spring shutdown hook runs, causing NoClassDefFoundError
      try {
        this.context = builder.run("--directory=%s".formatted(this.directory));
        final HttpServerConfiguration config = this.context.getBean(HttpServerConfiguration.class);
        config.setHttpResultInstance(this);
      } finally {
        System.clearProperty("org.springframework.boot.logging.LoggingSystem");
      }
    };
    this.executeSpringRunnable(runnable);
  }

  private void executeSpringRunnable(final Runnable runnable) {
    final Thread thread = Thread.currentThread();
    final ClassLoader oldClassLoader = thread.getContextClassLoader();
    final ClassLoader newClassLoader = HttpResultImpl.class.getClassLoader();
    thread.setContextClassLoader(newClassLoader);
    try {
      runnable.run();
    } finally {
      thread.setContextClassLoader(oldClassLoader);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyFilter(final ByteBuffer samples, final OriginalAudioMetadata metadata) {
    if (this.wsClients.isEmpty()) {
      return;
    }

    final ByteBuffer clamped = samples.order(ByteOrder.BIG_ENDIAN);
    final BinaryMessage message = new BinaryMessage(clamped);
    for (final WebSocketSession session : this.wsClients) {
      try {
        session.sendMessage(message);
      } catch (final Exception ignored) {}
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getFullUrl() {
    return String.format("http://%s:%s/index.html", this.domain, this.port);
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
    final Runnable runnable = () -> {
      if (this.context != null) {
        this.wsClients.forEach(session -> {
            try {
              session.close();
            } catch (final IOException ignored) {}
          });
        this.wsClients.clear();
        this.context.close();
      }
    };
    this.executeSpringRunnable(runnable);
  }

  @SpringBootApplication
  @EnableWebSocket
  static class HttpServerApplication implements ApplicationRunner {

    private String directory;

    @Override
    public void run(final ApplicationArguments args) {
      final List<String> optionalValues = args.getOptionValues("directory");
      this.directory = optionalValues != null && !optionalValues.isEmpty() ? optionalValues.getFirst() : "static";
    }

    @Bean
    public HttpServerConfiguration httpServerConfiguration() {
      return new HttpServerConfiguration();
    }

    @Bean
    public WebSocketConfigurer webSocketConfigurer(final HttpServerConfiguration config) {
      return registry -> registry.addHandler(config.createWebSocketHandler(), "/audio").setAllowedOrigins("*");
    }

    @Bean
    public WebMvcConfigurer webMvcConfigurer(final HttpServerConfiguration config) {
      return new WebMvcConfigurer() {
        @Override
        public void addResourceHandlers(final @NonNull ResourceHandlerRegistry registry) {
          registry
            .addResourceHandler("/**")
            // if you are using IDE to test, use file:/C:/Users/brand/IdeaProjects/mcav/mcav-http/mcav-website/out or whatever
            // path the static build files are located. This is because the classpath is not available in the IDE.
            .addResourceLocations("classpath:/static/", "file:/C:/Users/brand/IdeaProjects/mcav/mcav-http/mcav-website/out")
            .setCachePeriod(3600)
            .resourceChain(true)
            .addResolver(new PathResourceResolver());
        }

        @Override
        public void addViewControllers(final @NonNull ViewControllerRegistry registry) {
          registry.addViewController("/").setViewName("forward:/index.html");
        }
      };
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
