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
package me.brandonli.mcav.bukkit.hologram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TimeZone;
import java.util.function.Consumer;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.invocation.InvocationOnMock;

// changes the default time zone
@Isolated
final class StandardVideoHologramTest {

  private static final String FIRST_ORDINAL_SUFFIX = "st";

  private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
  private static final String PROGRESS = "■";

  private static final int UPLOAD_YEAR = 2024;
  private static final int UPLOAD_HOUR = 15;
  private static final int UPLOAD_MINUTE = 30;

  private static final String TITLE = "t";
  private static final String UPLOADER = "u";

  private static final int DURATION_SECONDS = 10;
  private static final String DURATION_TEXT = " 00:10";

  private static final int LONG_DURATION_SECONDS = 100;

  private static final int BAR_LENGTH = 20;

  private static final String UNDATED_TEXT_AT_START = "t\nu (Unknown date)\n00:00 ";

  private FakeServer server;
  private TimeZone previousTimeZone;
  // a location refers to its world only weakly, so the test keeps the world reachable until it ends
  private World world;
  private Location location;
  private List<TextDisplay> spawnedDisplays;

  @BeforeEach
  void startServer() throws ReflectiveOperationException {
    this.previousTimeZone = TimeZone.getDefault();
    final TimeZone utc = TimeZone.getTimeZone("UTC");
    TimeZone.setDefault(utc);
    this.server = FakeServer.start();
    this.server.injectModule();
    this.world = mock(World.class);
    this.location = new Location(this.world, 1, 2, 3);
    this.spawnedDisplays = new ArrayList<>();
    when(this.world.spawn(eq(this.location), eq(TextDisplay.class), ArgumentMatchers.<Consumer<? super TextDisplay>>any())).thenAnswer(
      this::spawnDisplay
    );
  }

  private TextDisplay spawnDisplay(final InvocationOnMock invocation) {
    final TextDisplay display = mock(TextDisplay.class);
    when(display.isValid()).thenReturn(true);
    final Consumer<TextDisplay> configurator = invocation.getArgument(2);
    configurator.accept(display);
    this.spawnedDisplays.add(display);

    return display;
  }

  @AfterEach
  void stopServer() {
    this.server.close();
    TimeZone.setDefault(this.previousTimeZone);
  }

  private UrlParseDump createDump(final String title, final String uploader, final int timestamp, final double duration) {
    final JsonObject json = new JsonObject();
    json.addProperty("title", title);
    json.addProperty("uploader", uploader);
    json.addProperty("timestamp", timestamp);
    json.addProperty("duration", duration);
    final Gson gson = GsonProvider.getSimple();
    return gson.fromJson(json, UrlParseDump.class);
  }

  private int epochSecond(final int day) {
    final LocalDateTime dateTime = LocalDateTime.of(UPLOAD_YEAR, 1, day, UPLOAD_HOUR, UPLOAD_MINUTE);
    final long seconds = dateTime.toEpochSecond(ZoneOffset.UTC);
    return (int) seconds;
  }

  private String expectedDate(final int day, final String suffix) {
    final LocalDateTime dateTime = LocalDateTime.of(UPLOAD_YEAR, 1, day, UPLOAD_HOUR, UPLOAD_MINUTE);
    final DateTimeFormatter monthDay = DateTimeFormatter.ofPattern("MMMM d", Locale.getDefault(Locale.Category.FORMAT));
    final DateTimeFormatter yearTime = DateTimeFormatter.ofPattern(", yyyy h:mm a", Locale.getDefault(Locale.Category.FORMAT));
    final String monthDayText = dateTime.format(monthDay);
    final String yearTimeText = dateTime.format(yearTime);
    return monthDayText + suffix + yearTimeText;
  }

  private Component lastText(final TextDisplay display) {
    final ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
    verify(display, atLeastOnce()).text(captor.capture());

    return captor.getValue();
  }

  private int countSegments(final Component component, final TextColor inheritedColor, final TextColor color) {
    final TextColor ownColor = component.color();
    final TextColor effectiveColor = ownColor == null ? inheritedColor : ownColor;
    int count = 0;
    if (component instanceof final TextComponent text && color.equals(effectiveColor)) {
      final String content = text.content();
      final String withoutProgress = content.replace(PROGRESS, "");
      final int contentLength = content.length();
      final int remainingLength = withoutProgress.length();
      count += contentLength - remainingLength;
    }

    final List<Component> children = component.children();
    for (final Component child : children) {
      count += this.countSegments(child, effectiveColor, color);
    }

    return count;
  }

  private int countFilled(final Component component) {
    return this.countSegments(component, null, NamedTextColor.GREEN);
  }

