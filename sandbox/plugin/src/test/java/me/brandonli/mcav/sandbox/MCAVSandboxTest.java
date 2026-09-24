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
package me.brandonli.mcav.sandbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import me.brandonli.mcav.MCAV;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.browser.BrowserModule;
import me.brandonli.mcav.bukkit.BukkitModule;
import me.brandonli.mcav.bukkit.utils.versioning.ServerEnvironment;
import me.brandonli.mcav.bukkit.utils.versioning.UnsupportedServerVersionException;
import me.brandonli.mcav.sandbox.audio.AudioProvider;
import me.brandonli.mcav.sandbox.command.AnnotationParserHandler;
import me.brandonli.mcav.sandbox.command.image.ImageManager;
import me.brandonli.mcav.sandbox.command.video.VideoPlayerManager;
import me.brandonli.mcav.sandbox.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.sandbox.listener.JukeBoxListener;
import me.brandonli.mcav.sandbox.testing.TestCommandManager;
import me.brandonli.mcav.sandbox.testing.TestServer;
import me.brandonli.mcav.sandbox.utils.IOUtils;
import me.brandonli.mcav.svc.SVCModule;
import me.brandonli.mcav.vm.VMModule;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.paper.LegacyPaperCommandManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockSettings;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

/**
 * Tests {@link MCAVSandbox}. The plugin is a mock that runs its real methods, because Paper only lets its own class
 * loader construct plugins; the library and the Paper command manager are mocked.
 */
final class MCAVSandboxTest {

  @TempDir
  private Path folder;

  private MCAVSandbox sandbox;
  private ComponentLogger logger;
  private MCAVApi api;
  private BukkitModule bukkitModule;
  private VMModule vmModule;
  private TestCommandManager commands;
  private MockedStatic<JavaPlugin> javaPlugins;
  private MockedStatic<MCAV> libraries;

  private MockedStatic<LegacyPaperCommandManager<CommandSender>> paperManagers;

  @BeforeEach
  void createPlugin() {
    final Server server = TestServer.reset();
    final MockSettings settings = withSettings();
    settings.defaultAnswer(Mockito.CALLS_REAL_METHODS);
    this.sandbox = mock(MCAVSandbox.class, settings);
    this.logger = mock(ComponentLogger.class);
    this.mockPluginEnvironment(server);
    this.mockLibrary();

    this.commands = new TestCommandManager();
    final LegacyPaperCommandManager<CommandSender> paperManager = this.commands.asPaperManager();
    this.javaPlugins = Mockito.mockStatic(JavaPlugin.class);
    this.javaPlugins.when(() -> JavaPlugin.getPlugin(MCAVSandbox.class)).thenReturn(this.sandbox);
    this.paperManagers = Mockito.mockStatic();
    this.paperManagers.when(() -> LegacyPaperCommandManager.createNative(eq(this.sandbox), notNull())).thenReturn(paperManager);
  }

  private void mockPluginEnvironment(final Server server) {
    final File folderFile = this.folder.toFile();
    doReturn(this.logger).when(this.sandbox).getComponentLogger();
    doReturn(folderFile).when(this.sandbox).getDataFolder();
    doReturn(server).when(this.sandbox).getServer();
    doAnswer(this::copyResource).when(this.sandbox).saveResource(anyString(), anyBoolean());
  }

  // stands in for JavaPlugin#saveResource, which reads the jar of the plugin
  private @Nullable Object copyResource(final InvocationOnMock invocation) throws IOException {
    final String name = invocation.getArgument(0);
    final Path target = this.folder.resolve(name);
    try (final InputStream stream = IOUtils.getResourceAsStream(name)) {
      Files.copy(stream, target);
    }
    return null;
  }

  private void mockLibrary() {
    this.api = mock(MCAVApi.class);
    this.bukkitModule = mock(BukkitModule.class);
    this.vmModule = mock(VMModule.class);
    when(this.api.getModule(BukkitModule.class)).thenReturn(this.bukkitModule);
    when(this.api.getModule(VMModule.class)).thenReturn(this.vmModule);
    this.libraries = Mockito.mockStatic(MCAV.class);
    this.libraries.when(MCAV::api).thenReturn(this.api);
  }

  @AfterEach
  void closeMocks() {
    this.javaPlugins.close();
    this.libraries.close();
    this.paperManagers.close();
  }

