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
package me.brandonli.mcav.sandbox.command.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import javax.imageio.ImageIO;
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.image.MatImageBuffer;
import me.brandonli.mcav.media.source.SourceDetectionHelper;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.testing.Components;
import me.brandonli.mcav.sandbox.testing.StandardErrorCapture;
import me.brandonli.mcav.sandbox.testing.TestServer;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

/**
 * Tests {@link AbstractImageCommand} with a display that only records what it is asked to show.
 *
 * <p>The loading thread of the image manager is replaced by the calling thread, so every step runs before
 * {@code displayImage} returns.
 */
final class AbstractImageCommandTest {

  @TempDir
  private Path folder;

  private final ExecutorService direct = MoreExecutors.newDirectExecutorService();

  private ImageManager manager;
  private DisplayableImage display;
  private RecordingCommand command;
  private CommandSender sender;
  private HttpServer server;

  /**
   * A command whose display is a mock and that remembers what it was asked to create.
   */
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
    public DisplayableImage createImage(final Pair<Integer, Integer> resolution, final ImageConfigurationProvider configProvider) {
      this.resolutions.add(resolution);
      this.providers.add(configProvider);
      return this.display;
    }
  }

  @BeforeEach
  void createCommand() {
    TestServer.reset();
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
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

  private static byte[] encode(final String format) throws IOException {
    final BufferedImage image = new BufferedImage(6, 4, BufferedImage.TYPE_INT_RGB);
    image.setRGB(0, 0, 0xFF0000);
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, format, output);
    return output.toByteArray();
  }

  private Path writeImage(final String name, final String format) throws IOException {
    final Path file = this.folder.resolve(name);
    final byte[] bytes = encode(format);
    Files.write(file, bytes);
    return file;
  }

  private String serveImage() throws IOException {
    final String path = "/picture.png";
    final byte[] bytes = encode("png");
    final InetAddress loopback = InetAddress.getLoopbackAddress();
    final InetSocketAddress address = new InetSocketAddress(loopback, 0);
    this.server = HttpServer.create(address, 0);
    this.server.createContext(path, exchange -> {
        exchange.sendResponseHeaders(200, bytes.length);
        try (final OutputStream output = exchange.getResponseBody()) {
          output.write(bytes);
        }
      });
    this.server.start();
    final InetSocketAddress bound = this.server.getAddress();
    final int port = bound.getPort();
    return "http://127.0.0.1:" + port + path;
  }

  private ImageBuffer verifyShown() {
    final ArgumentCaptor<ImageBuffer> captor = ArgumentCaptor.forClass(ImageBuffer.class);
    verify(this.manager).setCurrentImage(captor.capture());
    final ImageBuffer image = captor.getValue();
    verify(this.manager).releaseImage(true);
    verify(this.manager).setImage(this.display);
    verify(this.display).displayImage(image);
    return image;
  }

  private void assertNothingShown() {
    verify(this.manager, never()).setImage(any());
    verify(this.display, never()).displayImage(any());
  }

  @Test
  void loadsAnImageFileAndShowsIt() throws IOException {
    final Path file = this.writeImage("picture.png", "png");
    final String mrl = file.toString();
    this.command.displayImage(_ -> "configuration", this.sender, "4x2", mrl);
    try (final ImageBuffer image = this.verifyShown()) {
      final int width = image.getWidth();
      final int height = image.getHeight();
      assertEquals(6, width);
      assertEquals(4, height);
    }

    final Pair<Integer, Integer> resolution = this.command.resolutions.getFirst();
    final int resolutionWidth = resolution.getFirst();
    final int resolutionHeight = resolution.getSecond();
    assertEquals(4, resolutionWidth);
    assertEquals(2, resolutionHeight);
    final AbstractImageCommand.ImageConfigurationProvider provider = this.command.providers.getFirst();
    final Object configuration = provider.buildConfiguration(resolution);
    assertEquals("configuration", configuration);
    final List<Component> messages = Components.received(this.sender);
    final Component start = Message.LOAD_IMAGE_START.build();
    final Component done = Message.LOAD_IMAGE.build();
    final List<Component> expected = List.of(start, done);
    assertEquals(expected, messages);
  }

  @Test
  void downloadsAnImageAndShowsIt() throws IOException {
    final String url = this.serveImage();
    this.command.displayImage(_ -> "configuration", this.sender, "8x8", url);
    try (final ImageBuffer image = this.verifyShown()) {
      final int width = image.getWidth();
      assertEquals(6, width);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = { false, true })
  void loadsPathsWithSpacesWithOrWithoutSurroundingQuotes(final boolean quoted) throws IOException {
    final Path file = this.writeImage("my picture.png", "png");
    final String path = file.toString();
    final String mrl = quoted ? '"' + path + '"' : path;
    this.command.displayImage(_ -> "configuration", this.sender, "8x8", mrl);
    final ImageBuffer image = this.verifyShown();
    final int[] pixels = image.getPixels();
    assertEquals(0xFFFF0000, pixels[0]);
  }

  @Test
  void downloadsAnImageFromAQuotedUrl() throws IOException {
    final String url = this.serveImage();
    final String mrl = '"' + url + '"';
    this.command.displayImage(_ -> "configuration", this.sender, "8x8", mrl);
    final ImageBuffer image = this.verifyShown();
    final int[] pixels = image.getPixels();
    assertEquals(0xFFFF0000, pixels[0]);
  }

  @ParameterizedTest
  @ValueSource(strings = { "", "\"", "\"\"", "\"missing.png", "missing.png\"", "\"\"missing.png\"\"" })
  void rejectsEmptyOrMalformedQuotedSources(final String mrl) {
    this.command.displayImage(_ -> "configuration", this.sender, "8x8", mrl);
    this.assertNothingShown();
    final List<Component> messages = Components.received(this.sender);
    final Component invalid = Message.UNSUPPORTED_MRL.build();
    final List<Component> expected = List.of(invalid);
    assertEquals(expected, messages);
  }

  @Test
  void rejectsEmptyQuotedMrlEvenWhenRawQuotesResolveToAnImage() throws IOException {
    final Path imagePath = this.writeImage("literal-quotes.png", "png");
    final FileSource imageSource = FileSource.path(imagePath);
    // A Unix file can literally be named two quote characters. Model that lookup without creating a shared
    // working-directory file, since PIT minions run concurrently. Delimiters still represent an empty MRL.
    try (
      final MockedConstruction<SourceDetectionHelper> detection = Mockito.mockConstruction(SourceDetectionHelper.class, (helper, _) ->
        when(helper.detectSource("\"\"")).thenReturn(Optional.of(imageSource))
      )
    ) {
      this.command.displayImage(_ -> "configuration", this.sender, "8x8", "\"\"");
      this.assertNothingShown();
      final List<Component> messages = Components.received(this.sender);
      final Component invalid = Message.UNSUPPORTED_MRL.build();
      assertEquals(List.of(invalid), messages);
      final List<SourceDetectionHelper> lookups = detection.constructed();
      assertEquals(0, lookups.size(), "an empty quoted MRL must be rejected before consulting the filesystem");
    }
  }

  @Test
  void refusesInvalidResolutions() throws IOException {
    final Path file = this.writeImage("picture.png", "png");
    final String mrl = file.toString();
    this.command.displayImage(_ -> "configuration", this.sender, "wide", mrl);
    final List<Component> messages = Components.received(this.sender);
    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    final List<Component> expected = List.of(error);
    assertEquals(expected, messages);
    verify(this.manager, never()).getService();
    this.assertNothingShown();
  }

  @ParameterizedTest
  @ValueSource(strings = { "not a source", "0", "mp4||input" })
  void refusesMediaThatIsNotAFileOrUrl(final String mrl) {
    this.command.displayImage(_ -> "configuration", this.sender, "4x2", mrl);
    final List<Component> messages = Components.received(this.sender);
    final Component error = Message.UNSUPPORTED_MRL.build();
    final List<Component> expected = List.of(error);
    assertEquals(expected, messages);
    this.assertNothingShown();
  }

  @Test
  void refusesAnimatedImages() throws IOException {
    final Path file = this.writeImage("animation.gif", "gif");
    final String mrl = file.toString();
    this.command.displayImage(_ -> "configuration", this.sender, "4x2", mrl);
    final List<Component> messages = Components.received(this.sender);
    final Component error = Message.UNSUPPORTED_MRL.build();
    final List<Component> expected = List.of(error);
    assertEquals(expected, messages);
    this.assertNothingShown();
  }

  @Test
  void namesTheImageThatFailedToLoadInTheLog() throws IOException {
    final Path file = this.folder.resolve("unreadable.png");
    Files.writeString(file, "this is not an image");
    final String mrl = file.toString();
    final String output;
    try (final StandardErrorCapture capture = StandardErrorCapture.start()) {
      this.command.displayImage(_ -> "configuration", this.sender, "4x2", mrl);
      output = capture.getOutput();
    }
    final boolean named = output.contains("Failed to load the image " + mrl);
    assertTrue(named, "the log of a failed image names the media it could not load");
  }

  @Test
  void reportsImagesThatFailToLoad() throws IOException {
    final Path file = this.folder.resolve("broken.png");
    Files.writeString(file, "this is not an image");
    final String mrl = file.toString();
    this.command.displayImage(_ -> "configuration", this.sender, "4x2", mrl);
    final List<Component> messages = Components.received(this.sender);
    final Component start = Message.LOAD_IMAGE_START.build();
    final Component failure = Message.UNSUPPORTED_MRL.build();
    final List<Component> expected = List.of(start, failure);
    assertEquals(expected, messages);
    this.assertNothingShown();
    verify(this.manager, never()).releaseImage(true);
  }

  @Test
  void usesTheImageManagerOfThePlugin() {
    assertSame(this.manager, this.command.manager);
  }

  @Test
  void releasesTheLoadedImageWhenThePluginWasDisabledMeanwhile() throws IOException {
    final BukkitScheduler scheduler = TestServer.scheduler();
    final IllegalPluginAccessException disabled = new IllegalPluginAccessException("disabled");
    when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenThrow(disabled);
    final Path file = this.writeImage("picture.png", "png");
    final String mrl = file.toString();
    try (final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class)) {
      this.command.displayImage(_ -> "configuration", this.sender, "4x2", mrl);
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
  void releasesAnImageWhenAnAcceptedMainThreadTaskIsCancelledByShutdown() throws IOException {
    TestServer.resetWithDeferredTasks();
    final Path file = this.writeImage("queued.png", "png");
    final String mrl = file.toString();
    try (final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class)) {
      this.command.displayImage(_ -> "configuration", this.sender, "4x2", mrl);
      final List<MatImageBuffer> constructed = buffers.constructed();
      final MatImageBuffer image = constructed.getFirst();
      verify(image, never()).release();
      this.manager.shutdown();
      verify(image, times(1)).release();
      // Even if the accepted task runs after shutdown instead of being discarded, it cannot revive the image.
      TestServer.runPendingTasks();
      verify(image, times(1)).release();
      this.assertNothingShown();
    }
  }

  @Test
  void discardsTheOlderLoadedImageWhenANewerRequestIsQueued() throws IOException {
    TestServer.resetWithDeferredTasks();
    final Path file = this.writeImage("queued.png", "png");
    final String mrl = file.toString();
    try (final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class)) {
      this.command.displayImage(_ -> "first", this.sender, "4x2", mrl);
      this.command.displayImage(_ -> "second", this.sender, "8x4", mrl);
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
  void releasesTheLoadedImageWhenDisplayConfigurationFails() throws IOException {
    TestServer.resetWithDeferredTasks();
    final Path file = this.writeImage("queued.png", "png");
    final String mrl = file.toString();
    final RecordingCommand failing = spy(this.command);
    final IllegalStateException failure = new IllegalStateException("invalid display configuration");
    Mockito.doThrow(failure).when(failing).createImage(any(), any());
    try (final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class)) {
      failing.displayImage(_ -> "configuration", this.sender, "4x2", mrl);
      final List<MatImageBuffer> constructed = buffers.constructed();
      final MatImageBuffer image = constructed.getFirst();
      final IllegalStateException thrown = assertThrows(IllegalStateException.class, TestServer::runPendingTasks);
      assertSame(failure, thrown);
      verify(image, times(1)).release();
      this.assertNothingShown();
    }
  }

  @Test
  void releasesBothDisplayAndImageWhenDisplayingFails() throws IOException {
    TestServer.resetWithDeferredTasks();
    final Path file = this.writeImage("queued.png", "png");
    final String mrl = file.toString();
    final IllegalStateException failure = new IllegalStateException("failed display");
    Mockito.doThrow(failure).when(this.display).displayImage(any());
    try (final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class)) {
      this.command.displayImage(_ -> "configuration", this.sender, "4x2", mrl);
      final List<MatImageBuffer> constructed = buffers.constructed();
      final MatImageBuffer image = constructed.getFirst();
      final IllegalStateException thrown = assertThrows(IllegalStateException.class, TestServer::runPendingTasks);
      assertSame(failure, thrown);
      verify(image, times(1)).close();
      verify(this.display, times(1)).release();
    }
  }

  @Test
  void releasesALoadThatCompletesAfterItsManagerHasShutDown() throws IOException, InterruptedException {
    final ExecutorService deferred = mock(ExecutorService.class);
    final List<Runnable> work = new ArrayList<>();
    Mockito.doAnswer(invocation -> {
      final Runnable task = invocation.getArgument(0);
      work.add(task);
      return null;
    })
      .when(deferred)
      .execute(any(Runnable.class));
    when(deferred.awaitTermination(Mockito.anyLong(), any(java.util.concurrent.TimeUnit.class))).thenReturn(true);
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    final ImageManager deferredManager = new ImageManager(plugin, deferred);
    when(plugin.getImageManager()).thenReturn(deferredManager);
    final RecordingCommand deferredCommand = new RecordingCommand(plugin, this.display);
    final Path file = this.writeImage("late.png", "png");
    final String mrl = file.toString();
    deferredCommand.displayImage(_ -> "configuration", this.sender, "4x2", mrl);
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

  @Test
  void propagatesFatalConfigurationFailureWithoutAttemptingNativeCleanup() throws IOException {
    TestServer.resetWithDeferredTasks();
    final Path file = this.writeImage("queued.png", "png");
    final String mrl = file.toString();
    final RecordingCommand failing = spy(this.command);
    final OutOfMemoryError fatal = new OutOfMemoryError("fatal configuration failure");
    Mockito.doThrow(fatal).when(failing).createImage(any(), any());
    try (final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class)) {
      failing.displayImage(_ -> "configuration", this.sender, "4x2", mrl);
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
  void reportsARejectedLoadWithoutAllocatingAnImage() throws IOException {
    final Path file = this.writeImage("rejected.png", "png");
    final String mrl = file.toString();
    this.manager.shutdown();
    try (final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class)) {
      this.command.displayImage(_ -> "configuration", this.sender, "4x2", mrl);
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
  void failedSchedulingDiscardsTheImageAndPreservesCleanupFailures(final int cleanupMode) throws IOException {
    final Path file = this.writeImage("schedule-failure.png", "png");
    final String mrl = file.toString();
    final IllegalStateException primary = new IllegalStateException("scheduler rejected the loaded image");
    final RuntimeException cleanup = cleanupMode == 1 ? primary : new IllegalArgumentException("image release failed");
    final BukkitScheduler scheduler = TestServer.scheduler();
    when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenThrow(primary);
    final String output;
    try (
      final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class, (image, context) -> {
        if (cleanupMode != 0) {
          Mockito.doThrow(cleanup).when(image).release();
        }
      });
      final StandardErrorCapture capture = StandardErrorCapture.start()
    ) {
      this.command.displayImage(_ -> "configuration", this.sender, "4x2", mrl);
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
    final boolean reported = output.contains("scheduler rejected the loaded image");
    assertTrue(reported, "the callback reports the scheduling failure after releasing the image");
    this.assertNothingShown();
  }

  @ParameterizedTest
  @ValueSource(booleans = { false, true })
  void failedConfigurationPreservesItsPrimaryFailureWhenImageCleanupAlsoFails(final boolean sameFailure) throws IOException {
    TestServer.resetWithDeferredTasks();
    final Path file = this.writeImage("cleanup-failure.png", "png");
    final String mrl = file.toString();
    final RecordingCommand failing = spy(this.command);
    final IllegalStateException primary = new IllegalStateException("invalid image configuration");
    final RuntimeException cleanup = sameFailure ? primary : new IllegalArgumentException("image release failed");
    Mockito.doThrow(primary).when(failing).createImage(any(), any());
    try (
      final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class, (image, context) -> {
        Mockito.doThrow(cleanup).when(image).release();
      })
    ) {
      failing.displayImage(_ -> "configuration", this.sender, "4x2", mrl);
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
  void refusesNullArguments() {
    final AbstractImageCommand.ImageConfigurationProvider configuration = _ -> "configuration";
    assertThrows(NullPointerException.class, () -> new RecordingCommand(null, this.display));
    assertThrows(NullPointerException.class, () -> this.command.displayImage(null, this.sender, "4x2", "picture.png"));
    assertThrows(NullPointerException.class, () -> this.command.displayImage(configuration, null, "4x2", "picture.png"));
    assertThrows(NullPointerException.class, () -> this.command.displayImage(configuration, this.sender, null, "picture.png"));
    assertThrows(NullPointerException.class, () -> this.command.displayImage(configuration, this.sender, "4x2", null));
    verify(this.manager, never()).getService();
  }

  @Test
  void propagatesFatalSchedulingFailureWithoutDiscardingLoadedImage() throws IOException {
    final Path file = this.writeImage("schedule-fatal.png", "png");
    final String mrl = file.toString();
    final OutOfMemoryError fatal = new OutOfMemoryError("fatal scheduling sentinel");
    final BukkitScheduler scheduler = TestServer.scheduler();
    when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenThrow(fatal);

    final String output;
    try (
      final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class);
      final StandardErrorCapture capture = StandardErrorCapture.start()
    ) {
      this.command.displayImage(_ -> "configuration", this.sender, "4x2", mrl);
      output = capture.getOutput();
      verify(this.manager, never()).discardLoaded(Mockito.anyLong());
      final List<MatImageBuffer> constructed = buffers.constructed();
      final MatImageBuffer image = constructed.getFirst();
      verify(image, never()).release();
    }
    final boolean reported = output.contains("fatal scheduling sentinel");
    assertTrue(reported, "the fatal scheduling error is logged by the background callback handler");
    this.assertNothingShown();
  }

  @Test
  void propagatesFatalDiscardFailureDuringSchedulingFailureRecovery() throws IOException {
    final Path file = this.writeImage("discard-fatal.png", "png");
    final String mrl = file.toString();
    final IllegalStateException primary = new IllegalStateException("scheduling rejected");
    final OutOfMemoryError fatal = new OutOfMemoryError("fatal discard sentinel");
    final BukkitScheduler scheduler = TestServer.scheduler();
    when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenThrow(primary);

    final String output;
    try (
      final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class, (image, _) -> {
        Mockito.doThrow(fatal).doNothing().when(image).release();
      });
      final StandardErrorCapture capture = StandardErrorCapture.start()
    ) {
      this.command.displayImage(_ -> "configuration", this.sender, "4x2", mrl);
      output = capture.getOutput();
      verify(this.manager, times(1)).discardLoaded(Mockito.anyLong());
      final List<MatImageBuffer> constructed = buffers.constructed();
      final MatImageBuffer image = constructed.getFirst();
      verify(image, times(1)).release();
    }
    final Throwable[] suppressed = primary.getSuppressed();
    assertEquals(0, suppressed.length, "fatal cleanup failure must not be suppressed onto recoverable primary exception");
    final boolean reported = output.contains("fatal discard sentinel");
    assertTrue(reported, "the fatal discard failure is logged as the escaping callback error");
    this.assertNothingShown();
  }

  @Test
  void propagatesFatalImageCleanupFailureWhenDisplayConfigurationFails() throws IOException {
    TestServer.resetWithDeferredTasks();
    final Path file = this.writeImage("config-cleanup-fatal.png", "png");
    final String mrl = file.toString();
    final RecordingCommand failing = spy(this.command);
    final IllegalStateException primary = new IllegalStateException("display configuration failed");
    final OutOfMemoryError fatal = new OutOfMemoryError("fatal cleanup sentinel");
    Mockito.doThrow(primary).when(failing).createImage(any(), any());
    try (
      final MockedConstruction<MatImageBuffer> buffers = Mockito.mockConstruction(MatImageBuffer.class, (image, _) -> {
        Mockito.doThrow(fatal).doNothing().when(image).release();
      })
    ) {
      failing.displayImage(_ -> "configuration", this.sender, "4x2", mrl);
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
}
