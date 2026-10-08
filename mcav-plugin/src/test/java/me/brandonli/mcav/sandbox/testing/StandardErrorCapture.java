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
package me.brandonli.mcav.sandbox.testing;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Records what is written to standard error, where the SLF4J simple logger of the tests prints every log event
 * together with the stack trace of its exception.
 *
 * <p>Use it in a try-with-resources block; standard error is restored when it is closed. The tests of the plugin run
 * one at a time, so no other test writes to standard error while a capture is open.
 */
public final class StandardErrorCapture implements AutoCloseable {

  private final PrintStream previous;
  private final ByteArrayOutputStream recorded;

  private StandardErrorCapture(final PrintStream previous, final ByteArrayOutputStream recorded) {
    this.previous = previous;
    this.recorded = recorded;
  }

  /**
   * Starts recording standard error.
   *
   * @return the capture, which must be closed
   */
  public static StandardErrorCapture start() {
    final PrintStream previous = System.err;
    final ByteArrayOutputStream recorded = new ByteArrayOutputStream();
    final PrintStream recording = new PrintStream(recorded, true, StandardCharsets.UTF_8);
    System.setErr(recording);
    return new StandardErrorCapture(previous, recorded);
  }

  /**
   * Gets everything written to standard error since the capture started.
   *
   * @return the recorded text
   */
  public String getOutput() {
    return this.recorded.toString(StandardCharsets.UTF_8);
  }

  /**
   * Stops recording and restores the previous standard error.
   */
  @Override
  public void close() {
    System.setErr(this.previous);
  }
}
