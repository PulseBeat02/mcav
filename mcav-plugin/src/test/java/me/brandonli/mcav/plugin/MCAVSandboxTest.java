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
package me.brandonli.mcav.plugin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

import io.papermc.paper.InternalAPIBridge;
import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import io.papermc.paper.plugin.provider.classloader.PluginClassLoaderGroup;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import me.brandonli.mcav.MCAV;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.browser.BrowserModule;
import me.brandonli.mcav.bukkit.BukkitModule;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Pool;
import me.brandonli.mcav.bukkit.utils.versioning.ServerEnvironment;
import me.brandonli.mcav.bukkit.utils.versioning.UnsupportedServerVersionException;
import me.brandonli.mcav.plugin.audio.AudioProvider;
import me.brandonli.mcav.plugin.command.AnnotationParserHandler;
import me.brandonli.mcav.plugin.command.image.ImageManager;
import me.brandonli.mcav.plugin.command.video.Mcv2Support;
import me.brandonli.mcav.plugin.command.video.VideoPlayerManager;
import me.brandonli.mcav.plugin.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.plugin.listener.JukeboxListener;
import me.brandonli.mcav.plugin.listener.OnlinePlayers;
import me.brandonli.mcav.plugin.testing.TestCommandManager;
import me.brandonli.mcav.plugin.testing.TestServer;
import me.brandonli.mcav.plugin.utils.IoUtils;
import me.brandonli.mcav.vm.VmModule;
import me.brandonli.mcav.voicechat.SvcModule;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bytedeco.javacpp.Pointer;
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

// the plugin is a mock running its real methods: only Paper's class loader may construct plugins
final class MCAVSandboxTest {

  private static final String CONFIGURATION_FILE_NAME = "config.yml";
  private static final String ISO_FOLDER_NAME = "iso";
  private static final String NATIVES_FOLDER_NAME = "natives";

  private static final String PLUGIN_CLASS_NAME = MCAVSandbox.class.getName();

  @TempDir
  private Path folder;

  private MCAVSandbox sandbox;

  private ComponentLogger logger;

  private MCAVApi api;

  private BukkitModule bukkitModule;

  private VmModule vmModule;

  private BrowserModule browserModule;

  private TestCommandManager commands;

  private MockedStatic<JavaPlugin> javaPlugins;

  private MockedStatic<MCAV> libraries;

  private MockedStatic<LegacyPaperCommandManager<CommandSender>> paperManagers;

  // the mocked module injects no plugin, which the MCV2 pack server listens for the players with
  private MockedStatic<BukkitModule> bukkitModules;

