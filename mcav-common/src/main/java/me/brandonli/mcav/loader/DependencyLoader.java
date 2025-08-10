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
package me.brandonli.mcav.loader;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.capability.installer.vlc.VLCInstallationKit;
import me.brandonli.mcav.capability.installer.vlc.VLCInstaller;
import me.brandonli.mcav.capability.installer.ytdlp.YTDLPInstaller;
import me.brandonli.mcav.dependency.PackageInstaller;
import me.brandonli.mcav.utils.natives.NativeLoadingException;
import org.bytedeco.ffmpeg.ffmpeg;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacv.FFmpegLogCallback;
import org.bytedeco.opencv.presets.opencv_dnn_superres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DependencyLoader is responsible for loading JavaCV modules and installing optional dependencies
 * like VLC and yt-dlp.
 */
public final class DependencyLoader {

  private static final Logger LOGGER = LoggerFactory.getLogger(DependencyLoader.class);

  private static final List<Class<?>> CLASSES = List.of(
    org.bytedeco.opencv.presets.opencv_bgsegm.class,
    org.bytedeco.opencv.presets.opencv_face.class,
    org.bytedeco.opencv.presets.opencv_img_hash.class,
    org.bytedeco.opencv.presets.opencv_tracking.class,
    org.bytedeco.opencv.presets.opencv_ximgproc.class,
    org.bytedeco.opencv.presets.opencv_xphoto.class,
    opencv_dnn_superres.class
  );

  private final EnumSet<Capability> capabilities;

  DependencyLoader() {
    this.capabilities = EnumSet.allOf(Capability.class);
  }

  /**
   * Internal implementation
   *
   * @param capability the capability to check
   * @return true if the capability is supported, false otherwise
   */
  public boolean hasCapability(final Capability capability) {
    return this.capabilities.contains(capability);
  }

  /**
   * Internal implementation
   */
  public void loadModules() {
    final long start = System.currentTimeMillis();
    LOGGER.info("Loading JavaCV modules...");

    try {
      load();
    } catch (final Throwable error) {
      final PackageInstaller installer = new PackageInstaller();
      installer.install();
      load();
    }

    final long end = System.currentTimeMillis();
    LOGGER.info("JavaCV modules loaded in {} ms", end - start);
  }

  private static void load() {
    System.setProperty("org.bytedeco.openblas.load", "none"); // fix OpenBlas hanging
    System.setProperty("org.bytedeco.javacpp.pathsFirst", "true");
    FFmpegLogCallback.set();
    Loader.load(Loader.class);
    loadFFmpeg();
    CLASSES.forEach(Loader::load);
    FFmpegLogCallback.setLevel(avutil.AV_LOG_ERROR);
  }

  private static void loadFFmpeg() {
    try {
      Loader.load(ffmpeg.class);
    } catch (final UnsatisfiedLinkError e) {
      final String msg = requireNonNull(e.getMessage());
      if (msg.contains("jniavdevice")) {
        LOGGER.warn("jniavdevice is not available, some features may not work properly.");
        return;
      }
      throw new NativeLoadingException(e.getMessage(), e);
    }
  }

  /**
   * Internal implementation
   */
  public void installVLC() {
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

  /**
   * Internal implementation
   */
  public void installYTDLP() {
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
