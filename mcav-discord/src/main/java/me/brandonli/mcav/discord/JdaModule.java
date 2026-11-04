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
package me.brandonli.mcav.discord;

import me.brandonli.mcav.module.MCAVModule;

/**
 * Registers the Discord backend. The bot itself is created and logged in by your code with JDA, so the module only
 * has to be installed:
 *
 * {@snippet :
 * final MCAVApi api = MCAV.api();
 * api.install(JdaModule.class);
 * }
 *
 * <p>The module holds no state; stopping it does not close the bot or its voice connections, so release each
 * {@link DiscordPlayer} you created as its documentation describes.
 *
 * <p>This class is thread-safe.
 *
 * @since 1.0.0
 */
public final class JdaModule implements MCAVModule {

  private static final String MODULE_NAME = "jda";

  /**
   * Creates the module. The module loader calls this for you.
   *
   * @since 1.0.0
   */
  public JdaModule() {}

  /**
   * Starts the module. Nothing is prepared here, because the bot is logged in by your code and every
   * {@link DiscordPlayer} is created with {@link DiscordPlayer#voice(net.dv8tion.jda.api.JDA)}.
   *
   * @since 1.0.0
   */
  @Override
  public void start() {}

  /**
   * Stops the module. Nothing is released here, because the bot and its voice connections belong to your code.
   *
   * @since 1.0.0
   */
  @Override
  public void stop() {}

  /**
   * Returns the name of the module.
   *
   * @return {@code jda}
   * @since 1.0.0
   */
  @Override
  public String getModuleName() {
    return MODULE_NAME;
  }
}
