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
package me.brandonli.mcav.plugin.command.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Channel;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Result;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.plugin.command.video.Mcv2Output;
import me.brandonli.mcav.plugin.testing.TestServer;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;

final class Mcv2ImageTest {

  private static final int[] PIXELS = { 0xFF102030, 0xFF405060, 0xFF708090, 0xFFA0B0C0, 0xFFD0E0F0, 0xFF000000 };

  private static final int IMAGE_WIDTH = 3;
  private static final int IMAGE_HEIGHT = 2;

  private static final long HALF_A_SECOND_TICKS = 10L;

  private Plugin plugin;

  private Mcv2Output output;

  private BukkitTask task;

  private final List<int[]> given = new ArrayList<>();

  @BeforeEach
  void createImage() {
    TestServer.reset();
    this.plugin = mock(Plugin.class);
    this.output = mock(Mcv2Output.class);
    this.task = mock(BukkitTask.class);
    when(TestServer.getScheduler().runTaskTimerAsynchronously(any(Plugin.class), any(Runnable.class), anyLong(), anyLong())).thenReturn(
      this.task
    );
    doAnswer(this::recordAndAlterFrame).when(this.output).applyFilter(any(), any());
  }

  private boolean recordAndAlterFrame(final InvocationOnMock invocation) {
    final ImageBuffer frame = invocation.getArgument(0);
    final OriginalVideoMetadata metadata = invocation.getArgument(1);
    final int width = metadata.getVideoWidth();
    assertEquals(IMAGE_WIDTH, width);
    final int height = metadata.getVideoHeight();
    assertEquals(IMAGE_HEIGHT, height);

    this.given.add(frame.getPixels().clone());
    // the screen may resize the frame in place: it is the screen's own copy
    frame.getPixels()[0] = 0;

    return true;
  }

  @Test
  void shouldStartTheScreenAndGiveItTheImageTwiceASecondUntilReleased() {
    final Mcv2Image image = new Mcv2Image(this.plugin, this.output);
    final ImageBuffer picture = ImageBuffer.buffer(PIXELS.clone(), IMAGE_WIDTH, IMAGE_HEIGHT);

    image.displayImage(picture);
    // the caller keeps its image and may change or release it
    picture.getPixels()[1] = 0;

    verify(this.output).start();

    final ArgumentCaptor<Runnable> feeds = ArgumentCaptor.forClass(Runnable.class);
    verify(TestServer.getScheduler()).runTaskTimerAsynchronously(eq(this.plugin), feeds.capture(), eq(0L), eq(HALF_A_SECOND_TICKS));

    feeds.getValue().run();
    feeds.getValue().run();

    final int size = this.given.size();
    assertEquals(2, size);

    final int[] given = this.given.getFirst();
    assertArrayEquals(PIXELS, given);

    final int[] givenGiven = this.given.getLast();
    assertArrayEquals(PIXELS, givenGiven, "every frame is a fresh copy of the image");

    image.release();
    verify(this.task).cancel();
    verify(this.output).release();

    image.release();
    verify(this.task, times(1)).cancel();
  }

  @Test
  void shouldAskForAKeyframeEveryFourthSecond() {
    final Mcv2Result result = mock(Mcv2Result.class);
    final Mcv2Channel channel = mock(Mcv2Channel.class);
    when(this.output.getResult()).thenReturn(result);
    when(result.getChannel()).thenReturn(channel);
    final Mcv2Image image = new Mcv2Image(this.plugin, this.output);

    image.displayImage(ImageBuffer.buffer(PIXELS.clone(), IMAGE_WIDTH, IMAGE_HEIGHT));

    final ArgumentCaptor<Runnable> feeds = ArgumentCaptor.forClass(Runnable.class);
    verify(TestServer.getScheduler()).runTaskTimerAsynchronously(eq(this.plugin), feeds.capture(), eq(0L), eq(HALF_A_SECOND_TICKS));

    // a client that missed its first keyframe shows nothing until the next, so the image requests one after four seconds
    final int fourSeconds = (int) ((4 * 20) / HALF_A_SECOND_TICKS);
    for (int feed = 1; feed < fourSeconds; feed++) {
      feeds.getValue().run();
    }
    verify(channel, never()).requestKeyframe();

    feeds.getValue().run();
    verify(channel).requestKeyframe();

    for (int feed = 0; feed < fourSeconds; feed++) {
      feeds.getValue().run();
    }
    verify(channel, times(2)).requestKeyframe();

    final int size = this.given.size();
    assertEquals(2 * fourSeconds, size, "every feed gives the image");
  }

  @Test
  void shouldReleaseAScreenThatShowedNothing() {
    final Mcv2Image image = new Mcv2Image(this.plugin, this.output);
    image.release();
    verify(this.output).release();
    verify(this.output, never()).start();
  }

  @Test
  void shouldRefuseMissingParts() {
    assertThrows(NullPointerException.class, () -> new Mcv2Image(null, this.output));
    assertThrows(NullPointerException.class, () -> new Mcv2Image(this.plugin, null));
    assertThrows(NullPointerException.class, () -> new Mcv2Image(this.plugin, this.output).displayImage(null));
  }
}
