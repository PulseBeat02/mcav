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
package me.brandonli.mcav.plugin.command.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.MoreExecutors;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.image.MatImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import me.brandonli.mcav.media.source.SourceDetectionHelper;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.testing.Components;
import me.brandonli.mcav.plugin.testing.StandardErrorCapture;
import me.brandonli.mcav.plugin.testing.TestServer;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

// the image manager loads on the calling thread, so every step runs before displayImage returns
final class AbstractImageCommandTest {

  private static final String SCREEN_SIZE = "4x2";
  private static final int SCREEN_WIDTH = 4;
  private static final int SCREEN_HEIGHT = 2;
  private static final String LARGE_SCREEN_SIZE = "8x8";
  private static final int LARGE_SCREEN_WIDTH = 8;

  private static final int IMAGE_WIDTH = 6;
  private static final int IMAGE_HEIGHT = 4;

  private static final int RED_PIXEL = 0xFFFF0000;

  private static final int PNG_WIDTH_OFFSET = 16;
  private static final int PNG_HEIGHT_OFFSET = 20;

  private static final char QUOTE = '"';
  private static final String EMPTY_QUOTED_SOURCE = "\"\"";
  private static final String BLANK_QUOTED_SOURCE = "\"   \"";

  private static final String CONFIGURATION = "configuration";

  private static final String FILTER = "invert";

  private static final String PICTURE_FILE_NAME = "picture.png";
  private static final String QUEUED_FILE_NAME = "queued.png";
  private static final String PNG_FORMAT = "png";
  private static final String INVALID_IMAGE_CONTENT = "this is not an image";
  private static final String PICTURE_PATH = "/picture.png";
  private static final String MISSING_PATH = "/missing.png";
  private static final String FILTER_FAILURE_FILE_NAME = "filter-failure.png";
  private static final String FILTER_CLEANUP_FAILURE_FILE_NAME = "filter-cleanup-failure.png";
  private static final String MY_PICTURE_FILE_NAME = "my picture.png";
  private static final String LITERAL_QUOTES_FILE_NAME = "literal-quotes.png";
  private static final String ANIMATION_FILE_NAME = "animation.gif";
  private static final String BROKEN_FILE_NAME = "broken.png";
  private static final String OVERSIZED_FILE_NAME = "oversized.png";
  private static final String LATE_FILE_NAME = "late.png";
  private static final String REJECTED_FILE_NAME = "rejected.png";
  private static final String SCHEDULE_FAILURE_FILE_NAME = "schedule-failure.png";
  private static final String CLEANUP_FAILURE_FILE_NAME = "cleanup-failure.png";
  private static final String SCHEDULE_FATAL_FILE_NAME = "schedule-fatal.png";
  private static final String DISCARD_FATAL_FILE_NAME = "discard-fatal.png";
  private static final String CONFIGURATION_CLEANUP_FATAL_FILE_NAME = "config-cleanup-fatal.png";

  private static final int EPHEMERAL_PORT = 0;

  private static final String IMAGE_RELEASE_FAILURE_MESSAGE = "image release failed!";

  private static final String ERROR_LEVEL = "ERROR";
  private static final String STACK_FRAME = "\tat ";

  @TempDir
  private Path folder;

  private final ExecutorService direct = MoreExecutors.newDirectExecutorService();

  private ImageManager manager;
  private DisplayableImage display;
  private RecordingCommand command;
  private CommandSender sender;
  private HttpServer server;

  @BeforeEach
  void createCommand() {
    TestServer.reset();
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    when(plugin.getDataFolder()).thenReturn(this.folder.toFile());
    this.manager = spy(new ImageManager(plugin, this.direct));
    when(plugin.getImageManager()).thenReturn(this.manager);
    this.display = mock(DisplayableImage.class);
    this.command = new RecordingCommand(plugin, this.display);
    this.sender = mock(CommandSender.class);
  }

  @AfterEach
  void stopServerAndExecutor() {
    if (this.server != null) {
      this.server.stop(0);
    }
    this.manager.shutdown();
  }

  @Test
  void shouldLoadAnImageFileAndShowIt() throws IOException {
    final Path file = this.writeImage(PICTURE_FILE_NAME, PNG_FORMAT);
    final String mrl = file.toString();
    this.command.displayImage(_ -> CONFIGURATION, this.sender, SCREEN_SIZE, mrl, null);
    try (final ImageBuffer image = this.verifyShown()) {
      final int width = image.getWidth();
      final int height = image.getHeight();
      assertEquals(SCREEN_WIDTH, width);
      assertEquals(SCREEN_HEIGHT, height);
    }

    final Pair<Integer, Integer> resolution = this.command.resolutions.getFirst();
    final int resolutionWidth = resolution.getFirst();
    final int resolutionHeight = resolution.getSecond();
    assertEquals(SCREEN_WIDTH, resolutionWidth);
    assertEquals(SCREEN_HEIGHT, resolutionHeight);

    final AbstractImageCommand.ImageConfigurationProvider provider = this.command.providers.getFirst();
    final Object configuration = provider.buildConfiguration(resolution);
    assertEquals(CONFIGURATION, configuration);

    final List<Component> messages = Components.received(this.sender);
    final Component start = Message.LOAD_IMAGE_START.build();
    final Component done = Message.LOAD_IMAGE.build();
    final List<Component> expected = List.of(start, done);
    assertEquals(expected, messages);
  }

