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
package me.brandonli.mcav.json.ytdlp;

import com.google.gson.Gson;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import me.brandonli.mcav.capability.installer.ytdlp.YTDLPInstaller;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.utils.runtime.CommandTask;

/**
 * This class is a singleton that implements the YTDLPParser interface.
 * It uses a cache to store the results of URL parsing for 30 minutes.
 * The cache has a maximum size of 100 entries.
 */
public final class YTDLPParserImpl implements YTDLPParser {

  static final YTDLPParser INSTANCE = new YTDLPParserImpl();

  YTDLPParserImpl() {
    // no-op
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public URLParseDump parse(final UriSource input, final String... arguments) throws IOException {
    final URI uri = input.getUri();
    final String raw = uri.toString();
    final YTDLPInstaller installer = YTDLPInstaller.create();
    final Path path = installer.download(true);
    final String executable = path.toString();
    final String[] args = this.constructArguments(executable, raw, arguments);
    final CommandTask task = new CommandTask(args, true);
    final Gson gson = GsonProvider.getSimple();
    final String output = task.getOutput();
    return gson.fromJson(output, URLParseDump.class);
  }

  private String[] constructArguments(final String executable, final String raw, final String... arguments) {
    final String[] args = new String[4 + arguments.length];
    args[0] = executable;
    args[1] = "--dump-json";
    args[2] = "--no-cache-dir";
    args[3] = raw;

    if (arguments.length > 0) {
      System.arraycopy(arguments, 0, args, 4, arguments.length);
    }

    return args;
  }
}
