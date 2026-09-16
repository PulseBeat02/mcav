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
package me.brandonli.mcav.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import me.brandonli.mcav.capability.installer.ytdlp.YTDLPInstaller;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.json.ytdlp.YTDLPParser;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.device.DeviceSource;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link SourceUtils}.
 */
final class SourceUtilsTest {

  @TempDir
  private Path directory;

  private static boolean isGif(final String path) {
    final Path file = Path.of(path);
    final FileSource source = FileSource.path(file);
    return SourceUtils.isImageGif(source);
  }

  @Test
  void recognizesGifFilesAndUris() {
    final URI gifUri = URI.create("https://example.com/animation.gif?size=large");
    final URI opaqueUri = URI.create("mailto:someone@example.com");
    final UriSource gifSource = UriSource.uri(gifUri);
    final UriSource opaqueSource = UriSource.uri(opaqueUri);
    final Source device = DeviceSource.device(0);
    final boolean upperCase = isGif("images/ANIMATION.GIF");
    final boolean doubleExtension = isGif("images/animation.gif.mp4");
    final boolean dotInDirectory = isGif("images.gif/animation");
    final boolean trailingDot = isGif("animation.");
    final boolean backslashDirectory = isGif("images.gif\\animation");
    final boolean gifUriDetected = SourceUtils.isImageGif(gifSource);
    final boolean opaqueDetected = SourceUtils.isImageGif(opaqueSource);
    final boolean deviceDetected = SourceUtils.isImageGif(device);
    assertTrue(upperCase);
    assertFalse(doubleExtension);
    assertFalse(dotInDirectory);
    assertFalse(trailingDot);
    assertFalse(backslashDirectory);
    assertTrue(gifUriDetected);
    assertFalse(opaqueDetected);
    assertFalse(deviceDetected);
    assertThrows(NullPointerException.class, () -> SourceUtils.isImageGif(null));
  }

  @Test
  void recognizesUrisWithSchemeAndHost() {
    final boolean web = SourceUtils.isUri("https://example.com/video");
    final boolean stream = SourceUtils.isUri("rtsp://camera.local/live");
    final boolean drive = SourceUtils.isUri("c:/videos/clip.mp4");
    final boolean invalid = SourceUtils.isUri("http://[");
    assertTrue(web);
    assertTrue(stream);
    assertFalse(drive);
    assertFalse(invalid);
    assertThrows(NullPointerException.class, () -> SourceUtils.isUri(null));
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "https://example.com/clip.MP4",
      "https://example.com/live/stream.m3u8",
      "http://x.org/a/b/song.flac?x=1",
      "https://example.com/audio.mka",
      "https://example.com/phone.3gp",
      "https://example.com/bluray.m2ts",
      "https://example.com/camera.mts",
      "https://example.com/picture.avif",
      "https://example.com/dvd.vob",
    }
  )
  void recognizesDirectMediaLinks(final String url) {
    final boolean direct = SourceUtils.isDirectVideo(url);
    assertTrue(direct);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
    strings = { "", "https://example.com", "https://example.com/watch.php", "https://example.com/page", "mailto:a@b.c", "http://[" }
  )
  void rejectsLinksThatAreNotMediaFiles(final @Nullable String url) {
    final boolean direct = SourceUtils.isDirectVideo(url);
    assertFalse(direct);
  }

  @Test
  void readsAnExtensionOnlyAfterTheLastSeparator() {
    final boolean wholeWord = SourceUtils.isDirectVideo("mp4");
    final boolean directoryDot = SourceUtils.isDirectVideo("https://example.com/video.files/clip");
    final boolean trailingDot = SourceUtils.isDirectVideo("https://example.com/clip.");
    assertFalse(wholeWord, "a path without a dot has no extension, even when it reads like one");
    assertFalse(directoryDot, "a dot in a directory name is not the extension of the file");
    assertFalse(trailingDot, "a trailing dot leaves no extension behind it");
  }

  @Test
  void recognizesExistingPaths() throws IOException {
    final Path file = this.directory.resolve("exists.txt");
    Files.createFile(file);
    final String existingRaw = file.toString();
    final Path missingFile = this.directory.resolve("missing.txt");
    final String missingRaw = missingFile.toString();
    final boolean existing = SourceUtils.isPath(existingRaw);
    final boolean missing = SourceUtils.isPath(missingRaw);
    final boolean invalid = SourceUtils.isPath("bad\u0000path");
    assertTrue(existing);
    assertFalse(missing);
    assertFalse(invalid);
    assertThrows(NullPointerException.class, () -> SourceUtils.isPath(null));
  }

  @Test
  void asksTheParserWhetherAStreamIsLive() {
    final URLParseDump live = parseDump("{\"id\": \"live\", \"is_live\": true}");
    final URLParseDump recorded = parseDump("{\"id\": \"recorded\", \"is_live\": false}");
    final List<URI> asked = new ArrayList<>();
    final YTDLPParser liveParser = (input, _) -> {
      final URI uri = input.getUri();
      asked.add(uri);
      return live;
    };
    final YTDLPParser recordedParser = (_, _) -> recorded;

    final boolean liveResult = SourceUtils.isDynamicStream("https://example.com/live", liveParser);
    final boolean recordedResult = SourceUtils.isDynamicStream("https://example.com/recorded", recordedParser);
    final boolean invalidResult = SourceUtils.isDynamicStream("http://[", liveParser);

    final URI expectedUri = URI.create("https://example.com/live");
    final List<URI> expectedAsked = List.of(expectedUri);
    assertTrue(liveResult);
    assertFalse(recordedResult);
    assertFalse(invalidResult);
    assertEquals(expectedAsked, asked, "an invalid URL is never handed to the parser");
  }

  @Test
  void reportsNoLiveStreamWhenTheParserFails() {
    final YTDLPParser offlineParser = (_, _) -> {
      throw new IOException("offline");
    };
    final YTDLPParser rejectingParser = (_, _) -> {
      throw new IllegalArgumentException("not a web URL");
    };

    final boolean offlineResult = SourceUtils.isDynamicStream("https://example.com/live", offlineParser);
    final boolean rejectedResult = SourceUtils.isDynamicStream("ftp://example.com/live", rejectingParser);

    assertFalse(offlineResult, "a failing lookup is not a live stream");
    assertFalse(rejectedResult, "a URL the parser rejects is not a live stream");
  }

  private static URLParseDump parseDump(final String json) {
    final Gson gson = GsonProvider.getSimple();
    return gson.fromJson(json, URLParseDump.class);
  }

  @Test
  void asksTheSharedYtdlpInstallationWithoutTouchingTheNetwork() throws IOException {
    final YTDLPInstaller installer = Mockito.mock(YTDLPInstaller.class);
    final IOException offline = new IOException("offline");
    Mockito.when(installer.download(true)).thenThrow(offline);
    try (final MockedStatic<YTDLPInstaller> installers = Mockito.mockStatic(YTDLPInstaller.class)) {
      installers.when(YTDLPInstaller::shared).thenReturn(installer);
      final boolean live = SourceUtils.isDynamicStream("https://example.com/live");
      assertFalse(live, "yt-dlp that cannot be installed reports no live stream");
    }
    final YTDLPInstaller verifiedInstaller = Mockito.verify(installer);
    verifiedInstaller.download(true);
    assertThrows(NullPointerException.class, () -> SourceUtils.isDynamicStream(null));
  }

  @Test
  void cannotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(SourceUtils.class);
  }
}
