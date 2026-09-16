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
 * Registers the OpenGL backend. Install it with {@code MCAV.api().install(LWJGLModule.class)}. The OpenGL context
 * itself is created by your application, for example with GLFW or by Minecraft.
 */
public final class LWJGLModule implements MCAVModule {

  /**
   * Constructs the module. The module loader creates it for you.
   */
  public LWJGLModule() {
    // stateless
  }

  /**
   * Starts the module. Nothing is prepared here, because every {@link GLTextureFilter} creates its OpenGL resources
   * on the render thread of your application.
   */
  @Override
  public void start() {
    // nothing to prepare
  }

  /**
   * Stops the module. Nothing is released here; release every {@link GLTextureFilter} on the render thread instead.
   */
  @Override
  public void stop() {
    // nothing to release
  }

  /**
   * Gets the name of the module.
   *
   * @return {@code "lwjgl"}
   */
  @Override
  public String getModuleName() {
    return "lwjgl";
  }
}
