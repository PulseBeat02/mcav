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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.capability.CapabilityGuard;
import me.brandonli.mcav.capability.installer.ytdlp.YTDLPInstaller;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.utils.runtime.CommandTask;

/**
 * The default {@link YTDLPParser}, which runs the yt-dlp executable installed by the library with
 * {@code --dump-json}.
 *
 * <p>Only the first video is resolved: {@code --no-playlist} picks the video of a watch page that also names a
 * playlist, and {@code --playlist-items 1} limits a playlist page to its first entry. The URL follows the {@code --}
 * marker, so it can never be read as an option, and only absolute {@code http} and {@code https} URLs are accepted.
 * yt-dlp is killed if it takes longer than two minutes.
 */
public final class YTDLPParserImpl implements YTDLPParser {

  static final YTDLPParser INSTANCE = new YTDLPParserImpl();

  private static final String DUMP_JSON = "--dump-json";
  private static final String NO_CACHE = "--no-cache-dir";
  private static final String NO_PLAYLIST = "--no-playlist";
  private static final String PLAYLIST_ITEMS = "--playlist-items";
  private static final String FIRST_ITEM = "1";
  private static final String NO_WARNINGS = "--no-warnings";
  // every argument after this marker is a URL, so a URL such as --exec=... can never be read as an option
  private static final String END_OF_OPTIONS = "--";
  private static final int FIXED_ARGUMENTS = 9;
  private static final Set<String> WEB_SCHEMES = Set.of("http", "https");
  // resolving a page takes a few seconds; a yt-dlp stuck on an unresponsive site must not block its caller forever
  private static final Duration TIMEOUT = Duration.ofMinutes(2);

  private final ExecutableLocator locator;
  private final Function<String[], CommandTask> taskFactory;

  YTDLPParserImpl() {
    // use YTDLPParser.simple()
    this(YTDLPParserImpl::installYtdlp, CommandTask::new);
  }

  /**
   * Creates a parser that runs yt-dlp through the specified seams instead of the installed executable.
   *
   * @param locator     finds the yt-dlp executable, installing it if needed
   * @param taskFactory creates the task that runs a command line
   */
  @VisibleForTesting
  YTDLPParserImpl(final ExecutableLocator locator, final Function<String[], CommandTask> taskFactory) {
    this.locator = locator;
    this.taskFactory = taskFactory;
  }

  /**
   * Finds the yt-dlp executable.
   */
  @FunctionalInterface
  @VisibleForTesting
  interface ExecutableLocator {
    /**
     * Finds the yt-dlp executable, installing it first if needed.
     *
     * @return the path of the executable
     * @throws IOException if yt-dlp cannot be installed
     */
    Path locate() throws IOException;
  }

  /**
   * Finds the yt-dlp executable, installing it first if needed. While the library prepares yt-dlp in the background,
   * this fails at once instead of waiting behind the download of the background installation.
   *
   * @throws IllegalStateException if the library is still preparing yt-dlp in the background
   */
  private static Path installYtdlp() throws IOException {
    final CapabilityGuard guard = CapabilityGuard.shared();
    guard.checkNotPreparing(Capability.YT_DLP);
    // the installer is shared with the rest of the library, so concurrent parses download yt-dlp at most once
    final YTDLPInstaller installer = YTDLPInstaller.shared();
    return installer.download(true);
  }

  @Override
  public URLParseDump parse(final UriSource input, final String... arguments) throws IOException {
    Preconditions.checkNotNull(input, "Input must not be null");
    Preconditions.checkNotNull(arguments, "Arguments must not be null");
    for (final String argument : arguments) {
      Preconditions.checkNotNull(argument, "Arguments must not contain null");
    }
    final URI uri = input.getUri();
    requireWebUri(uri);
    final String url = uri.toString();
    final String output = this.runYtdlp(url, arguments);
    final String json = firstLine(output);
    if (json.isEmpty()) {
      throw new YTDLPParseException("yt-dlp printed no metadata for " + url);
    }
    return parseDump(json, url);
  }

  private static void requireWebUri(final URI uri) {
    final String scheme = uri.getScheme();
    final String lowerScheme = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
    final boolean web = uri.isAbsolute() && WEB_SCHEMES.contains(lowerScheme);
    Preconditions.checkArgument(web, "yt-dlp only resolves absolute http and https URLs, but got %s", uri);
  }

  /**
   * Runs yt-dlp, installing it first if needed.
   *
   * @return the standard output of yt-dlp
   * @throws YTDLPParseException if yt-dlp exits with an error
   */
  private String runYtdlp(final String url, final String[] arguments) throws IOException {
    final Path executablePath = this.locator.locate();
    final String executable = executablePath.toString();
    final String[] command = buildCommand(executable, url, arguments);
    final CommandTask task = this.taskFactory.apply(command);
    final int exitCode = task.run(TIMEOUT);
    final String output = task.getOutput();
    if (exitCode != 0) {
      final String errorOutput = task.getErrorOutput();
      final String reason = errorOutput.isBlank() ? output : errorOutput;
      final String strippedReason = reason.strip();
      throw new YTDLPParseException("yt-dlp failed for %s: %s".formatted(url, strippedReason));
    }
    return output;
  }

  private static URLParseDump parseDump(final String json, final String url) {
    final Gson gson = GsonProvider.getSimple();
    final URLParseDump dump;
    try {
      dump = gson.fromJson(json, URLParseDump.class);
    } catch (final JsonSyntaxException exception) {
      // also thrown for text after the document and for values that do not fit their field
      throw new YTDLPParseException("yt-dlp printed invalid metadata for " + url, exception);
    }
    if (dump == null) {
      throw new YTDLPParseException("yt-dlp printed no metadata for " + url);
    }
    return dump;
  }

  private static String[] buildCommand(final String executable, final String url, final String[] arguments) {
    final List<String> command = new ArrayList<>(FIXED_ARGUMENTS + arguments.length);
    command.add(executable);
    command.add(DUMP_JSON);
    command.add(NO_CACHE);
    command.add(NO_PLAYLIST);
    command.add(PLAYLIST_ITEMS);
    command.add(FIRST_ITEM);
    command.add(NO_WARNINGS);
    final List<String> extraArguments = List.of(arguments);
    command.addAll(extraArguments);
    command.add(END_OF_OPTIONS);
    command.add(url);
    return command.toArray(new String[0]);
  }

  private static String firstLine(final String output) {
    final String trimmed = output.strip();
    final int newline = trimmed.indexOf('\n');
    if (newline < 0) {
      return trimmed;
    }
    final String first = trimmed.substring(0, newline);
    return first.strip();
  }
}
