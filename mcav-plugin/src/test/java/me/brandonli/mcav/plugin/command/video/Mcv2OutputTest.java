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
package me.brandonli.mcav.plugin.command.video;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import me.brandonli.mcav.bukkit.media.mcv2.Mcv2PackServer;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Result;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Tests {@link Mcv2Output}: frames and the start reach the screen's result, and a release gives the screen's slot of
 * the pack back, even when the result fails to release.
 */
final class Mcv2OutputTest {

  private Mcv2Result result;

  private Mcv2PackServer.Lease lease;

  private Mcv2Output output;

  @BeforeEach
  void createOutput() {
    this.result = mock(Mcv2Result.class);
    this.lease = mock(Mcv2PackServer.Lease.class);
    this.output = new Mcv2Output(this.result, this.lease);
  }

  @Test
  void passesTheFramesAndTheStartToTheResult() {
    final ImageBuffer frame = mock(ImageBuffer.class);
    final OriginalVideoMetadata metadata = OriginalVideoMetadata.of(64, 32);
    when(this.result.applyFilter(frame, metadata)).thenReturn(true);
    assertTrue(this.output.applyFilter(frame, metadata));
    assertFalse(this.output.applyFilter(mock(ImageBuffer.class), metadata), "what the result answers comes back");
    this.output.start();
    verify(this.result).start();
    assertSame(this.result, this.output.getResult());
  }

  @Test
  void releasesTheResultThenGivesTheSlotBack() {
    this.output.release();
    final InOrder order = inOrder(this.result, this.lease);
    order.verify(this.result).release();
    order.verify(this.lease).close();
  }

  @Test
  void givesTheSlotBackWhenTheResultFailsToRelease() {
    final IllegalStateException failure = new IllegalStateException("release");
    doThrow(failure).when(this.result).release();
    assertSame(failure, assertThrows(IllegalStateException.class, this.output::release));
    verify(this.lease).close();
  }

  @Test
  void refusesMissingParts() {
    assertThrows(NullPointerException.class, () -> new Mcv2Output(null, this.lease));
    assertThrows(NullPointerException.class, () -> new Mcv2Output(this.result, null));
  }
}
