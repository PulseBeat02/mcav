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

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;

// One reference-counted VLC instance for every VlcPlayer, because each instance loads all of VLC's plugins.
final class SharedMediaPlayerFactory {

  private static final String[] VLC_ARGUMENTS = { "--quiet", "--no-video-title-show", "--no-snapshot-preview", "--no-osd" };

  private static final SharedMediaPlayerFactory INSTANCE = new SharedMediaPlayerFactory(SharedMediaPlayerFactory::createFactory);

  private final Supplier<MediaPlayerFactory> creator;
  private final Object lock;

  private @Nullable MediaPlayerFactory factory;
  private int references;

  // Use getInstance() outside of tests.
  @VisibleForTesting
  SharedMediaPlayerFactory(final Supplier<MediaPlayerFactory> creator) {
    Preconditions.checkNotNull(creator, "Creator must not be null!");

    this.creator = creator;
    this.lock = new Object();
  }

  static SharedMediaPlayerFactory getInstance() {
    return INSTANCE;
  }

  private static MediaPlayerFactory createFactory() {
    return new MediaPlayerFactory(VLC_ARGUMENTS);
  }

  // Creates the instance when no reference is held; every successful call must be paired with release().
  MediaPlayerFactory acquire() {
    synchronized (this.lock) {
      MediaPlayerFactory current = this.factory;
      if (current == null) {
        current = this.creator.get();
        this.factory = current;
      }

      this.references++;

      return current;
    }
  }

  // Shuts the instance down with its last reference; a call without a matching acquire() is ignored.
  void release() {
    synchronized (this.lock) {
      if (this.references == 0) {
        return;
      }

      this.references--;

      if (this.references > 0) {
        return;
      }

      final MediaPlayerFactory current = requireNonNull(this.factory, "A referenced instance always exists!");
      this.factory = null;
      current.release();
    }
  }

  @VisibleForTesting
  int getReferences() {
    synchronized (this.lock) {
      return this.references;
    }
  }
}
