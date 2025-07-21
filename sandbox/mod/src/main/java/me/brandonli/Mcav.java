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
package me.brandonli;

import me.brandonli.block.ModBlockEntities;
import me.brandonli.block.ModBlocks;
import me.brandonli.command.HelloCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

public class Mcav implements ModInitializer {

  public static final String MOD_ID = "mcav";

  @Override
  public void onInitialize() {
    this.registerBlocks();
    this.registerServerCommands();
  }

  private void registerBlocks() {
    ModBlocks.init();
    ModBlockEntities.init();
  }

  private void registerServerCommands() {
    CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
      HelloCommand.register(dispatcher);
    });
  }
}
