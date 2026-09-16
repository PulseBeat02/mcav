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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;

/**
 * A VLC instance shared by every {@link VLCPlayer}.
 *
 * <p>Creating a {@link MediaPlayerFactory} starts a whole VLC instance, which loads hundreds of plugins and takes
 * noticeable time and memory, so one instance is shared and reference counted. The instance is created when the
 * first playback acquires it and released when the last playback releases it; a later acquisition creates a new
 * one.
 */
final class SharedMediaPlayerFactory {

  private static final String[] VLC_ARGUMENTS = { "--quiet", "--no-video-title-show", "--no-snapshot-preview", "--no-osd" };
  private static final SharedMediaPlayerFactory INSTANCE = new SharedMediaPlayerFactory(SharedMediaPlayerFactory::createFactory);

  private final Supplier<MediaPlayerFactory> creator;
  private final Object lock;

  private @Nullable MediaPlayerFactory factory;
  private int references;

  /**
   * Constructs a new shared factory. Use {@link #getInstance()} outside of tests.
   *
   * @param creator creates a new VLC instance when the first reference is acquired
   */
  @VisibleForTesting
  SharedMediaPlayerFactory(final Supplier<MediaPlayerFactory> creator) {
    Preconditions.checkNotNull(creator, "Creator must not be null");
    this.creator = creator;
    this.lock = new Object();
  }

  /**
   * Gets the VLC instance shared by every player.
   *
   * @return the shared factory
   */
  static SharedMediaPlayerFactory getInstance() {
    return INSTANCE;
  }

  private static MediaPlayerFactory createFactory() {
    return new MediaPlayerFactory(VLC_ARGUMENTS);
  }

  /**
   * Acquires the VLC instance, creating it if no reference is held. Every successful call must be paired with
   * {@link #release()}.
   *
   * @return the VLC instance
   * @throws RuntimeException if the VLC instance cannot be created; no reference is acquired then
   */
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

  /**
   * Releases one reference to the VLC instance, shutting the instance down when no references remain. Calls without
   * a matching {@link #acquire()} are ignored.
   */
  void release() {
    synchronized (this.lock) {
      if (this.references == 0) {
        return;
      }
      this.references--;
      if (this.references > 0) {
        return;
      }
      final MediaPlayerFactory current = Preconditions.checkNotNull(this.factory, "A referenced instance always exists");
      this.factory = null;
      current.release();
    }
  }

  /**
   * Gets the number of references currently held.
   *
   * @return the number of acquisitions that have not been released yet
   */
  @VisibleForTesting
  int getReferences() {
    synchronized (this.lock) {
      return this.references;
    }
  }
}
