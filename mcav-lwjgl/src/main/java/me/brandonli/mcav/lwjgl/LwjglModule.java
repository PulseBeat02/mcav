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
package me.brandonli.mcav.lwjgl;

import me.brandonli.mcav.module.MCAVModule;

/**
 * Registers the OpenGL backend. Install it before you use the backend:
 *
 * {@snippet :
 * final MCAVApi api = MCAV.api();
 * api.install(LwjglModule.class);
 * }
 *
 * <p>The OpenGL context itself is created by your application, for example with GLFW or by Minecraft.
 *
 * <p>This class is stateless and thread-safe.
 *
 * @since 1.0.0
 */
public final class LwjglModule implements MCAVModule {

  private static final String MODULE_NAME = "lwjgl";

  /**
   * Creates the module. The module loader calls this for you.
   *
   * @since 1.0.0
   */
  public LwjglModule() {}

  /**
   * Starts the module. Nothing is prepared here, because every {@link GlTextureFilter} creates its OpenGL resources
   * on the render thread of your application.
   *
   * @since 1.0.0
   */
  @Override
  public void start() {}

  /**
   * Stops the module. Nothing is released here; release every {@link GlTextureFilter} on the render thread instead.
   *
   * @since 1.0.0
   */
  @Override
  public void stop() {}

  /**
   * Returns the name of the module.
   *
   * @return {@code "lwjgl"}
   * @since 1.0.0
   */
  @Override
  public String getModuleName() {
    return MODULE_NAME;
  }
}
