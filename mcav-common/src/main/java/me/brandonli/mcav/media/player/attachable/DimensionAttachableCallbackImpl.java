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
import me.brandonli.mcav.utils.immutable.Dimension;

/**
 * The default {@link DimensionAttachableCallback}.
 */
public final class DimensionAttachableCallbackImpl extends AbstractAttachableCallback<Dimension> implements DimensionAttachableCallback {

  DimensionAttachableCallbackImpl() {
    super(Dimension.NONE);
  }

  @Override
  public void attach(final Dimension value) {
    Preconditions.checkNotNull(value, "Dimension must not be null");
    final boolean empty = value.isEmpty();
    Preconditions.checkArgument(!empty, "Dimension must not be empty but was %s", value);
    super.attach(value);
  }
}
