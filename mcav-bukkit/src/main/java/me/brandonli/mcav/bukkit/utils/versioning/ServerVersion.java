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
package me.brandonli.mcav.bukkit.utils.versioning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public enum ServerVersion {
  V_1_7_10,
  V_1_8,
  V_1_8_3,
  V_1_8_8,
  V_1_9,
  V_1_9_1,
  V_1_9_2,
  V_1_9_4,
  V_1_10,
  V_1_10_1,
  V_1_10_2,
  V_1_11,
  V_1_11_2,
  V_1_12,
  V_1_12_1,
  V_1_12_2,
  V_1_13,
  V_1_13_1,
  V_1_13_2,
  V_1_14,
  V_1_14_1,
  V_1_14_2,
  V_1_14_3,
  V_1_14_4,
  V_1_15,
  V_1_15_1,
  V_1_15_2,
  V_1_16,
  V_1_16_1,
  V_1_16_2,
  V_1_16_3,
  V_1_16_4,
  V_1_16_5,
  V_1_17,
  V_1_17_1,
  V_1_18,
  V_1_18_1,
  V_1_18_2,
  V_1_19,
  V_1_19_1,
  V_1_19_2,
  V_1_19_3,
  V_1_19_4,
  V_1_20,
  V_1_20_1,
  V_1_20_2,
  V_1_20_3,
  V_1_20_4,
  V_1_20_5,
  V_1_20_6,
  V_1_21,
  V_1_21_1,
  V_1_21_2,
  V_1_21_3,
  V_1_21_4,
  V_1_21_5,
  ERROR;

  private static final ServerVersion[] REVERSED;

  static {
    final ServerVersion[] values = ServerVersion.values();
    final List<ServerVersion> list = Arrays.asList(values);
    final List<ServerVersion> sublist = list.subList(0, list.size() - 1);
    final List<ServerVersion> reversed = new ArrayList<>(sublist);
    Collections.reverse(reversed);
    REVERSED = reversed.toArray(new ServerVersion[0]);
  }

  private final String name;

  ServerVersion() {
    final String name = this.name();
    final String sub = name.substring(2);
    this.name = sub.replace("_", ".");
  }

  public String getReleaseName() {
    return this.name;
  }

  public static ServerVersion[] getReversed() {
    return REVERSED;
  }
}