  // a test must not stop the deallocator thread of the test JVM
  private MockedStatic<Pointer> pointers;

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
    final LegacyPaperCommandManager<CommandSender> paperManager = this.commands.createPaperManager();
    this.javaPlugins = Mockito.mockStatic(JavaPlugin.class);
    this.javaPlugins.when(() -> JavaPlugin.getPlugin(MCAVSandbox.class)).thenReturn(this.sandbox);
    this.paperManagers = Mockito.mockStatic();
    this.paperManagers.when(() -> LegacyPaperCommandManager.createNative(eq(this.sandbox), notNull())).thenReturn(paperManager);
  }

  @AfterEach
  void closeMocks() {
    this.javaPlugins.close();
    this.libraries.close();
    this.paperManagers.close();
    this.bukkitModules.close();
    this.pointers.close();
  }

  @Test
  void shouldCreateNothingButThePluginWhenPaperConstructsIt() throws ReflectiveOperationException, IOException {
    final InternalAPIBridge bridge = mock(InternalAPIBridge.class);
    final ProtectionDomain domain = MCAVSandbox.class.getProtectionDomain();
    final CodeSource source = domain.getCodeSource();
    final URL classes = source.getLocation();
    try (
      final MockedStatic<InternalAPIBridge> bridges = Mockito.mockStatic(InternalAPIBridge.class);
      final FolderPluginClassLoader loader = new FolderPluginClassLoader(classes)
    ) {
      bridges.when(InternalAPIBridge::get).thenReturn(bridge);
      final Class<?> pluginClass = loader.loadClass(PLUGIN_CLASS_NAME);
      final Constructor<?> constructor = pluginClass.getConstructor();
      final Object plugin = constructor.newInstance();
      final @Nullable JavaPlugin initialized = loader.getPlugin();
      assertSame(plugin, initialized);

      final Method getter = pluginClass.getMethod("getMCAV");
      final InvocationTargetException invocation = assertThrows(InvocationTargetException.class, () -> getter.invoke(plugin));
      final Throwable cause = invocation.getCause();
      final IllegalStateException refusal = assertInstanceOf(IllegalStateException.class, cause);
      final String message = refusal.getMessage();
      assertEquals("The library is only available while the plugin is enabled!", message);
    }
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
    try (final InputStream stream = IoUtils.getResourceAsStream(name)) {
      Files.copy(stream, target);
    }
    return null;
  }

  private void mockLibrary() {
    this.api = mock(MCAVApi.class);
    this.bukkitModule = mock(BukkitModule.class);
    this.vmModule = mock(VmModule.class);
    this.browserModule = mock(BrowserModule.class);
    when(this.api.getModule(BukkitModule.class)).thenReturn(this.bukkitModule);
    when(this.api.getModule(VmModule.class)).thenReturn(this.vmModule);
    when(this.api.getModule(BrowserModule.class)).thenReturn(this.browserModule);
    this.libraries = Mockito.mockStatic(MCAV.class);
    this.libraries.when(MCAV::api).thenReturn(this.api);
    this.bukkitModules = Mockito.mockStatic(BukkitModule.class, Mockito.CALLS_REAL_METHODS);
    this.bukkitModules.when(BukkitModule::getPlugin).thenReturn(this.sandbox);
    this.pointers = Mockito.mockStatic(Pointer.class);
  }

  @Test
  void shouldRefuseItsPartsBeforeItIsEnabled() {
    final IllegalStateException library = assertThrows(IllegalStateException.class, this.sandbox::getMCAV);
    final IllegalStateException configuration = assertThrows(IllegalStateException.class, this.sandbox::getConfiguration);
    final IllegalStateException videos = assertThrows(IllegalStateException.class, this.sandbox::getVideoPlayerManager);
    final IllegalStateException audio = assertThrows(IllegalStateException.class, this.sandbox::getAudioProvider);
    final IllegalStateException images = assertThrows(IllegalStateException.class, this.sandbox::getImageManager);
    final IllegalStateException mcv2 = assertThrows(IllegalStateException.class, this.sandbox::getMcv2Support);

    final String message = mcv2.getMessage();
    assertEquals("The MCV2 support is only available while the plugin is enabled!", message);

    final IllegalStateException online = assertThrows(IllegalStateException.class, this.sandbox::getOnlinePlayers);

    final String onlineMessage = online.getMessage();
    assertEquals("The list of online players is only available while the plugin is enabled!", onlineMessage);

    final String libraryMessage = library.getMessage();
    final String configurationMessage = configuration.getMessage();
    final String videosMessage = videos.getMessage();
    final String audioMessage = audio.getMessage();
    final String imagesMessage = images.getMessage();
    assertEquals("The library is only available while the plugin is enabled!", libraryMessage);
    assertEquals("The configuration is only available while the plugin is enabled!", configurationMessage);
    assertEquals("The video player manager is only available while the plugin is enabled!", videosMessage);
    assertEquals("The audio provider is only available while the plugin is enabled!", audioMessage);
    assertEquals("The image manager is only available while the plugin is enabled!", imagesMessage);

    final boolean qemuAvailable = this.sandbox.isQemuAvailable();
    assertFalse(qemuAvailable);

    final boolean browserSupported = this.sandbox.isBrowserSupported();
    assertFalse(browserSupported);
  }

  @Test
  void shouldDisableCleanlyWhenItWasNeverEnabled() {
    this.sandbox.onDisable();
    verify(this.api, never()).release();
  }

  @Test
  void shouldStopTheJavaCppDeallocatorWhenDisabled() {
    this.sandbox.onEnable();
    this.sandbox.onDisable();
    this.pointers.verify(Pointer::interruptDeallocatorThread);
  }

  @Test
  void shouldLeaveJavaCppAloneWhenItWasNeverEnabled() {
    this.sandbox.onDisable();
    this.pointers.verify(Pointer::interruptDeallocatorThread, never());
  }

  @Test
  void shouldEnableTheLibraryTheConfigurationTheCommandsAndTheListener() {
    when(this.vmModule.isQemuAvailable()).thenReturn(true);
    when(this.browserModule.isSupported()).thenReturn(true);
    this.sandbox.onEnable();
    this.assertLibraryInstalled();
    this.assertConfigurationAndManagersCreated();
    this.assertCommandsAndListenerRegistered();

    final VideoPlayerManager videoManager = this.sandbox.getVideoPlayerManager();
    final ImageManager imageManager = this.sandbox.getImageManager();
    final OnlinePlayers online = this.sandbox.getOnlinePlayers();
    verify(TestServer.getPluginManager()).registerEvents(online, this.sandbox);

    this.sandbox.onDisable();
    verify(this.api).release();

    final ExecutorService videoWorker = videoManager.getService();
    final ExecutorService imageWorker = imageManager.getService();
    final boolean videoStopped = videoWorker.isShutdown();
    final boolean imageStopped = imageWorker.isShutdown();
    assertTrue(videoStopped);
    assertTrue(imageStopped);
  }

  @Test
  void shouldRefuseReenableBeforeStartingResourcesOfAClosedPluginLoader() {
    this.sandbox.onEnable();
    this.sandbox.onDisable();

    this.sandbox.onEnable();

    this.assertDisabledItselfWithOneError(
      "Paper cannot re-enable this plugin after it has been disabled!",
      "restart the server to enable MCAV"
    );
    verify(this.api, times(1)).install(BukkitModule.class, BrowserModule.class, VmModule.class, SvcModule.class);
    assertThrows(IllegalStateException.class, this.sandbox::getVideoPlayerManager);

    this.sandbox.onDisable();
    verify(this.api, times(1)).release();
  }

  @Test
  void shouldRefuseItsPartsButKeepItsConfigurationAfterItIsDisabled() {
    this.sandbox.onEnable();
    final PluginDataConfigurationMapper configuration = this.sandbox.getConfiguration();
    this.sandbox.onDisable();
    assertThrows(IllegalStateException.class, this.sandbox::getMCAV);
    assertThrows(IllegalStateException.class, this.sandbox::getVideoPlayerManager);
    assertThrows(IllegalStateException.class, this.sandbox::getAudioProvider);
    assertThrows(IllegalStateException.class, this.sandbox::getImageManager);
    assertThrows(IllegalStateException.class, this.sandbox::getMcv2Support);
    assertThrows(IllegalStateException.class, this.sandbox::getOnlinePlayers);

    final PluginDataConfigurationMapper kept = this.sandbox.getConfiguration();
    assertSame(configuration, kept);
  }

  private void assertLibraryInstalled() {
    verify(this.api).install(BukkitModule.class, BrowserModule.class, VmModule.class, SvcModule.class);
    verify(this.bukkitModule).inject(this.sandbox);
    verify(this.logger, never()).warn(anyString());

    final MCAVApi library = this.sandbox.getMCAV();
    assertSame(this.api, library);

    final boolean qemuAvailable = this.sandbox.isQemuAvailable();
    assertTrue(qemuAvailable);

    final boolean browserSupported = this.sandbox.isBrowserSupported();
    assertTrue(browserSupported);
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

    final Mcv2Support mcv2Support = this.sandbox.getMcv2Support();
    assertNotNull(mcv2Support);

    final Path iso = this.folder.resolve(ISO_FOLDER_NAME);
    final boolean isoFolder = Files.isDirectory(iso);
    assertTrue(isoFolder);
  }

  private void assertCommandsAndListenerRegistered() {
    final Set<String> syntaxes = this.commands.getSyntaxes();
    final int count = syntaxes.size();
    assertEquals(39, count);

    final PluginManager pluginManager = TestServer.getPluginManager();
    verify(pluginManager).registerEvents(any(JukeboxListener.class), eq(this.sandbox));
  }

  @Test
  void shouldApplyAndLogTheMcv2EncoderBudget() throws IOException {
    final String defaults;
    try (final InputStream stream = IoUtils.getResourceAsStream(CONFIGURATION_FILE_NAME)) {
      defaults = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    final boolean contained = defaults.contains("  encoder-threads: 0\n");
    assertTrue(contained);

    Files.writeString(this.folder.resolve(CONFIGURATION_FILE_NAME), defaults.replace("  encoder-threads: 0\n", "  encoder-threads: 3\n"));
    try {
      this.sandbox.onEnable();

      final int threads = Pool.shared().getThreads();
      assertEquals(3, threads);

      verify(this.logger).info("MCV2 encoders share {} of {} processors.", 3, Runtime.getRuntime().availableProcessors());

      this.sandbox.onDisable();
    } finally {
      Pool.setSharedThreads(0);
    }
  }

  @Test
  void shouldDecideTheNativeKernelsAtStartup() throws IOException {
    final String defaults;
    try (final InputStream stream = IoUtils.getResourceAsStream(CONFIGURATION_FILE_NAME)) {
      defaults = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    final boolean contained = defaults.contains("  native: auto\n");
    assertTrue(contained);

    Files.writeString(this.folder.resolve(CONFIGURATION_FILE_NAME), defaults.replace("  native: auto\n", "  native: \"off\"\n"));
    try {
      this.sandbox.onEnable();

      final String describeNatives = Mcv2.describeNatives();
      assertEquals("Java, turned off by mcv2.native=off", describeNatives);
      verify(this.logger).info("MCV2 uses the {} kernels.", describeNatives);

      this.sandbox.onDisable();
    } finally {
      Mcv2.installNatives(this.folder.resolve(NATIVES_FOLDER_NAME), "auto");
    }
  }

  @Test
  void shouldStopTheListenerTheCommandsAndTheAudioWhenDisabled() {
    try (
      final MockedConstruction<JukeboxListener> listeners = Mockito.mockConstruction(JukeboxListener.class);
      final MockedConstruction<AnnotationParserHandler> handlers = Mockito.mockConstruction(AnnotationParserHandler.class);
      final MockedConstruction<AudioProvider> providers = Mockito.mockConstruction(AudioProvider.class);
      final MockedConstruction<Mcv2Support> supports = Mockito.mockConstruction(Mcv2Support.class)
    ) {
      this.sandbox.onEnable();
      this.sandbox.onDisable();
      verify(supports.constructed().getFirst()).start();
      verify(supports.constructed().getFirst()).shutdown();

      final List<JukeboxListener> constructedListeners = listeners.constructed();
      final List<AnnotationParserHandler> constructedHandlers = handlers.constructed();
      final List<AudioProvider> constructedProviders = providers.constructed();

      final int size = constructedListeners.size();
      assertEquals(1, size);

      final int constructedHandlersSize = constructedHandlers.size();
      assertEquals(1, constructedHandlersSize);

      final int constructedProvidersSize = constructedProviders.size();
      assertEquals(1, constructedProvidersSize);

      final JukeboxListener listener = constructedListeners.getFirst();
      final AnnotationParserHandler handler = constructedHandlers.getFirst();
      final AudioProvider provider = constructedProviders.getFirst();
      verify(listener).shutdown();
      verify(handler).shutdownCommands();
      verify(provider).shutdown();
    }
  }

  @Test
  void shouldKeepOwnershipWhenCommandRegistrationFailsPartwayThrough() {
    final IllegalStateException failure = new IllegalStateException("partial registration!");
    try (
      final MockedConstruction<AnnotationParserHandler> handlers = Mockito.mockConstruction(AnnotationParserHandler.class, (handler, _) ->
        this.failCommandRegistration(handler, failure)
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

  private void failCommandRegistration(final AnnotationParserHandler handler, final IllegalStateException failure) {
    doThrow(failure).when(handler).registerCommands();
  }

  @Test
  void shouldKeepOwnershipWhenListenerStartupFails() {
    final IllegalStateException failure = new IllegalStateException("partial listener startup!");
    try (
      final MockedConstruction<JukeboxListener> listeners = Mockito.mockConstruction(JukeboxListener.class, (listener, _) ->
        this.failListenerStart(listener, failure)
      )
    ) {
      final IllegalStateException thrown = assertThrows(IllegalStateException.class, this.sandbox::onEnable);
      assertSame(failure, thrown);

      this.sandbox.onDisable();
      final List<JukeboxListener> constructed = listeners.constructed();
      final JukeboxListener listener = constructed.getFirst();
      verify(listener).shutdown();
      verify(this.api).release();
    }
  }

  private void failListenerStart(final JukeboxListener listener, final IllegalStateException failure) {
    doThrow(failure).when(listener).start();
  }

  @Test
  void shouldAttemptEveryOwnedCleanupOnceEvenWhenTheFirstTwoFail() {
    final IllegalStateException first = new IllegalStateException("video cleanup!");
    final IllegalArgumentException second = new IllegalArgumentException("image cleanup!");
    try (
      final MockedConstruction<VideoPlayerManager> videos = Mockito.mockConstruction(VideoPlayerManager.class, (manager, _) ->
        this.failShutdown(manager, first)
      );
      final MockedConstruction<ImageManager> images = Mockito.mockConstruction(ImageManager.class, (manager, _) ->
        this.failShutdown(manager, second)
      );
      final MockedConstruction<JukeboxListener> listeners = Mockito.mockConstruction(JukeboxListener.class);
      final MockedConstruction<AnnotationParserHandler> handlers = Mockito.mockConstruction(AnnotationParserHandler.class);
      final MockedConstruction<AudioProvider> providers = Mockito.mockConstruction(AudioProvider.class);
      final MockedConstruction<Mcv2Support> supports = Mockito.mockConstruction(Mcv2Support.class);
      final MockedConstruction<OnlinePlayers> trackers = Mockito.mockConstruction(OnlinePlayers.class)
    ) {
      this.sandbox.onEnable();
      final IllegalStateException thrown = assertThrows(IllegalStateException.class, this.sandbox::onDisable);
      assertSame(first, thrown);

      final Throwable[] suppressed = thrown.getSuppressed();
      final Throwable[] expected = { second };
      assertArrayEquals(expected, suppressed);

      this.sandbox.onDisable();
      final List<VideoPlayerManager> videoManagers = videos.constructed();
      final List<ImageManager> imageManagers = images.constructed();
      final List<JukeboxListener> createdListeners = listeners.constructed();
      final List<AnnotationParserHandler> createdHandlers = handlers.constructed();
      final List<AudioProvider> audioProviders = providers.constructed();
      final VideoPlayerManager videoManager = videoManagers.getFirst();
      final ImageManager imageManager = imageManagers.getFirst();
      final JukeboxListener listener = createdListeners.getFirst();
      final AnnotationParserHandler handler = createdHandlers.getFirst();
      final AudioProvider provider = audioProviders.getFirst();
      final List<Mcv2Support> mcv2Supports = supports.constructed();
      final List<OnlinePlayers> playerTrackers = trackers.constructed();
      final Mcv2Support support = mcv2Supports.getFirst();
      final OnlinePlayers online = playerTrackers.getFirst();
      verify(videoManager, times(1)).shutdown();
      verify(imageManager, times(1)).shutdown();
      verify(listener, times(1)).shutdown();
      verify(handler, times(1)).shutdownCommands();
      verify(provider, times(1)).shutdown();
      verify(support, times(1)).shutdown();
      verify(online, times(1)).shutdown();
      verify(this.api, times(1)).release();
      this.pointers.verify(Pointer::interruptDeallocatorThread, times(1));
    }
  }

  private void failShutdown(final VideoPlayerManager manager, final RuntimeException failure) {
    doThrow(failure).when(manager).shutdown();
  }

  private void failShutdown(final ImageManager manager, final RuntimeException failure) {
    doThrow(failure).when(manager).shutdown();
  }

  @Test
  void shouldLogTheStartAndHowLongLoadingTook() {
    final AtomicLong installationMilliseconds = new AtomicLong();
    doAnswer(_ -> this.recordSlowInstallation(installationMilliseconds))
      .when(this.api)
      .install(BukkitModule.class, BrowserModule.class, VmModule.class, SvcModule.class);
    this.sandbox.onEnable();

    final ArgumentCaptor<Object> duration = ArgumentCaptor.forClass(Object.class);
    verify(this.logger).info("Loading MCAV.");
    verify(this.logger).info(eq("MCAV loaded in {} ms."), duration.capture());

    final Object logged = duration.getValue();
    final long milliseconds = (Long) logged;

    final boolean matches = installationMilliseconds.get() > 0;
    assertTrue(matches, "installation consumed measurable time");

    final boolean secondMatches = milliseconds >= installationMilliseconds.get();
    assertTrue(secondMatches, "the logged duration includes the entire installation");

    // a sum of two timestamps instead of their difference would be billions of milliseconds
    final boolean plausible = milliseconds >= 0 && milliseconds < 600_000;
    assertTrue(plausible, "the log reports how long loading took, which was %s ms".formatted(milliseconds));

    this.sandbox.onDisable();
  }

  private @Nullable Object recordSlowInstallation(final AtomicLong installationMilliseconds) throws InterruptedException {
    final long before = System.currentTimeMillis();
    Thread.sleep(25);
    installationMilliseconds.set(System.currentTimeMillis() - before);

    return null;
  }

  @Test
  void shouldWarnWhenThereIsNoBrowserForThisServer() {
    when(this.vmModule.isQemuAvailable()).thenReturn(true);
    this.sandbox.onEnable();
    verify(this.logger).warn(
      "There is no browser for the operating system and processor of this server, so browsers will not be available."
    );

    final boolean browserSupported = this.sandbox.isBrowserSupported();
    assertFalse(browserSupported);

    this.sandbox.onDisable();
  }

  @Test
  void shouldWarnWhenQemuIsNotAvailable() {
    when(this.browserModule.isSupported()).thenReturn(true);
    this.sandbox.onEnable();
    verify(this.logger).warn("QEMU is not installed and MCAV cannot install it on this server, so virtual machines will not be available.");

    final boolean qemuAvailable = this.sandbox.isQemuAvailable();
    assertFalse(qemuAvailable);

    this.sandbox.onDisable();
  }

  @Test
  void shouldDisableItselfWithOneErrorOnAnUnsupportedServerVersion() {
    final Server server = TestServer.getServer();
    when(server.getMinecraftVersion()).thenReturn("1.20.1");
    final UnsupportedServerVersionException failure = assertThrows(
      UnsupportedServerVersionException.class,
      ServerEnvironment::checkSupported
    );

    doThrow(failure).when(this.bukkitModule).inject(this.sandbox);

    this.sandbox.onEnable();

    final String reason = "Minecraft 1.20.1 is not supported, MCAV only runs on Minecraft 26.3 and its patch releases!";
    this.assertDisabledItselfWithOneError(reason, "run MCAV on a Minecraft 26.3 server");
    assertThrows(IllegalStateException.class, this.sandbox::getConfiguration, "nothing after the library is loaded");

    this.sandbox.onDisable();
    verify(this.api).release();
  }

  @Test
  void shouldDisableItselfWithOneErrorWhenVoiceChatIsEnabledButMissing() throws IOException {
    final Path configuration = this.folder.resolve(CONFIGURATION_FILE_NAME);
    Files.writeString(configuration, "simple-voice-chat:\n  enabled: true\n");

    this.sandbox.onEnable();

    final String reason = "Simple Voice Chat audio is enabled, but the Simple Voice Chat plugin is not installed!";
    final String remedy = "install the Simple Voice Chat plugin or set simple-voice-chat.enabled to false in config.yml";
    this.assertDisabledItselfWithOneError(reason, remedy);

    final AudioProvider audioProvider = this.sandbox.getAudioProvider();
    assertNotNull(audioProvider);
    assertThrows(IllegalStateException.class, this.sandbox::getVideoPlayerManager, "nothing after the audio is created");

    this.sandbox.onDisable();
    verify(this.api).release();
  }

  private void assertDisabledItselfWithOneError(final String reason, final String remedy) {
    verify(this.logger).error("MCAV cannot be enabled: {} To fix it, {}.", reason, remedy);
    verify(this.logger).error(anyString(), any(), any());

    final PluginManager pluginManager = TestServer.getPluginManager();
    verify(pluginManager).disablePlugin(this.sandbox);
  }

  @Test
  void shouldLetUnexpectedFailuresEscapeWithoutDisablingItself() {
    final IllegalStateException failure = new IllegalStateException("the packet listener failed!");
    doThrow(failure).when(this.bukkitModule).inject(this.sandbox);

    final IllegalStateException exception = assertThrows(IllegalStateException.class, this.sandbox::onEnable);

    assertSame(failure, exception, "an unexpected failure is not turned into a setup problem");

    final PluginManager pluginManager = TestServer.getPluginManager();
    verify(pluginManager, never()).disablePlugin(this.sandbox);
    verify(this.logger, never()).error(anyString(), any(), any());

    this.sandbox.onDisable();
    verify(this.api).release();
  }

  @Test
  void shouldReleaseTheLibraryWhenInstallingItsModulesFails() {
    final IllegalStateException failure = new IllegalStateException("a module failed to start!");
    doThrow(failure).when(this.api).install(BukkitModule.class, BrowserModule.class, VmModule.class, SvcModule.class);
    final IllegalStateException exception = assertThrows(IllegalStateException.class, this.sandbox::onEnable);
    assertSame(failure, exception);

    this.sandbox.onDisable();
    verify(this.api).release();
  }

  // stands in for Paper's plugin class loader: it defines the plugin class itself and receives each plugin it builds in init
  private static final class FolderPluginClassLoader extends URLClassLoader implements ConfiguredPluginClassLoader {

    private @Nullable JavaPlugin plugin;

    FolderPluginClassLoader(final URL classes) {
      super(new URL[] { classes }, MCAVSandboxTest.class.getClassLoader());
    }

    @Override
    protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
      if (!name.equals(PLUGIN_CLASS_NAME)) {
        return super.loadClass(name, resolve);
      }

      final @Nullable Class<?> loaded = this.findLoadedClass(name);
      if (loaded != null) {
        return loaded;
      }

      return this.findClass(name);
    }

    @Override
    public Class<?> loadClass(final String name, final boolean resolve, final boolean checkGlobal, final boolean checkLibraries)
      throws ClassNotFoundException {
      return this.loadClass(name, resolve);
    }

    @Override
    public void init(final JavaPlugin plugin) {
      this.plugin = plugin;
    }

    @Override
    public @Nullable JavaPlugin getPlugin() {
      return this.plugin;
    }

    @Override
    public PluginMeta getConfiguration() {
      final String message = "The stand-in class loader has no plugin descriptor!";
      throw new UnsupportedOperationException(message);
    }

    @Override
    public @Nullable PluginClassLoaderGroup getGroup() {
      final String message = "The stand-in class loader belongs to no group!";
      throw new UnsupportedOperationException(message);
    }
  }
}
