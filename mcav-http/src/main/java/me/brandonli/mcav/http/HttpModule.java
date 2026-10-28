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
 * Registers the HTTP audio backend. Servers are created with the factories of {@link HttpResult}, so the module only
 * has to be installed:
 *
 * {@snippet :
 * final MCAVApi api = MCAV.api();
 * api.install(HttpModule.class);
 * }
 *
 * <p>The module holds no state; stopping it does not stop the servers you started, so stop each {@link HttpResult}
 * yourself with {@link HttpResult#stop()}.
 *
 * <p>This class is thread-safe.
 *
 * @since 1.0.0
 */
public final class HttpModule implements MCAVModule {

  private static final String MODULE_NAME = "http";

  /**
   * Creates the module. The module loader calls this constructor for you.
   *
   * @since 1.0.0
   */
  public HttpModule() {}

  /**
   * Starts the module. Nothing is prepared here, because every {@link HttpResult} starts its own web server with
   * {@link HttpResult#start()}.
   *
   * @since 1.0.0
   */
  @Override
  public void start() {}

  /**
   * Stops the module. Nothing is released here, because every {@link HttpResult} stops its own web server with
   * {@link HttpResult#stop()}.
   *
   * @since 1.0.0
   */
  @Override
  public void stop() {}

  /**
   * Returns the name of the module.
   *
   * @return {@code http}
   * @since 1.0.0
   */
  @Override
  public String getModuleName() {
    return MODULE_NAME;
  }
}
