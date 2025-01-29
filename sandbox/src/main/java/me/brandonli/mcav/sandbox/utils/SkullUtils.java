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
package me.brandonli.mcav.sandbox.utils;

/*

MIT License

Copyright (c) 2025 Brandon Li

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

*/

import static java.util.Objects.requireNonNull;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Base64;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

public final class SkullUtils {

  private SkullUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  public static ItemStack getSkull(final String base64) {
    final URL url = getUrlFromBase64(base64);
    final UUID random = UUID.randomUUID();
    final PlayerProfile profile = Bukkit.createPlayerProfile(random);
    final PlayerTextures textures = profile.getTextures();
    textures.setSkin(url);
    profile.setTextures(textures);
    final ItemStack head = new ItemStack(Material.PLAYER_HEAD);
    final ItemMeta meta = requireNonNull(head.getItemMeta());
    final SkullMeta skullMeta = (SkullMeta) meta;
    skullMeta.setOwnerProfile(profile);
    head.setItemMeta(skullMeta);
    return head;
  }

  public static URL getUrlFromBase64(final String base64) {
    final Base64.Decoder decoder = Base64.getDecoder();
    final byte[] decodedBytes = decoder.decode(base64);
    final String decoded = new String(decodedBytes);
    final int start = "{\"textures\":{\"SKIN\":{\"url\":\"".length();
    final int length = decoded.length();
    final int end = length - "\"}}}".length();
    final String url = decoded.substring(start, end);
    final URI uri = URI.create(url);
    try {
      return uri.toURL();
    } catch (final MalformedURLException e) {
      throw new AssertionError(e);
    }
  }
}
