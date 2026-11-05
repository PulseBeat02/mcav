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
package me.brandonli.mcav.utils.runtime;

import java.io.Serial;
import me.brandonli.mcav.MCAVException;

/**
 * Thrown when an external program fails: it cannot be started, it exits with a non-zero exit code, it does not finish
 * in time, waiting for it is interrupted, or its output cannot be read.
 *
 * <p>This is an {@link MCAVException}: the failure belongs to the external program and its input, such as a
 * source FFmpeg cannot decode, not to the state of the caller or to an unsupported operation. It is unchecked so that
 * callers who cannot handle a failing program need no {@code throws} clause, while callers who can, for example by
 * skipping the source, catch it explicitly.
 *
 * @since 1.0.0
 */
public final class ProcessException extends MCAVException {

  @Serial
  private static final long serialVersionUID = -9183271875521286701L;

  ProcessException(final String message) {
    super(message);
  }

  ProcessException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
