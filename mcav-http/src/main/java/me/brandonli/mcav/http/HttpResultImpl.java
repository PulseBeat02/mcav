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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * The default {@link HttpResult}, built on an embedded Spring Boot web server.
 *
 * <p>Every listener gets an {@link AudioListener} with its own sender thread, so samples are handed over from the
 * audio thread without blocking on slow connections: a listener that cannot keep up loses its oldest samples
 * instead of stalling the pipeline, and one whose writes block for {@value #SEND_TIME_LIMIT_MILLIS} milliseconds
 * is disconnected.
 *
 * <p>Spring Boot normally takes over the logging system of the JVM when it starts, resetting the levels and
 * handlers of {@code java.util.logging} or Log4j 2 for the whole server that hosts the library. Unless the system
 * property {@value #LOGGING_SYSTEM_PROPERTY} was already set, it is set to {@code none} before the first start,
 * so Spring logs through SLF4J like every other library and leaves the logging of the host alone.
 */
public final class HttpResultImpl implements HttpResult {

  static final String RESULT_BEAN_NAME = "mcavHttpResult";

  /**
   * The system property that selects the logging system of Spring Boot.
   */
  static final String LOGGING_SYSTEM_PROPERTY = "org.springframework.boot.logging.LoggingSystem";

  private static final Logger LOGGER = LoggerFactory.getLogger(HttpResultImpl.class);
  private static final int SEND_TIME_LIMIT_MILLIS = 5_000;
  private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

  private final String domain;
  private final int port;
  private final @Nullable Path directory;
  private final @Nullable InetAddress bindAddress;
  private final List<String> extraProperties;
  private final Map<String, AudioListener> listeners;
  private final Object lifecycleLock;

  private volatile MediaInfo currentMedia;
  private volatile @Nullable ConfigurableApplicationContext context;

  /**
   * Creates a server that listens on every network interface.
   *
   * @param domain    the host name listeners use
   * @param port      the port, or 0 for any free port
   * @param directory the directory with the web page, or null for the bundled copy
   */
  HttpResultImpl(final String domain, final int port, final @Nullable Path directory) {
    this(domain, port, directory, null, List.of());
  }

  /**
   * Creates a server with additional Spring properties, such as {@code server.address=127.0.0.1} to listen on the
   * loopback interface only. Port 0 picks a free port, which {@link #getLocalPort()} reports once started.
   *
   * @param domain          the host name listeners use
   * @param port            the port, or 0 for any free port
   * @param directory       the directory with the web page, or null for the bundled copy
   * @param extraProperties Spring properties in {@code key=value} form, applied after the defaults
   */
  @VisibleForTesting
  HttpResultImpl(final String domain, final int port, final @Nullable Path directory, final List<String> extraProperties) {
    this(domain, port, directory, null, extraProperties);
  }

  /**
   * Creates a server.
   *
   * @param domain          the host name listeners use
   * @param port            the port, or 0 for any free port
   * @param directory       the directory with the web page, or null for the bundled copy
   * @param bindAddress     the address to listen on, or null for every network interface
   * @param extraProperties Spring properties in {@code key=value} form, applied after the defaults
   */
  HttpResultImpl(
    final String domain,
    final int port,
    final @Nullable Path directory,
    final @Nullable InetAddress bindAddress,
    final List<String> extraProperties
  ) {
    Preconditions.checkNotNull(domain, "Domain must not be null");
    Preconditions.checkNotNull(extraProperties, "Extra properties must not be null");
    this.domain = domain;
    this.port = port;
    this.directory = directory;
    this.bindAddress = bindAddress;
    this.extraProperties = List.copyOf(extraProperties);
    this.listeners = new ConcurrentHashMap<>();
    this.lifecycleLock = new Object();
    this.currentMedia = MediaInfo.EMPTY;
  }

  /**
   * Starts the embedded web server and blocks until it accepts connections. The class loader of the library is
   * the context class loader of the calling thread while Spring starts, and the previous one is restored
   * afterwards. Calling it while the server runs has no effect.
   *
   * @throws HttpException if the server cannot be started, for example because the port is in use
   */
  @Override
  public void start() {
    synchronized (this.lifecycleLock) {
      if (this.context != null) {
        return;
      }

      final Thread thread = Thread.currentThread();
      final ClassLoader previous = thread.getContextClassLoader();
      final ClassLoader own = HttpResultImpl.class.getClassLoader();
      // Spring resolves classes and resources through the context class loader, which plugin hosts do not set
      thread.setContextClassLoader(own);
      try {
        this.context = this.createApplication();
        final String url = this.getFullUrl();
        LOGGER.info("Audio web player running at {}", url);
      } catch (final RuntimeException exception) {
        final String message = exception.getMessage();
        throw new HttpException("Failed to start the web server on port " + this.port + ": " + message, exception);
      } finally {
        thread.setContextClassLoader(previous);
      }
    }
  }

  private ConfigurableApplicationContext createApplication() {
    keepTheLoggingOfTheHost();

    final SpringApplicationBuilder builder = new SpringApplicationBuilder(HttpServerApplication.class);
    builder.web(WebApplicationType.SERVLET);
    builder.headless(true);
    builder.logStartupInfo(false);
    // plugin class loaders are closed before JVM shutdown hooks run, so Spring must not register one
    builder.registerShutdownHook(false);
    builder.initializers(context -> {
      final ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
      beanFactory.registerSingleton(RESULT_BEAN_NAME, this);
    });

    final List<String> properties = this.createProperties();
    final String[] array = properties.toArray(new String[0]);
    builder.properties(array);
    return builder.run();
  }

  /**
   * Creates the Spring properties of the server: the defaults first, then the extra properties, which therefore
   * override the defaults.
   *
   * @return the properties in {@code key=value} form
   */
  private List<String> createProperties() {
    final String location = staticLocation(this.directory);
    final List<String> properties = new ArrayList<>();
    properties.add("server.port=" + this.port);
    properties.add("spring.application.name=mcav-http-" + this.port);
    properties.add("spring.main.banner-mode=off");
    properties.add("spring.jmx.enabled=false");
    properties.add("spring.main.lazy-initialization=true");
    properties.add("spring.web.resources.static-locations=" + location);
    properties.add("server.shutdown=immediate");
    properties.add("server.compression.enabled=true");

    final InetAddress address = this.bindAddress;
    if (address != null) {
      final String hostAddress = address.getHostAddress();
      properties.add("server.address=" + hostAddress);
    }

    properties.addAll(this.extraProperties);
    return properties;
  }

  /**
   * Stops Spring Boot from reconfiguring the logging system of the JVM, unless someone chose a logging system for
   * it with {@value #LOGGING_SYSTEM_PROPERTY}.
   */
  @VisibleForTesting
  static void keepTheLoggingOfTheHost() {
    final String chosen = System.getProperty(LOGGING_SYSTEM_PROPERTY);
    if (chosen == null) {
      System.setProperty(LOGGING_SYSTEM_PROPERTY, "none");
    }
  }

  /**
   * Gets the Spring resource location of the web page.
   *
   * @param directory the directory with the web page, or null for the copy bundled in the jar
   * @return the location, always ending with a slash
   */
  @VisibleForTesting
  static String staticLocation(final @Nullable Path directory) {
    if (directory == null) {
      return "classpath:/static/";
    }
    final Path absolute = directory.toAbsolutePath();
    final URI location = absolute.toUri();
    final String uri = location.toString();
    return uri.endsWith("/") ? uri : uri + "/";
  }

  /**
   * Disconnects every listener with {@link CloseStatus#GOING_AWAY} and stops the embedded web server. The
   * connections are closed on other threads, so a listener whose write is stuck never delays stopping. Calling it
   * while the server is stopped has no effect.
   */
  @Override
  public void stop() {
    synchronized (this.lifecycleLock) {
      final ConfigurableApplicationContext current = this.context;
      if (current == null) {
        return;
      }
      this.context = null;
      this.disconnectListeners();
      closeWithOwnClassLoader(current);
    }
  }

  private void disconnectListeners() {
    final Collection<AudioListener> connected = this.listeners.values();
    for (final AudioListener listener : connected) {
      // closing waits for a write in progress, so a stuck browser must not hold up stopping or starting
      listener.closeAsync(CloseStatus.GOING_AWAY);
    }
    this.listeners.clear();
  }

  /**
   * Closes the Spring context with the class loader of this class as the context class loader of the thread, because
   * Spring looks up its own resources through it while shutting down, and restores the previous one afterwards.
   * Visible for testing.
   *
   * @param current the context to close
   */
  @VisibleForTesting
  static void closeWithOwnClassLoader(final ConfigurableApplicationContext current) {
    final Thread thread = Thread.currentThread();
    final ClassLoader previous = thread.getContextClassLoader();
    final ClassLoader own = HttpResultImpl.class.getClassLoader();
    thread.setContextClassLoader(own);
    try {
      current.close();
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  /**
   * Checks whether the embedded web server is running.
   *
   * @return true between a successful {@link #start()} and the next {@link #stop()}
   */
  @Override
  public boolean isRunning() {
    return this.context != null;
  }

  /**
   * Gets the port the server listens on, which differs from the configured port when that was 0.
   *
   * @return the bound port while running, otherwise the configured port
   */
  @VisibleForTesting
  int getLocalPort() {
    final ConfigurableApplicationContext current = this.context;
    if (current instanceof final WebServerApplicationContext web) {
      final WebServer webServer = web.getWebServer();
      final WebServer server = Objects.requireNonNull(webServer, "A running server has a web server");
      return server.getPort();
    }
    return this.port;
  }

  /**
   * Gets the address the server listens on.
   *
   * @return the address, or null for every network interface
   */
  @VisibleForTesting
  @Nullable InetAddress getBindAddress() {
    return this.bindAddress;
  }

  /**
   * Gets the directory the web page is served from.
   *
   * @return the directory, or null for the copy bundled in the jar
   */
  @VisibleForTesting
  @Nullable Path getDirectory() {
    return this.directory;
  }

  /**
   * Registers a browser that connected to the audio socket.
   *
   * @param session the WebSocket session
   */
  void addListener(final WebSocketSession session) {
    final String id = session.getId();
    final AudioListener listener = new AudioListener(session, SEND_TIME_LIMIT_MILLIS, SEND_BUFFER_LIMIT_BYTES, this::dropListener);
    this.listeners.put(id, listener);
    listener.start();
  }

  /**
   * Gets the listener of a WebSocket session. Visible for testing.
   *
   * @param id the id of the session
   * @return the listener, or null if no browser of that session is connected
   */
  @VisibleForTesting
  @Nullable AudioListener getListener(final String id) {
    return this.listeners.get(id);
  }

  /**
   * Forgets a browser that disconnected.
   *
   * @param session the WebSocket session
   */
  void removeListener(final WebSocketSession session) {
    final String id = session.getId();
    final AudioListener listener = this.listeners.remove(id);
    if (listener != null) {
      listener.stop();
    }
  }

  private void dropListener(final AudioListener listener) {
    final WebSocketSession session = listener.getSession();
    final String id = session.getId();
    this.listeners.remove(id, listener);
    listener.closeAsync(CloseStatus.SESSION_NOT_RELIABLE);
  }

  /**
   * Gets the number of browsers currently connected to the audio socket.
   *
   * @return the listener count, 0 while the server is stopped
   */
  @Override
  public int getListenerCount() {
    return this.listeners.size();
  }

  /**
   * Queues a copy of the remaining samples for every listener without blocking and without moving the position of
   * the buffer. A listener whose connection closed or whose write is stuck is disconnected.
   *
   * @param samples  the little-endian 16-bit stereo samples between the position and the limit of the buffer
   * @param metadata the metadata of the original audio
   * @return always true, so the pipeline continues with the next filter
   */
  @Override
  public boolean applyFilter(final ByteBuffer samples, final OriginalAudioMetadata metadata) {
    Preconditions.checkNotNull(samples, "Samples must not be null");
    Preconditions.checkNotNull(metadata, "Metadata must not be null");
    final boolean empty = this.listeners.isEmpty();
    if (empty) {
      return true;
    }

    // the page decodes little-endian 16-bit stereo, which is exactly the format of the pipeline
    final byte[] bytes = copyRemaining(samples);
    final Collection<AudioListener> connected = this.listeners.values();
    for (final AudioListener listener : connected) {
      final boolean queued = listener.offer(bytes);
      if (!queued) {
        this.dropListener(listener);
      }
    }
    return true;
  }

  private static byte[] copyRemaining(final ByteBuffer samples) {
    final ByteBuffer source = samples.duplicate();
    final int remaining = source.remaining();
    final byte[] bytes = new byte[remaining];
    source.get(bytes);
    return bytes;
  }

  /**
   * Gets the address of the web page, built from the host name and the configured port.
   *
   * @return the URL, such as {@code http://play.example.com:8080/}
   */
  @Override
  public String getFullUrl() {
    final String host = urlHost(this.domain);
    return "http://" + host + ":" + this.port + "/";
  }

  /**
   * Gets the host part of a URL. A host name or IPv4 address is used as it is; an IPv6 address, the only host
   * that contains a colon, is put in brackets, with the {@code %} of a zone id encoded as RFC 6874 requires.
   *
   * @param domain the host name or address
   * @return the host as it appears in a URL
   */
  @VisibleForTesting
  static String urlHost(final String domain) {
    final boolean ipv6 = domain.indexOf(':') >= 0;
    final boolean bracketed = domain.startsWith("[");
    if (!ipv6 || bracketed) {
      return domain;
    }
    final String encoded = domain.replace("%", "%25");
    return "[" + encoded + "]";
  }

  /**
   * Shows the title, artist, thumbnail, and statistics yt-dlp reported, as {@link MediaInfo#of(URLParseDump)}
   * takes them.
   *
   * @param dump the output of yt-dlp
   */
  @Override
  public void setCurrentMedia(final URLParseDump dump) {
    Preconditions.checkNotNull(dump, "Dump must not be null");
    this.currentMedia = MediaInfo.of(dump);
  }

  /**
   * Shows information about the current media; browsers see it the next time they ask for {@code /media}.
   *
   * @param info the information, or null to show {@link MediaInfo#EMPTY}
   */
  @Override
  public void setCurrentMedia(final @Nullable MediaInfo info) {
    this.currentMedia = info == null ? MediaInfo.EMPTY : info;
  }

  /**
   * Gets the information served at {@code /media}.
   *
   * @return the information, {@link MediaInfo#EMPTY} when nothing is playing
   */
  @Override
  public MediaInfo getCurrentMedia() {
    return this.currentMedia;
  }
}
