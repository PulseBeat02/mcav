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
package me.brandonli.mcav.media.player.multimedia.vlc;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;

// VLC opens media asynchronously, so the playback waits for the first playing, finished or error event to learn whether it opened.
final class PlaybackEventListener extends MediaPlayerEventAdapter {

  private final VideoPlayerMultiplexer owner;
  private final String resource;
  private final AtomicBoolean active;
  private final CountDownLatch settled;
  private final AtomicBoolean failed;

  PlaybackEventListener(final VideoPlayerMultiplexer owner, final String resource, final AtomicBoolean active) {
    this.owner = owner;
    this.resource = resource;
    this.active = active;
    this.settled = new CountDownLatch(1);
    this.failed = new AtomicBoolean(false);
  }

  @Override
  public void playing(final MediaPlayer mediaPlayer) {
    this.settled.countDown();
  }

  @Override
  public void finished(final MediaPlayer mediaPlayer) {
    this.settled.countDown();
  }

  // The failure is recorded before the report, so a wait in awaitOpened(long) that times out during the report still sees it.
  @Override
  public void error(final MediaPlayer mediaPlayer) {
    this.failed.set(true);
    try {
      this.reportFailure();
    } finally {
      this.settled.countDown();
    }
  }

  private void reportFailure() {
    if (!this.active.get()) {
      return;
    }

    final BiConsumer<String, Throwable> handler = this.owner.getExceptionHandler();
    final String message = "Media %s could not be played by VLC!".formatted(this.resource);
    final PlayerException exception = new PlayerException(message);
    final String handlerMessage = "VLC playback of %s failed.".formatted(this.resource);
    handler.accept(handlerMessage, exception);
  }

  boolean awaitOpened(final long timeoutMilliseconds) throws InterruptedException {
    this.settled.await(timeoutMilliseconds, TimeUnit.MILLISECONDS);

    final boolean failedToOpen = this.failed.get();
    return !failedToOpen;
  }
}
