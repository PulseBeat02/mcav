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
package me.brandonli.mcav;

import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.loader.DependencyLoader;
import me.brandonli.mcav.media.player.pipeline.filter.audio.DirectAudioOutput;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;
import me.brandonli.mcav.module.MCAVModule;
import me.brandonli.mcav.module.ModuleLoader;
import me.brandonli.mcav.utils.ReflectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The main implementation of the MCAV library.
 */
public final class MCAV implements MCAVApi {

  private static final Logger LOGGER = LoggerFactory.getLogger(MCAV.class);

  private final DependencyLoader dependencyLoader;
  private final ModuleLoader moduleLoader;
  private final AtomicBoolean loaded;

  MCAV() {
    this.dependencyLoader = ReflectionUtils.newInstance(DependencyLoader.class);
    this.moduleLoader = ReflectionUtils.newInstance(ModuleLoader.class);
    this.loaded = new AtomicBoolean(false);
  }

  /**
   * Returns a new instance of the MCAV API.
   *
   * @return a new instance of {@link MCAVApi}
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
    return this.dependencyLoader.hasCapability(capability);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void install(final Class<?>... plugins) {
    this.dependencyLoader.installYTDLP();
    this.dependencyLoader.installVLC();
    this.installMisc();
    this.moduleLoader.loadPlugins(plugins);
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
    this.moduleLoader.shutdownPlugins();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T extends MCAVModule> T getModule(final Class<T> moduleClass) {
    return this.moduleLoader.getModule(moduleClass);
  }

  private void installMisc() {
    this.dependencyLoader.loadModules();
    this.loadMapCache();
    ImageIO.setUseCache(false);
    DirectAudioOutput.init();
  }

  private void loadMapCache() {
    LOGGER.info("Loading map cache...");
    final long start = System.currentTimeMillis();
    DitherPalette.init();
    final long end = System.currentTimeMillis();
    LOGGER.info("Map cache loaded in {} ms", end - start);
  }
}
