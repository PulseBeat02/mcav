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
import java.io.IOException;
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
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import org.apache.catalina.webresources.TomcatURLStreamHandlerFactory;
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
 * instead of stalling the pipeline, and one whose writes block for {@value #SEND_TIME_LIMIT_MILLISECONDS} milliseconds
 * is disconnected.
 *
 * <p>Spring Boot normally takes over the logging system of the JVM when it starts, resetting the levels and
 * handlers of {@code java.util.logging} or Log4j 2 for the whole server that hosts the library. Unless the system
 * property {@value #LOGGING_SYSTEM_PROPERTY} was already set, it is set to {@code none} before the first start,
 * so Spring logs through SLF4J like every other library and leaves the logging of the host alone.
 *
 * <p>This class is thread-safe.
 *
 * @since 1.0.0
 */
public final class SpringHttpResult implements HttpResult {

  private static final String RESULT_BEAN_NAME = "mcavHttpResult";

  static final String LOGGING_SYSTEM_PROPERTY = "org.springframework.boot.logging.LoggingSystem";

  private static final Logger LOGGER = LoggerFactory.getLogger(SpringHttpResult.class);
  private static final int SEND_TIME_LIMIT_MILLISECONDS = 5_000;
  private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

  private static final String NO_LOGGING_SYSTEM = "none";

  private static final String BUNDLED_PAGE_LOCATION = "classpath:/mcav/http/website/";
  private static final String PATH_SEPARATOR = "/";

  private static final char IPV6_SEPARATOR = ':';
  private static final String OPENING_BRACKET = "[";
  private static final String ZONE_SEPARATOR = "%";
  private static final String ENCODED_ZONE_SEPARATOR = "%25";

  private final String domain;
  private final int port;
  private final @Nullable Path directory;
  private final @Nullable InetAddress bindAddress;
  private final List<String> extraProperties;
  private final Map<String, AudioListener> listeners;
  private final Object lifecycleLock;

  private volatile MediaInformation currentMedia;
  private volatile @Nullable ConfigurableApplicationContext context;
  private volatile boolean acceptingListeners;

  SpringHttpResult(final String domain, final int port, final @Nullable Path directory) {
    this(domain, port, directory, null, List.of());
  }

  @VisibleForTesting
  SpringHttpResult(final String domain, final int port, final @Nullable Path directory, final List<String> extraProperties) {
    this(domain, port, directory, null, extraProperties);
  }

  SpringHttpResult(
    final String domain,
    final int port,
    final @Nullable Path directory,
    final @Nullable InetAddress bindAddress,
    final List<String> extraProperties
  ) {
    Preconditions.checkNotNull(domain, "Domain must not be null!");
    Preconditions.checkNotNull(extraProperties, "Extra properties must not be null!");

    this.domain = domain;
    this.port = port;
    this.directory = directory;
    this.bindAddress = bindAddress;
    this.extraProperties = List.copyOf(extraProperties);
    this.listeners = new ConcurrentHashMap<>();
    this.lifecycleLock = new Object();
    this.acceptingListeners = true;
    this.currentMedia = MediaInformation.EMPTY;
  }

  /**
   * Starts the embedded web server and blocks until it accepts connections. The class loader of the library is
   * the context class loader of the calling thread while Spring starts, and the previous one is restored
   * afterwards. Calling it while the server runs has no effect.
   *
   * @throws HttpException if the server cannot be started, for example because the port is in use
   * @since 1.0.0
   */
  @Override
  public void start() {
    synchronized (this.lifecycleLock) {
      if (this.context != null) {
        return;
      }

      this.startWithOwnClassLoader();
    }
  }

  /**
   * Disconnects every listener with {@link CloseStatus#GOING_AWAY} and stops the embedded web server. The
   * connections are closed on other threads, so a listener whose write is stuck never delays stopping. Calling it
   * while the server is stopped has no effect.
   *
   * @since 1.0.0
   */
  @Override
  public void stop() {
    synchronized (this.lifecycleLock) {
      // Clear before disconnectListeners(): a handshake finishing during shutdown must not add a listener.
      this.acceptingListeners = false;
      final ConfigurableApplicationContext current = this.context;
      this.disconnectListeners();

      if (current == null) {
        return;
      }

      this.context = null;
      this.closeWithOwnClassLoader(current);
    }
  }

  private void startWithOwnClassLoader() {
    final Thread thread = Thread.currentThread();
    final ClassLoader previous = thread.getContextClassLoader();
    final ClassLoader own = SpringHttpResult.class.getClassLoader();
    // Spring resolves classes and resources through the context class loader, which plugin hosts do not set
    thread.setContextClassLoader(own);
    try {
      this.startApplication();
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  private void startApplication() {
    try {
      this.context = this.createApplication();
      this.acceptingListeners = true;

      final String url = this.getFullUrl();
      LOGGER.info("Audio web player is running at {}.", url);
    } catch (final RuntimeException exception) {
      final String reason = exception.getMessage();
      final String message = "Web server on port %s failed with \"%s\" while starting!".formatted(this.port, reason);
      throw new HttpException(message, exception);
    }
  }

  private ConfigurableApplicationContext createApplication() {
    this.keepTheLoggingOfTheHost();
    // The JVM keeps a URL factory forever; a replacement plugin loader must not register another copy of Tomcat's.
    TomcatURLStreamHandlerFactory.disable();

    final SpringApplicationBuilder builder = new SpringApplicationBuilder(HttpServerApplication.class);
    builder.web(WebApplicationType.SERVLET);
    builder.headless(true);
    builder.logStartupInfo(false);
    // plugin class loaders are closed before JVM shutdown hooks run, so Spring must not register one
    builder.registerShutdownHook(false);
    builder.initializers(this::registerResult);

    final List<String> properties = this.createProperties();
    final String[] array = properties.toArray(new String[0]);
    builder.properties(array);
    return builder.run();
  }

  @VisibleForTesting
  void keepTheLoggingOfTheHost() {
    final String chosen = System.getProperty(LOGGING_SYSTEM_PROPERTY);
    if (chosen == null) {
      System.setProperty(LOGGING_SYSTEM_PROPERTY, NO_LOGGING_SYSTEM);
    }
  }

  private void registerResult(final ConfigurableApplicationContext context) {
    final ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
    beanFactory.registerSingleton(RESULT_BEAN_NAME, this);
  }

  private List<String> createProperties() {
    final String location = this.staticLocation(this.directory);
    final String portProperty = "server.port=%s".formatted(this.port);
    final String nameProperty = "spring.application.name=mcav-http-%s".formatted(this.port);
    final String locationProperty = "spring.web.resources.static-locations=%s".formatted(location);
    final List<String> properties = new ArrayList<>();
    properties.add(portProperty);
    properties.add(nameProperty);
    properties.add("spring.main.banner-mode=off");
    properties.add("spring.jmx.enabled=false");
    properties.add("spring.main.lazy-initialization=true");
    properties.add(locationProperty);
    properties.add("server.shutdown=immediate");
    properties.add("server.compression.enabled=true");

    final InetAddress address = this.bindAddress;
    if (address != null) {
      final String hostAddress = address.getHostAddress();
      final String addressProperty = "server.address=%s".formatted(hostAddress);
      properties.add(addressProperty);
    }

    // last, so they override the defaults
    properties.addAll(this.extraProperties);
    return properties;
  }

  @VisibleForTesting
  String staticLocation(final @Nullable Path directory) {
    if (directory == null) {
      return BUNDLED_PAGE_LOCATION;
    }

    final Path absolute = directory.toAbsolutePath();
    final URI location = absolute.toUri();
    final String uri = location.toString();
    return uri.endsWith(PATH_SEPARATOR) ? uri : uri + PATH_SEPARATOR;
  }

  private void disconnectListeners() {
    final Collection<AudioListener> connected = this.listeners.values();
    for (final AudioListener listener : connected) {
      // closing waits for a write in progress, so a stuck browser must not hold up stopping or starting
      listener.closeAsync(CloseStatus.GOING_AWAY);
    }
    this.listeners.clear();
  }

  @VisibleForTesting
  void closeWithOwnClassLoader(final ConfigurableApplicationContext current) {
    final Thread thread = Thread.currentThread();
    final ClassLoader previous = thread.getContextClassLoader();
    final ClassLoader own = SpringHttpResult.class.getClassLoader();
    // Spring finds its own resources through the context class loader while closing
    thread.setContextClassLoader(own);
    // Spring silently skips closing if the caller's interrupt prevents taking its shutdown lock.
    final boolean interrupted = Thread.interrupted();
    try {
      current.close();
    } finally {
      thread.setContextClassLoader(previous);
      if (interrupted) {
        thread.interrupt();
      }
    }
  }

  void addListener(final WebSocketSession session) {
    final boolean acceptingBefore = this.acceptingListeners;
    if (!acceptingBefore) {
      this.closeQuietly(session);
      return;
    }

    final String id = session.getId();
    final AudioListener listener = new AudioListener(session, SEND_TIME_LIMIT_MILLISECONDS, SEND_BUFFER_LIMIT_BYTES, this::dropListener);
    this.listeners.put(id, listener);
    listener.start();

    // stop() clears the flag before it clears the map, so a listener registered after that clear is only visible
    // here; taking the lifecycle lock instead would deadlock against the web server waiting for this handshake
    final boolean acceptingAfter = this.acceptingListeners;
    if (!acceptingAfter) {
      this.listeners.remove(id, listener);
      listener.closeAsync(CloseStatus.GOING_AWAY);
    }
  }

  private void closeQuietly(final WebSocketSession session) {
    try {
      session.close(CloseStatus.GOING_AWAY);
    } catch (final IOException | RuntimeException exception) {
      // the browser is already gone, which is the outcome this method wants anyway
    }
  }

  private void dropListener(final AudioListener listener) {
    final WebSocketSession session = listener.getSession();
    final String id = session.getId();
    this.listeners.remove(id, listener);
    listener.closeAsync(CloseStatus.SESSION_NOT_RELIABLE);
  }

  void removeListener(final WebSocketSession session) {
    final String id = session.getId();
    final AudioListener listener = this.listeners.remove(id);
    if (listener != null) {
      listener.stop();
    }
  }

  /**
   * Queues a copy of the remaining samples for every listener without blocking and without moving the position of
   * the buffer. A listener whose connection closed or whose write is stuck is disconnected.
   *
   * @param samples  the little-endian 16-bit stereo samples between the position and the limit of the buffer
   * @param metadata the metadata of the original audio
   * @return always false, because the samples are only copied out and never changed
   * @throws NullPointerException if {@code samples} or {@code metadata} is null
   * @since 1.0.0
   */
  @Override
  public boolean applyFilter(final ByteBuffer samples, final OriginalAudioMetadata metadata) {
    Preconditions.checkNotNull(samples, "Samples must not be null!");
    Preconditions.checkNotNull(metadata, "Metadata must not be null!");

    final boolean empty = this.listeners.isEmpty();
    if (empty) {
      return false;
    }

    final byte[] bytes = this.copyRemaining(samples);
    final Collection<AudioListener> connected = this.listeners.values();
    for (final AudioListener listener : connected) {
      final boolean queued = listener.offer(bytes);
      if (!queued) {
        this.dropListener(listener);
      }
    }

    return false;
  }

  private byte[] copyRemaining(final ByteBuffer samples) {
    final ByteBuffer source = samples.duplicate();
    final int remaining = source.remaining();
    final byte[] bytes = new byte[remaining];
    source.get(bytes);
    return bytes;
  }

  /**
   * Returns whether the embedded web server is running.
   *
   * @return true between a successful {@link #start()} and the next {@link #stop()}
   * @since 1.0.0
   */
  @Override
  public boolean isRunning() {
    return this.context != null;
  }

  @VisibleForTesting
  int getLocalPort() {
    final ConfigurableApplicationContext current = this.context;
    if (current instanceof final WebServerApplicationContext web) {
      final WebServer webServer = web.getWebServer();
      final WebServer server = Objects.requireNonNull(webServer, "A running server must have a web server!");
      return server.getPort();
    }

    return this.port;
  }

  @VisibleForTesting
  @Nullable InetAddress getBindAddress() {
    return this.bindAddress;
  }

  @VisibleForTesting
  @Nullable Path getDirectory() {
    return this.directory;
  }

  @VisibleForTesting
  @Nullable AudioListener getListener(final String id) {
    return this.listeners.get(id);
  }

  /**
   * Returns the number of browsers currently connected to the audio socket.
   *
   * @return the listener count, 0 while the server is stopped
   * @since 1.0.0
   */
  @Override
  public int getListenerCount() {
    return this.listeners.size();
  }

  /**
   * Shows the title, artist, thumbnail, and statistics yt-dlp reported, as {@link MediaInformation#of(UrlParseDump)}
   * takes them.
   *
   * @param dump the output of yt-dlp
   * @throws NullPointerException if {@code dump} is null
   * @since 1.0.0
   */
  @Override
  public void setCurrentMedia(final UrlParseDump dump) {
    Preconditions.checkNotNull(dump, "Dump must not be null!");
    this.currentMedia = MediaInformation.of(dump);
  }

  /**
   * Shows information about the current media; browsers see it the next time they ask for {@code /media}.
   *
   * @param information the information, or null to show {@link MediaInformation#EMPTY}
   * @since 1.0.0
   */
  @Override
  public void setCurrentMedia(final @Nullable MediaInformation information) {
    this.currentMedia = information == null ? MediaInformation.EMPTY : information;
  }

  /**
   * Returns the information served at {@code /media}.
   *
   * @return the information, {@link MediaInformation#EMPTY} when nothing is playing
   * @since 1.0.0
   */
  @Override
  public MediaInformation getCurrentMedia() {
    return this.currentMedia;
  }

  /**
   * Returns the address of the web page, built from the host name and the configured port.
   *
   * @return the URL, such as {@code http://play.example.com:8080/}
   * @since 1.0.0
   */
  @Override
  public String getFullUrl() {
    final String host = this.urlHost(this.domain);
    return "http://%s:%s/".formatted(host, this.port);
  }

  @VisibleForTesting
  String urlHost(final String domain) {
    final boolean isIpv6 = domain.indexOf(IPV6_SEPARATOR) >= 0;
    final boolean bracketed = domain.startsWith(OPENING_BRACKET);
    if (!isIpv6 || bracketed) {
      return domain;
    }

    // RFC 6874 encodes the percent sign of a zone id
    final String encoded = domain.replace(ZONE_SEPARATOR, ENCODED_ZONE_SEPARATOR);
    return "[%s]".formatted(encoded);
  }
}
