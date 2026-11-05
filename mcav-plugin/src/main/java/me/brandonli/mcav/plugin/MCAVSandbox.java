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

import java.io.File;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;
import me.brandonli.mcav.MCAV;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.browser.BrowserModule;
import me.brandonli.mcav.bukkit.BukkitModule;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Pool;
import me.brandonli.mcav.bukkit.resourcepack.provider.PackHosting;
import me.brandonli.mcav.bukkit.utils.versioning.ServerEnvironment;
import me.brandonli.mcav.bukkit.utils.versioning.UnsupportedServerVersionException;
import me.brandonli.mcav.plugin.audio.AudioProvider;
import me.brandonli.mcav.plugin.audio.MissingVoiceChatException;
import me.brandonli.mcav.plugin.command.AnnotationParserHandler;
import me.brandonli.mcav.plugin.command.image.ImageManager;
import me.brandonli.mcav.plugin.command.video.Mcv2Support;
import me.brandonli.mcav.plugin.command.video.VideoPlayerManager;
import me.brandonli.mcav.plugin.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.plugin.listener.JukeboxListener;
import me.brandonli.mcav.plugin.listener.OnlinePlayers;
import me.brandonli.mcav.plugin.utils.CleanupUtils;
import me.brandonli.mcav.plugin.utils.Mcv2Hosting;
import me.brandonli.mcav.vm.VmModule;
import me.brandonli.mcav.voicechat.SvcModule;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The MCAV sandbox plugin, which demonstrates every feature of the library through commands.
 *
 * <p>Everything is created in {@link #onEnable()} in dependency order: the library, the configuration, the audio
 * and media managers, the commands, and the listeners. The getters fail with an {@link IllegalStateException}
 * when called before the plugin is enabled and, except {@link #getConfiguration()}, after it is disabled.
 *
 * <p>Paper constructs this class from the plugin descriptor, and the commands it registers are documented in the plugin
 * pages of the MCAV docs.
 *
 * <p>This class is not thread-safe: Paper calls {@link #onEnable()} and {@link #onDisable()} on the server main thread,
 * and other threads may call its getters only while it is enabled.
 *
 * @since 1.0.0
 */
public final class MCAVSandbox extends JavaPlugin {

  private static final String NATIVES_FOLDER = "natives";

  private @MonotonicNonNull ComponentLogger logger;

  private @Nullable MCAVApi mcav;
  private @MonotonicNonNull PluginDataConfigurationMapper configurationMapper;
  private @Nullable AudioProvider audioProvider;
  private @Nullable ImageManager imageManager;
  private @Nullable VideoPlayerManager videoPlayerManager;
  private @Nullable Mcv2Support mcv2Support;
  private @Nullable OnlinePlayers onlinePlayers;
  private @Nullable AnnotationParserHandler annotationParserHandler;
  private @Nullable JukeboxListener listener;

  private boolean qemuAvailable;
  private boolean browserSupported;

  private boolean disabled;

  /**
   * Creates the plugin. Paper calls it for you; everything else is created in {@link #onEnable()}.
   *
   * @since 1.0.0
   */
  public MCAVSandbox() {}

  /**
   * Enables the plugin: installs the library and its modules, reads the configuration, starts the audio outputs
   * and the media managers, and registers the commands and the jukebox listener.
   *
   * <p>An instance that Paper has disabled cannot be enabled again because its class loader and lifecycle
   * registrations are closed. A later enable attempt logs one error with an instruction to restart the server.
   *
   * <p>Two failures are setup problems of the server rather than bugs: a server that runs a Minecraft version the
   * library does not support, and Simple Voice Chat audio that is enabled in {@code config.yml} while the Simple Voice
   * Chat plugin is missing. For either, the plugin logs one error that names the problem and how to fix it, and then
   * disables itself through the plugin manager, which calls {@link #onDisable()} to release what was created.
   *
   * @throws RuntimeException if anything else fails; Paper then reports the failure and disables the plugin, and
   *                          {@link #onDisable()} releases what was created
   * @since 1.0.0
   */
  @Override
  public void onEnable() {
    this.logger = this.getComponentLogger();

    if (this.disabled) {
      final String message = "Paper cannot re-enable this plugin after it has been disabled!";
      this.disableBecause(new IllegalStateException(message), "restart the server to enable MCAV");
      return;
    }

    try {
      this.loadMCAV();
      this.loadPluginData();
      this.loadMcv2Encoders();
      this.loadManagers();
      this.loadCommands();
      this.loadListeners();
    } catch (final UnsupportedServerVersionException exception) {
      final String remedy = "run MCAV on a Minecraft %s server".formatted(ServerEnvironment.SUPPORTED_MINECRAFT_VERSION);
      this.disableBecause(exception, remedy);
    } catch (final MissingVoiceChatException exception) {
      this.disableBecause(exception, "install the Simple Voice Chat plugin or set simple-voice-chat.enabled to false in config.yml");
    }
  }

  /**
   * Disables the plugin: stops the video and the images, the jukebox listener, the commands, the audio outputs, the
   * MCV2 support and the online player tracking, and releases the library. Parts that were never created, because the
   * plugin failed to enable, are skipped.
   *
   * @throws RuntimeException if a part fails to stop, after every other part was stopped
   * @since 1.0.0
   */
  @Override
  public void onDisable() {
    this.disabled = true;

    final VideoPlayerManager videos = this.videoPlayerManager;
    final ImageManager images = this.imageManager;
    final JukeboxListener jukebox = this.listener;
    final AnnotationParserHandler commands = this.annotationParserHandler;
    final AudioProvider audio = this.audioProvider;
    final Mcv2Support mcv2 = this.mcv2Support;
    final OnlinePlayers online = this.onlinePlayers;
    final MCAVApi library = this.mcav;

    this.mcv2Support = null;
    this.onlinePlayers = null;
    this.videoPlayerManager = null;
    this.imageManager = null;
    this.listener = null;
    this.annotationParserHandler = null;
    this.audioProvider = null;
    this.mcav = null;

    CleanupUtils.runAll(
      this.whenPresent(videos, VideoPlayerManager::shutdown),
      this.whenPresent(images, ImageManager::shutdown),
      this.whenPresent(jukebox, JukeboxListener::shutdown),
      this.whenPresent(commands, AnnotationParserHandler::shutdownCommands),
      this.whenPresent(audio, AudioProvider::shutdown),
      this.whenPresent(mcv2, Mcv2Support::shutdown),
      this.whenPresent(online, OnlinePlayers::shutdown),
      this.whenPresent(library, MCAVApi::release)
    );
  }

  private void disableBecause(final RuntimeException failure, final String remedy) {
    final ComponentLogger pluginLogger = this.requireLogger();
    final String reason = failure.getMessage();
    pluginLogger.error("MCAV cannot be enabled: {} To fix it, {}.", reason, remedy);

    final Server server = this.getServer();
    final PluginManager pluginManager = server.getPluginManager();
    pluginManager.disablePlugin(this);
  }

  private void loadMCAV() {
    final ComponentLogger pluginLogger = this.requireLogger();
    pluginLogger.info("Loading MCAV.");

    final long start = System.currentTimeMillis();
    final MCAVApi api = MCAV.api();
    // kept before installing, so onDisable releases whatever the modules created even when installing fails
    this.mcav = api;
    api.install(BukkitModule.class, BrowserModule.class, VmModule.class, SvcModule.class);

    final BukkitModule bukkitModule = api.getModule(BukkitModule.class);
    bukkitModule.inject(this);

    final VmModule vmModule = api.getModule(VmModule.class);
    this.qemuAvailable = vmModule.isQemuAvailable();
    if (!this.qemuAvailable) {
      pluginLogger.warn("QEMU is not installed and MCAV cannot install it on this server, so virtual machines will not be available.");
    }

    final BrowserModule browserModule = api.getModule(BrowserModule.class);
    this.browserSupported = browserModule.isSupported();
    if (!this.browserSupported) {
      pluginLogger.warn("There is no browser for the operating system and processor of this server, so browsers will not be available.");
    }

    final long end = System.currentTimeMillis();
    final long duration = end - start;
    pluginLogger.info("MCAV loaded in {} ms.", duration);
  }

  private void loadPluginData() {
    final PluginDataConfigurationMapper mapper = new PluginDataConfigurationMapper(this);
    mapper.deserialize();
    this.configurationMapper = mapper;
  }

  private void loadMcv2Encoders() {
    final PluginDataConfigurationMapper mapper = this.getConfiguration();
    final int encoderThreads = mapper.getMcv2EncoderThreads();
    Pool.setSharedThreads(encoderThreads);

    final Runtime runtime = Runtime.getRuntime();
    final int processors = runtime.availableProcessors();
    final ComponentLogger pluginLogger = this.requireLogger();
    final Pool sharedPool = Pool.shared();
    final int sharedThreads = sharedPool.getThreads();
    pluginLogger.info("MCV2 encoders share {} of {} processors.", sharedThreads, processors);

    // the native kernels extract into the data folder, never /tmp; installing them decides which kernels run and logs it
    final File dataFolder = this.getDataFolder();
    final Path dataPath = dataFolder.toPath();
    final Path nativesPath = dataPath.resolve(NATIVES_FOLDER);
    final String nativeLibrary = mapper.getMcv2Native();
    Mcv2.installNatives(nativesPath, nativeLibrary);

    final String kernels = Mcv2.describeNatives();
    pluginLogger.info("MCV2 uses the {} kernels.", kernels);
  }

  private void loadManagers() {
    final AudioProvider provider = new AudioProvider(this);
    this.audioProvider = provider;
    provider.initialize();

    this.videoPlayerManager = new VideoPlayerManager(this);
    this.imageManager = new ImageManager(this);

    final OnlinePlayers online = new OnlinePlayers();
    this.onlinePlayers = online;
    online.start(this);

    final PluginDataConfigurationMapper mapper = this.getConfiguration();
    final Mcv2Hosting hosting = mapper.getMcv2PackHosting();
    final File dataFolder = this.getDataFolder();
    final Path dataPath = dataFolder.toPath();
    final String host = mapper.getMcv2PackHttpHost();
    final int port = mapper.getMcv2PackHttpPort();
    final Function<Path, PackHosting> packHosting = hosting.hosting(host, port);
    final Mcv2Support support = new Mcv2Support(dataPath, packHosting);
    this.mcv2Support = support;
    support.start();
  }

  private void loadCommands() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this);
    this.annotationParserHandler = handler;
    handler.registerCommands();
  }

  private void loadListeners() {
    final JukeboxListener jukeboxListener = new JukeboxListener(this);
    this.listener = jukeboxListener;
    jukeboxListener.start();
  }

  private <PartType> Runnable whenPresent(final @Nullable PartType part, final Consumer<PartType> cleanup) {
    return () -> this.cleanUpIfPresent(part, cleanup);
  }

  private <PartType> void cleanUpIfPresent(final @Nullable PartType part, final Consumer<PartType> cleanup) {
    if (part != null) {
      cleanup.accept(part);
    }
  }

  private ComponentLogger requireLogger() {
    return this.require(this.logger, "logger");
  }

  private <ValueType> ValueType require(final @Nullable ValueType value, final String name) {
    if (value == null) {
      final String message = "The %s is only available while the plugin is enabled!".formatted(name);
      throw new IllegalStateException(message);
    }

    return value;
  }

  /**
   * Returns the library instance.
   *
   * @return the library
   * @throws IllegalStateException if the plugin is not enabled
   * @since 1.0.0
   */
  public MCAVApi getMCAV() {
    return this.require(this.mcav, "library");
  }

  /**
   * Returns the configuration.
   *
   * @return the configuration
   * @throws IllegalStateException if the plugin was never enabled
   * @since 1.0.0
   */
  public PluginDataConfigurationMapper getConfiguration() {
    return this.require(this.configurationMapper, "configuration");
  }

  /**
   * Returns the manager of the video player.
   *
   * @return the video player manager
   * @throws IllegalStateException if the plugin is not enabled
   * @since 1.0.0
   */
  public VideoPlayerManager getVideoPlayerManager() {
    return this.require(this.videoPlayerManager, "video player manager");
  }

  /**
   * Returns the provider of audio outputs.
   *
   * @return the audio provider
   * @throws IllegalStateException if the plugin is not enabled
   * @since 1.0.0
   */
  public AudioProvider getAudioProvider() {
    return this.require(this.audioProvider, "audio provider");
  }

  /**
   * Returns the manager of displayed images.
   *
   * @return the image manager
   * @throws IllegalStateException if the plugin is not enabled
   * @since 1.0.0
   */
  public ImageManager getImageManager() {
    return this.require(this.imageManager, "image manager");
  }

  /**
   * Returns what the MCV2 screens share: the one pack that decodes them all, and who loaded it.
   *
   * @return the MCV2 support
   * @throws IllegalStateException if the plugin is not enabled
   * @since 1.0.0
   */
  public Mcv2Support getMcv2Support() {
    return this.require(this.mcv2Support, "MCV2 support");
  }

  /**
   * Returns the players online, which a wall shown to {@code @a} keeps following.
   *
   * @return the online players
   * @throws IllegalStateException if the plugin is not enabled
   * @since 1.0.0
   */
  public OnlinePlayers getOnlinePlayers() {
    return this.require(this.onlinePlayers, "list of online players");
  }

  /**
   * Returns whether virtual machines can start: QEMU was found when the plugin was enabled, or MCAV installs it when the
   * first virtual machine starts.
   *
   * @return true if virtual machines can be created
   * @since 1.0.0
   */
  public boolean isQemuAvailable() {
    return this.qemuAvailable;
  }

  /**
   * Returns whether the browser can run on the operating system and processor of this server, as found when the plugin
   * was enabled.
   *
   * @return true if browsers can be created
   * @since 1.0.0
   */
  public boolean isBrowserSupported() {
    return this.browserSupported;
  }
}
