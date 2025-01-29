/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.capability.installer.vlc.VLCInstallationKit;
import me.brandonli.mcav.capability.installer.vlc.VLCInstaller;
import me.brandonli.mcav.capability.installer.ytdlp.YTDLPInstaller;
import me.brandonli.mcav.media.player.multimedia.vlc.MediaPlayerFactoryProvider;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.Palette;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.OSUtils;
import org.bytedeco.ffmpeg.ffmpeg;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacv.FFmpegLogCallback;
import org.bytedeco.opencv.global.*;
import org.bytedeco.opencv.opencv_java;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCAV is the main implementation of the {@link MCAVApi} interface, providing core functionality
 * to manage and handle media playback capabilities, installation of dependencies, and resource cleanup.
 * <p>
 * This class is designed to initialize and manage the capabilities supported by the library based on
 * the {@link Capability} values provided. It utilizes a set of asynchronous tasks for installing
 * required tools and dependencies and ensures proper shutdown of resources when no longer in use.
 * <p>
 * This class is immutable and thread-safe.
 */
public final class MCAV implements MCAVApi {

  private static final Logger LOGGER = LoggerFactory.getLogger(MCAV.class);

  private final Map<Class<?>, MCAVModule> modules;
  private final EnumSet<Capability> capabilities;
  private final AtomicBoolean loaded;

  MCAV() {
    this.capabilities = EnumSet.allOf(Capability.class);
    this.modules = new HashMap<>();
    this.loaded = new AtomicBoolean(false);
  }

  /**
   * Provides a static factory method to obtain an instance of the {@link MCAVApi} interface,
   * implemented by the {@link MCAV} class. This method initializes a new instance of {@code MCAV},
   * which serves as the primary implementation of the library's capabilities.
   *
   * @return a new instance of {@link MCAVApi} implemented by {@link MCAV}, offering media playback
   * capabilities, resource installation, and management functionalities.
   */
  public static MCAVApi api() {
    return new MCAV();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean hasCapability(final Capability capability) {
    if (!this.loaded.get()) {
      throw new MCAVLoadingException("MCAV hasn't been loaded yet!");
    }
    return this.capabilities.contains(capability);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void install(final Class<?>... plugins) {
    this.installYTDLP();
    this.installVLC();
    this.installMisc();
    this.loadPlugins(plugins);
    this.updateLoadStatus();
  }

  @Override
  public <T extends MCAVModule> T getModule(final Class<T> moduleClass) {
    final MCAVModule module = this.modules.get(moduleClass);
    if (module == null) {
      final String name = moduleClass.getSimpleName();
      final String msg = "Module %s does not exist or is not loaded!".formatted(name);
      throw new ModuleException(msg);
    }
    return moduleClass.cast(module);
  }

  private void loadPlugins(final Class<?>... plugins) {
    final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
    final MethodType type = MethodType.methodType(void.class);
    for (final Class<?> plugin : plugins) {
      if (!MCAVModule.class.isAssignableFrom(plugin)) {
        final String name = plugin.getSimpleName();
        final String msg = "Plugin %s is not a valid MCAV plugin!".formatted(name);
        throw new ModuleException(msg);
      }
      final MCAVModule module = this.tryPluginLoad(plugin, lookup, type);
      final Class<?> moduleClass = module.getClass();
      this.modules.put(moduleClass, module);
      module.start();
    }
  }

  private MCAVModule tryPluginLoad(final Class<?> plugin, final MethodHandles.Lookup lookup, final MethodType type) {
    try {
      final MethodHandle constructor = lookup.findConstructor(plugin, type);
      return (MCAVModule) constructor.invoke();
    } catch (final Throwable e) {
      throw new ModuleException(e.getMessage(), e);
    }
  }

  private void shutdownPlugins() {
    this.modules.values().forEach(MCAVModule::stop);
  }

  private void updateLoadStatus() {
    this.loaded.set(true);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void release() {
    this.shutdownPlugins();
    MediaPlayerFactoryProvider.shutdown();
  }

  private void installMisc() {
    this.loadModules();
    this.loadMapCache();
    ImageIO.setUseCache(false);
  }

  private void loadMapCache() {
    LOGGER.info("Loading map cache...");
    final long start = System.currentTimeMillis();
    Palette.init();
    final long end = System.currentTimeMillis();
    LOGGER.info("Map cache loaded in {} ms", end - start);
  }

  private void loadModules() {
    final long start = System.currentTimeMillis();
    LOGGER.info("Loading JavaCV modules...");
    FFmpegLogCallback.set();
    Loader.load(Loader.class);
    this.loadOpenCVModules();
    Loader.load(ffmpeg.class);
    final long end = System.currentTimeMillis();
    LOGGER.info("JavaCV modules loaded in {} ms", end - start);
  }

  private void loadOpenCVModules() {
    final OS os = OSUtils.getOS();
    if (os != OS.WINDOWS) {
      // load headless libraries only
      System.setProperty("org.bytedeco.javacpp.loadlibraries", "false");
      Loader.load(opencv_core.class);
      Loader.load(opencv_imgproc.class);
      Loader.load(opencv_imgcodecs.class);
      Loader.load(opencv_videoio.class);
      Loader.load(opencv_video.class);
      Loader.load(opencv_calib3d.class);
      Loader.load(opencv_features2d.class);
      Loader.load(opencv_objdetect.class);
      Loader.load(opencv_photo.class);
      Loader.load(opencv_dnn.class);
    } else {
      Loader.load(opencv_java.class);
    }
    FFmpegLogCallback.setLevel(avutil.AV_LOG_ERROR);
    avutil.setLogCallback(new FFmpegLogger());
  }

  private void installVLC() {
    try {
      final VLCInstaller installer = VLCInstaller.create();
      if (!installer.isSupported()) {
        this.capabilities.remove(Capability.VLC);
        LOGGER.info("VLC is not enabled, skipping installation.");
        return;
      }
      LOGGER.info("Installing VLC...");
      final long start = System.currentTimeMillis();
      VLCInstallationKit.create().start();
      final long end = System.currentTimeMillis();
      LOGGER.info("VLC installation took {} ms", end - start);
    } catch (final IOException e) {
      this.capabilities.remove(Capability.VLC);
      final String msg = e.getMessage();
      if (msg != null) {
        LOGGER.error(msg);
      }
      LOGGER.info("Failed to install VLC, skipping installation.");
    }
  }

  private void installYTDLP() {
    try {
      final YTDLPInstaller installer = YTDLPInstaller.create();
      if (!installer.isSupported()) {
        this.capabilities.remove(Capability.YT_DLP);
        LOGGER.info("yt-dlp is not enabled, skipping installation.");
        return;
      }
      LOGGER.info("Installing yt-dlp");
      final long start = System.currentTimeMillis();
      YTDLPInstaller.create().download(true);
      final long end = System.currentTimeMillis();
      LOGGER.info("yt-dlp installation took {} ms", end - start);
    } catch (final IOException e) {
      this.capabilities.remove(Capability.YT_DLP);
      final String msg = e.getMessage();
      if (msg != null) {
        LOGGER.error(msg);
      }
      LOGGER.info("Failed to install yt-dlp, skipping installation.");
    }
  }
}
