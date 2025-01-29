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
package me.brandonli.mcav;

import static java.util.Objects.requireNonNull;

import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;

public final class BlockTextureCalculator {

  @SuppressWarnings("nullness")
  public static void main(final String[] args) {
    final Map<String, Integer> textureMap = new HashMap<>();
    final Collection<String> types = ItemTypes.values()
      .stream()
      .map(ItemType::getPlacedType)
      .filter(Objects::nonNull)
      .filter(StateType::isSolid)
      .map(StateType::getName)
      .map(String::toLowerCase)
      .collect(Collectors.toSet());
    try {
      final URL resourceUrl = requireNonNull(BlockTextureCalculator.class.getClassLoader()).getResource("textures.zip");
      try (final InputStream is = requireNonNull(resourceUrl).openStream(); final ZipInputStream zipStream = new ZipInputStream(is)) {
        ZipEntry entry;
        while ((entry = zipStream.getNextEntry()) != null) {
          if (entry.isDirectory()) {
            continue;
          }
          final byte[] bytes;
          if (entry.getSize() > 0) {
            bytes = new byte[(int) entry.getSize()];
            zipStream.read(bytes);
          } else {
            bytes = zipStream.readAllBytes();
          }

          final String name = entry.getName();
          final String withoutExtension = name.substring(0, name.lastIndexOf('.'));
          if (types.contains(withoutExtension)) {
            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            final int width = image.getWidth();
            final int height = image.getHeight();
            long totalRed = 0;
            long totalGreen = 0;
            long totalBlue = 0;
            long pixelCount = 0;
            for (int y = 0; y < height; y++) {
              for (int x = 0; x < width; x++) {
                final int rgb = image.getRGB(x, y);
                final int alpha = (rgb >> 24) & 0xff;
                if (alpha > 0) {
                  final int red = (rgb >> 16) & 0xff;
                  final int green = (rgb >> 8) & 0xff;
                  final int blue = rgb & 0xff;
                  totalRed += red;
                  totalGreen += green;
                  totalBlue += blue;
                  pixelCount++;
                }
              }
            }
            if (pixelCount > 0) {
              final int avgRed = (int) (totalRed / pixelCount);
              final int avgGreen = (int) (totalGreen / pixelCount);
              final int avgBlue = (int) (totalBlue / pixelCount);
              final int avgColor = (avgRed << 16) | (avgGreen << 8) | avgBlue;
              textureMap.put(withoutExtension, avgColor);
            }
          }
          zipStream.closeEntry();
        }
      }
    } catch (final IOException e) {
      throw new AssertionError(e);
    }
  }
}