  @Test
  void refusesItsPartsBeforeItIsEnabled() {
    final IllegalStateException library = assertThrows(IllegalStateException.class, this.sandbox::getMCAV);
    final IllegalStateException configuration = assertThrows(IllegalStateException.class, this.sandbox::getConfiguration);
    final IllegalStateException videos = assertThrows(IllegalStateException.class, this.sandbox::getVideoPlayerManager);
    final IllegalStateException audio = assertThrows(IllegalStateException.class, this.sandbox::getAudioProvider);
    final IllegalStateException images = assertThrows(IllegalStateException.class, this.sandbox::getImageManager);
    final String libraryMessage = library.getMessage();
    final String configurationMessage = configuration.getMessage();
    final String videosMessage = videos.getMessage();
    final String audioMessage = audio.getMessage();
    final String imagesMessage = images.getMessage();
    assertEquals("The library is not available before the plugin is enabled", libraryMessage);
    assertEquals("The configuration is not available before the plugin is enabled", configurationMessage);
    assertEquals("The video player manager is not available before the plugin is enabled", videosMessage);
    assertEquals("The audio provider is not available before the plugin is enabled", audioMessage);
    assertEquals("The image manager is not available before the plugin is enabled", imagesMessage);
    final boolean qemu = this.sandbox.isQemuInstalled();
    assertFalse(qemu);
  }

  @Test
  void disablesCleanlyWhenItWasNeverEnabled() {
    this.sandbox.onDisable();
    verify(this.api, never()).release();
  }

  @Test
  void enablesTheLibraryTheConfigurationTheCommandsAndTheListener() {
    when(this.vmModule.isQemuInstalled()).thenReturn(true);
    this.sandbox.onEnable();
    this.assertLibraryInstalled();
    this.assertConfigurationAndManagersCreated();
    this.assertCommandsAndListenerRegistered();

    final VideoPlayerManager videoManager = this.sandbox.getVideoPlayerManager();
    final ImageManager imageManager = this.sandbox.getImageManager();
    this.sandbox.onDisable();
    verify(this.api).release();
    final ExecutorService videoWorker = videoManager.getService();
    final ExecutorService imageWorker = imageManager.getService();
    final boolean videoStopped = videoWorker.isShutdown();
    final boolean imageStopped = imageWorker.isShutdown();
    assertTrue(videoStopped);
    assertTrue(imageStopped);
  }

  private void assertLibraryInstalled() {
    verify(this.api).install(BukkitModule.class, BrowserModule.class, VMModule.class, SVCModule.class);
    verify(this.bukkitModule).inject(this.sandbox);
    verify(this.logger, never()).warn(anyString());
    final MCAVApi library = this.sandbox.getMCAV();
    assertSame(this.api, library);
    final boolean qemu = this.sandbox.isQemuInstalled();
    assertTrue(qemu);
  }

  private void assertConfigurationAndManagersCreated() {
    final PluginDataConfigurationMapper configuration = this.sandbox.getConfiguration();
    final String host = configuration.getHttpHostName();
    assertEquals("localhost", host);
    final AudioProvider audioProvider = this.sandbox.getAudioProvider();
    final VideoPlayerManager videoManager = this.sandbox.getVideoPlayerManager();
    final ImageManager imageManager = this.sandbox.getImageManager();
    assertNotNull(audioProvider);
    assertNotNull(videoManager);
    assertNotNull(imageManager);
    final Path iso = this.folder.resolve("iso");
    final boolean isoFolder = Files.isDirectory(iso);
    assertTrue(isoFolder);
  }

  private void assertCommandsAndListenerRegistered() {
    final Set<String> syntaxes = this.commands.syntaxes();
    final int count = syntaxes.size();
    assertEquals(25, count);
    final PluginManager pluginManager = TestServer.pluginManager();
    verify(pluginManager).registerEvents(any(JukeBoxListener.class), eq(this.sandbox));
  }

