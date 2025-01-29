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
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.capability.installer.qemu.QemuInstaller;
import me.brandonli.mcav.capability.installer.vlc.VLCInstallationKit;
import me.brandonli.mcav.capability.installer.vlc.github.ReleasePackageManager;
import me.brandonli.mcav.capability.installer.ytdlp.YTDLPInstaller;
import me.brandonli.mcav.media.player.browser.ChromeDriverServiceProvider;
import me.brandonli.mcav.media.player.combined.vlc.MediaPlayerFactoryProvider;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.Palette;
import org.bytedeco.ffmpeg.ffmpeg;
import org.bytedeco.javacpp.Loader;
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

  private final Set<Capability> capabilities;

  MCAV() {
    this.capabilities = Arrays.stream(Capability.values()).filter(Capability::isEnabled).collect(Collectors.toSet());
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
    this.loadVLCPackages();
    ImageIO.setUseCache(false);
  }

  private void loadVLCPackages() {
    LOGGER.info("Loading GitHub releases...");
    final long start = System.currentTimeMillis();
    ReleasePackageManager.init();
    final long end = System.currentTimeMillis();
    LOGGER.info("GitHub releases loaded in {} ms", end - start);
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
    Loader.load(opencv_java.class);
    Loader.load(ffmpeg.class);
    final long end = System.currentTimeMillis();
    LOGGER.info("JavaCV modules loaded in {} ms", end - start);
  }

  private void installQemu() {
    try {
      LOGGER.info("Installing QEMU...");
      final long start = System.currentTimeMillis();
      if (!this.hasCapability(Capability.QEMU)) {
        LOGGER.info("QEMU is not enabled, skipping installation.");
        return;
      }
      QemuInstaller.create().download(true);
      final long end = System.currentTimeMillis();
      LOGGER.info("QEMU installation took {} ms", end - start);
    } catch (final IOException e) {
      throw new MCAVLoadingException(e.getMessage());
    }
  }

  private void installVLC() {
    try {
      LOGGER.info("Installing VLC...");
      final long start = System.currentTimeMillis();
      if (!this.hasCapability(Capability.VLC)) {
        LOGGER.info("VLC is not enabled, skipping installation.");
        return;
      }
      VLCInstallationKit.create().start();
      final long end = System.currentTimeMillis();
      LOGGER.info("VLC installation took {} ms", end - start);
    } catch (final IOException e) {
      throw new MCAVLoadingException(e.getMessage());
    }
  }

  private void installYTDLP() {
    try {
      LOGGER.info("Installing yt-dlp");
      final long start = System.currentTimeMillis();
      if (!this.hasCapability(Capability.YTDLP)) {
        LOGGER.info("yt-dlp is not enabled, skipping installation.");
        return;
      }
      YTDLPInstaller.create().download(true);
      final long end = System.currentTimeMillis();
      LOGGER.info("yt-dlp installation took {} ms", end - start);
    } catch (final IOException e) {
      throw new MCAVLoadingException(e.getMessage());
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