  private int countEmpty(final Component component) {
    return this.countSegments(component, null, NamedTextColor.GRAY);
  }

  @Test
  void shouldGiveAStandardVideoHologramFromBasicThatSpawnsNothingBeforeARequest() {
    final Hologram hologram = Hologram.basic();
    final Optional<TextDisplay> display = hologram.getDisplay();
    assertInstanceOf(StandardVideoHologram.class, hologram);

    final boolean nothingSpawned = display.isEmpty();
    assertTrue(nothingSpawned, "nothing is spawned before a request");
  }

  @Test
  void shouldSpawnAConfiguredDisplayWithTheMetadataOfTheVideo() {
    final StandardVideoHologram hologram = new StandardVideoHologram();
    final int timestamp = this.epochSecond(1);
    final UrlParseDump dump = this.createDump("Some Video", "Some Channel", timestamp, 60);
    hologram.spawn(this.location, dump);
    final TextDisplay display = this.spawnedDisplays.getFirst();
    final TextDisplay current = hologram.getDisplay().orElseThrow();
    final Component text = this.lastText(display);
    final String plain = PLAIN.serialize(text);
    final String date = this.expectedDate(1, FIRST_ORDINAL_SUFFIX);
    assertSame(display, current);

    final String expected = "Some Video\nSome Channel (%s)".formatted(date);
    assertEquals(expected, plain);
    verify(display).setPersistent(false);
    verify(display).setAlignment(TextDisplay.TextAlignment.CENTER);
    verify(display).setBillboard(Display.Billboard.VERTICAL);
    verify(display).setVisibleByDefault(true);
    verify(display).setSeeThrough(false);
    verify(display).setBackgroundColor(Color.BLACK);
  }

  @Test
  void shouldWriteOrdinalDaysLikeEnglishDoes() {
    final int[] days = { 2, 3, 4, 11, 12, 13, 21, 22, 23, 31 };
    final String[] suffixes = { "nd", "rd", "th", "th", "th", "th", FIRST_ORDINAL_SUFFIX, "nd", "rd", FIRST_ORDINAL_SUFFIX };
    for (int index = 0; index < days.length; index++) {
      final StandardVideoHologram hologram = new StandardVideoHologram();
      final int timestamp = this.epochSecond(days[index]);
      final UrlParseDump dump = this.createDump(TITLE, UPLOADER, timestamp, 1);
      hologram.spawn(this.location, dump);
      final TextDisplay display = hologram.getDisplay().orElseThrow();
      final Component text = this.lastText(display);
      final String plain = PLAIN.serialize(text);
      final String date = this.expectedDate(days[index], suffixes[index]);
      final String expected = "t\nu (%s)".formatted(date);
      assertEquals(expected, plain);
    }
  }

  @Test
  void shouldUsePlaceholdersForMissingMetadataAndNotParseTagsInIt() {
    final StandardVideoHologram hologram = new StandardVideoHologram();
    final UrlParseDump missing = this.createDump(null, null, 0, 1);
    hologram.spawn(this.location, missing);
    final TextDisplay first = hologram.getDisplay().orElseThrow();
    final Component missingText = this.lastText(first);
    final String missingPlain = PLAIN.serialize(missingText);
    final UrlParseDump tagged = this.createDump("<red>Title</red>", "<bold>Me", -5, 1);
    hologram.spawn(this.location, tagged);
    final TextDisplay second = hologram.getDisplay().orElseThrow();
    final Component taggedText = this.lastText(second);
    final String taggedPlain = PLAIN.serialize(taggedText);
    assertEquals("Unknown title\nUnknown uploader (Unknown date)", missingPlain);
    assertEquals("<red>Title</red>\n<bold>Me (Unknown date)", taggedPlain);
  }

  @Test
  void shouldShowMetadataWithBackslashesAsItIs() {
    final StandardVideoHologram hologram = new StandardVideoHologram();
    final UrlParseDump dump = this.createDump("Paths like C:\\", "a\\<red>b\\", -5, 1);
    hologram.spawn(this.location, dump);
    final Component text = this.lastText(hologram.getDisplay().orElseThrow());
    final String plain = PLAIN.serialize(text);
    assertEquals("Paths like C:\\\na\\<red>b\\ (Unknown date)", plain);
  }