  @Test
  void stopsTheListenerTheCommandsAndTheAudioWhenDisabled() {
    try (
      final MockedConstruction<JukeBoxListener> listeners = Mockito.mockConstruction(JukeBoxListener.class);
      final MockedConstruction<AnnotationParserHandler> handlers = Mockito.mockConstruction(AnnotationParserHandler.class);
      final MockedConstruction<AudioProvider> providers = Mockito.mockConstruction(AudioProvider.class)
    ) {
      this.sandbox.onEnable();
      this.sandbox.onDisable();

      final List<JukeBoxListener> constructedListeners = listeners.constructed();
      final List<AnnotationParserHandler> constructedHandlers = handlers.constructed();
      final List<AudioProvider> constructedProviders = providers.constructed();
      assertEquals(1, constructedListeners.size());
      assertEquals(1, constructedHandlers.size());
      assertEquals(1, constructedProviders.size());

      final JukeBoxListener listener = constructedListeners.getFirst();
      final AnnotationParserHandler handler = constructedHandlers.getFirst();
      final AudioProvider provider = constructedProviders.getFirst();
      verify(listener).shutdown();
      verify(handler).shutdownCommands();
      verify(provider).shutdown();
    }
  }

  @Test
  void keepsOwnershipWhenCommandRegistrationFailsPartwayThrough() {
    final IllegalStateException failure = new IllegalStateException("partial registration");
    try (
      final MockedConstruction<AnnotationParserHandler> handlers = Mockito.mockConstruction(AnnotationParserHandler.class, (handler, _) ->
        doThrow(failure).when(handler).registerCommands()
      )
    ) {
      final IllegalStateException thrown = assertThrows(IllegalStateException.class, this.sandbox::onEnable);
      assertSame(failure, thrown);
      this.sandbox.onDisable();
      final List<AnnotationParserHandler> constructed = handlers.constructed();
      final AnnotationParserHandler handler = constructed.getFirst();
      verify(handler).shutdownCommands();
      verify(this.api).release();
    }
  }

  @Test
  void keepsOwnershipWhenListenerStartupFails() {
    final IllegalStateException failure = new IllegalStateException("partial listener startup");
    try (
      final MockedConstruction<JukeBoxListener> listeners = Mockito.mockConstruction(JukeBoxListener.class, (listener, _) ->
        doThrow(failure).when(listener).start()
      )
    ) {
      final IllegalStateException thrown = assertThrows(IllegalStateException.class, this.sandbox::onEnable);
      assertSame(failure, thrown);
      this.sandbox.onDisable();
      final List<JukeBoxListener> constructed = listeners.constructed();
      final JukeBoxListener listener = constructed.getFirst();
      verify(listener).shutdown();
      verify(this.api).release();
    }
  }

  @Test
  void attemptsEveryOwnedCleanupOnceEvenWhenTheFirstTwoFail() {
    final IllegalStateException first = new IllegalStateException("video cleanup");
    final IllegalArgumentException second = new IllegalArgumentException("image cleanup");
    try (
      final MockedConstruction<VideoPlayerManager> videos = Mockito.mockConstruction(VideoPlayerManager.class, (manager, _) ->
        doThrow(first).when(manager).shutdown()
      );
      final MockedConstruction<ImageManager> images = Mockito.mockConstruction(ImageManager.class, (manager, _) ->
        doThrow(second).when(manager).shutdown()
      );
      final MockedConstruction<JukeBoxListener> listeners = Mockito.mockConstruction(JukeBoxListener.class);
      final MockedConstruction<AnnotationParserHandler> handlers = Mockito.mockConstruction(AnnotationParserHandler.class);
      final MockedConstruction<AudioProvider> providers = Mockito.mockConstruction(AudioProvider.class)
    ) {
      this.sandbox.onEnable();
      final IllegalStateException thrown = assertThrows(IllegalStateException.class, this.sandbox::onDisable);
      assertSame(first, thrown);
      final Throwable[] suppressed = thrown.getSuppressed();
      assertArrayEquals(new Throwable[] { second }, suppressed);
      this.sandbox.onDisable();
      final List<VideoPlayerManager> videoManagers = videos.constructed();
      final List<ImageManager> imageManagers = images.constructed();
      final List<JukeBoxListener> createdListeners = listeners.constructed();
      final List<AnnotationParserHandler> createdHandlers = handlers.constructed();
      final List<AudioProvider> audioProviders = providers.constructed();
      final VideoPlayerManager videoManager = videoManagers.getFirst();
      final ImageManager imageManager = imageManagers.getFirst();
      final JukeBoxListener listener = createdListeners.getFirst();
      final AnnotationParserHandler handler = createdHandlers.getFirst();
      final AudioProvider provider = audioProviders.getFirst();
      verify(videoManager, times(1)).shutdown();
      verify(imageManager, times(1)).shutdown();
      verify(listener, times(1)).shutdown();
      verify(handler, times(1)).shutdownCommands();
      verify(provider, times(1)).shutdown();
      verify(this.api, times(1)).release();
    }
  }

