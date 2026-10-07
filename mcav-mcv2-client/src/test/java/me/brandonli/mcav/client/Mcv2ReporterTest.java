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
package me.brandonli.mcav.client;

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

  private final AtomicLong clock = new AtomicLong(Long.MAX_VALUE - Mcv2Reporter.STEADY_NANOS);

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
  private void tickAfter(final long nanos) {
    this.clock.addAndGet(nanos);
    this.reporter.tick();
  }

  @Test
  void asksNothingWhileTheServerDoesNotListen() {
    this.channel.open = false;
    this.tickAfter(0);
    this.tickAfter(2 * Mcv2Reporter.STEADY_NANOS);
    verifyNoInteractions(this.shaders);
    assertEquals(List.of(), this.channel.sent);
  }

  @Test
  void sendsWhatIrisSaysOnceItHeldASecondAndOnlyOnce() {
    this.tickAfter(0);
    this.tickAfter(Mcv2Reporter.STEADY_NANOS - 1);
    assertEquals(List.of(), this.channel.sent);
    this.tickAfter(1);
    this.tickAfter(Mcv2Reporter.STEADY_NANOS);
    this.tickAfter(Mcv2Reporter.STEADY_NANOS);
    assertEquals(1, this.channel.sent.size(), "the server is told once, past the wrap of the clock too");
    assertArrayEquals(SHADERS_OFF.toBytes(), this.channel.sent.getFirst());
  }

  @Test
  void sendsAChangeThatHeldASecondButNotAShorterOne() {
    this.tickAfter(0);
    this.tickAfter(Mcv2Reporter.STEADY_NANOS);
    // Iris has no pipeline for a moment while it reloads one
    when(this.shaders.report()).thenReturn(SHADERS_ON);
    this.tickAfter(1);
    when(this.shaders.report()).thenReturn(SHADERS_OFF);
    this.tickAfter(Mcv2Reporter.STEADY_NANOS);
    assertEquals(1, this.channel.sent.size());

    when(this.shaders.report()).thenReturn(SHADERS_ON);
    this.tickAfter(1);
    this.tickAfter(Mcv2Reporter.STEADY_NANOS - 1);
    assertEquals(1, this.channel.sent.size());
    this.tickAfter(1);
    assertEquals(2, this.channel.sent.size());
    assertArrayEquals(SHADERS_ON.toBytes(), this.channel.sent.getLast());
  }

  @Test
  void tellsTheServerOfANewConnectionAgain() {
    this.tickAfter(0);
    this.tickAfter(Mcv2Reporter.STEADY_NANOS);
    this.reporter.reset();
    this.tickAfter(1);
    assertEquals(1, this.channel.sent.size(), "the new server is told once the report held a second on it");
    this.tickAfter(Mcv2Reporter.STEADY_NANOS);
    assertEquals(2, this.channel.sent.size());
    assertArrayEquals(SHADERS_OFF.toBytes(), this.channel.sent.getLast());
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
