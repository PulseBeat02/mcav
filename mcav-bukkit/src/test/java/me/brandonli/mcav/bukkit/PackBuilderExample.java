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
import me.brandonli.mcav.bukkit.utils.resourcepack.SoundExtractorUtils;
import me.brandonli.mcav.media.source.UriSource;

public final class PackBuilderExample {

  public static void main(final String[] args) throws IOException {
    final UriSource audio = UriSource.uri(URI.create("http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"));
    final Path ogg = SoundExtractorUtils.extractOggAudio(audio); // temporary path
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    pack.sound("mcav:example", ogg);
    final Path dest = Path.of("pack.zip");
    pack.zip(dest);
  }
}