  @Test
  void logsTheStartAndHowLongLoadingTook() {
    this.sandbox.onEnable();

    final ArgumentCaptor<Object> duration = ArgumentCaptor.forClass(Object.class);
    verify(this.logger).info("Loading MCAV");
    verify(this.logger).info(eq("MCAV loaded in {} ms"), duration.capture());
    final Object logged = duration.getValue();
    final long milliseconds = (Long) logged;
    // a sum of two timestamps instead of their difference would be billions of milliseconds
    final boolean plausible = milliseconds >= 0 && milliseconds < 600_000;
    assertTrue(plausible, "the log reports how long loading took, which was " + milliseconds + " ms");
    this.sandbox.onDisable();
  }

  @Test
  void warnsWhenQemuIsNotInstalled() {
    this.sandbox.onEnable();
    verify(this.logger).warn("QEMU is not installed, virtual machines will not be available");
    final boolean qemu = this.sandbox.isQemuInstalled();
    assertFalse(qemu);
    this.sandbox.onDisable();
  }

  @Test
  void disablesItselfWithOneErrorOnAnUnsupportedServerVersion() {
    final Server server = TestServer.server();
    when(server.getMinecraftVersion()).thenReturn("1.20.1");
    final UnsupportedServerVersionException failure = assertThrows(
      UnsupportedServerVersionException.class,
      ServerEnvironment::checkSupported
    );
    doThrow(failure).when(this.bukkitModule).inject(this.sandbox);

    this.sandbox.onEnable();

    final String reason = "MCAV only supports Minecraft 26.2, but the server is running 1.20.1!";
    this.assertDisabledItselfWithOneError(reason, "run MCAV on a Minecraft 26.2 server");
    assertThrows(IllegalStateException.class, this.sandbox::getConfiguration, "nothing after the library is loaded");
    this.sandbox.onDisable();
    verify(this.api).release();
  }

  @Test
  void disablesItselfWithOneErrorWhenVoiceChatIsEnabledButMissing() throws IOException {
    final Path config = this.folder.resolve("config.yml");
    Files.writeString(config, "simple-voice-chat:\n  enabled: true\n");

    this.sandbox.onEnable();

    final String reason = "Simple Voice Chat audio is enabled, but the voicechat plugin is not installed";
    final String remedy = "install the Simple Voice Chat plugin or set simple-voice-chat.enabled to false in config.yml";
    this.assertDisabledItselfWithOneError(reason, remedy);
    final AudioProvider audioProvider = this.sandbox.getAudioProvider();
    assertNotNull(audioProvider);
    assertThrows(IllegalStateException.class, this.sandbox::getVideoPlayerManager, "nothing after the audio is created");
    this.sandbox.onDisable();
    verify(this.api).release();
  }

  private void assertDisabledItselfWithOneError(final String reason, final String remedy) {
    verify(this.logger).error("MCAV cannot be enabled: {} ({})", reason, remedy);
    verify(this.logger).error(anyString(), any(), any());
    final PluginManager pluginManager = TestServer.pluginManager();
    verify(pluginManager).disablePlugin(this.sandbox);
  }

  @Test
  void letsUnexpectedFailuresEscapeWithoutDisablingItself() {
    final IllegalStateException failure = new IllegalStateException("the packet listener failed");
    doThrow(failure).when(this.bukkitModule).inject(this.sandbox);

    final IllegalStateException exception = assertThrows(IllegalStateException.class, this.sandbox::onEnable);

    assertSame(failure, exception, "an unexpected failure is not turned into a setup problem");
    final PluginManager pluginManager = TestServer.pluginManager();
    verify(pluginManager, never()).disablePlugin(this.sandbox);
    verify(this.logger, never()).error(anyString(), any(), any());
    this.sandbox.onDisable();
    verify(this.api).release();
  }

  @Test
  void releasesTheLibraryWhenInstallingItsModulesFails() {
    final IllegalStateException failure = new IllegalStateException("a module failed to start");
    doThrow(failure).when(this.api).install(BukkitModule.class, BrowserModule.class, VMModule.class, SVCModule.class);
    final IllegalStateException exception = assertThrows(IllegalStateException.class, this.sandbox::onEnable);
    assertSame(failure, exception);
    this.sandbox.onDisable();
    verify(this.api).release();
  }
}
