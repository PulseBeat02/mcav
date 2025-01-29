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
package me.brandonli.mcav.sandbox.command.video;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VideoFlagsParser {

  private static final String YT_DLP_FLAGS = "--yt-dlp";
  private static final Pattern PATTERN = Pattern.compile(YT_DLP_FLAGS + "\\{(.+?)}");

  public VideoFlagsParser() {
    // no-op
  }

  // example flag --yt-dlp{format=...,other=...}
  public String[] parseYTDLPFlags(final String flags) {
    if (flags == null || flags.isEmpty()) {
      return new String[0];
    }

    final Optional<String> optional = this.searchForFlag(flags);
    if (optional.isEmpty()) {
      return new String[0];
    }

    final String flag = optional.get();
    final Matcher matcher = PATTERN.matcher(flag);
    if (!matcher.find()) {
      return new String[0];
    }

    final String content = matcher.group(1);
    if (content == null || content.isEmpty()) {
      return new String[0];
    }

    final String[] pairs = content.split("(?<!\\\\),");
    final String[] result = new String[pairs.length];
    for (int i = 0; i < pairs.length; i++) {
      final String[] keyValue = pairs[i].split("=", 2);
      if (keyValue.length == 2) {
        result[i] = "--" + keyValue[0].trim() + " " + keyValue[1].trim();
      } else {
        result[i] = "--" + keyValue[0].trim();
      }
    }

    return result;
  }

  private Optional<String> searchForFlag(final String flags) {
    final String[] split = flags.split("(?<!\\\\)\\s+");
    String result = null;
    for (final String flag : split) {
      if (!flag.startsWith(YT_DLP_FLAGS)) {
        continue;
      }
      result = flag;
      break;
    }
    return Optional.ofNullable(result);
  }
}
