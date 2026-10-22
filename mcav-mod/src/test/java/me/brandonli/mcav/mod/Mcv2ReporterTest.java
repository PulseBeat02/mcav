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
package me.brandonli.mcav.mod;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class Mcv2ReporterTest {

  private static final Mcv2Report SHADERS_OFF = new Mcv2Report(true, ShaderPack.NONE, false);

  private static final Mcv2Report SHADERS_ON = new Mcv2Report(true, ShaderPack.IN_USE, false);

  private final AtomicLong clock = new AtomicLong(Long.MAX_VALUE - Mcv2Reporter.STEADY_NANOSECONDS);

  private final RecordingChannel channel = new RecordingChannel();

  private IrisShaders shaders;

  private Mcv2Reporter reporter;

  @BeforeEach
  void createReporter() {
    this.shaders = mock(IrisShaders.class);
    when(this.shaders.report()).thenReturn(SHADERS_OFF);
    this.reporter = new Mcv2Reporter(this.shaders, this.channel, this.clock::get);
  }

  /** Ticks once, after a time. */
  private void tickAfter(final long nanoseconds) {
    this.clock.addAndGet(nanoseconds);
    this.reporter.tick();
  }

  @Test
  void shouldAskNothingWhileTheServerDoesNotListen() {
    this.channel.open = false;
    this.tickAfter(0);
    this.tickAfter(2 * Mcv2Reporter.STEADY_NANOSECONDS);
    verifyNoInteractions(this.shaders);
    final List<byte[]> sent = this.channel.sent;
    assertEquals(List.of(), sent);
  }

  @Test
  void shouldSendWhatIrisSaysOnceItHeldASecondAndOnlyOnce() {
    this.tickAfter(0);
    this.tickAfter(Mcv2Reporter.STEADY_NANOSECONDS - 1);
    final List<byte[]> sent = this.channel.sent;
    assertEquals(List.of(), sent);
    this.tickAfter(1);
    this.tickAfter(Mcv2Reporter.STEADY_NANOSECONDS);
    this.tickAfter(Mcv2Reporter.STEADY_NANOSECONDS);
    final int sentCount = this.channel.sent.size();
    final byte[] first = this.channel.sent.getFirst();
    assertEquals(1, sentCount, "the server is told once, past the wrap of the clock too");
    assertArrayEquals(SHADERS_OFF.toBytes(), first);
  }

  @Test
  void shouldSendAChangeThatHeldASecondButNotAShorterOne() {
    this.tickAfter(0);
    this.tickAfter(Mcv2Reporter.STEADY_NANOSECONDS);
    when(this.shaders.report()).thenReturn(SHADERS_ON);
    this.tickAfter(1);
    when(this.shaders.report()).thenReturn(SHADERS_OFF);
    this.tickAfter(Mcv2Reporter.STEADY_NANOSECONDS);
    final int sentAfterABriefChange = this.channel.sent.size();
    assertEquals(1, sentAfterABriefChange);

    when(this.shaders.report()).thenReturn(SHADERS_ON);
    this.tickAfter(1);
    this.tickAfter(Mcv2Reporter.STEADY_NANOSECONDS - 1);
    final int sentBeforeASecond = this.channel.sent.size();
    assertEquals(1, sentBeforeASecond);
    this.tickAfter(1);
    final int sentAfterASecond = this.channel.sent.size();
    final byte[] last = this.channel.sent.getLast();
    assertEquals(2, sentAfterASecond);
    assertArrayEquals(SHADERS_ON.toBytes(), last);
  }

  @Test
  void shouldTellTheServerOfANewConnectionAgain() {
    this.tickAfter(0);
    this.tickAfter(Mcv2Reporter.STEADY_NANOSECONDS);
    this.reporter.reset();
    this.tickAfter(1);
    final int sentToTheNewServer = this.channel.sent.size();
    assertEquals(1, sentToTheNewServer, "the new server is told once the report held a second on it");
    this.tickAfter(Mcv2Reporter.STEADY_NANOSECONDS);
    final int sentOnceItHeld = this.channel.sent.size();
    final byte[] last = this.channel.sent.getLast();
    assertEquals(2, sentOnceItHeld);
    assertArrayEquals(SHADERS_OFF.toBytes(), last);
  }

  /** A channel that records what is sent on it. */
  private static final class RecordingChannel implements ReportChannel {

    private final List<byte[]> sent = new ArrayList<>();

    private boolean open = true;

    @Override
    public boolean isOpen() {
      return this.open;
    }

    @Override
    public void send(final byte[] report) {
      this.sent.add(report);
    }
  }
}
