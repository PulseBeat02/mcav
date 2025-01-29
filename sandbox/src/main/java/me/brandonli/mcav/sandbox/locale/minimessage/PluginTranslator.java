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
package me.brandonli.mcav.sandbox.locale.minimessage;

import java.util.Locale;
import java.util.ResourceBundle;
import net.kyori.adventure.key.Key;
import org.checkerframework.checker.nullness.qual.NonNull;

public final class PluginTranslator extends MiniMessageTranslator {

  private final Key key;
  private final ResourceBundle bundle;

  public PluginTranslator(final Key key, final ResourceBundle bundle) {
    this.key = key;
    this.bundle = bundle;
  }

  @Override
  protected String getMiniMessageString(final String key, final Locale locale) {
    return this.bundle.getString(key);
  }

  @Override
  public @NonNull Key name() {
    return this.key;
  }
}
