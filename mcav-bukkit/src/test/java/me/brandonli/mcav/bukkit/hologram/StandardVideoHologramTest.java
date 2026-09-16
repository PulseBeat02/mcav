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
import static org.junit.jupiter.api.Assertions.assertNull;
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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.function.Consumer;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
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

/**
 * Tests {@link Hologram}, {@link StandardVideoHologram}, and {@link VideoHologram}. The tests change the default time
 * zone, so they run in isolation.
 */
@Isolated
final class StandardVideoHologramTest {

  private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
  private static final String PROGRESS = "■";

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
      invocation -> {
        final TextDisplay display = mock(TextDisplay.class);
        when(display.isValid()).thenReturn(true);
        final Consumer<TextDisplay> configurator = invocation.getArgument(2);
        configurator.accept(display);
        this.spawnedDisplays.add(display);
        return display;
      }
    );
  }

  @AfterEach
  void stopServer() {
    this.server.close();
    TimeZone.setDefault(this.previousTimeZone);
  }

  private static URLParseDump createDump(final String title, final String uploader, final int timestamp, final double duration) {
    final URLParseDump dump = new URLParseDump();
    dump.title = title;
    dump.uploader = uploader;
    dump.timestamp = timestamp;
    dump.duration = duration;
    return dump;
  }

  private static int epochSecond(final int day) {
    final LocalDateTime dateTime = LocalDateTime.of(2024, 1, day, 15, 30);
    final long seconds = dateTime.toEpochSecond(ZoneOffset.UTC);
    return (int) seconds;
  }

  private static String expectedDate(final int day, final String suffix) {
    final LocalDateTime dateTime = LocalDateTime.of(2024, 1, day, 15, 30);
    final DateTimeFormatter monthDay = DateTimeFormatter.ofPattern("MMMM d");
    final DateTimeFormatter yearTime = DateTimeFormatter.ofPattern(", yyyy h:mm a");
    final String monthDayText = dateTime.format(monthDay);
    final String yearTimeText = dateTime.format(yearTime);
    return monthDayText + suffix + yearTimeText;
  }

  private static Component lastText(final TextDisplay display) {
    final ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
    verify(display, atLeastOnce()).text(captor.capture());
    return captor.getValue();
  }

  /**
   * Counts the progress segments drawn in the color, following the color inheritance of the component tree.
   */
  private static int countSegments(final Component component, final TextColor inheritedColor, final TextColor color) {
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
      count += countSegments(child, effectiveColor, color);
    }
    return count;
  }

  private static int countFilled(final Component component) {
    return countSegments(component, null, NamedTextColor.GREEN);
  }

  private static int countEmpty(final Component component) {
    return countSegments(component, null, NamedTextColor.GRAY);
  }

  @Test
  void basicHologramsShowVideoMetadata() {
    final Hologram hologram = Hologram.basic();
    final TextDisplay display = hologram.getDisplay();
    assertInstanceOf(StandardVideoHologram.class, hologram);
    assertNull(display, "nothing is spawned before a request");
  }

  @Test
  void spawnsAConfiguredDisplayWithTheMetadataOfTheVideo() {
    final StandardVideoHologram hologram = new StandardVideoHologram();
    final int timestamp = epochSecond(1);
    final URLParseDump dump = createDump("Some Video", "Some Channel", timestamp, 60);
    hologram.handleRequest(this.location, dump);
    final TextDisplay display = this.spawnedDisplays.getFirst();
    final TextDisplay current = hologram.getDisplay();
    final Component text = lastText(display);
    final String plain = PLAIN.serialize(text);
    final String date = expectedDate(1, "st");
    assertSame(display, current);
    assertEquals("Some Video\nSome Channel (" + date + ")", plain);
    verify(display).setPersistent(false);
    verify(display).setAlignment(TextDisplay.TextAlignment.CENTER);
    verify(display).setBillboard(Display.Billboard.VERTICAL);
    verify(display).setVisibleByDefault(true);
    verify(display).setSeeThrough(false);
    verify(display).setBackgroundColor(Color.BLACK);
  }

  @Test
  void writesOrdinalDaysLikeEnglishDoes() {
    final int[] days = { 2, 3, 4, 11, 12, 13, 21, 22, 23, 31 };
    final String[] suffixes = { "nd", "rd", "th", "th", "th", "th", "st", "nd", "rd", "st" };
    for (int index = 0; index < days.length; index++) {
      final StandardVideoHologram hologram = new StandardVideoHologram();
      final int timestamp = epochSecond(days[index]);
      final URLParseDump dump = createDump("t", "u", timestamp, 1);
      hologram.handleRequest(this.location, dump);
      final TextDisplay display = hologram.getDisplay();
      final Component text = lastText(display);
      final String plain = PLAIN.serialize(text);
      final String date = expectedDate(days[index], suffixes[index]);
      assertEquals("t\nu (" + date + ")", plain);
    }
  }

  @Test
  void usesPlaceholdersForMissingMetadataAndDoesNotParseTagsInIt() {
    final StandardVideoHologram hologram = new StandardVideoHologram();
    final URLParseDump missing = createDump(null, null, 0, 1);
    hologram.handleRequest(this.location, missing);
    final TextDisplay first = hologram.getDisplay();
    final Component missingText = lastText(first);
    final String missingPlain = PLAIN.serialize(missingText);
    final URLParseDump tagged = createDump("<red>Title</red>", "<bold>Me", -5, 1);
    hologram.handleRequest(this.location, tagged);
    final TextDisplay second = hologram.getDisplay();
    final Component taggedText = lastText(second);
    final String taggedPlain = PLAIN.serialize(taggedText);
    assertEquals("Unknown title\nUnknown uploader (Unknown date)", missingPlain);
    assertEquals("<red>Title</red>\n<bold>Me (Unknown date)", taggedPlain);
  }

  @Test
  void replacesThePreviousDisplayOnANewRequest() {
    final StandardVideoHologram hologram = new StandardVideoHologram();
    final URLParseDump dump = createDump("t", "u", 0, 1);
    hologram.handleRequest(this.location, dump);
    hologram.handleRequest(this.location, dump);
    final TextDisplay first = this.spawnedDisplays.get(0);
    final TextDisplay second = this.spawnedDisplays.get(1);
    final TextDisplay current = hologram.getDisplay();
    verify(first).remove();
    verify(second, never()).remove();
    assertSame(second, current);
  }

  @Test
  void rejectsInvalidRequests() {
    final StandardVideoHologram hologram = new StandardVideoHologram();
    final URLParseDump dump = createDump("t", "u", 0, 1);
    final Location nowhere = new Location(null, 0, 0, 0);
    assertThrows(NullPointerException.class, () -> hologram.handleRequest(null, dump));
    assertThrows(NullPointerException.class, () -> hologram.handleRequest(this.location, null));
    assertThrows(IllegalArgumentException.class, () -> hologram.handleRequest(nowhere, dump));
  }

  @Test
  void startingBeforeARequestDoesNothing() {
    final StandardVideoHologram hologram = new StandardVideoHologram();
    hologram.start();
    final BukkitScheduler scheduler = this.server.getScheduler();
    verify(scheduler, never()).runTaskTimer(any(Plugin.class), any(Runnable.class), any(Long.class), any(Long.class));
  }

  private TextDisplay startHologram(final double duration) {
    final StandardVideoHologram hologram = new StandardVideoHologram();
    final URLParseDump dump = createDump("t", "u", 0, duration);
    hologram.handleRequest(this.location, dump);
    hologram.start();
    return this.spawnedDisplays.getLast();
  }

  @Test
  void startsOneProgressTaskThatRunsEverySecond() {
    final StandardVideoHologram hologram = new StandardVideoHologram();
    final URLParseDump dump = createDump("t", "u", 0, 10);
    hologram.handleRequest(this.location, dump);
    hologram.start();
    hologram.start();
    final BukkitScheduler scheduler = this.server.getScheduler();
    final Plugin plugin = this.server.getPlugin();
    final int tasks = this.server.getScheduledTaskCount();
    verify(scheduler, times(1)).runTaskTimer(eq(plugin), any(Runnable.class), eq(0L), eq(20L));
    assertEquals(1, tasks);
  }

  @Test
  void drawsAnEmptyBarAtTheStartAndFillsItOncePerSecond() {
    final TextDisplay display = this.startHologram(10);
    this.server.runTasks();
    final Component start = lastText(display);
    final String startPlain = PLAIN.serialize(start);
    final int startFilled = countFilled(start);
    final int startEmpty = countEmpty(start);

    this.server.runTasks();
    final Component oneSecond = lastText(display);
    final String oneSecondPlain = PLAIN.serialize(oneSecond);
    final int oneSecondFilled = countFilled(oneSecond);

    final String bar = PROGRESS.repeat(20);
    assertEquals("t\nu (Unknown date)\n00:00 " + bar + " 00:10", startPlain);
    assertEquals(0, startFilled);
    assertEquals(20, startEmpty);
    assertEquals("t\nu (Unknown date)\n00:01 " + bar + " 00:10", oneSecondPlain);
    assertEquals(2, oneSecondFilled);
  }

  @Test
  void fillsTheBarAtTheEndAndStopsOnceTheVideoEnded() {
    final TextDisplay display = this.startHologram(10);
    for (int second = 0; second <= 10; second++) {
      this.server.runTasks();
    }
    final Component end = lastText(display);
    final String endPlain = PLAIN.serialize(end);
    final int endFilled = countFilled(end);
    final int tasksBeforeFinish = this.server.getScheduledTaskCount();

    this.server.runTasks();
    final int tasksAfterFinish = this.server.getScheduledTaskCount();

    final String bar = PROGRESS.repeat(20);
    assertEquals("t\nu (Unknown date)\n00:10 " + bar + " 00:10", endPlain);
    assertEquals(20, endFilled);
    assertEquals(1, tasksBeforeFinish);
    assertEquals(0, tasksAfterFinish, "the progress stops after the video ended");
    verify(display, times(12)).text(any(Component.class));
  }

  @Test
  void roundsFractionalDurationsUpToWholeSeconds() {
    final TextDisplay display = this.startHologram(9.2);
    this.server.runTasks();
    final Component start = lastText(display);
    final String startPlain = PLAIN.serialize(start);
    final boolean endsWithDuration = startPlain.endsWith(" 00:10");
    assertTrue(endsWithDuration, startPlain);
  }

  @Test
  void showsMinutesAndAnEmptyBarForVideosWithoutDuration() {
    final StandardVideoHologram hologram = new StandardVideoHologram();
    final URLParseDump longDump = createDump("t", "u", 0, 125);
    hologram.handleRequest(this.location, longDump);
    hologram.start();
    this.server.runTasks();
    final TextDisplay longDisplay = hologram.getDisplay();
    final Component longText = lastText(longDisplay);
    final String longPlain = PLAIN.serialize(longText);
    final URLParseDump negativeDump = createDump("t", "u", 0, -3);
    hologram.handleRequest(this.location, negativeDump);
    hologram.start();
    this.server.runTasks();
    final TextDisplay negativeDisplay = hologram.getDisplay();
    final Component negativeText = lastText(negativeDisplay);
    final int negativeFilled = countFilled(negativeText);
    final String negativePlain = PLAIN.serialize(negativeText);
    this.server.runTasks();
    final int tasks = this.server.getScheduledTaskCount();
    final String bar = PROGRESS.repeat(20);
    assertEquals("t\nu (Unknown date)\n00:00 " + bar + " 02:05", longPlain);
    assertEquals("t\nu (Unknown date)\n00:00 " + bar + " 00:00", negativePlain);
    assertEquals(0, negativeFilled);
    assertEquals(0, tasks);
  }

  @Test
  void stopsTheProgressWhenTheDisplayIsGone() {
    final StandardVideoHologram invalid = new StandardVideoHologram();
    final URLParseDump dump = createDump("t", "u", 0, 100);
    invalid.handleRequest(this.location, dump);
    final TextDisplay invalidDisplay = this.spawnedDisplays.getFirst();
    when(invalidDisplay.isValid()).thenReturn(false);
    invalid.start();
    this.server.runTasks();
    final int afterInvalid = this.server.getScheduledTaskCount();
    final StandardVideoHologram detached = new StandardVideoHologram();
    detached.handleRequest(this.location, dump);
    detached.start();
    detached.setDisplay(null);
    this.server.runTasks();
    final int afterDetached = this.server.getScheduledTaskCount();
    assertEquals(0, afterInvalid);
    assertEquals(0, afterDetached);
    verify(invalidDisplay, times(1)).text(any(Component.class));
  }

  @Test
  void killingRemovesTheDisplayAndStopsTheProgress() {
    final StandardVideoHologram hologram = new StandardVideoHologram();
    final URLParseDump dump = createDump("t", "u", 0, 100);
    hologram.handleRequest(this.location, dump);
    hologram.start();
    final TextDisplay display = hologram.getDisplay();
    hologram.kill();
    hologram.kill();
    final TextDisplay afterKill = hologram.getDisplay();
    final int tasks = this.server.getScheduledTaskCount();
    assertNull(afterKill);
    assertEquals(0, tasks);
    verify(display, times(1)).remove();
  }

  @Test
  void replacingTheDisplayKeepsThePreviousEntity() {
    final StandardVideoHologram hologram = new StandardVideoHologram();
    final TextDisplay first = mock(TextDisplay.class);
    final TextDisplay second = mock(TextDisplay.class);
    hologram.setDisplay(first);
    hologram.setDisplay(second);
    final TextDisplay current = hologram.getDisplay();
    assertSame(second, current);
    verify(first, never()).remove();
  }
}
