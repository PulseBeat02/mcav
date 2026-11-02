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
package me.brandonli.mcav.media.player.attachable;

import com.google.common.base.Preconditions;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A thread-safe {@link AttachableCallback} that stores its value in an atomic reference.
 *
 * @param <ValueType> the type of value the slot holds
 * @since 1.0.0
 */
public abstract class AbstractAttachableCallback<ValueType> implements AttachableCallback<ValueType> {

  private final ValueType fallback;
  private final AtomicReference<@Nullable ValueType> attached;

  /**
   * Constructs a slot.
   *
   * @param fallback the value returned while nothing is attached
   * @throws NullPointerException if {@code fallback} is null
   * @since 1.0.0
   */
  protected AbstractAttachableCallback(final ValueType fallback) {
    Preconditions.checkNotNull(fallback, "Fallback must not be null!");

    this.fallback = fallback;
    this.attached = new AtomicReference<>();
  }

  /**
   * Stores {@code value} in this callback's slot, replacing the value attached before.
   *
   * @param value the non-null value, retained by reference; replacing a value does not release it
   * @throws NullPointerException if {@code value} is null
   * @since 1.0.0
   */
  @Override
  public void attach(final ValueType value) {
    Preconditions.checkNotNull(value, "Attached value must not be null!");

    this.attached.set(value);
  }

  /**
   * Empties this callback's slot, so {@link #retrieve()} returns the fallback value again.
   *
   * @since 1.0.0
   */
  @Override
  public void detach() {
    this.attached.set(null);
  }

  /**
   * Returns whether a value was explicitly attached to this callback, even if it equals the fallback value.
   *
   * @return a snapshot of the attached state, which can change immediately after this call
   * @since 1.0.0
   */
  @Override
  public boolean isAttached() {
    final ValueType current = this.attached.get();
    return current != null;
  }

  /**
   * Returns the value attached to this callback, or its fallback value when nothing is attached.
   *
   * @return the value
   * @since 1.0.0
   */
  @Override
  public ValueType retrieve() {
    final ValueType current = this.attached.get();
    return current == null ? this.fallback : current;
  }
}
