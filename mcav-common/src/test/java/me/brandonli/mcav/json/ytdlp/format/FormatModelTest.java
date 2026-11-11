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
package me.brandonli.mcav.json.ytdlp.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.ReflectionAccessFilter;
import com.google.gson.ReflectionAccessFilter.FilterResult;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.media.source.uri.UriSource;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class FormatModelTest {

  private static final double DELTA = 1.0e-9;

  private static final String FIXTURE_RESOURCE = "/ytdlp/video.json";

  private static final String THUMBNAIL_URL = "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg";
  private static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64)";
  private static final int HTTP_CHUNK_SIZE = 10485760;
  private static final String REPOSITORY = "yt-dlp/yt-dlp";

  private static final int VIDEO_FORMAT_INDEX = 2;
  private static final int VIDEO_WIDTH = 1920;
  private static final int VIDEO_HEIGHT = 1080;
  private static final double FRAMES_PER_SECOND = 29.97;
  private static final String DYNAMIC_RANGE = "SDR";
  private static final String RESOLUTION = "1920x1080";

  private static final String AUDIO_CODEC = "opus";
  private static final int SAMPLE_RATE = 48000;
  private static final int AUDIO_CHANNELS = 2;
  private static final String LANGUAGE = "en";

  private static final List<Class<?>> MODELS = List.of(
    DownloaderOptions.class,
    Format.class,
    Fragment.class,
    Heatmap.class,
    HttpHeaders.class,
    Thumbnail.class,
    UrlParseDump.class,
    Version.class
  );

  private static final String BASE_TEXT = "a";
  private static final String CHANGED_TEXT = "b";
  private static final int BASE_NUMBER = 1;
  private static final int CHANGED_NUMBER = 2;
  private static final double BASE_FRACTION = 1.5;
  private static final double CHANGED_FRACTION = 2.5;

  private static final String TITLE = "Clip";
  private static final String DESCRIPTION = "Video from a file";

  private static final String ABSENT = "none";

  private static final ReflectionAccessFilter MODEL_FIELDS_BLOCKED = FormatModelTest::blockModels;

  private static UrlParseDump fixture() throws IOException {
    final String json = fixtureText();
    final Gson gson = GsonProvider.getSimple();
    return gson.fromJson(json, UrlParseDump.class);
  }

  private static String fixtureText() throws IOException {
    try (final InputStream stream = FormatModelTest.class.getResourceAsStream(FIXTURE_RESOURCE)) {
      assertNotNull(stream, "the fixture must be on the test classpath");

      final byte[] bytes = stream.readAllBytes();
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  private static Format format(final String json) {
    final Gson gson = GsonProvider.getSimple();
    return gson.fromJson(json, Format.class);
  }

  @Test
  void shouldReadTheVideoMetadata() throws IOException {
    final UrlParseDump dump = fixture();
    final List<String> categories = dump.getCategories();
    final List<String> tags = dump.getTags();
    final List<String> sortFields = dump.getFormatSortFields();
    final Optional<String> id = dump.getId();
    final Optional<String> expectedId = Optional.of("dQw4w9WgXcQ");
    final Optional<String> title = dump.getTitle();
    final Optional<String> expectedTitle = Optional.of("Example Video");
    final Optional<String> fullTitle = dump.getFullTitle();
    final Optional<String> description = dump.getDescription();
    final Optional<String> expectedDescription = Optional.of("An example description\nwith two lines");
    final Optional<String> thumbnail = dump.getThumbnail();
    final Optional<String> expectedThumbnail = Optional.of(THUMBNAIL_URL);
    final Optional<String> channelId = dump.getChannelId();
    final Optional<String> expectedChannelId = Optional.of("UCuAXFkgsw1L7xaCfnd5JJOw");
    final Optional<String> channelUrl = dump.getChannelUrl();
    final Optional<String> expectedChannelUrl = Optional.of("https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw");
    final Optional<String> channel = dump.getChannel();
    final Optional<String> expectedChannel = Optional.of("Example Channel");
    final boolean channelVerified = dump.isChannelVerified();
    final Optional<String> uploader = dump.getUploader();
    final Optional<String> expectedUploader = Optional.of("Example Uploader");
    final Optional<String> uploaderId = dump.getUploaderId();
    final Optional<String> expectedUploaderId = Optional.of("@example");
    final Optional<String> uploaderUrl = dump.getUploaderUrl();
    final Optional<String> expectedUploaderUrl = Optional.of("https://www.youtube.com/@example");
    final Optional<String> uploadDate = dump.getUploadDate();
    final Optional<String> expectedUploadDate = Optional.of("20091025");
    final int timestamp = dump.getTimestamp();
    final double duration = dump.getDuration();
    final Optional<String> durationString = dump.getDurationString();
    final Optional<String> expectedDurationString = Optional.of("3:32");
    final long viewCount = dump.getViewCount();
    final long likeCount = dump.getLikeCount();
    final int ageLimit = dump.getAgeLimit();
    final Optional<String> webpageUrl = dump.getWebpageUrl();
    final Optional<String> expectedWebpageUrl = Optional.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
    final Optional<String> originalUrl = dump.getOriginalUrl();
    final Optional<String> expectedOriginalUrl = Optional.of("https://youtu.be/dQw4w9WgXcQ");
    final Optional<String> webpageUrlBasename = dump.getWebpageUrlBasename();
    final Optional<String> expectedWebpageUrlBasename = Optional.of("watch");
    final Optional<String> webpageUrlDomain = dump.getWebpageUrlDomain();
    final Optional<String> expectedWebpageUrlDomain = Optional.of("youtube.com");
    final Optional<String> extractor = dump.getExtractor();
    final Optional<String> expectedExtractor = Optional.of("youtube");
    final Optional<String> extractorKey = dump.getExtractorKey();
    final Optional<String> expectedExtractorKey = Optional.of("Youtube");
    final Optional<String> displayId = dump.getDisplayId();
    final Optional<String> availability = dump.getAvailability();
    final Optional<String> expectedAvailability = Optional.of("public");
    final Optional<String> liveStatus = dump.getLiveStatus();
    final Optional<String> expectedLiveStatus = Optional.of("not_live");
    final boolean playableInEmbed = dump.isPlayableInEmbed();
    final boolean live = dump.isLive();
    final boolean wasLive = dump.wasLive();
    assertEquals(expectedId, id);
    assertEquals(expectedTitle, title);
    assertEquals(expectedTitle, fullTitle);
    assertEquals(expectedDescription, description);
    assertEquals(expectedThumbnail, thumbnail);
    assertEquals(expectedChannelId, channelId);
    assertEquals(expectedChannelUrl, channelUrl);
    assertEquals(expectedChannel, channel);
    assertTrue(channelVerified);
    assertEquals(expectedUploader, uploader);
    assertEquals(expectedUploaderId, uploaderId);
    assertEquals(expectedUploaderUrl, uploaderUrl);
    assertEquals(expectedUploadDate, uploadDate);
    assertEquals(1256453732, timestamp);
    assertEquals(212, duration);
    assertEquals(expectedDurationString, durationString);
    assertEquals(1700000000, viewCount);
    assertEquals(18000000, likeCount);
    assertEquals(0, ageLimit);
    assertEquals(expectedWebpageUrl, webpageUrl);
    assertEquals(expectedOriginalUrl, originalUrl);
    assertEquals(expectedWebpageUrlBasename, webpageUrlBasename);
    assertEquals(expectedWebpageUrlDomain, webpageUrlDomain);
    assertEquals(expectedExtractor, extractor);
    assertEquals(expectedExtractorKey, extractorKey);
    assertEquals(expectedId, displayId);
    assertEquals(expectedAvailability, availability);
    assertEquals(expectedLiveStatus, liveStatus);
    assertTrue(playableInEmbed);
    assertFalse(live);
    assertFalse(wasLive);

    final List<String> expectedCategories = List.of("Music");
    final List<String> expectedTags = List.of("example", "video");
    final List<String> expectedSortFields = List.of("quality", "res", "fps");
    final int epoch = dump.getEpoch();
    final Optional<String> type = dump.getType();
    final Optional<String> expectedType = Optional.of("video");
    final Optional<String> fileName = dump.getFileName();
    final Optional<String> expectedFileName = Optional.of("Example Video [dQw4w9WgXcQ].mp4");
    final Optional<String> fileNameAlias = dump.getFileNameAlias();
    assertEquals(expectedCategories, categories);
    assertEquals(expectedTags, tags);
    assertEquals(expectedSortFields, sortFields);
    assertEquals(1757808000, epoch);
    assertEquals(expectedType, type);
    assertEquals(expectedFileName, fileName);
    assertEquals(expectedFileName, fileNameAlias);
  }

  @Test
  void shouldReadTheRequestedFormatAtTheTopLevel() throws IOException {
    final UrlParseDump dump = fixture();
    final DownloaderOptions options = dump.getDownloaderOptions().orElseThrow();
    final HttpHeaders headers = dump.getHttpHeaders().orElseThrow();
    final Optional<String> formatId = dump.getFormatId();
    final Optional<String> expectedFormatId = Optional.of("137+251");
    final Optional<String> formatNote = dump.getFormatNote();
    final Optional<String> expectedFormatNote = Optional.of("1080p+medium");
    final Optional<String> extension = dump.getExtension();
    final Optional<String> expectedExtension = Optional.of("mp4");
    final Optional<String> videoCodec = dump.getVideoCodec();
    final Optional<String> expectedVideoCodec = Optional.of("avc1.640028");
    final Optional<String> audioCodec = dump.getAudioCodec();
    final Optional<String> expectedAudioCodec = Optional.of(AUDIO_CODEC);
    final Optional<String> protocol = dump.getProtocol();
    final Optional<String> expectedProtocol = Optional.of("https+https");
    final Optional<String> resolution = dump.getResolution();
    final Optional<String> expectedResolution = Optional.of(RESOLUTION);
    final int width = dump.getWidth();
    final int height = dump.getHeight();
    final double framesPerSecond = dump.getFramesPerSecond();
    final double quality = dump.getQuality();
    final double totalBitrate = dump.getTotalBitrate();
    final double aspectRatio = dump.getAspectRatio();
    final int audioSampleRate = dump.getAudioSampleRate();
    final int audioChannels = dump.getAudioChannels();
    final int sourcePreference = dump.getSourcePreference();
    final int languagePreference = dump.getLanguagePreference();
    final Optional<String> language = dump.getLanguage();
    final Optional<String> expectedLanguage = Optional.of(LANGUAGE);
    final Optional<String> dynamicRange = dump.getDynamicRange();
    final Optional<String> expectedDynamicRange = Optional.of(DYNAMIC_RANGE);
    final Optional<String> videoExtension = dump.getVideoExtension();
    final Optional<String> audioExtension = dump.getAudioExtension();
    final Optional<String> expectedAudioExtension = Optional.of(ABSENT);
    final Optional<String> format = dump.getFormat();
    final Optional<String> expectedFormat = Optional.of("137 - 1920x1080 (1080p)+251 - audio only (medium)");
    final boolean drmProtected = dump.isDrmProtected();
    final Optional<String> url = dump.getUrl();
    final int httpChunkSize = options.getHttpChunkSize();
    final Optional<String> userAgent = headers.getUserAgent();
    final Optional<String> expectedUserAgent = Optional.of(USER_AGENT);
    assertEquals(expectedFormatId, formatId);
    assertEquals(expectedFormatNote, formatNote);
    assertEquals(expectedExtension, extension);
    assertEquals(expectedVideoCodec, videoCodec);
    assertEquals(expectedAudioCodec, audioCodec);
    assertEquals(expectedProtocol, protocol);
    assertEquals(expectedResolution, resolution);
    assertEquals(VIDEO_WIDTH, width);
    assertEquals(VIDEO_HEIGHT, height);
    assertEquals(FRAMES_PER_SECOND, framesPerSecond, DELTA);
    assertEquals(9.0, quality, DELTA);
    assertEquals(4530.189, totalBitrate, DELTA);
    assertEquals(1.78, aspectRatio, DELTA);
    assertEquals(SAMPLE_RATE, audioSampleRate);
    assertEquals(AUDIO_CHANNELS, audioChannels);
    assertEquals(-1, sourcePreference);
    assertEquals(-1, languagePreference);
    assertEquals(expectedLanguage, language);
    assertEquals(expectedDynamicRange, dynamicRange);
    assertEquals(expectedExtension, videoExtension);
    assertEquals(expectedAudioExtension, audioExtension);
    assertEquals(expectedFormat, format);
    assertFalse(drmProtected);
    assertEquals(Optional.empty(), url);
    assertEquals(HTTP_CHUNK_SIZE, httpChunkSize);
    assertEquals(expectedUserAgent, userAgent);
  }

  @Test
  void shouldReadCountsAndSizesBeyondTheIntRange() throws IOException {
    final UrlParseDump dump = fixture();
    final List<Format> formats = dump.getFormats();

    final Format video = formats.get(VIDEO_FORMAT_INDEX);
    final long commentCount = dump.getCommentCount();
    final long channelFollowerCount = dump.getChannelFollowerCount();
    final long dumpApproximateFileSize = dump.getApproximateFileSize();
    final long fileSize = video.getFileSize();
    final long videoApproximateFileSize = video.getApproximateFileSize();
    assertEquals(2300000000L, commentCount);
    assertEquals(4100000000L, channelFollowerCount);
    assertEquals(5403437753L, dumpApproximateFileSize);
    assertEquals(5368709120L, fileSize);
    assertEquals(5400000000L, videoApproximateFileSize);
  }

  @Test
  void shouldReturnAbsentValuesAsEmptyZeroOrFalse() throws IOException {
    final UrlParseDump dump = fixture();
    final List<Format> formats = dump.getFormats();

    final Format audio = formats.get(1);
    final Optional<Object> averageRating = dump.getAverageRating();
    final Optional<Object> releaseTimestamp = dump.getReleaseTimestamp();
    final Optional<Object> chapters = dump.getChapters();
    final Optional<Object> playlist = dump.getPlaylist();
    final Optional<Object> playlistIndex = dump.getPlaylistIndex();
    final Optional<Object> releaseYear = dump.getReleaseYear();
    final Optional<Object> requestedSubtitles = dump.getRequestedSubtitles();
    final Optional<Object> anyStreamDrmProtected = dump.getAnyStreamDrmProtected();
    final Optional<Object> fileSize = dump.getFileSize();
    final Optional<Object> dumpPreference = dump.getPreference();
    final Optional<Object> videoBitrate = dump.getVideoBitrate();
    final Optional<Object> audioBitrate = dump.getAudioBitrate();
    final int width = audio.getWidth();
    final int height = audio.getHeight();
    final double framesPerSecond = audio.getFramesPerSecond();
    final double aspectRatio = audio.getAspectRatio();
    final Optional<Object> audioPreference = audio.getPreference();
    final Optional<String> dynamicRange = audio.getDynamicRange();
    final List<Fragment> fragments = audio.getFragments();
    final List<Fragment> expectedFragments = List.of();
    assertEquals(Optional.empty(), averageRating);
    assertEquals(Optional.empty(), releaseTimestamp);
    assertEquals(Optional.empty(), chapters);
    assertEquals(Optional.empty(), playlist);
    assertEquals(Optional.empty(), playlistIndex);
    assertEquals(Optional.empty(), releaseYear);
    assertEquals(Optional.empty(), requestedSubtitles);
    assertEquals(Optional.empty(), anyStreamDrmProtected);
    assertEquals(Optional.empty(), fileSize);
    assertEquals(Optional.empty(), dumpPreference);
    assertEquals(Optional.empty(), videoBitrate);
    assertEquals(Optional.empty(), audioBitrate);
    assertEquals(0, width);
    assertEquals(0, height);
    assertEquals(0.0, framesPerSecond, DELTA);
    assertEquals(0.0, aspectRatio, DELTA);
    assertEquals(Optional.empty(), audioPreference);
    assertEquals(Optional.empty(), dynamicRange);
    assertEquals(expectedFragments, fragments);

    final Gson gson = GsonProvider.getSimple();
    final UrlParseDump empty = gson.fromJson("{}", UrlParseDump.class);
    final Optional<String> id = empty.getId();
    final List<Format> emptyFormats = empty.getFormats();
    final List<Format> expectedEmptyFormats = List.of();
    final double duration = empty.getDuration();
    assertEquals(Optional.empty(), id);
    assertEquals(expectedEmptyFormats, emptyFormats);
    assertEquals(0, duration);

    final boolean channelVerified = empty.isChannelVerified();
    final boolean playableInEmbed = empty.isPlayableInEmbed();
    assertFalse(channelVerified);
    assertFalse(playableInEmbed);
  }

  @Test
  void shouldReadEveryFormatField() throws IOException {
    final UrlParseDump dump = fixture();
    final List<Format> formats = dump.getFormats();

    final Format audio = formats.get(1);
    final Format video = formats.get(VIDEO_FORMAT_INDEX);
    final DownloaderOptions options = audio.getDownloaderOptions().orElseThrow();
    final Optional<String> formatId = audio.getFormatId();
    final Optional<String> expectedFormatId = Optional.of("251");
    final Optional<String> formatNote = audio.getFormatNote();
    final Optional<String> expectedFormatNote = Optional.of("medium");
    final Optional<String> extension = audio.getExtension();
    final Optional<String> expectedExtension = Optional.of("webm");
    final Optional<String> protocol = audio.getProtocol();
    final Optional<String> expectedProtocol = Optional.of("https");
    final Optional<String> audioCodec = audio.getAudioCodec();
    final Optional<String> expectedAudioCodec = Optional.of(AUDIO_CODEC);
    final Optional<String> videoCodec = audio.getVideoCodec();
    final Optional<String> expectedVideoCodec = Optional.of(ABSENT);
    final Optional<String> url = audio.getUrl();
    final Optional<String> expectedUrl = Optional.of("https://rr1---sn.example.googlevideo.com/videoplayback?itag=251");
    final int audioChannels = audio.getAudioChannels();
    final double quality = audio.getQuality();
    final boolean drmProtected = audio.isDrmProtected();
    final int sourcePreference = audio.getSourcePreference();
    final int audioSampleRate = audio.getAudioSampleRate();
    final long fileSize = audio.getFileSize();
    final Optional<String> language = audio.getLanguage();
    final Optional<String> expectedLanguage = Optional.of(LANGUAGE);
    final int languagePreference = audio.getLanguagePreference();
    final Optional<String> audioContainer = audio.getContainer();
    final Optional<String> expectedAudioContainer = Optional.of("webm_dash");
    final Optional<String> audioExtension = audio.getAudioExtension();
    final Optional<String> videoExtension = audio.getVideoExtension();
    final double audioVideoBitrate = audio.getVideoBitrate();
    final double audioBitrate = audio.getAudioBitrate();
    final double totalBitrate = audio.getTotalBitrate();
    final Optional<String> resolution = audio.getResolution();
    final Optional<String> expectedResolution = Optional.of("audio only");
    final Optional<String> format = audio.getFormat();
    final Optional<String> expectedFormat = Optional.of("251 - audio only (medium)");
    final int httpChunkSize = options.getHttpChunkSize();
    final int width = video.getWidth();
    final int height = video.getHeight();
    final double framesPerSecond = video.getFramesPerSecond();
    final double videoVideoBitrate = video.getVideoBitrate();
    final Optional<String> dynamicRange = video.getDynamicRange();
    final Optional<String> expectedDynamicRange = Optional.of(DYNAMIC_RANGE);
    final Optional<String> videoContainer = video.getContainer();
    final Optional<String> expectedVideoContainer = Optional.of("mp4_dash");
    final Optional<Object> formatIndex = video.getFormatIndex();
    final Optional<String> manifestUrl = video.getManifestUrl();
    assertEquals(expectedFormatId, formatId);
    assertEquals(expectedFormatNote, formatNote);
    assertEquals(expectedExtension, extension);
    assertEquals(expectedProtocol, protocol);
    assertEquals(expectedAudioCodec, audioCodec);
    assertEquals(expectedVideoCodec, videoCodec);
    assertEquals(expectedUrl, url);
    assertEquals(AUDIO_CHANNELS, audioChannels);
    assertEquals(3.0, quality, DELTA);
    assertFalse(drmProtected);
    assertEquals(-1, sourcePreference);
    assertEquals(SAMPLE_RATE, audioSampleRate);
    assertEquals(3437753L, fileSize);
    assertEquals(expectedLanguage, language);
    assertEquals(-1, languagePreference);
    assertEquals(expectedAudioContainer, audioContainer);
    assertEquals(expectedExtension, audioExtension);
    assertEquals(expectedVideoCodec, videoExtension);
    assertEquals(0.0, audioVideoBitrate, DELTA);
    assertEquals(129.689, audioBitrate, DELTA);
    assertEquals(129.689, totalBitrate, DELTA);
    assertEquals(expectedResolution, resolution);
    assertEquals(expectedFormat, format);
    assertEquals(HTTP_CHUNK_SIZE, httpChunkSize);
    assertEquals(VIDEO_WIDTH, width);
    assertEquals(VIDEO_HEIGHT, height);
    assertEquals(FRAMES_PER_SECOND, framesPerSecond, DELTA);
    assertEquals(4400.5, videoVideoBitrate, DELTA);
    assertEquals(expectedDynamicRange, dynamicRange);
    assertEquals(expectedVideoContainer, videoContainer);
    assertEquals(Optional.empty(), formatIndex);
    assertEquals(Optional.empty(), manifestUrl);
  }

  @Test
  void shouldReadStoryboardFragments() throws IOException {
    final UrlParseDump dump = fixture();
    final List<Format> formats = dump.getFormats();

    final Format storyboard = formats.getFirst();
    final List<Fragment> fragments = storyboard.getFragments();

    final Fragment fragment = fragments.getFirst();
    final Optional<String> protocol = storyboard.getProtocol();
    final Optional<String> expectedProtocol = Optional.of("mhtml");
    final int rows = storyboard.getRows();
    final int columns = storyboard.getColumns();
    final double framesPerSecond = storyboard.getFramesPerSecond();
    final long approximateFileSize = storyboard.getApproximateFileSize();
    assertEquals(expectedProtocol, protocol);
    assertEquals(10, rows);
    assertEquals(10, columns);
    assertEquals(0.5, framesPerSecond, DELTA);
    assertEquals(0L, approximateFileSize);

    final int fragmentsCount = fragments.size();
    final Optional<String> url = fragment.getUrl();
    final Optional<String> expectedUrl = Optional.of("https://i.ytimg.com/sb/dQw4w9WgXcQ/storyboard3_L0/default.jpg?sqp=a");
    final double duration = fragment.getDuration();
    assertEquals(1, fragmentsCount);
    assertEquals(expectedUrl, url);
    assertEquals(212.0, duration, DELTA);
  }

  @Test
  void shouldMapTheHttpHeaderNames() throws IOException {
    final UrlParseDump dump = fixture();
    final List<Format> formats = dump.getFormats();

    final Format storyboard = formats.getFirst();
    final HttpHeaders headers = storyboard.getHttpHeaders().orElseThrow();
    final Optional<String> userAgent = headers.getUserAgent();
    final Optional<String> expectedUserAgent = Optional.of(USER_AGENT);
    final Optional<String> accept = headers.getAccept();
    final Optional<String> expectedAccept = Optional.of("text/html,application/xhtml+xml");
    final Optional<String> acceptLanguage = headers.getAcceptLanguage();
    final Optional<String> expectedAcceptLanguage = Optional.of("en-us,en;q=0.5");
    final Optional<String> secFetchMode = headers.getSecFetchMode();
    final Optional<String> expectedSecFetchMode = Optional.of("navigate");
    assertEquals(expectedUserAgent, userAgent);
    assertEquals(expectedAccept, accept);
    assertEquals(expectedAcceptLanguage, acceptLanguage);
    assertEquals(expectedSecFetchMode, secFetchMode);

    final Gson gson = GsonProvider.getSimple();
    final String json = gson.toJson(headers);
    assertEquals(
      "{\"User-Agent\":\"Mozilla/5.0 (X11; Linux x86_64)\",\"Accept\":\"text/html,application/xhtml+xml\",\"Accept-Language\":\"en-us,en;q\\u003d0.5\",\"Sec-Fetch-Mode\":\"navigate\"}",
      json
    );
  }

  @Test
  void shouldReadThumbnailsHeatmapAndVersion() throws IOException {
    final UrlParseDump dump = fixture();
    final List<Thumbnail> thumbnails = dump.getThumbnails();
    final List<Heatmap> heatmap = dump.getHeatmap();
    final Version version = dump.getVersion().orElseThrow();

    final Thumbnail thumbnail = thumbnails.getFirst();
    final Heatmap marker = heatmap.getFirst();
    final Optional<String> url = thumbnail.getUrl();
    final Optional<String> expectedUrl = Optional.of(THUMBNAIL_URL);
    final int preference = thumbnail.getPreference();
    final Optional<String> id = thumbnail.getId();
    final Optional<String> expectedId = Optional.of("40");
    final int height = thumbnail.getHeight();
    final int width = thumbnail.getWidth();
    final Optional<String> resolution = thumbnail.getResolution();
    final Optional<String> expectedResolution = Optional.of(RESOLUTION);
    final double startTime = marker.getStartTime();
    final double endTime = marker.getEndTime();
    final double value = marker.getValue();
    final Optional<String> reportedVersion = version.getVersion();
    final Optional<String> expectedReportedVersion = Optional.of("2025.09.05");
    final Optional<Object> currentGitHead = version.getCurrentGitHead();
    final Optional<String> releaseGitHead = version.getReleaseGitHead();
    final Optional<String> expectedReleaseGitHead = Optional.of("abc123def456");
    final Optional<String> repository = version.getRepository();
    final Optional<String> expectedRepository = Optional.of(REPOSITORY);
    assertEquals(expectedUrl, url);
    assertEquals(-1, preference);
    assertEquals(expectedId, id);
    assertEquals(1080, height);
    assertEquals(1920, width);
    assertEquals(expectedResolution, resolution);
    assertEquals(0.0, startTime, DELTA);
    assertEquals(2.12, endTime, DELTA);
    assertEquals(1.0, value, DELTA);
    assertEquals(expectedReportedVersion, reportedVersion);
    assertEquals(Optional.empty(), currentGitHead);
    assertEquals(expectedReleaseGitHead, releaseGitHead);
    assertEquals(expectedRepository, repository);
  }

  @Test
  void shouldConvertTheStreamUrlIntoASource() {
    final Format format = format("{\"url\": \"https://cdn.example.com/video.mp4?token=a%20b\"}");
    final UriSource source = format.toUriSource();
    final URI uri = source.getUri();
    final URI expected = URI.create("https://cdn.example.com/video.mp4?token=a%20b");
    assertEquals(expected, uri);
  }

  @Test
  void shouldRejectStreamsWithoutUrl() {
    final Format format = format("{\"format_id\": \"sb0\"}");
    final IllegalStateException exception = assertThrows(IllegalStateException.class, format::toUriSource);
    final String message = exception.getMessage();
    assertEquals("Stream has no URL!", message);
  }

  static Stream<Named<Class<?>>> models() {
    return MODELS.stream().map(FormatModelTest::named);
  }

  static Stream<Arguments> modelFields() {
    return MODELS.stream().flatMap(FormatModelTest::fieldsOf);
  }

  private static Named<Class<?>> named(final Class<?> clazz) {
    return Named.of(clazz.getSimpleName(), clazz);
  }

  private static Stream<Arguments> fieldsOf(final Class<?> clazz) {
    final List<Field> fields = instanceFields(clazz);
    return fields.stream().map(field -> Arguments.of(named(clazz), field.getName()));
  }

  private static List<Field> instanceFields(final Class<?> clazz) {
    final Field[] fields = clazz.getDeclaredFields();
    return Arrays.stream(fields)
      .filter(field -> !Modifier.isStatic(field.getModifiers()))
      .toList();
  }

  private static Object parse(final Class<?> clazz, final JsonObject json) {
    final Gson gson = GsonProvider.getSimple();
    return gson.fromJson(json, clazz);
  }

  private static JsonObject baseJson(final Class<?> clazz) {
    final JsonObject json = new JsonObject();
    for (final Field field : instanceFields(clazz)) {
      final Type type = field.getGenericType();
      final JsonElement value = baseValue(type);
      json.add(jsonName(field), value);
    }

    return json;
  }

  private static JsonObject changedJson(final Class<?> clazz, final String fieldName) throws NoSuchFieldException {
    final JsonObject json = baseJson(clazz);
    final Field field = clazz.getDeclaredField(fieldName);
    final Type type = field.getGenericType();
    final JsonElement value = changedValue(type);
    json.add(jsonName(field), value);
    return json;
  }

  private static String jsonName(final Field field) {
    final Field component = findComponentField(field);
    final SerializedName serializedName = component.getAnnotation(SerializedName.class);
    if (serializedName == null) {
      return field.getName();
    }

    return serializedName.value();
  }

  private static FilterResult blockModels(final Class<?> rawClass) {
    if (MODELS.contains(rawClass)) {
      return FilterResult.BLOCK_ALL;
    }

    return FilterResult.INDECISIVE;
  }

  private static Field findComponentField(final Field field) {
    final Class<?> owner = field.getDeclaringClass();
    final Class<?>[] nestedTypes = owner.getDeclaredClasses();
    return Arrays.stream(nestedTypes)
      .filter(Class::isRecord)
      .map(Class::getDeclaredFields)
      .flatMap(Arrays::stream)
      .filter(component -> component.getName().equals(field.getName()))
      .findFirst()
      .orElseThrow();
  }

  private static JsonElement baseValue(final Type type) {
    if (type instanceof final ParameterizedType listType) {
      final Type[] elementTypes = listType.getActualTypeArguments();
      final JsonElement element = baseValue(elementTypes[0]);
      final JsonArray array = new JsonArray();
      array.add(element);
      return array;
    }

    if (MODELS.contains(type)) {
      return new JsonObject();
    }

    return basePrimitive(type);
  }

  private static JsonElement basePrimitive(final Type type) {
    if (type == int.class || type == long.class) {
      return new JsonPrimitive(BASE_NUMBER);
    }

    if (type == double.class) {
      return new JsonPrimitive(BASE_FRACTION);
    }

    if (type == boolean.class) {
      return new JsonPrimitive(true);
    }

    return new JsonPrimitive(BASE_TEXT);
  }

  private static JsonElement changedValue(final Type type) {
    if (type instanceof ParameterizedType) {
      return new JsonArray();
    }

    if (type instanceof final Class<?> clazz && MODELS.contains(clazz)) {
      final List<Field> fields = instanceFields(clazz);
      final Field first = fields.getFirst();
      final Type firstType = first.getGenericType();
      final JsonObject json = new JsonObject();
      json.add(jsonName(first), baseValue(firstType));
      return json;
    }

    return changedPrimitive(type);
  }

  private static JsonElement changedPrimitive(final Type type) {
    if (type == int.class || type == long.class) {
      return new JsonPrimitive(CHANGED_NUMBER);
    }

    if (type == double.class) {
      return new JsonPrimitive(CHANGED_FRACTION);
    }

    if (type == boolean.class) {
      return new JsonPrimitive(false);
    }

    return new JsonPrimitive(CHANGED_TEXT);
  }

  private static Method findGetter(final Class<?> clazz, final String fieldName) throws NoSuchMethodException {
    final char first = Character.toUpperCase(fieldName.charAt(0));
    final String rest = fieldName.substring(1);
    final String capitalized = "%s%s".formatted(first, rest);
    final String getterName = "get%s".formatted(capitalized);
    final String booleanGetterName = "is%s".formatted(capitalized);
    final Set<String> names = Set.of(getterName, booleanGetterName, fieldName);
    for (final Method method : clazz.getMethods()) {
      if (names.contains(method.getName()) && method.getParameterCount() == 0) {
        return method;
      }
    }

    final String message = "Field %s of %s has no getter!".formatted(fieldName, clazz);
    throw new NoSuchMethodException(message);
  }

  private static @Nullable Object unwrap(final @Nullable Object value) {
    if (value instanceof final Optional<?> optional) {
      return optional.orElseThrow();
    }

    return value;
  }

  @ParameterizedTest(name = "{0}.{1}")
  @MethodSource("modelFields")
  void shouldReturnEveryReportedValue(final Class<?> clazz, final String fieldName) throws ReflectiveOperationException {
    final Object model = parse(clazz, baseJson(clazz));
    final Field field = clazz.getDeclaredField(fieldName);
    field.setAccessible(true);
    final Object expected = field.get(model);
    final Method getter = findGetter(clazz, fieldName);
    final Object value = getter.invoke(model);
    final Object reported = unwrap(value);
    assertEquals(expected, reported);
  }

  @ParameterizedTest(name = "{0}.{1}")
  @MethodSource("modelFields")
  void shouldTellModelsApartByEveryValue(final Class<?> clazz, final String fieldName) throws NoSuchFieldException {
    final Object model = parse(clazz, baseJson(clazz));
    final Object changed = parse(clazz, changedJson(clazz, fieldName));
    assertNotEquals(model, changed);

    final int modelHash = model.hashCode();
    final int changedHash = changed.hashCode();
    assertNotEquals(modelHash, changedHash);

    final String text = model.toString();
    final boolean named = text.contains("%s=".formatted(fieldName));
    assertTrue(named);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("models")
  void shouldEqualAModelWithTheSameValues(final Class<?> clazz) {
    final Object model = parse(clazz, baseJson(clazz));
    final Object same = parse(clazz, baseJson(clazz));
    final Object identical = model;
    assertEquals(model, same);
    assertEquals(model, identical);
    assertNotEquals(model, BASE_TEXT);

    final int modelHash = model.hashCode();
    final int sameHash = same.hashCode();
    assertEquals(modelHash, sameHash);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("models")
  void shouldReadWhatItWrites(final Class<?> clazz) {
    final Object model = parse(clazz, baseJson(clazz));
    final Gson gson = GsonProvider.getSimple();
    final String json = gson.toJson(model);
    final Object read = gson.fromJson(json, clazz);
    assertEquals(model, read);
  }

  @Test
  void shouldBuildTheModelsWithoutWritingTheirFieldsByReflection() throws IOException {
    final String json = fixtureText();
    final GsonBuilder builder = new GsonBuilder();
    final Gson gson = builder.addReflectionAccessFilter(MODEL_FIELDS_BLOCKED).create();
    final UrlParseDump dump = gson.fromJson(json, UrlParseDump.class);
    final UrlParseDump expected = fixture();
    assertEquals(expected, dump);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("models")
  void shouldReturnEmptyListsForUnreportedLists(final Class<?> clazz) throws ReflectiveOperationException {
    final Object model = parse(clazz, new JsonObject());
    for (final Field field : instanceFields(clazz)) {
      this.assertEmptyWhenAList(clazz, field, model);
    }
  }

  private void assertEmptyWhenAList(final Class<?> clazz, final Field field, final Object model) throws ReflectiveOperationException {
    if (field.getType() != List.class) {
      return;
    }

    final Method getter = findGetter(clazz, field.getName());
    final Object value = getter.invoke(model);
    assertEquals(List.of(), value);
  }

  @Test
  void shouldNotLetCallersChangeTheLists() throws IOException {
    final UrlParseDump dump = fixture();
    final List<Format> formats = dump.getFormats();
    assertThrows(UnsupportedOperationException.class, formats::clear);
  }

  @Test
  void shouldCreateMetadataWithATitle() {
    final UrlParseDump dump = UrlParseDump.of(TITLE);
    final Optional<String> title = dump.getTitle();
    final Optional<String> expectedTitle = Optional.of(TITLE);
    final Optional<String> description = dump.getDescription();
    final List<Format> formats = dump.getFormats();
    final List<Format> expectedFormats = List.of();
    assertEquals(expectedTitle, title);
    assertEquals(Optional.empty(), description);
    assertEquals(expectedFormats, formats);
  }

  @Test
  void shouldCreateMetadataWithATitleAndADescription() {
    final UrlParseDump dump = UrlParseDump.of(TITLE, DESCRIPTION);
    final Optional<String> title = dump.getTitle();
    final Optional<String> expectedTitle = Optional.of(TITLE);
    final Optional<String> description = dump.getDescription();
    final Optional<String> expectedDescription = Optional.of(DESCRIPTION);
    assertEquals(expectedTitle, title);
    assertEquals(expectedDescription, description);
  }

  @Test
  void shouldCreateMetadataWithALength() {
    final Duration length = Duration.ofSeconds(255, 500_000_000);
    final UrlParseDump dump = UrlParseDump.of(TITLE, DESCRIPTION, length);
    final double seconds = dump.getDuration();
    assertEquals(255.5, seconds);

    final UrlParseDump unknown = UrlParseDump.of(TITLE, DESCRIPTION);
    final double unknownSeconds = unknown.getDuration();
    assertEquals(0.0, unknownSeconds);
  }

  @Test
  void shouldRejectMissingTitlesAndDescriptions() {
    assertThrows(NullPointerException.class, () -> UrlParseDump.of(null));
    assertThrows(NullPointerException.class, () -> UrlParseDump.of(null, DESCRIPTION));
    assertThrows(NullPointerException.class, () -> UrlParseDump.of(TITLE, null));
    assertThrows(NullPointerException.class, () -> UrlParseDump.of(null, DESCRIPTION, Duration.ZERO));
    assertThrows(NullPointerException.class, () -> UrlParseDump.of(TITLE, null, Duration.ZERO));
    assertThrows(NullPointerException.class, () -> UrlParseDump.of(TITLE, DESCRIPTION, null));
  }

  @Test
  void shouldRejectANegativeLength() {
    final Duration negative = Duration.ofNanos(-1);
    final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
      UrlParseDump.of(TITLE, DESCRIPTION, negative)
    );
    final String message = failure.getMessage();
    assertEquals("Duration PT-0.000000001S must not be negative!", message);
  }
}