  @Test
  void shouldReplaceThePreviousDisplayOnANewRequest() {
    final StandardVideoHologram hologram = new StandardVideoHologram();
    final UrlParseDump dump = this.createDump(TITLE, UPLOADER, 0, 1);
    hologram.spawn(this.location, dump);
    hologram.spawn(this.location, dump);
    final TextDisplay first = this.spawnedDisplays.get(0);
    final TextDisplay second = this.spawnedDisplays.get(1);
    final TextDisplay current = hologram.getDisplay().orElseThrow();
    verify(first).remove();
    verify(second, never()).remove();
    assertSame(second, current);
  }

  @Test
  void shouldRejectInvalidRequests() {
    final StandardVideoHologram hologram = new StandardVideoHologram();
    final UrlParseDump dump = this.createDump(TITLE, UPLOADER, 0, 1);
    final Location nowhere = new Location(null, 0, 0, 0);
    assertThrows(NullPointerException.class, () -> hologram.spawn(null, dump));
    assertThrows(NullPointerException.class, () -> hologram.spawn(this.location, null));
    assertThrows(IllegalArgumentException.class, () -> hologram.spawn(nowhere, dump));
  }

  @Test
  void shouldDoNothingWhenStartingBeforeARequest() {
    final StandardVideoHologram hologram = new StandardVideoHologram();
    hologram.start();
    final BukkitScheduler scheduler = this.server.getScheduler();
    verify(scheduler, never()).runTaskTimer(any(Plugin.class), any(Runnable.class), any(Long.class), any(Long.class));
  }

  @Test
  void shouldRejectStartingADisplayWithoutAnInjectedPlugin() {
    final StandardVideoHologram hologram = new StandardVideoHologram();
    final UrlParseDump dump = this.createDump(TITLE, UPLOADER, 0, DURATION_SECONDS);
    hologram.spawn(this.location, dump);
    FakeServer.clearInjectedPlugin();

    assertThrows(IllegalStateException.class, hologram::start);
  }

  private TextDisplay startHologram(final double duration) {
    final StandardVideoHologram hologram = new StandardVideoHologram();
    final UrlParseDump dump = this.createDump(TITLE, UPLOADER, 0, duration);
    hologram.spawn(this.location, dump);
    hologram.start();
    return this.spawnedDisplays.getLast();
  }

  @Test
  void shouldStartOneProgressTaskThatRunsEverySecond() {
    final StandardVideoHologram hologram = new StandardVideoHologram();
    final UrlParseDump dump = this.createDump(TITLE, UPLOADER, 0, DURATION_SECONDS);
    hologram.spawn(this.location, dump);
    hologram.start();
    hologram.start();
    final BukkitScheduler scheduler = this.server.getScheduler();
    final Plugin plugin = this.server.getPlugin();
    final int tasks = this.server.getScheduledTaskCount();
    verify(scheduler, times(1)).runTaskTimer(eq(plugin), any(Runnable.class), eq(0L), eq(20L));
    assertEquals(1, tasks);
  }

  @Test
  void shouldDrawAnEmptyBarAtTheStartAndFillItOncePerSecond() {
    final TextDisplay display = this.startHologram(DURATION_SECONDS);
    this.server.runTasks();
    final Component start = this.lastText(display);
    final String startPlain = PLAIN.serialize(start);
    final int startFilled = this.countFilled(start);
    final int startEmpty = this.countEmpty(start);

    this.server.runTasks();
    final Component oneSecond = this.lastText(display);
    final String oneSecondPlain = PLAIN.serialize(oneSecond);
    final int oneSecondFilled = this.countFilled(oneSecond);

    final String bar = PROGRESS.repeat(BAR_LENGTH);
    final String expectedStart = UNDATED_TEXT_AT_START + bar + DURATION_TEXT;
    assertEquals(expectedStart, startPlain);
    assertEquals(0, startFilled);
    assertEquals(BAR_LENGTH, startEmpty);

    final String expectedOneSecond = "t\nu (Unknown date)\n00:01 %s%s".formatted(bar, DURATION_TEXT);
    assertEquals(expectedOneSecond, oneSecondPlain);
    assertEquals(2, oneSecondFilled);
  }

  @Test
  void shouldFillTheBarAtTheEndAndStopOnceTheVideoEnded() {
    final TextDisplay display = this.startHologram(DURATION_SECONDS);
    for (int second = 0; second <= DURATION_SECONDS; second++) {
      this.server.runTasks();
    }

    final Component end = this.lastText(display);
    final String endPlain = PLAIN.serialize(end);
    final int endFilled = this.countFilled(end);
    final int tasksBeforeFinish = this.server.getScheduledTaskCount();

    this.server.runTasks();
    final int tasksAfterFinish = this.server.getScheduledTaskCount();

    final String bar = PROGRESS.repeat(BAR_LENGTH);
    final String expectedEnd = "t\nu (Unknown date)\n00:10 %s%s".formatted(bar, DURATION_TEXT);
    assertEquals(expectedEnd, endPlain);
    assertEquals(BAR_LENGTH, endFilled);
    assertEquals(1, tasksBeforeFinish);
    assertEquals(0, tasksAfterFinish, "the progress stops after the video ended");
    verify(display, times(12)).text(any(Component.class));
  }

