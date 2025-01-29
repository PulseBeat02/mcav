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
package me.brandonli.mcav.sandbox.data;

import java.util.Map;

public enum ProviderMethod {
  MC_PACK_HOSTING,
  LOCALLY_HOSTED_DAEMON,
  ON_SERVER;

  private static final Map<String, ProviderMethod> LOOKUP_TABLE = Map.of(
    "MC_PACK_HOSTING",
    MC_PACK_HOSTING,
    "LOCALLY_HOSTED_DAEMON",
    LOCALLY_HOSTED_DAEMON,
    "ON_SERVER",
    ON_SERVER
  );

  public static ProviderMethod fromString(final String locale) {
    final String upper = locale.toUpperCase();
    return LOOKUP_TABLE.getOrDefault(upper, ProviderMethod.MC_PACK_HOSTING);
  }
}
