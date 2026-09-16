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
package me.brandonli.mcav.http;

import me.brandonli.mcav.module.MCAVModule;

/**
 * Registers the HTTP audio backend. Install it with {@code MCAV.api().install(HttpModule.class)}; servers are
 * created with the factories of {@link HttpResult}.
 */
public final class HttpModule implements MCAVModule {

  /**
   * Constructs the module. The module loader creates it for you.
   */
  public HttpModule() {
    // stateless
  }

  /**
   * Starts the module. Nothing is prepared here, because every {@link HttpResult} starts its own web server with
   * {@link HttpResult#start()}.
   */
  @Override
  public void start() {
    // servers are started individually
  }

  /**
   * Stops the module. Nothing is released here, because every {@link HttpResult} stops its own web server with
   * {@link HttpResult#stop()}.
   */
  @Override
  public void stop() {
    // servers are stopped individually
  }

  /**
   * Gets the name of the module.
   *
   * @return {@code http}
   */
  @Override
  public String getModuleName() {
    return "http";
  }
}