  @Test
  void shouldRoundFractionalDurationsUpToWholeSeconds() {
    final TextDisplay display = this.startHologram(9.2);
    this.server.runTasks();
    final Component start = this.lastText(display);
    final String startPlain = PLAIN.serialize(start);
    final boolean endsWithDuration = startPlain.endsWith(DURATION_TEXT);
    assertTrue(endsWithDuration, startPlain);
  }

  @Test
  void shouldShowMinutesAndAnEmptyBarForVideosWithoutDuration() {
    final StandardVideoHologram hologram = new StandardVideoHologram();
    final UrlParseDump longDump = this.createDump(TITLE, UPLOADER, 0, 125);
    hologram.spawn(this.location, longDump);
    hologram.start();
    this.server.runTasks();
    final TextDisplay longDisplay = hologram.getDisplay().orElseThrow();
    final Component longText = this.lastText(longDisplay);
    final String longPlain = PLAIN.serialize(longText);
    final UrlParseDump negativeDump = this.createDump(TITLE, UPLOADER, 0, -3);
    hologram.spawn(this.location, negativeDump);
    hologram.start();
    this.server.runTasks();
    final TextDisplay negativeDisplay = hologram.getDisplay().orElseThrow();
    final Component negativeText = this.lastText(negativeDisplay);
    final int negativeFilled = this.countFilled(negativeText);
    final String negativePlain = PLAIN.serialize(negativeText);
    this.server.runTasks();
    final int tasks = this.server.getScheduledTaskCount();
    final String bar = PROGRESS.repeat(BAR_LENGTH);
    final String expectedLong = UNDATED_TEXT_AT_START + bar + " 02:05";
    assertEquals(expectedLong, longPlain);

    final String expectedNegative = UNDATED_TEXT_AT_START + bar + " 00:00";
    assertEquals(expectedNegative, negativePlain);
    assertEquals(0, negativeFilled);
    assertEquals(0, tasks);
  }

  @Test
  void shouldStopTheProgressWhenTheDisplayIsGone() {
    final StandardVideoHologram invalid = new StandardVideoHologram();
    final UrlParseDump dump = this.createDump(TITLE, UPLOADER, 0, LONG_DURATION_SECONDS);
    invalid.spawn(this.location, dump);
    final TextDisplay invalidDisplay = this.spawnedDisplays.getFirst();
    when(invalidDisplay.isValid()).thenReturn(false);
    invalid.start();
    this.server.runTasks();
    final int afterInvalid = this.server.getScheduledTaskCount();
    final StandardVideoHologram detached = new StandardVideoHologram();
    detached.spawn(this.location, dump);
    detached.start();
    detached.setDisplay(null);
    this.server.runTasks();
    final int afterDetached = this.server.getScheduledTaskCount();
    assertEquals(0, afterInvalid);
    assertEquals(0, afterDetached);
    verify(invalidDisplay, times(1)).text(any(Component.class));
  }

  @Test
  void shouldRemoveTheDisplayAndStopTheProgressWhenKilled() {
    final StandardVideoHologram hologram = new StandardVideoHologram();
    final UrlParseDump dump = this.createDump(TITLE, UPLOADER, 0, LONG_DURATION_SECONDS);
    hologram.spawn(this.location, dump);
    hologram.start();
    final TextDisplay display = hologram.getDisplay().orElseThrow();
    hologram.kill();
    hologram.kill();
    final Optional<TextDisplay> afterKill = hologram.getDisplay();
    final int tasks = this.server.getScheduledTaskCount();
    final boolean displayAbsentAfterKill = afterKill.isEmpty();
    assertTrue(displayAbsentAfterKill);
    assertEquals(0, tasks);
    verify(display, times(1)).remove();
  }

  @Test
  void shouldKeepThePreviousEntityWhenReplacingTheDisplay() {
    final StandardVideoHologram hologram = new StandardVideoHologram();
    final TextDisplay first = mock(TextDisplay.class);
    final TextDisplay second = mock(TextDisplay.class);
    hologram.setDisplay(first);
    hologram.setDisplay(second);
    final TextDisplay current = hologram.getDisplay().orElseThrow();
    assertSame(second, current);
    verify(first, never()).remove();
  }
}
