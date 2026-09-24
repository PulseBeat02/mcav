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
package me.brandonli.mcav.sandbox.command.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.concurrent.ExecutorService;
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.image.ImagePlayer;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.testing.TestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ImageManager}.
 */
final class ImageManagerTest {

  private ImageManager manager;
  private ImagePlayer player;
  private DisplayableImage display;
  private ImageBuffer image;

  @BeforeEach
  void createManager() {
    TestServer.resetWithDeferredTasks();
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    this.manager = new ImageManager(plugin);
    this.player = mock(ImagePlayer.class);
    this.display = mock(DisplayableImage.class);
    this.image = mock(ImageBuffer.class);
  }

  @AfterEach
  void shutDown() {
    this.manager.shutdown();
  }

  private void fill() {
    this.manager.setPlayer(this.player);
    this.manager.setImage(this.display);
    this.manager.setCurrentImage(this.image);
  }

  @Test
  void releasesEverythingOnceWhenForced() {
    this.fill();
    this.manager.releaseImage(true);
    this.manager.releaseImage(true);
    verify(this.display, times(1)).release();
    verify(this.image, times(1)).close();
    verify(this.player, times(1)).release();
    final int pending = TestServer.runPendingTasks();
    assertEquals(0, pending);
  }

  @Test
  void releasesOnTheMainThreadWhenNotForced() {
    this.fill();
    this.manager.releaseImage(false);
    verify(this.display, never()).release();
    final int pending = TestServer.runPendingTasks();
    assertEquals(1, pending);
    verify(this.display).release();
    verify(this.image).close();
    verify(this.player).release();
  }

  @Test
  void releasesNothingWhenEmpty() {
    this.manager.releaseImage(true);
    this.manager.setImage(this.display);
    this.manager.releaseImage(true);
    verify(this.display).release();
  }

  @Test
  void forgetsAnImageThatFailedToClose() {
    this.fill();
    final IllegalStateException failure = new IllegalStateException("already closed");
    doThrow(failure).when(this.image).close();
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> this.manager.releaseImage(true));
    assertEquals(failure, thrown);
    verify(this.player, times(1)).release();
    this.manager.releaseImage(true);
    verify(this.image, times(1)).close();
    verify(this.display, times(1)).release();
    verify(this.player, times(1)).release();
  }

  @Test
  void stopsItsWorkerWhenShutDown() {
    this.fill();
    this.manager.shutdown();
    verify(this.display).release();
    final ExecutorService service = this.manager.getService();
    final boolean shutdown = service.isShutdown();
    assertTrue(shutdown);
  }

  @Test
  void rejectsALoadThatFinishesAfterShutdownOrClear() {
    final long beforeClear = this.manager.beginLoad();
    this.manager.releaseImage(true);
    final boolean retainedAfterClear = this.manager.retainLoaded(beforeClear, this.image);
    assertFalse(retainedAfterClear);
    final long beforeShutdown = this.manager.beginLoad();
    this.manager.shutdown();
    final boolean retainedAfterShutdown = this.manager.retainLoaded(beforeShutdown, this.image);
    assertFalse(retainedAfterShutdown);
    assertThrows(java.util.concurrent.RejectedExecutionException.class, this.manager::beginLoad);
  }

  @Test
  void shutsDownTheWorkerEvenWhenDisplayCleanupFails() {
    this.fill();
    final IllegalStateException failure = new IllegalStateException("display cleanup failed");
    doThrow(failure).when(this.display).release();
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, this.manager::shutdown);
    assertEquals(failure, thrown);
    verify(this.image).close();
    verify(this.player).release();
    final ExecutorService service = this.manager.getService();
    final boolean stopped = service.isShutdown();
    assertTrue(stopped);
  }

  @Test
  void discardingAnOldRequestCannotReleaseTheNewRequestsImage() {
    final long oldRequest = this.manager.beginLoad();
    final long newRequest = this.manager.beginLoad();
    final boolean retained = this.manager.retainLoaded(newRequest, this.image);
    assertTrue(retained);
    this.manager.discardLoaded(oldRequest);
    verify(this.image, never()).release();
    this.manager.discardLoaded(newRequest);
    verify(this.image, times(1)).release();
    this.manager.discardLoaded(newRequest);
    verify(this.image, times(1)).release();
  }

  @Test
  void cancellationAndReplacementNeverReviveAnOlderLoadToken() {
    final long oldest = this.manager.beginLoad();
    final long replaced = this.manager.beginLoad();
    this.manager.releaseImage(true);
    final boolean oldestAccepted = this.manager.retainLoaded(oldest, this.image);
    final boolean replacedAccepted = this.manager.retainLoaded(replaced, this.image);
    assertFalse(oldestAccepted);
    assertFalse(replacedAccepted);
    final long current = this.manager.beginLoad();
    assertFalse(this.manager.retainLoaded(oldest, this.image));
    assertFalse(this.manager.retainLoaded(replaced, this.image));
    assertTrue(this.manager.retainLoaded(current, this.image));
    final ImageBuffer taken = this.manager.takeLoaded(current);
    org.junit.jupiter.api.Assertions.assertSame(this.image, taken);
  }

  @Test
  void refusesANullPlugin() {
    assertThrows(NullPointerException.class, () -> new ImageManager(null));
  }
}
