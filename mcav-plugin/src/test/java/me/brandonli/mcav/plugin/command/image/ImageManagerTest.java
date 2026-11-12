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
import java.util.concurrent.RejectedExecutionException;
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.image.ImagePlayer;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.testing.TestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

  @Test
  void shouldReleaseEverythingOnceWhenForced() {
    this.fill();
    this.manager.releaseImage(ReleaseThread.CALLING_THREAD);
    this.manager.releaseImage(ReleaseThread.CALLING_THREAD);
    verify(this.display, times(1)).release();
    verify(this.image, times(1)).close();
    verify(this.player, times(1)).release();

    final int pending = TestServer.runPendingTasks();
    assertEquals(0, pending);
  }

  private void fill() {
    this.manager.setPlayer(this.player);
    this.manager.setImage(this.display);
    this.manager.setCurrentImage(this.image);
  }

  @Test
  void shouldReleaseOnTheMainThreadWhenNotForced() {
    this.fill();
    this.manager.releaseImage(ReleaseThread.MAIN_THREAD);
    verify(this.display, never()).release();

    final int pending = TestServer.runPendingTasks();
    assertEquals(1, pending);
    verify(this.display).release();
    verify(this.image).close();
    verify(this.player).release();
  }

  @Test
  void shouldReleaseNothingWhenEmpty() {
    this.manager.releaseImage(ReleaseThread.CALLING_THREAD);
    this.manager.setImage(this.display);
    this.manager.releaseImage(ReleaseThread.CALLING_THREAD);
    verify(this.display).release();
  }

  @Test
  void shouldForgetAnImageThatFailedToClose() {
    this.fill();
    final IllegalStateException failure = new IllegalStateException("already closed!");
    doThrow(failure).when(this.image).close();
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
      this.manager.releaseImage(ReleaseThread.CALLING_THREAD)
    );
    assertEquals(failure, thrown);
    verify(this.player, times(1)).release();

    this.manager.releaseImage(ReleaseThread.CALLING_THREAD);
    verify(this.image, times(1)).close();
    verify(this.display, times(1)).release();
    verify(this.player, times(1)).release();
  }

  @Test
  void shouldStopItsWorkerWhenShutDown() {
    this.fill();
    this.manager.shutdown();
    verify(this.display).release();

    final ExecutorService service = this.manager.getService();
    final boolean isShutdown = service.isShutdown();
    assertTrue(isShutdown);
  }

  @Test
  void shouldRejectALoadThatFinishesAfterShutdownOrClear() {
    final long beforeClear = this.manager.beginLoad();
    this.manager.releaseImage(ReleaseThread.CALLING_THREAD);
    final boolean retainedAfterClear = this.manager.retainLoaded(beforeClear, this.image);
    assertFalse(retainedAfterClear);

    final long beforeShutdown = this.manager.beginLoad();
    this.manager.shutdown();
    final boolean retainedAfterShutdown = this.manager.retainLoaded(beforeShutdown, this.image);
    assertFalse(retainedAfterShutdown);
    assertThrows(RejectedExecutionException.class, this.manager::beginLoad);
  }

  @Test
  void shouldShutDownTheWorkerEvenWhenDisplayCleanupFails() {
    this.fill();
    final IllegalStateException failure = new IllegalStateException("display cleanup failed!");
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
  void shouldNotReleaseTheNewRequestsImageWhenDiscardingAnOldRequest() {
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
  void shouldNeverReviveAnOlderLoadTokenOnCancellationAndReplacement() {
    final long oldest = this.manager.beginLoad();
    final long replaced = this.manager.beginLoad();
    this.manager.releaseImage(ReleaseThread.CALLING_THREAD);
    final boolean oldestAccepted = this.manager.retainLoaded(oldest, this.image);
    final boolean replacedAccepted = this.manager.retainLoaded(replaced, this.image);
    assertFalse(oldestAccepted);
    assertFalse(replacedAccepted);

    final long current = this.manager.beginLoad();

    final boolean retained = this.manager.retainLoaded(oldest, this.image);
    assertFalse(retained);

    final boolean managerRetained = this.manager.retainLoaded(replaced, this.image);
    assertFalse(managerRetained);

    final boolean secondRetained = this.manager.retainLoaded(current, this.image);
    assertTrue(secondRetained);

    final ImageBuffer taken = this.manager.takeLoaded(current);
    Assertions.assertSame(this.image, taken);
  }

  @Test
  void shouldRejectANullReleaseThread() {
    final NullPointerException exception = assertThrows(NullPointerException.class, () -> this.manager.releaseImage(null));

    final String message = exception.getMessage();
    assertEquals("Release thread must not be null!", message);
  }

  @Test
  void shouldRefuseANullPluginOrWorker() {
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    final ExecutorService worker = mock(ExecutorService.class);
    assertThrows(NullPointerException.class, () -> new ImageManager(null));
    assertThrows(NullPointerException.class, () -> new ImageManager(null, worker));
    assertThrows(NullPointerException.class, () -> new ImageManager(plugin, null));
  }
}
