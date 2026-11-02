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

  private static final String GETTER_PREFIX = "get";
  private static final String BOOLEAN_GETTER_PREFIX = "is";

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
    assertEquals(Optional.of("dQw4w9WgXcQ"), dump.getId());
    assertEquals(Optional.of("Example Video"), dump.getTitle());
    assertEquals(Optional.of("Example Video"), dump.getFullTitle());
    assertEquals(Optional.of("An example description\nwith two lines"), dump.getDescription());
    assertEquals(Optional.of(THUMBNAIL_URL), dump.getThumbnail());
    assertEquals(Optional.of("UCuAXFkgsw1L7xaCfnd5JJOw"), dump.getChannelId());
    assertEquals(Optional.of("https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw"), dump.getChannelUrl());
    assertEquals(Optional.of("Example Channel"), dump.getChannel());
    assertTrue(dump.isChannelVerified());
    assertEquals(Optional.of("Example Uploader"), dump.getUploader());
    assertEquals(Optional.of("@example"), dump.getUploaderId());
    assertEquals(Optional.of("https://www.youtube.com/@example"), dump.getUploaderUrl());
    assertEquals(Optional.of("20091025"), dump.getUploadDate());
    assertEquals(1256453732, dump.getTimestamp());
    assertEquals(212, dump.getDuration());
    assertEquals(Optional.of("3:32"), dump.getDurationString());
    assertEquals(1700000000, dump.getViewCount());
    assertEquals(18000000, dump.getLikeCount());
    assertEquals(0, dump.getAgeLimit());
    assertEquals(Optional.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ"), dump.getWebpageUrl());
    assertEquals(Optional.of("https://youtu.be/dQw4w9WgXcQ"), dump.getOriginalUrl());
    assertEquals(Optional.of("watch"), dump.getWebpageUrlBasename());
    assertEquals(Optional.of("youtube.com"), dump.getWebpageUrlDomain());
    assertEquals(Optional.of("youtube"), dump.getExtractor());
    assertEquals(Optional.of("Youtube"), dump.getExtractorKey());
    assertEquals(Optional.of("dQw4w9WgXcQ"), dump.getDisplayId());
    assertEquals(Optional.of("public"), dump.getAvailability());
    assertEquals(Optional.of("not_live"), dump.getLiveStatus());
    assertTrue(dump.isPlayableInEmbed());
    assertFalse(dump.isLive());
    assertFalse(dump.wasLive());

    final List<String> expectedCategories = List.of("Music");
    final List<String> expectedTags = List.of("example", "video");
    final List<String> expectedSortFields = List.of("quality", "res", "fps");
    assertEquals(expectedCategories, categories);
    assertEquals(expectedTags, tags);
    assertEquals(expectedSortFields, sortFields);
    assertEquals(1757808000, dump.getEpoch());
    assertEquals(Optional.of("video"), dump.getType());
    assertEquals(Optional.of("Example Video [dQw4w9WgXcQ].mp4"), dump.getFileName());
    assertEquals(Optional.of("Example Video [dQw4w9WgXcQ].mp4"), dump.getFileNameAlias());
  }

  @Test
  void shouldReadTheRequestedFormatAtTheTopLevel() throws IOException {
    final UrlParseDump dump = fixture();
    final DownloaderOptions options = dump.getDownloaderOptions().orElseThrow();
    final HttpHeaders headers = dump.getHttpHeaders().orElseThrow();
    assertEquals(Optional.of("137+251"), dump.getFormatId());
    assertEquals(Optional.of("1080p+medium"), dump.getFormatNote());
    assertEquals(Optional.of("mp4"), dump.getExtension());
    assertEquals(Optional.of("avc1.640028"), dump.getVideoCodec());
    assertEquals(Optional.of(AUDIO_CODEC), dump.getAudioCodec());
    assertEquals(Optional.of("https+https"), dump.getProtocol());
    assertEquals(Optional.of(RESOLUTION), dump.getResolution());
    assertEquals(VIDEO_WIDTH, dump.getWidth());
    assertEquals(VIDEO_HEIGHT, dump.getHeight());
    assertEquals(FRAMES_PER_SECOND, dump.getFramesPerSecond(), DELTA);
    assertEquals(9.0, dump.getQuality(), DELTA);
    assertEquals(4530.189, dump.getTotalBitrate(), DELTA);
    assertEquals(1.78, dump.getAspectRatio(), DELTA);
    assertEquals(SAMPLE_RATE, dump.getAudioSampleRate());
    assertEquals(AUDIO_CHANNELS, dump.getAudioChannels());
    assertEquals(-1, dump.getSourcePreference());
    assertEquals(-1, dump.getLanguagePreference());
    assertEquals(Optional.of(LANGUAGE), dump.getLanguage());
    assertEquals(Optional.of(DYNAMIC_RANGE), dump.getDynamicRange());
    assertEquals(Optional.of("mp4"), dump.getVideoExtension());
    assertEquals(Optional.of(ABSENT), dump.getAudioExtension());
    assertEquals(Optional.of("137 - 1920x1080 (1080p)+251 - audio only (medium)"), dump.getFormat());
    assertFalse(dump.isDrmProtected());
    assertEquals(Optional.empty(), dump.getUrl());
    assertEquals(HTTP_CHUNK_SIZE, options.getHttpChunkSize());
    assertEquals(Optional.of(USER_AGENT), headers.getUserAgent());
  }

  @Test
  void shouldReadCountsAndSizesBeyondTheIntRange() throws IOException {
    final UrlParseDump dump = fixture();
    final List<Format> formats = dump.getFormats();

    final Format video = formats.get(VIDEO_FORMAT_INDEX);
    assertEquals(2300000000L, dump.getCommentCount());
    assertEquals(4100000000L, dump.getChannelFollowerCount());
    assertEquals(5403437753L, dump.getApproximateFileSize());
    assertEquals(5368709120L, video.getFileSize());
    assertEquals(5400000000L, video.getApproximateFileSize());
  }

  @Test
  void shouldLeaveUnknownValuesNullOrZero() throws IOException {
    final UrlParseDump dump = fixture();
    final List<Format> formats = dump.getFormats();

    final Format audio = formats.get(1);
    assertEquals(Optional.empty(), dump.getAverageRating());
    assertEquals(Optional.empty(), dump.getReleaseTimestamp());
    assertEquals(Optional.empty(), dump.getChapters());
    assertEquals(Optional.empty(), dump.getPlaylist());
    assertEquals(Optional.empty(), dump.getPlaylistIndex());
    assertEquals(Optional.empty(), dump.getReleaseYear());
    assertEquals(Optional.empty(), dump.getRequestedSubtitles());
    assertEquals(Optional.empty(), dump.getAnyStreamDrmProtected());
    assertEquals(Optional.empty(), dump.getFileSize());
    assertEquals(Optional.empty(), dump.getPreference());
    assertEquals(Optional.empty(), dump.getVideoBitrate());
    assertEquals(Optional.empty(), dump.getAudioBitrate());
    assertEquals(0, audio.getWidth());
    assertEquals(0, audio.getHeight());
    assertEquals(0.0, audio.getFramesPerSecond(), DELTA);
    assertEquals(0.0, audio.getAspectRatio(), DELTA);
    assertEquals(Optional.empty(), audio.getPreference());
    assertEquals(Optional.empty(), audio.getDynamicRange());
    assertEquals(List.of(), audio.getFragments());

    final Gson gson = GsonProvider.getSimple();
    final UrlParseDump empty = gson.fromJson("{}", UrlParseDump.class);
    assertEquals(Optional.empty(), empty.getId());
    assertEquals(List.of(), empty.getFormats());
    assertEquals(0, empty.getDuration());

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
    assertEquals(Optional.of("251"), audio.getFormatId());
    assertEquals(Optional.of("medium"), audio.getFormatNote());
    assertEquals(Optional.of("webm"), audio.getExtension());
    assertEquals(Optional.of("https"), audio.getProtocol());
    assertEquals(Optional.of(AUDIO_CODEC), audio.getAudioCodec());
    assertEquals(Optional.of(ABSENT), audio.getVideoCodec());
    assertEquals(Optional.of("https://rr1---sn.example.googlevideo.com/videoplayback?itag=251"), audio.getUrl());
    assertEquals(AUDIO_CHANNELS, audio.getAudioChannels());
    assertEquals(3.0, audio.getQuality(), DELTA);
    assertFalse(audio.isDrmProtected());
    assertEquals(-1, audio.getSourcePreference());
    assertEquals(SAMPLE_RATE, audio.getAudioSampleRate());
    assertEquals(3437753L, audio.getFileSize());
    assertEquals(Optional.of(LANGUAGE), audio.getLanguage());
    assertEquals(-1, audio.getLanguagePreference());
    assertEquals(Optional.of("webm_dash"), audio.getContainer());
    assertEquals(Optional.of("webm"), audio.getAudioExtension());
    assertEquals(Optional.of(ABSENT), audio.getVideoExtension());
    assertEquals(0.0, audio.getVideoBitrate(), DELTA);
    assertEquals(129.689, audio.getAudioBitrate(), DELTA);
    assertEquals(129.689, audio.getTotalBitrate(), DELTA);
    assertEquals(Optional.of("audio only"), audio.getResolution());
    assertEquals(Optional.of("251 - audio only (medium)"), audio.getFormat());
    assertEquals(HTTP_CHUNK_SIZE, options.getHttpChunkSize());
    assertEquals(VIDEO_WIDTH, video.getWidth());
    assertEquals(VIDEO_HEIGHT, video.getHeight());
    assertEquals(FRAMES_PER_SECOND, video.getFramesPerSecond(), DELTA);
    assertEquals(4400.5, video.getVideoBitrate(), DELTA);
    assertEquals(Optional.of(DYNAMIC_RANGE), video.getDynamicRange());
    assertEquals(Optional.of("mp4_dash"), video.getContainer());
    assertEquals(Optional.empty(), video.getFormatIndex());
    assertEquals(Optional.empty(), video.getManifestUrl());
  }

  @Test
  void shouldReadStoryboardFragments() throws IOException {
    final UrlParseDump dump = fixture();
    final List<Format> formats = dump.getFormats();

    final Format storyboard = formats.getFirst();
    final List<Fragment> fragments = storyboard.getFragments();

    final Fragment fragment = fragments.getFirst();
    assertEquals(Optional.of("mhtml"), storyboard.getProtocol());
    assertEquals(10, storyboard.getRows());
    assertEquals(10, storyboard.getColumns());
    assertEquals(0.5, storyboard.getFramesPerSecond(), DELTA);
    assertEquals(0L, storyboard.getApproximateFileSize());

    final int fragmentsCount = fragments.size();
    assertEquals(1, fragmentsCount);
    assertEquals(Optional.of("https://i.ytimg.com/sb/dQw4w9WgXcQ/storyboard3_L0/default.jpg?sqp=a"), fragment.getUrl());
    assertEquals(212.0, fragment.getDuration(), DELTA);
  }

  @Test
  void shouldMapTheHttpHeaderNames() throws IOException {
    final UrlParseDump dump = fixture();
    final List<Format> formats = dump.getFormats();

    final Format storyboard = formats.getFirst();
    final HttpHeaders headers = storyboard.getHttpHeaders().orElseThrow();
    assertEquals(Optional.of(USER_AGENT), headers.getUserAgent());
    assertEquals(Optional.of("text/html,application/xhtml+xml"), headers.getAccept());
    assertEquals(Optional.of("en-us,en;q=0.5"), headers.getAcceptLanguage());
    assertEquals(Optional.of("navigate"), headers.getSecFetchMode());

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
    assertEquals(Optional.of(THUMBNAIL_URL), thumbnail.getUrl());
    assertEquals(-1, thumbnail.getPreference());
    assertEquals(Optional.of("40"), thumbnail.getId());
    assertEquals(1080, thumbnail.getHeight());
    assertEquals(1920, thumbnail.getWidth());
    assertEquals(Optional.of(RESOLUTION), thumbnail.getResolution());
    assertEquals(0.0, marker.getStartTime(), DELTA);
    assertEquals(2.12, marker.getEndTime(), DELTA);
    assertEquals(1.0, marker.getValue(), DELTA);
    assertEquals(Optional.of("2025.09.05"), version.getVersion());
    assertEquals(Optional.empty(), version.getCurrentGitHead());
    assertEquals(Optional.of("abc123def456"), version.getReleaseGitHead());
    assertEquals(Optional.of(REPOSITORY), version.getRepository());
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
    final String capitalized = first + fieldName.substring(1);
    final Set<String> names = Set.of(GETTER_PREFIX + capitalized, BOOLEAN_GETTER_PREFIX + capitalized, fieldName);
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
    final boolean named = text.contains(fieldName + "=");
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
    assertEquals(Optional.of(TITLE), dump.getTitle());
    assertEquals(Optional.empty(), dump.getDescription());
    assertEquals(List.of(), dump.getFormats());
  }

  @Test
  void shouldCreateMetadataWithATitleAndADescription() {
    final UrlParseDump dump = UrlParseDump.of(TITLE, DESCRIPTION);
    assertEquals(Optional.of(TITLE), dump.getTitle());
    assertEquals(Optional.of(DESCRIPTION), dump.getDescription());
  }

  @Test
  void shouldRejectMissingTitlesAndDescriptions() {
    assertThrows(NullPointerException.class, () -> UrlParseDump.of(null));
    assertThrows(NullPointerException.class, () -> UrlParseDump.of(null, DESCRIPTION));
    assertThrows(NullPointerException.class, () -> UrlParseDump.of(TITLE, null));
  }
}
