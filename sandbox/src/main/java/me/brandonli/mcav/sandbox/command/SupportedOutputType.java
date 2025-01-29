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
package me.brandonli.mcav.sandbox.command;

import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;

public interface SupportedOutputType {
  enum BrowserSupportedOutputs {
    MAP;

    private static final Map<String, BrowserSupportedOutputs> LOOK_UP = Map.of("map", MAP);

    public static @Nullable BrowserSupportedOutputs from(final String name) {
      return LOOK_UP.get(name.toLowerCase());
    }
  }

  enum VideoSupportedOutputs {
    MAP,
    CHAT,
    SCOREBOARD,
    ENTITY;

    private static final Map<String, VideoSupportedOutputs> LOOK_UP = Map.of(
      "map",
      MAP,
      "chat",
      CHAT,
      "scoreboard",
      SCOREBOARD,
      "entity",
      ENTITY
    );

    public static @Nullable VideoSupportedOutputs from(final String name) {
      return LOOK_UP.get(name.toLowerCase());
    }
  }
}
