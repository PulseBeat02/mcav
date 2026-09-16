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
package me.brandonli.mcav.jda;

import me.brandonli.mcav.module.MCAVModule;

/**
 * Registers the Discord backend. Install it with {@code MCAV.api().install(JDAModule.class)}; the bot itself is
 * created and logged in by your code with JDA.
 */
public final class JDAModule implements MCAVModule {

  /**
   * Constructs the module. The module loader creates it for you.
   */
  public JDAModule() {
    // stateless
  }

  /**
   * Starts the module. Nothing is prepared here, because the bot is logged in by your code and every
   * {@link DiscordPlayer} is created with {@link DiscordPlayer#voice(net.dv8tion.jda.api.JDA)}.
   */
  @Override
  public void start() {
    // nothing to prepare
  }

  /**
   * Stops the module. Nothing is released here, because the bot and its voice connections belong to your code.
   */
  @Override
  public void stop() {
    // nothing to release
  }

  /**
   * Gets the name of the module.
   *
   * @return {@code jda}
   */
  @Override
  public String getModuleName() {
    return "jda";
  }
}
