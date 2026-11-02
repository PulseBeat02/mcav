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
package me.brandonli.mcav.bukkit;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import me.brandonli.mcav.bukkit.resourcepack.SimpleResourcePack;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.utils.ffmpeg.AudioExtractor;

public final class PackBuilderExample {

  private static final URI VIDEO = URI.create("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4");

  private static final String PACK_FILE_NAME = "pack.zip";

  private static final String SOUND_KEY = "mcav:example";

  private PackBuilderExample() {
    throw new UnsupportedOperationException("Example class cannot be instantiated!");
  }

  static void main(final String[] args) throws IOException {
    if (args.length != 1) {
      final String message = "Usage: PackBuilderExample <resource-pack-format>";
      throw new IllegalArgumentException(message);
    }

    final int format = Integer.parseInt(args[0]);
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    pack.metadata(format, "MCAV example audio");
    final UriSource video = UriSource.uri(VIDEO);
    final Path sound = AudioExtractor.extractOggVorbis(video);
    pack.sound(SOUND_KEY, sound);
    final Path destination = Path.of(PACK_FILE_NAME);
    pack.zip(destination);
  }
}
