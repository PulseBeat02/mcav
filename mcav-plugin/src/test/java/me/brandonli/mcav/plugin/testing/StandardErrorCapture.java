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
package me.brandonli.mcav.plugin.testing;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

// the tests' SLF4J simple logger prints every log event to standard error
public final class StandardErrorCapture implements AutoCloseable {

  private final PrintStream previous;
  private final ByteArrayOutputStream recorded;

  private StandardErrorCapture(final PrintStream previous, final ByteArrayOutputStream recorded) {
    this.previous = previous;
    this.recorded = recorded;
  }

  public static StandardErrorCapture start() {
    final PrintStream previous = System.err;
    final ByteArrayOutputStream recorded = new ByteArrayOutputStream();
    final PrintStream recording = new PrintStream(recorded, true, StandardCharsets.UTF_8);
    System.setErr(recording);

    return new StandardErrorCapture(previous, recorded);
  }

  @Override
  public void close() {
    System.setErr(this.previous);
  }

  public String getOutput() {
    return this.recorded.toString(StandardCharsets.UTF_8);
  }
}
