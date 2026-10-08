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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Settings;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Channel;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

final class Mcv2PlaybackTest {

  private Mcv2Channel channel;

  static List<byte[]> stream() {
    final MCV2 encoder = new MCV2(Settings.DEFAULT, ForkJoinPool.commonPool(), 1, true);
    final byte[] rgb = new byte[32 * 32 * 3];
    for (int at = 0; at < rgb.length; at += 3) {
      rgb[at] = 1;
      rgb[at + 1] = 2;
      rgb[at + 2] = 3;
    }
    return List.of(encoder.encode(rgb, 32, 32, 0), encoder.encode(rgb, 32, 32, 1), encoder.encode(rgb, 32, 32, 2));
  }

  @BeforeEach
  void createChannel() {
    this.channel = mock(Mcv2Channel.class);
  }

  private long[] sent(final int times) {
    final ArgumentCaptor<byte[]> frames = ArgumentCaptor.forClass(byte[].class);
    verify(this.channel, times(times)).send(frames.capture());
    final List<byte[]> all = frames.getAllValues();
    final byte[] last = all.getLast();
    return new long[] { Mcv2Decoder.u32(last, 12), Mcv2Decoder.u32(last, 16) };
  }

  @Test
  void waitsForAViewer() {
    when(this.channel.getRecipients()).thenReturn(Set.of());
    new Mcv2Playback(this.channel, stream(), 0).run();
    verify(this.channel).update();
    verify(this.channel, never()).send(any());
  }

  @Test
  void loopsWithIdsThatKeepIncreasing() {
    when(this.channel.getRecipients()).thenReturn(Set.of(UUID.randomUUID()));
    final Mcv2Playback playback = new Mcv2Playback(this.channel, stream(), 0);
    playback.run();
    assertEquals(0, this.sent(1)[0]);
    playback.run();
    playback.run();
    final long[] third = this.sent(3);
    assertEquals(2, third[0]);
    assertEquals(1, third[1]);
    // the fourth run starts the stream over, three ids later
    playback.run();
    final long[] fourth = this.sent(4);
    assertEquals(3, fourth[0]);
    assertEquals(3, fourth[1]);
  }

  @Test
  void startsOverForANewViewer() {
    when(this.channel.getRecipients()).thenReturn(Set.of(UUID.randomUUID()));
    when(this.channel.takeKeyframeRequest()).thenReturn(true, false, true);
    final Mcv2Playback playback = new Mcv2Playback(this.channel, stream(), 0);
    // a request before the first frame changes nothing: the first frame is the keyframe
    playback.run();
    assertEquals(0, this.sent(1)[0]);
    playback.run();
    // a request in the middle starts over from the keyframe, three ids later
    playback.run();
    final long[] restarted = this.sent(3);
    assertEquals(3, restarted[0]);
    assertEquals(3, restarted[1]);
  }

  @Test
  void numbersItsFramesFromTheFirstFrameIdOfItsSlotAcrossTheWrap() {
    when(this.channel.getRecipients()).thenReturn(Set.of(UUID.randomUUID()));
    final Mcv2Playback playback = new Mcv2Playback(this.channel, stream(), Mcv2Decoder.MAX_U32 - 1);
    playback.run();
    assertEquals(Mcv2Decoder.MAX_U32 - 1, this.sent(1)[0]);
    playback.run();
    assertEquals(Mcv2Decoder.MAX_U32, this.sent(2)[0]);
    playback.run();
    final long[] third = this.sent(3);
    assertEquals(0, third[0], "the ids wrap like the clients' unsigned 32-bit sequence");
    assertEquals(Mcv2Decoder.MAX_U32, third[1]);
  }

  @Test
  void refusesAnEmptyStream() {
    assertThrows(IllegalArgumentException.class, () -> new Mcv2Playback(this.channel, List.of(), 0));
    assertThrows(NullPointerException.class, () -> new Mcv2Playback(null, stream(), 0));
  }
}
