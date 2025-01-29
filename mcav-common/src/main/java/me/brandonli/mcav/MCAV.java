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
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.capability.installer.qemu.QemuInstaller;
import me.brandonli.mcav.capability.installer.vlc.VLCInstallationKit;
import me.brandonli.mcav.capability.installer.vlc.VLCInstaller;
import me.brandonli.mcav.capability.installer.ytdlp.YTDLPInstaller;
import me.brandonli.mcav.media.player.browser.ChromeDriverServiceProvider;
import me.brandonli.mcav.media.player.combined.vlc.MediaPlayerFactoryProvider;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.Palette;
import org.bytedeco.ffmpeg.ffmpeg;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacv.FFmpegLogCallback;
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

  private final EnumSet<Capability> capabilities;
  private final AtomicBoolean loaded;

  MCAV() {
    this.capabilities = EnumSet.allOf(Capability.class);
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
  public void install() {
    this.installYTDLP();
    this.installVLC();
    this.installQemu();
    this.installWebDriver();
    this.installMisc();
    this.updateLoadStatus();
  }

  private void updateLoadStatus() {
    this.loaded.set(true);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void release() {
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
    Loader.load(opencv_java.class);
    Loader.load(ffmpeg.class);
    final long end = System.currentTimeMillis();
    LOGGER.info("JavaCV modules loaded in {} ms", end - start);
  }

  private void installQemu() {
    try {
      final QemuInstaller installer = QemuInstaller.create();
      if (!installer.isSupported()) {
        this.capabilities.remove(Capability.QEMU);
        LOGGER.info("QEMU is not enabled, skipping installation.");
        return;
      }
      LOGGER.info("Installing QEMU...");
      final long start = System.currentTimeMillis();
      installer.download(true);
      final long end = System.currentTimeMillis();
      LOGGER.info("QEMU installation took {} ms", end - start);
    } catch (final IOException e) {
      this.capabilities.remove(Capability.QEMU);
      final String msg = e.getMessage();
      if (msg != null) {
        LOGGER.error(msg);
      }
      LOGGER.info("Failed to install QEMU, skipping installation.");
    }
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

  private void installWebDriver() {
    LOGGER.info("Installing ChromeDriver...");
    final long start = System.currentTimeMillis();
    ChromeDriverServiceProvider.init();
    final long end = System.currentTimeMillis();
    LOGGER.info("ChromeDriver installation took {} ms", end - start);
  }
}
