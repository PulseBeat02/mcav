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

import me.brandonli.mcav.MCAV;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.browser.BrowserModule;
import me.brandonli.mcav.bukkit.BukkitModule;
import me.brandonli.mcav.bukkit.utils.versioning.ServerEnvironment;
import me.brandonli.mcav.bukkit.utils.versioning.UnsupportedServerVersionException;
import me.brandonli.mcav.media.mcv2.encode.EncoderPool;
import me.brandonli.mcav.sandbox.audio.AudioProvider;
import me.brandonli.mcav.sandbox.audio.MissingVoiceChatException;
import me.brandonli.mcav.sandbox.command.AnnotationParserHandler;
import me.brandonli.mcav.sandbox.command.image.ImageManager;
import me.brandonli.mcav.sandbox.command.video.Mcv2Support;
import me.brandonli.mcav.sandbox.command.video.VideoPlayerManager;
import me.brandonli.mcav.sandbox.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.sandbox.listener.JukeBoxListener;
import me.brandonli.mcav.sandbox.utils.CleanupUtils;
import me.brandonli.mcav.svc.SVCModule;
import me.brandonli.mcav.vm.VMModule;
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
 * when called before the plugin is enabled.
 */
public final class MCAVSandbox extends JavaPlugin {

  private static final String UNSUPPORTED_VERSION_REMEDY =
    "run MCAV on a Minecraft " + ServerEnvironment.SUPPORTED_MINECRAFT_VERSION + " server";
  private static final String MISSING_VOICE_CHAT_REMEDY =
    "install the Simple Voice Chat plugin or set simple-voice-chat.enabled to false in config.yml";

  private @MonotonicNonNull ComponentLogger logger;
  private @Nullable MCAVApi mcav;
  private @MonotonicNonNull PluginDataConfigurationMapper configurationMapper;
  private @Nullable AudioProvider audioProvider;
  private @Nullable ImageManager imageManager;
  private @Nullable VideoPlayerManager videoPlayerManager;
  private @Nullable Mcv2Support mcv2Support;
  private @Nullable AnnotationParserHandler annotationParserHandler;
  private @Nullable JukeBoxListener listener;
  private boolean qemuInstalled;

  /**
   * Constructs the plugin. Paper creates it for you; everything else is created in {@link #onEnable()}.
   */
  public MCAVSandbox() {}

  /**
   * Enables the plugin: installs the library and its modules, reads the configuration, starts the audio outputs
   * and the media managers, and registers the commands and the jukebox listener.
   *
   * <p>Two failures are setup problems of the server rather than bugs: a server that runs a Minecraft version the
   * library does not support, and Simple Voice Chat audio that is enabled in {@code config.yml} while the voicechat
   * plugin is missing. For either, the plugin logs one error that names the problem and how to fix it, and then
   * disables itself through the plugin manager, which calls {@link #onDisable()} to release what was created.
   *
   * @throws RuntimeException if anything else fails; Paper then reports the failure and disables the plugin, and
   *                          {@link #onDisable()} releases what was created
   */
  @Override
  public void onEnable() {
    this.logger = this.getComponentLogger();
    try {
      this.loadMCAV();
      this.loadPluginData();
      this.loadManagers();
      this.loadCommands();
      this.loadListeners();
    } catch (final UnsupportedServerVersionException exception) {
      this.disableBecause(exception, UNSUPPORTED_VERSION_REMEDY);
    } catch (final MissingVoiceChatException exception) {
      this.disableBecause(exception, MISSING_VOICE_CHAT_REMEDY);
    }
  }

  /**
   * Logs on one line why the plugin cannot be enabled and how to fix it, and disables the plugin. The plugin manager
   * calls {@link #onDisable()}, which releases whatever was created before the failure.
   *
   * @param failure the setup problem
   * @param remedy  what the server owner has to do about it
   */
  private void disableBecause(final IllegalStateException failure, final String remedy) {
    final ComponentLogger pluginLogger = this.requireLogger();
    final String reason = failure.getMessage();
    pluginLogger.error("MCAV cannot be enabled: {} ({})", reason, remedy);

    final Server server = this.getServer();
    final PluginManager pluginManager = server.getPluginManager();
    pluginManager.disablePlugin(this);
  }

  private void loadMCAV() {
    final ComponentLogger pluginLogger = this.requireLogger();
    pluginLogger.info("Loading MCAV");
    final long start = System.currentTimeMillis();
    final MCAVApi api = MCAV.api();
    // kept before installing, so onDisable releases whatever the modules created even when installing fails
    this.mcav = api;
    api.install(BukkitModule.class, BrowserModule.class, VMModule.class, SVCModule.class);

    final BukkitModule bukkitModule = api.getModule(BukkitModule.class);
    bukkitModule.inject(this);
    final VMModule vmModule = api.getModule(VMModule.class);
    this.qemuInstalled = vmModule.isQemuInstalled();
    if (!this.qemuInstalled) {
      pluginLogger.warn("QEMU is not installed, virtual machines will not be available");
    }

    final long end = System.currentTimeMillis();
    final long duration = end - start;
    pluginLogger.info("MCAV loaded in {} ms", duration);
  }

