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

/**
 * Represents the version of the Minecraft server.
 */
public enum ServerVersion {
  /**
   * Server Version 1.7.10
   */
  V_1_7_10,
  /**
   * Server Version 1.8
   */
  V_1_8,
  /**
   * Server Version 1.8.3
   */
  V_1_8_3,
  /**
   * Server Version 1.8.8
   */
  V_1_8_8,
  /**
   * Server Version 1.9
   */
  V_1_9,
  /**
   * Server Version 1.9.1
   */
  V_1_9_1,
  /**
   * Server Version 1.9.2
   */
  V_1_9_2,
  /**
   * Server Version 1.9.4
   */
  V_1_9_4,
  /**
   * Server Version 1.10
   */
  V_1_10,
  /**
   * Server Version 1.10.1
   */
  V_1_10_1,
  /**
   * Server Version 1.10.2
   */
  V_1_10_2,
  /**
   * Server Version 1.11
   */
  V_1_11,
  /**
   * Server Version 1.11.2
   */
  V_1_11_2,
  /**
   * Server Version 1.12
   */
  V_1_12,
  /**
   * Server Version 1.12.1
   */
  V_1_12_1,
  /**
   * Server Version 1.12.2
   */
  V_1_12_2,
  /**
   * Server Version 1.13
   */
  V_1_13,
  /**
   * Server Version 1.13.1
   */
  V_1_13_1,
  /**
   * Server Version 1.13.2
   */
  V_1_13_2,
  /**
   * Server Version 1.14
   */
  V_1_14,
  /**
   * Server Version 1.14.1
   */
  V_1_14_1,
  /**
   * Server Version 1.14.2
   */
  V_1_14_2,
  /**
   * Server Version 1.14.3
   */
  V_1_14_3,
  /**
   * Server Version 1.14.4
   */
  V_1_14_4,
  /**
   * Server Version 1.15
   */
  V_1_15,
  /**
   * Server Version 1.15.1
   */
  V_1_15_1,
  /**
   * Server Version 1.15.2
   */
  V_1_15_2,
  /**
   * Server Version 1.16
   */
  V_1_16,
  /**
   * Server Version 1.16.1
   */
  V_1_16_1,
  /**
   * Server Version 1.16.2
   */
  V_1_16_2,
  /**
   * Server Version 1.16.3
   */
  V_1_16_3,
  /**
   * Server Version 1.16.4
   */
  V_1_16_4,
  /**
   * Server Version 1.16.5
   */
  V_1_16_5,
  /**
   * Server Version 1.17
   */
  V_1_17,
  /**
   * Server Version 1.17.1
   */
  V_1_17_1,
  /**
   * Server Version 1.18
   */
  V_1_18,
  /**
   * Server Version 1.18.1
   */
  V_1_18_1,
  /**
   * Server Version 1.18.2
   */
  V_1_18_2,
  /**
   * Server Version 1.19
   */
  V_1_19,
  /**
   * Server Version 1.19.1
   */
  V_1_19_1,
  /**
   * Server Version 1.19.2
   */
  V_1_19_2,
  /**
   * Server Version 1.19.3
   */
  V_1_19_3,
  /**
   * Server Version 1.19.4
   */
  V_1_19_4,
  /**
   * Server Version 1.20
   */
  V_1_20,
  /**
   * Server Version 1.20.1
   */
  V_1_20_1,
  /**
   * Server Version 1.20.2
   */
  V_1_20_2,
  /**
   * Server Version 1.20.3
   */
  V_1_20_3,
  /**
   * Server Version 1.20.4
   */
  V_1_20_4,
  /**
   * Server Version 1.20.5
   */
  V_1_20_5,
  /**
   * Server Version 1.20.6
   */
  V_1_20_6,
  /**
   * Server Version 1.21
   */
  V_1_21,
  /**
   * Server Version 1.21.1
   */
  V_1_21_1,
  /**
   * Server Version 1.21.2
   */
  V_1_21_2,
  /**
   * Server Version 1.21.3
   */
  V_1_21_3,
  /**
   * Server Version 1.21.4
   */
  V_1_21_4,
  /**
   * Server Version 1.21.5
   */
  V_1_21_5,
  /**
   * Invalid Version
   */
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

  /**
   * Gets the release name of this server version.
   *
   * @return the release name, e.g. "1.20.4"
   */
  public String getReleaseName() {
    return this.name;
  }

  /**
   * Gets a reversed array of all server versions, excluding the ERROR version.
   *
   * @return an array of server versions in reverse order
   */
  public static ServerVersion[] getReversed() {
    return REVERSED;
  }
}
