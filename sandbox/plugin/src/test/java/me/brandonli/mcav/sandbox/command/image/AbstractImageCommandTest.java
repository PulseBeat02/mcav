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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import java.util.concurrent.ExecutorService;
import javax.imageio.ImageIO;
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.image.MatImageBuffer;
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
    this.manager = mock(ImageManager.class);
    when(plugin.getImageManager()).thenReturn(this.manager);
    when(this.manager.getService()).thenReturn(this.direct);
    this.display = mock(DisplayableImage.class);
    this.command = new RecordingCommand(plugin, this.display);
    this.sender = mock(CommandSender.class);
  }

  @AfterEach
  void stopServerAndExecutor() {
    if (this.server != null) {
      this.server.stop(0);
    }
    this.direct.shutdown();
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
    final boolean named = output.contains(mrl);
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
  void refusesNullArguments() {
    final AbstractImageCommand.ImageConfigurationProvider configuration = _ -> "configuration";
    assertThrows(NullPointerException.class, () -> new RecordingCommand(null, this.display));
    assertThrows(NullPointerException.class, () -> this.command.displayImage(null, this.sender, "4x2", "picture.png"));
    assertThrows(NullPointerException.class, () -> this.command.displayImage(configuration, null, "4x2", "picture.png"));
    assertThrows(NullPointerException.class, () -> this.command.displayImage(configuration, this.sender, null, "picture.png"));
    assertThrows(NullPointerException.class, () -> this.command.displayImage(configuration, this.sender, "4x2", null));
    verify(this.manager, never()).getService();
  }
}
