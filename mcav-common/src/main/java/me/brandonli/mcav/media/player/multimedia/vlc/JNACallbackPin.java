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
package me.brandonli.mcav.media.player.multimedia.vlc;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility class to pin JNA callbacks to prevent them from being garbage collected.
 */
final class JNACallbackPin {

  private static final Set<Object> HELD = Collections.newSetFromMap(new ConcurrentHashMap<>());

  private JNACallbackPin() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  static <T> T pin(final T cb) {
    requireNonNull(cb);
    HELD.add(cb);
    return cb;
  }
}