  private void loadPluginData() {
    final PluginDataConfigurationMapper mapper = new PluginDataConfigurationMapper(this);
    mapper.deserialize();
    this.configurationMapper = mapper;
    // one encoder budget for every MCV2 screen of the server
    EncoderPool.setSharedThreads(mapper.getMcv2EncoderThreads());
    final int processors = Runtime.getRuntime().availableProcessors();
    this.requireLogger().info("MCV2 encoders share {} of {} processors", EncoderPool.shared().getThreads(), processors);
  }

  private void loadManagers() {
    final AudioProvider provider = new AudioProvider(this);
    this.audioProvider = provider;
    provider.initialize();
    this.videoPlayerManager = new VideoPlayerManager(this);
    this.imageManager = new ImageManager(this);
    this.mcv2Support = new Mcv2Support(this.getDataFolder().toPath());
  }

  private void loadCommands() {
    final AnnotationParserHandler handler = new AnnotationParserHandler(this);
    this.annotationParserHandler = handler;
    handler.registerCommands();
  }

  private void loadListeners() {
    final JukeBoxListener jukeBoxListener = new JukeBoxListener(this);
    this.listener = jukeBoxListener;
    jukeBoxListener.start();
  }

  /**
   * Disables the plugin: stops the video and the images, the jukebox listener, the commands and the audio outputs,
   * and releases the library. Parts that were never created, because the plugin failed to enable, are skipped.
   */
  @Override
  public void onDisable() {
    final VideoPlayerManager videos = this.videoPlayerManager;
    final ImageManager images = this.imageManager;
    final JukeBoxListener jukebox = this.listener;
    final AnnotationParserHandler commands = this.annotationParserHandler;
    final AudioProvider audio = this.audioProvider;
    final Mcv2Support mcv2 = this.mcv2Support;
    final MCAVApi library = this.mcav;
    this.mcv2Support = null;
    this.videoPlayerManager = null;
    this.imageManager = null;
    this.listener = null;
    this.annotationParserHandler = null;
    this.audioProvider = null;
    this.mcav = null;
    CleanupUtils.runAll(
      () -> {
        if (videos != null) {
          videos.shutdown();
        }
      },
      () -> {
        if (images != null) {
          images.shutdown();
        }
      },
      () -> {
        if (jukebox != null) {
          jukebox.shutdown();
        }
      },
      () -> {
        if (commands != null) {
          commands.shutdownCommands();
        }
      },
      () -> {
        if (audio != null) {
          audio.shutdown();
        }
      },
      () -> {
        if (mcv2 != null) {
          mcv2.close();
        }
      },
      () -> {
        if (library != null) {
          library.release();
        }
      }
    );
  }

  private static <T> T require(final @Nullable T value, final String name) {
    if (value == null) {
      throw new IllegalStateException("The " + name + " is not available before the plugin is enabled");
    }
    return value;
  }

  private ComponentLogger requireLogger() {
    return require(this.logger, "logger");
  }

  /**
   * Gets the library instance.
   *
   * @return the library
   */
  public MCAVApi getMCAV() {
    return require(this.mcav, "library");
  }

  /**
   * Gets the configuration.
   *
   * @return the configuration
   */
  public PluginDataConfigurationMapper getConfiguration() {
    return require(this.configurationMapper, "configuration");
  }

  /**
   * Gets the manager of the video player.
   *
   * @return the video player manager
   */
  public VideoPlayerManager getVideoPlayerManager() {
    return require(this.videoPlayerManager, "video player manager");
  }

  /**
   * Gets the provider of audio outputs.
   *
   * @return the audio provider
   */
  public AudioProvider getAudioProvider() {
    return require(this.audioProvider, "audio provider");
  }

  /**
   * Gets the manager of displayed images.
   *
   * @return the image manager
   */
  public ImageManager getImageManager() {
    return require(this.imageManager, "image manager");
  }

  /**
   * Gets what the MCV2 commands share: the served pack and who loaded it.
   *
   * @return the MCV2 support
   */
  public Mcv2Support getMcv2Support() {
    return require(this.mcv2Support, "MCV2 support");
  }

  /**
   * Checks whether QEMU was found when the plugin was enabled.
   *
   * @return true if virtual machines can be created
   */
  public boolean isQemuInstalled() {
    return this.qemuInstalled;
  }
}
