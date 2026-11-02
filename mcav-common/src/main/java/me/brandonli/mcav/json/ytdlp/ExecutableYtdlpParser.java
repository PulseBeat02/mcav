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
import me.brandonli.mcav.capability.installer.FileMode;
import me.brandonli.mcav.capability.installer.InstallationException;
import me.brandonli.mcav.capability.installer.ytdlp.YtdlpInstaller;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.utils.FileOperationException;
import me.brandonli.mcav.utils.http.DownloadException;
import me.brandonli.mcav.utils.runtime.CommandTask;
import me.brandonli.mcav.utils.runtime.ProcessException;

/**
 * The default {@link YtdlpParser}, which runs the yt-dlp executable installed by the library with
 * {@code --dump-json}.
 *
 * <p>Only the first video is resolved: {@code --no-playlist} picks the video of a watch page that also names a
 * playlist, and {@code --playlist-items 1} limits a playlist page to its first entry. The URL follows the {@code --}
 * marker, so it can never be read as an option, and only absolute {@code http} and {@code https} URLs are accepted.
 * yt-dlp is killed if it takes longer than two minutes.
 *
 * <p>This class is immutable and thread-safe; every parse runs its own yt-dlp process.
 *
 * @since 1.0.0
 */
public final class ExecutableYtdlpParser implements YtdlpParser {

  static final YtdlpParser INSTANCE = new ExecutableYtdlpParser();

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

  private static final Duration TIMEOUT = Duration.ofMinutes(2);

  private static final char LINE_SEPARATOR = '\n';

  private static final String NO_METADATA_MESSAGE = "URL %s got no metadata from yt-dlp!";

  private final ExecutableLocator locator;
  private final Function<String[], CommandTask> taskFactory;

  private ExecutableYtdlpParser() {
    this(ExecutableYtdlpParser::installYtdlp, CommandTask::new);
  }

  // Creates a parser that runs yt-dlp through the specified seams instead of the installed executable.
  @VisibleForTesting
  ExecutableYtdlpParser(final ExecutableLocator locator, final Function<String[], CommandTask> taskFactory) {
    this.locator = locator;
    this.taskFactory = taskFactory;
  }

  // Finds the yt-dlp executable, installing it first if needed. While the library prepares yt-dlp in the background,
  // this fails at once instead of waiting behind the download of the background installation.
  private static Path installYtdlp() {
    final CapabilityGuard guard = CapabilityGuard.shared();
    guard.checkNotPreparing(Capability.YT_DLP);
    final YtdlpInstaller installer = YtdlpInstaller.shared();
    return installer.download(FileMode.EXECUTABLE);
  }

  /**
   * Runs the yt-dlp executable on a URL and parses the first JSON output line. The default command disables playlists on watch pages and
   * selects the first playlist entry; supplied options can override these command defaults.
   *
   * @param input     the non-null web-page source with an absolute {@code http} or {@code https} URI
   * @param arguments a non-null array of non-null extra yt-dlp options, passed verbatim after defaults and
   *                  before the URL, which yt-dlp always reads as a URL and never as an option
   * @return the parsed metadata and streams
   * @throws InstallationException    if yt-dlp cannot be installed
   * @throws DownloadException        if the download of yt-dlp fails
   * @throws FileOperationException   if the downloaded yt-dlp cannot be moved into place
   * @throws ProcessException         if yt-dlp cannot be run, or does not finish within two minutes
   * @throws YtdlpParseException      if yt-dlp reports an error or prints no usable JSON
   * @throws IllegalArgumentException if the input is not an absolute {@code http} or {@code https} URL
   * @throws IllegalStateException    if the parser runs the yt-dlp of the library, as {@link #simple()} does, and the
   *                                  library is still preparing yt-dlp in the background; wait for
   *                                  {@link me.brandonli.mcav.MCAVApi#whenCapabilityReady(me.brandonli.mcav.capability.Capability)}
   *                                  with {@link me.brandonli.mcav.capability.Capability#YT_DLP} or try again shortly
   * @throws NullPointerException     if {@code input}, the argument array or an argument is null
   * @since 1.0.0
   */
  @Override
  public UrlParseDump parse(final UriSource input, final String... arguments) {
    Preconditions.checkNotNull(input, "Input must not be null!");
    Preconditions.checkNotNull(arguments, "Arguments must not be null!");

    for (final String argument : arguments) {
      Preconditions.checkNotNull(argument, "Arguments must not contain null!");
    }

    final URI uri = input.getUri();
    requireWebUri(uri);
    final String url = uri.toString();
    final String output = this.runYtdlp(url, arguments);
    final String json = firstLine(output);
    if (json.isEmpty()) {
      final String message = NO_METADATA_MESSAGE.formatted(url);
      throw new YtdlpParseException(message);
    }

    return parseDump(json, url);
  }

  private static void requireWebUri(final URI uri) {
    final String scheme = uri.getScheme();
    final String lowerScheme = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
    final boolean isWebUri = uri.isAbsolute() && WEB_SCHEMES.contains(lowerScheme);
    Preconditions.checkArgument(isWebUri, "URI %s must be an absolute http or https URL for yt-dlp!", uri);
  }

  private String runYtdlp(final String url, final String[] arguments) {
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
      final String message = "yt-dlp failed with \"%s\" while URL %s was being resolved!".formatted(strippedReason, url);
      throw new YtdlpParseException(message);
    }

    return output;
  }

  private static UrlParseDump parseDump(final String json, final String url) {
    final Gson gson = GsonProvider.getSimple();
    final UrlParseDump dump;
    try {
      dump = gson.fromJson(json, UrlParseDump.class);
    } catch (final JsonSyntaxException | NumberFormatException exception) {
      final String message = "URL %s got invalid metadata from yt-dlp!".formatted(url);
      throw new YtdlpParseException(message, exception);
    }

    if (dump == null) {
      final String message = NO_METADATA_MESSAGE.formatted(url);
      throw new YtdlpParseException(message);
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
    final int newline = trimmed.indexOf(LINE_SEPARATOR);
    if (newline < 0) {
      return trimmed;
    }

    final String first = trimmed.substring(0, newline);
    return first.strip();
  }

  @FunctionalInterface
  @VisibleForTesting
  interface ExecutableLocator {
    Path locate();
  }
}