  private Path writeImage(final String name, final String format) throws IOException {
    final Path file = this.folder.resolve(name);
    final byte[] bytes = this.encode(format);
    Files.write(file, bytes);
    return file;
  }

  private byte[] encode(final String format) throws IOException {
    final BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
    image.setRGB(0, 0, 0xFF0000);
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, format, output);
    return output.toByteArray();
  }

  private ImageBuffer verifyShown() {
    final ArgumentCaptor<ImageBuffer> captor = ArgumentCaptor.forClass(ImageBuffer.class);
    verify(this.manager).setCurrentImage(captor.capture());

    final ImageBuffer image = captor.getValue();
    verify(this.manager).releaseImage(ReleaseThread.CALLING_THREAD);
    verify(this.manager).setImage(this.display);
    verify(this.display).displayImage(image);

    return image;
  }

  @Test
  void shouldFilterAnImageAtTheSizeItIsShownAt() throws IOException {
    final Path file = this.writeImage(PICTURE_FILE_NAME, PNG_FORMAT);
    this.command.displayImage(_ -> CONFIGURATION, this.sender, SCREEN_SIZE, file.toString(), FILTER);
    try (final ImageBuffer image = this.verifyShown()) {
      final int width = image.getWidth();
      assertEquals(SCREEN_WIDTH, width);

      final int height = image.getHeight();
      assertEquals(SCREEN_HEIGHT, height);

      final int[] pixels = image.getPixels();
      assertEquals(0xffffff, pixels[pixels.length - 1] & 0xffffff);
    }
  }

  @Test
  void shouldReleaseTheDecodedImageWhenResizingForFiltersFails() throws IOException {
    final Path file = this.writeImage(FILTER_FAILURE_FILE_NAME, PNG_FORMAT);
    final IllegalStateException failure = new IllegalStateException("resize failed after decoding!");
    try (
      final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class);
      final MockedConstruction<ResizeFilter> resizers = Mockito.mockConstruction(ResizeFilter.class, (filter, context) ->
        this.failFilter(filter, failure)
      )
    ) {
      this.command.displayImage(_ -> CONFIGURATION, this.sender, SCREEN_SIZE, file.toString(), FILTER);

      final int size = resizers.constructed().size();
      assertEquals(1, size);

      final int buffersSize = buffers.constructed().size();
      assertEquals(1, buffersSize);

      final MatImageBuffer decoded = buffers.constructed().getFirst();
      verify(decoded, times(1)).release();

      final List<Component> expected = List.of(Message.LOAD_IMAGE_START.build(), Message.UNSUPPORTED_MRL.build());
      final List<Component> received = Components.received(this.sender);
      assertEquals(expected, received);

      this.assertNothingShown();
      verify(this.manager, never()).retainLoaded(Mockito.anyLong(), any());
    }
  }

  private void failFilter(final ResizeFilter filter, final Throwable failure) {
    Mockito.doThrow(failure).when(filter).applyFilter(any());
  }

  private void assertNothingShown() {
    verify(this.manager, never()).setImage(any());
    verify(this.display, never()).displayImage(any());
  }

  @ParameterizedTest
  @ValueSource(booleans = { false, true })
  void shouldKeepTheFilterFailureWhenReleasingTheImageAlsoFails(final boolean sameFailure) throws IOException {
    final Path file = this.writeImage(FILTER_CLEANUP_FAILURE_FILE_NAME, PNG_FORMAT);
    final IllegalStateException failure = new IllegalStateException("resize failed!");
    final RuntimeException cleanupFailure = sameFailure ? failure : new IllegalArgumentException("release failed!");
    try (
      final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class, (image, context) ->
        this.failRelease(image, cleanupFailure)
      );
      final MockedConstruction<ResizeFilter> resizers = Mockito.mockConstruction(ResizeFilter.class, (filter, context) ->
        this.failFilter(filter, failure)
      )
    ) {
      this.command.displayImage(_ -> CONFIGURATION, this.sender, SCREEN_SIZE, file.toString(), FILTER);

      final int size = resizers.constructed().size();
      assertEquals(1, size);

      verify(buffers.constructed().getFirst(), times(1)).release();

      final Throwable[] expected = sameFailure ? new Throwable[0] : new Throwable[] { cleanupFailure };

      final Throwable[] suppressed = failure.getSuppressed();
      assertArrayEquals(expected, suppressed);

      final List<Component> listExpected = List.of(Message.LOAD_IMAGE_START.build(), Message.UNSUPPORTED_MRL.build());
      final List<Component> received = Components.received(this.sender);
      assertEquals(listExpected, received);

      this.assertNothingShown();
    }
  }

  private void failRelease(final MatImageBuffer image, final RuntimeException failure) {
    Mockito.doThrow(failure).when(image).release();
  }

  @Test
  void shouldNotReleaseTheDecodedImageWhenResizingForFiltersFailsFatally() throws IOException {
    final Path file = this.writeImage(FILTER_FAILURE_FILE_NAME, PNG_FORMAT);
    final OutOfMemoryError fatal = new OutOfMemoryError("fatal resize sentinel!");
    try (
      final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class);
      final MockedConstruction<ResizeFilter> resizers = Mockito.mockConstruction(ResizeFilter.class, (filter, context) ->
        this.failFilter(filter, fatal)
      )
    ) {
      this.command.displayImage(_ -> CONFIGURATION, this.sender, SCREEN_SIZE, file.toString(), FILTER);

      final List<ResizeFilter> createdResizers = resizers.constructed();

      final int size = createdResizers.size();
      assertEquals(1, size);

      final List<MatImageBuffer> createdBuffers = buffers.constructed();
      final MatImageBuffer decoded = createdBuffers.getFirst();
      verify(decoded, never()).release();
    }
  }

  @Test
  void shouldRefuseFiltersThatAreNotAllowedBeforeLoading() {
    this.command.displayImage(_ -> CONFIGURATION, this.sender, SCREEN_SIZE, PICTURE_FILE_NAME, "fps");
    final Component refused = Message.FILTERS_INVALID.build("Filter fps counts the frames of a video, and an image has one!");

    final List<Component> received = Components.received(this.sender);
    final List<Component> expectedMessages = List.of(refused);
    assertEquals(expectedMessages, received);

    verify(this.manager, never()).beginLoad();
  }

  @Test
  void shouldDownloadAnImageAndShowIt() throws IOException {
    final String url = this.serveImage();
    this.command.displayImage(_ -> CONFIGURATION, this.sender, LARGE_SCREEN_SIZE, url, null);
    try (final ImageBuffer image = this.verifyShown()) {
      final int width = image.getWidth();
      assertEquals(LARGE_SCREEN_WIDTH, width);
    }
  }

  private String serveImage() throws IOException {
    final byte[] bytes = this.encode(PNG_FORMAT);
    return this.serve(bytes);
  }

  private String serve(final byte[] bytes) throws IOException {
    final String path = PICTURE_PATH;
    final InetAddress loopback = InetAddress.getLoopbackAddress();
    final InetSocketAddress address = new InetSocketAddress(loopback, EPHEMERAL_PORT);
    this.server = HttpServer.create(address, 0);
    this.server.createContext(path, exchange -> this.sendImage(exchange, bytes));
    this.server.start();
    final InetSocketAddress bound = this.server.getAddress();
    final int port = bound.getPort();
    return "http://127.0.0.1:%s%s".formatted(port, path);
  }

  private void sendImage(final HttpExchange exchange, final byte[] bytes) throws IOException {
    exchange.sendResponseHeaders(200, bytes.length);
    try (final OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = { false, true })
  void shouldLoadPathsWithSpacesWithOrWithoutSurroundingQuotes(final boolean quoted) throws IOException {
    final Path file = this.writeImage(MY_PICTURE_FILE_NAME, PNG_FORMAT);
    final String path = file.toString();
    final String mrl = quoted ? QUOTE + path + QUOTE : path;
    this.command.displayImage(_ -> CONFIGURATION, this.sender, LARGE_SCREEN_SIZE, mrl, null);
    final ImageBuffer image = this.verifyShown();

    final int[] pixels = image.getPixels();
    assertEquals(RED_PIXEL, pixels[0]);
  }

  @Test
  void shouldDownloadAnImageFromAQuotedUrl() throws IOException {
    final String url = this.serveImage();
    final String mrl = QUOTE + url + QUOTE;
    this.command.displayImage(_ -> CONFIGURATION, this.sender, LARGE_SCREEN_SIZE, mrl, null);
    final ImageBuffer image = this.verifyShown();

    final int[] pixels = image.getPixels();
    assertEquals(RED_PIXEL, pixels[0]);
  }

  @ParameterizedTest
  @ValueSource(strings = { "", EMPTY_QUOTED_SOURCE, BLANK_QUOTED_SOURCE })
  void shouldRejectEmptySources(final String mrl) {
    this.command.displayImage(_ -> CONFIGURATION, this.sender, LARGE_SCREEN_SIZE, mrl, null);
    this.assertNothingShown();

    final List<Component> messages = Components.received(this.sender);
    final Component invalid = Message.UNSUPPORTED_MRL.build();
    final List<Component> expected = List.of(invalid);
    assertEquals(expected, messages);
  }

  @ParameterizedTest
  @CsvSource({
    "not a source, not a source",
    "\", \"",
    "\"missing.png, \"missing.png",
    "missing.png\", missing.png\"",
    "\"\"missing.png\"\", \"missing.png\"",
  })
  void shouldNameSourcesThatAreNeitherAFileNorAUrl(final String mrl, final String source) {
    this.command.displayImage(_ -> CONFIGURATION, this.sender, LARGE_SCREEN_SIZE, mrl, null);
    this.assertNothingShown();
    verify(this.manager, never()).beginLoad();

    final List<Component> messages = Components.received(this.sender);
    final String reason = "Image %s is neither a file on the server nor a URL!".formatted(source);
    final Component unavailable = Message.IMAGE_UNAVAILABLE.build(reason);
    final List<Component> expected = List.of(unavailable);
    assertEquals(expected, messages);
  }

  @Test
  void shouldRejectEmptyQuotedMrlEvenWhenRawQuotesResolveToAnImage() throws IOException {
    final Path imagePath = this.writeImage(LITERAL_QUOTES_FILE_NAME, PNG_FORMAT);
    final FileSource imageSource = FileSource.path(imagePath);
    // A file named two quote characters is mocked, not created, because concurrent PIT minions share the working directory.
    try (
      final MockedConstruction<SourceDetectionHelper> detection = Mockito.mockConstruction(SourceDetectionHelper.class, (helper, _) ->
        this.detectQuotesAsImage(helper, imageSource)
      )
    ) {
      this.command.displayImage(_ -> CONFIGURATION, this.sender, LARGE_SCREEN_SIZE, EMPTY_QUOTED_SOURCE, null);
      this.assertNothingShown();

      final List<Component> messages = Components.received(this.sender);
      final Component invalid = Message.UNSUPPORTED_MRL.build();
      final List<Component> expectedMessages = List.of(invalid);
      assertEquals(expectedMessages, messages);

      final List<SourceDetectionHelper> lookups = detection.constructed();

      final int size = lookups.size();
      assertEquals(0, size, "an empty quoted MRL must be rejected before consulting the filesystem");
    }
  }

  private void detectQuotesAsImage(final SourceDetectionHelper helper, final FileSource imageSource) {
    when(helper.detectSource(EMPTY_QUOTED_SOURCE)).thenReturn(Optional.of(imageSource));
  }

  @Test
  void shouldRefuseInvalidResolutions() throws IOException {
    final Path file = this.writeImage(PICTURE_FILE_NAME, PNG_FORMAT);
    final String mrl = file.toString();
    this.command.displayImage(_ -> CONFIGURATION, this.sender, "wide", mrl, null);
    final List<Component> messages = Components.received(this.sender);
    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    final List<Component> expected = List.of(error);
    assertEquals(expected, messages);
    verify(this.manager, never()).getService();
    this.assertNothingShown();
  }

  @ParameterizedTest
  @ValueSource(strings = { "0", "mp4||input" })
  void shouldRefuseMediaThatIsNotAFileOrUrl(final String mrl) {
    this.command.displayImage(_ -> CONFIGURATION, this.sender, SCREEN_SIZE, mrl, null);
    final List<Component> messages = Components.received(this.sender);
    final Component error = Message.UNSUPPORTED_MRL.build();
    final List<Component> expected = List.of(error);
    assertEquals(expected, messages);
    this.assertNothingShown();
  }

  @Test
  void shouldRefuseAnimatedImages() throws IOException {
    final Path file = this.writeImage(ANIMATION_FILE_NAME, "gif");
    final String mrl = file.toString();
    this.command.displayImage(_ -> CONFIGURATION, this.sender, SCREEN_SIZE, mrl, null);
    final List<Component> messages = Components.received(this.sender);
    final Component error = Message.UNSUPPORTED_MRL.build();
    final List<Component> expected = List.of(error);
    assertEquals(expected, messages);
    this.assertNothingShown();
  }

  @Test
  void shouldLogAnImageThatFailedToLoadAsAnErrorWithItsTrace() throws IOException {
    final Path file = this.writeImage(FILTER_FAILURE_FILE_NAME, PNG_FORMAT);
    final String mrl = file.toString();
    final IllegalStateException failure = new IllegalStateException("resize failed after decoding!");

    final String output;
    try (
      final MockedConstruction<ResizeFilter> _ = Mockito.mockConstruction(ResizeFilter.class, (filter, _) ->
        this.failFilter(filter, failure)
      );
      final StandardErrorCapture capture = StandardErrorCapture.start()
    ) {
      this.command.displayImage(_ -> CONFIGURATION, this.sender, SCREEN_SIZE, mrl, FILTER);
      output = capture.getOutput();
    }

    final boolean named = output.contains("Image %s could not be loaded.".formatted(mrl));
    final boolean error = output.contains(ERROR_LEVEL);
    final boolean traced = output.contains(STACK_FRAME);
    assertTrue(named, "the log of a failed image names the media it could not load");
    assertTrue(error, output);
    assertTrue(traced, output);
  }

  @Test
  void shouldReportImagesThatFailToLoad() throws IOException {
    final Path file = this.folder.resolve(BROKEN_FILE_NAME);
    Files.writeString(file, INVALID_IMAGE_CONTENT);
    final String mrl = file.toString();
    final String output = this.displayCapturingErrors(mrl);

    final String reason = "File %s is not a supported image!".formatted(mrl);
    this.assertUnavailableAfterLoading(reason);
    this.assertNoErrorLogged(output);
    verify(this.manager, never()).releaseImage(ReleaseThread.CALLING_THREAD);
  }

  private String displayCapturingErrors(final String mrl) {
    try (final StandardErrorCapture capture = StandardErrorCapture.start()) {
      this.command.displayImage(_ -> CONFIGURATION, this.sender, SCREEN_SIZE, mrl, null);
      return capture.getOutput();
    }
  }

  private void assertUnavailableAfterLoading(final String reason) {
    final List<Component> messages = Components.received(this.sender);
    final Component start = Message.LOAD_IMAGE_START.build();
    final Component unavailable = Message.IMAGE_UNAVAILABLE.build(reason);
    final List<Component> expected = List.of(start, unavailable);
    assertEquals(expected, messages);
    this.assertNothingShown();
  }

  private void assertNoErrorLogged(final String output) {
    final boolean logged = output.contains(ERROR_LEVEL) || output.contains(STACK_FRAME);
    assertFalse(logged, output);
  }

  @Test
  void shouldTellTheSenderWhenAnImageDeclaresMorePixelsThanTheLimit() throws IOException {
    final byte[] bytes = this.encode(PNG_FORMAT);
    final ByteBuffer header = ByteBuffer.wrap(bytes);
    header.putInt(PNG_WIDTH_OFFSET, 8193);
    header.putInt(PNG_HEIGHT_OFFSET, 8192);
    final Path file = this.folder.resolve(OVERSIZED_FILE_NAME);
    Files.write(file, bytes);
    final String mrl = file.toString();
    final String output = this.displayCapturingErrors(mrl);

    final String reason =
      "Image size 8193x8192 exceeds the limit of 67108864 pixels that the system property mcav.image.maximum_pixels sets!";
    this.assertUnavailableAfterLoading(reason);
    this.assertNoErrorLogged(output);
  }

  @Test
  void shouldTellTheSenderTheStatusOfAFailedDownload() throws IOException {
    final String url = this.serveImage();
    final String missing = url.replace(PICTURE_PATH, MISSING_PATH);
    final String output = this.displayCapturingErrors(missing);

    final String reason = "Request for %s got status 404, which is not a success!".formatted(missing);
    this.assertUnavailableAfterLoading(reason);
    this.assertNoErrorLogged(output);
  }

  @Test
  void shouldNotNameTheCachedCopyOfADownloadThatIsNotAnImage() throws IOException {
    final byte[] text = INVALID_IMAGE_CONTENT.getBytes(StandardCharsets.UTF_8);
    final String url = this.serve(text);
    final String output = this.displayCapturingErrors(url);

    final String reason = "URL %s is not an image the server can show!".formatted(url);
    this.assertUnavailableAfterLoading(reason);
    this.assertNoErrorLogged(output);
  }

  @Test
  void shouldReleaseTheLoadedImageWhenThePluginWasDisabledMeanwhile() throws IOException {
    final BukkitScheduler scheduler = TestServer.getScheduler();
    final IllegalPluginAccessException disabled = new IllegalPluginAccessException("disabled!");
    when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenThrow(disabled);
    final Path file = this.writeImage(PICTURE_FILE_NAME, PNG_FORMAT);
    final String mrl = file.toString();
    try (final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class)) {
      this.command.displayImage(_ -> CONFIGURATION, this.sender, SCREEN_SIZE, mrl, null);
      final List<MatImageBuffer> constructed = buffers.constructed();
      final int count = constructed.size();
      assertEquals(1, count);

      final MatImageBuffer image = constructed.getFirst();
      verify(image).release();
    }

    this.assertNothingShown();
    verify(this.manager, never()).setCurrentImage(any());

    final List<Component> messages = Components.received(this.sender);
    final Component start = Message.LOAD_IMAGE_START.build();
    final List<Component> expected = List.of(start);
    assertEquals(expected, messages);
  }

  @Test
  void shouldReleaseAnImageWhenAnAcceptedMainThreadTaskIsCancelledByShutdown() throws IOException {
    TestServer.resetWithDeferredTasks();
    final Path file = this.writeImage(QUEUED_FILE_NAME, PNG_FORMAT);
    final String mrl = file.toString();
    try (final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class)) {
      this.command.displayImage(_ -> CONFIGURATION, this.sender, SCREEN_SIZE, mrl, null);
      final List<MatImageBuffer> constructed = buffers.constructed();
      final MatImageBuffer image = constructed.getFirst();
      verify(image, never()).release();

      this.manager.shutdown();
      verify(image, times(1)).release();

      TestServer.runPendingTasks();
      verify(image, times(1)).release();
      this.assertNothingShown();
    }
  }

  @Test
  void shouldDiscardTheOlderLoadedImageWhenANewerRequestIsQueued() throws IOException {
    TestServer.resetWithDeferredTasks();
    final Path file = this.writeImage(QUEUED_FILE_NAME, PNG_FORMAT);
    final String mrl = file.toString();
    try (final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class)) {
      this.command.displayImage(_ -> "first", this.sender, SCREEN_SIZE, mrl, null);
      this.command.displayImage(_ -> "second", this.sender, "8x4", mrl, null);
      final List<MatImageBuffer> constructed = buffers.constructed();
      final MatImageBuffer first = constructed.get(0);
      final MatImageBuffer second = constructed.get(1);
      verify(first, times(1)).release();

      TestServer.runPendingTasks();
      verify(this.display, never()).displayImage(first);
      verify(this.display, times(1)).displayImage(second);
      verify(first, times(1)).release();

      final int creations = this.command.resolutions.size();
      assertEquals(1, creations);
    }
  }

  @Test
  void shouldReplaceTheImageThatAnotherImageCommandShows() throws IOException {
    final Path file = this.writeImage(PICTURE_FILE_NAME, PNG_FORMAT);
    final String mrl = file.toString();
    final DisplayableImage replacement = mock(DisplayableImage.class);
    final RecordingCommand other = new RecordingCommand(this.command.plugin, replacement);
    try (final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class)) {
      this.command.displayImage(_ -> CONFIGURATION, this.sender, SCREEN_SIZE, mrl, null);
      other.displayImage(_ -> CONFIGURATION, this.sender, SCREEN_SIZE, mrl, null);
      final List<MatImageBuffer> constructed = buffers.constructed();
      final MatImageBuffer first = constructed.getFirst();
      final MatImageBuffer second = constructed.getLast();
      verify(this.display, times(1)).release();
      verify(first, times(1)).close();
      verify(replacement, times(1)).displayImage(second);

      verify(replacement, never()).release();
      verify(second, never()).close();
    }
  }

  @Test
  void shouldReleaseTheLoadedImageWhenDisplayConfigurationFails() throws IOException {
    TestServer.resetWithDeferredTasks();
    final Path file = this.writeImage(QUEUED_FILE_NAME, PNG_FORMAT);
    final String mrl = file.toString();
    final RecordingCommand failing = spy(this.command);
    final IllegalStateException failure = new IllegalStateException("invalid display configuration!");
    Mockito.doThrow(failure).when(failing).createImage(any(), any());
    try (final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class)) {
      failing.displayImage(_ -> CONFIGURATION, this.sender, SCREEN_SIZE, mrl, null);
      final List<MatImageBuffer> constructed = buffers.constructed();
      final MatImageBuffer image = constructed.getFirst();
      final IllegalStateException thrown = assertThrows(IllegalStateException.class, TestServer::runPendingTasks);
      assertSame(failure, thrown);
      verify(image, times(1)).release();
      this.assertNothingShown();
    }
  }

  @Test
  void shouldReleaseBothDisplayAndImageWhenDisplayingFails() throws IOException {
    TestServer.resetWithDeferredTasks();
    final Path file = this.writeImage(QUEUED_FILE_NAME, PNG_FORMAT);
    final String mrl = file.toString();
    final IllegalStateException failure = new IllegalStateException("failed display!");
    Mockito.doThrow(failure).when(this.display).displayImage(any());
    try (final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class)) {
      this.command.displayImage(_ -> CONFIGURATION, this.sender, SCREEN_SIZE, mrl, null);
      final List<MatImageBuffer> constructed = buffers.constructed();
      final MatImageBuffer image = constructed.getFirst();
      final IllegalStateException thrown = assertThrows(IllegalStateException.class, TestServer::runPendingTasks);
      assertSame(failure, thrown);
      verify(image, times(1)).close();
      verify(this.display, times(1)).release();
    }
  }

  @Test
  void shouldReleaseALoadThatCompletesAfterItsManagerHasShutDown() throws IOException, InterruptedException {
    final ExecutorService deferred = mock(ExecutorService.class);
    final List<Runnable> work = new ArrayList<>();
    Mockito.doAnswer(invocation -> this.deferTask(invocation, work))
      .when(deferred)
      .execute(any(Runnable.class));
    when(deferred.awaitTermination(Mockito.anyLong(), any(TimeUnit.class))).thenReturn(true);
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    final ImageManager deferredManager = new ImageManager(plugin, deferred);
    when(plugin.getImageManager()).thenReturn(deferredManager);
    final RecordingCommand deferredCommand = new RecordingCommand(plugin, this.display);
    final Path file = this.writeImage(LATE_FILE_NAME, PNG_FORMAT);
    final String mrl = file.toString();
    deferredCommand.displayImage(_ -> CONFIGURATION, this.sender, SCREEN_SIZE, mrl, null);
    deferredManager.shutdown();
    try (final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class)) {
      final Runnable lateWork = work.getFirst();
      lateWork.run();
      final List<MatImageBuffer> constructed = buffers.constructed();
      final MatImageBuffer image = constructed.getFirst();
      verify(image, times(1)).release();
      verify(this.display, never()).displayImage(any());
    }
    verify(deferred).shutdown();
  }

  private @Nullable Object deferTask(final InvocationOnMock invocation, final List<Runnable> work) {
    final Runnable task = invocation.getArgument(0);
    work.add(task);

    return null;
  }

  @Test
  void shouldPropagateFatalConfigurationFailureWithoutAttemptingNativeCleanup() throws IOException {
    TestServer.resetWithDeferredTasks();
    final Path file = this.writeImage(QUEUED_FILE_NAME, PNG_FORMAT);
    final String mrl = file.toString();
    final RecordingCommand failing = spy(this.command);
    final OutOfMemoryError fatal = new OutOfMemoryError("fatal configuration failure!");
    Mockito.doThrow(fatal).when(failing).createImage(any(), any());
    try (final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class)) {
      failing.displayImage(_ -> CONFIGURATION, this.sender, SCREEN_SIZE, mrl, null);
      final List<MatImageBuffer> constructed = buffers.constructed();
      final MatImageBuffer image = constructed.getFirst();
      final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, TestServer::runPendingTasks);
      assertSame(fatal, thrown);
      verify(image, never()).release();
      verify(image, never()).close();
      verify(this.display, never()).release();
    }
  }

  @Test
  void shouldReportARejectedLoadWithoutAllocatingAnImage() throws IOException {
    final Path file = this.writeImage(REJECTED_FILE_NAME, PNG_FORMAT);
    final String mrl = file.toString();
    this.manager.shutdown();
    try (final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class)) {
      this.command.displayImage(_ -> CONFIGURATION, this.sender, SCREEN_SIZE, mrl, null);
      final List<MatImageBuffer> constructed = buffers.constructed();
      final boolean empty = constructed.isEmpty();
      assertTrue(empty);
    }
    final List<Component> messages = Components.received(this.sender);
    final Component loading = Message.LOAD_IMAGE_START.build();
    final Component rejected = Message.UNSUPPORTED_MRL.build();
    final List<Component> expected = List.of(loading, rejected);
    assertEquals(expected, messages);
    this.assertNothingShown();
  }

  @ParameterizedTest
  @ValueSource(ints = { 0, 1, 2 })
  void shouldDiscardTheImageAndPreserveCleanupFailuresWhenSchedulingFails(final int cleanupMode) throws IOException {
    final Path file = this.writeImage(SCHEDULE_FAILURE_FILE_NAME, PNG_FORMAT);
    final String mrl = file.toString();
    final IllegalStateException primary = new IllegalStateException("scheduler rejected the loaded image!");
    final RuntimeException cleanup = cleanupMode == 1 ? primary : new IllegalArgumentException(IMAGE_RELEASE_FAILURE_MESSAGE);
    final BukkitScheduler scheduler = TestServer.getScheduler();
    when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenThrow(primary);
    final String output;
    try (
      final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class, (image, context) ->
        this.failReleaseInCleanupMode(image, cleanupMode, cleanup)
      );
      final StandardErrorCapture capture = StandardErrorCapture.start()
    ) {
      this.command.displayImage(_ -> CONFIGURATION, this.sender, SCREEN_SIZE, mrl, null);
      final List<MatImageBuffer> constructed = buffers.constructed();
      final MatImageBuffer image = constructed.getFirst();
      verify(image, times(1)).release();

      this.manager.shutdown();
      verify(image, times(1)).release();

      output = capture.getOutput();
    }
    final Throwable[] suppressed = primary.getSuppressed();
    final Throwable[] expected = cleanupMode == 2 ? new Throwable[] { cleanup } : new Throwable[0];
    assertArrayEquals(expected, suppressed);

    final boolean reported = output.contains("scheduler rejected the loaded image!");
    assertTrue(reported, "the callback reports the scheduling failure after releasing the image");
    this.assertNothingShown();
  }

  private void failReleaseInCleanupMode(final MatImageBuffer image, final int cleanupMode, final RuntimeException cleanup) {
    if (cleanupMode != 0) {
      Mockito.doThrow(cleanup).when(image).release();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = { false, true })
  void shouldPreserveThePrimaryFailureOfAFailedConfigurationWhenImageCleanupAlsoFails(final boolean sameFailure) throws IOException {
    TestServer.resetWithDeferredTasks();
    final Path file = this.writeImage(CLEANUP_FAILURE_FILE_NAME, PNG_FORMAT);
    final String mrl = file.toString();
    final RecordingCommand failing = spy(this.command);
    final IllegalStateException primary = new IllegalStateException("invalid image configuration!");
    final RuntimeException cleanup = sameFailure ? primary : new IllegalArgumentException(IMAGE_RELEASE_FAILURE_MESSAGE);
    Mockito.doThrow(primary).when(failing).createImage(any(), any());
    try (
      final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class, (image, context) ->
        this.failRelease(image, cleanup)
      )
    ) {
      failing.displayImage(_ -> CONFIGURATION, this.sender, SCREEN_SIZE, mrl, null);
      final IllegalStateException thrown = assertThrows(IllegalStateException.class, TestServer::runPendingTasks);
      assertSame(primary, thrown);

      final Throwable[] suppressed = thrown.getSuppressed();
      final Throwable[] expected = sameFailure ? new Throwable[0] : new Throwable[] { cleanup };
      assertArrayEquals(expected, suppressed);

      final List<MatImageBuffer> constructed = buffers.constructed();
      final MatImageBuffer image = constructed.getFirst();
      verify(image, times(1)).release();
      this.assertNothingShown();
    }
  }

  @Test
  void shouldRefuseNullArguments() {
    final AbstractImageCommand.ImageConfigurationProvider configuration = _ -> CONFIGURATION;
    assertThrows(NullPointerException.class, () -> new RecordingCommand(null, this.display));
    assertThrows(NullPointerException.class, () -> this.command.displayImage(null, this.sender, SCREEN_SIZE, PICTURE_FILE_NAME, null));
    assertThrows(NullPointerException.class, () -> this.command.displayImage(configuration, null, SCREEN_SIZE, PICTURE_FILE_NAME, null));
    assertThrows(NullPointerException.class, () -> this.command.displayImage(configuration, this.sender, null, PICTURE_FILE_NAME, null));
    assertThrows(NullPointerException.class, () -> this.command.displayImage(configuration, this.sender, SCREEN_SIZE, null, null));
    verify(this.manager, never()).getService();
  }

  @Test
  void shouldPropagateFatalSchedulingFailureWithoutDiscardingLoadedImage() throws IOException {
    final Path file = this.writeImage(SCHEDULE_FATAL_FILE_NAME, PNG_FORMAT);
    final String mrl = file.toString();
    final OutOfMemoryError fatal = new OutOfMemoryError("fatal scheduling sentinel!");
    final BukkitScheduler scheduler = TestServer.getScheduler();
    when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenThrow(fatal);

    final String output;
    try (
      final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class);
      final StandardErrorCapture capture = StandardErrorCapture.start()
    ) {
      this.command.displayImage(_ -> CONFIGURATION, this.sender, SCREEN_SIZE, mrl, null);
      output = capture.getOutput();
      verify(this.manager, never()).discardLoaded(Mockito.anyLong());

      final List<MatImageBuffer> constructed = buffers.constructed();
      final MatImageBuffer image = constructed.getFirst();
      verify(image, never()).release();
    }
    final boolean reported = output.contains("fatal scheduling sentinel!");
    assertTrue(reported, "the fatal scheduling error is logged by the background callback handler");
    this.assertNothingShown();
  }

  @Test
  void shouldPropagateFatalDiscardFailureDuringSchedulingFailureRecovery() throws IOException {
    final Path file = this.writeImage(DISCARD_FATAL_FILE_NAME, PNG_FORMAT);
    final String mrl = file.toString();
    final IllegalStateException primary = new IllegalStateException("scheduling rejected!");
    final OutOfMemoryError fatal = new OutOfMemoryError("fatal discard sentinel!");
    final BukkitScheduler scheduler = TestServer.getScheduler();
    when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenThrow(primary);

    final String output;
    try (
      final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class, (image, _) ->
        this.failFirstRelease(image, fatal)
      );
      final StandardErrorCapture capture = StandardErrorCapture.start()
    ) {
      this.command.displayImage(_ -> CONFIGURATION, this.sender, SCREEN_SIZE, mrl, null);
      output = capture.getOutput();
      verify(this.manager, times(1)).discardLoaded(Mockito.anyLong());

      final List<MatImageBuffer> constructed = buffers.constructed();
      final MatImageBuffer image = constructed.getFirst();
      verify(image, times(1)).release();
    }
    final Throwable[] suppressed = primary.getSuppressed();
    assertEquals(0, suppressed.length, "fatal cleanup failure must not be suppressed onto recoverable primary exception");

    final boolean reported = output.contains("fatal discard sentinel!");
    assertTrue(reported, "the fatal discard failure is logged as the escaping callback error");
    this.assertNothingShown();
  }

  private void failFirstRelease(final MatImageBuffer image, final OutOfMemoryError fatal) {
    Mockito.doThrow(fatal).doNothing().when(image).release();
  }

  @Test
  void shouldPropagateFatalImageCleanupFailureWhenDisplayConfigurationFails() throws IOException {
    TestServer.resetWithDeferredTasks();
    final Path file = this.writeImage(CONFIGURATION_CLEANUP_FATAL_FILE_NAME, PNG_FORMAT);
    final String mrl = file.toString();
    final RecordingCommand failing = spy(this.command);
    final IllegalStateException primary = new IllegalStateException("display configuration failed!");
    final OutOfMemoryError fatal = new OutOfMemoryError("fatal cleanup sentinel!");
    Mockito.doThrow(primary).when(failing).createImage(any(), any());
    try (
      final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class, (image, _) ->
        this.failFirstRelease(image, fatal)
      )
    ) {
      failing.displayImage(_ -> CONFIGURATION, this.sender, SCREEN_SIZE, mrl, null);
      final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, TestServer::runPendingTasks);
      assertSame(fatal, thrown);

      final Throwable[] suppressed = primary.getSuppressed();
      assertEquals(0, suppressed.length, "fatal cleanup error must not be suppressed onto recoverable primary exception");

      final List<MatImageBuffer> constructed = buffers.constructed();
      final MatImageBuffer image = constructed.getFirst();
      verify(image, times(1)).release();
      this.assertNothingShown();
    }
  }

  private static final class RecordingCommand extends AbstractImageCommand {

    private final DisplayableImage display;

    private final List<Pair<Integer, Integer>> resolutions;
    private final List<ImageConfigurationProvider> providers;

    RecordingCommand(final MCAVSandbox plugin, final DisplayableImage display) {
      super(plugin);
      this.display = display;
      this.resolutions = new ArrayList<>();
      this.providers = new ArrayList<>();
    }

    @Override
    public DisplayableImage createImage(final Pair<Integer, Integer> resolution, final ImageConfigurationProvider configurationProvider) {
      this.resolutions.add(resolution);
      this.providers.add(configurationProvider);

      return this.display;
    }
  }
}
