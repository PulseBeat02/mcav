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
package me.brandonli.mcav.vm;

import com.google.common.base.MoreObjects;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

final class QemuProgram {

  private final Path executable;
  private final List<String> arguments;
  private final Map<String, String> environment;

  private QemuProgram(final Path executable, final List<String> arguments, final Map<String, String> environment) {
    this.executable = executable;
    this.arguments = List.copyOf(arguments);
    this.environment = Map.copyOf(environment);
  }

  static QemuProgram of(final Path executable) {
    return of(executable, List.of(), Map.of());
  }

  static QemuProgram of(final Path executable, final List<String> arguments, final Map<String, String> environment) {
    return new QemuProgram(executable, arguments, environment);
  }

  Path getExecutable() {
    return this.executable;
  }

  List<String> getArguments() {
    return this.arguments;
  }

  Map<String, String> getEnvironment() {
    return this.environment;
  }

  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final QemuProgram program)) {
      return false;
    }

    return (
      this.executable.equals(program.executable) && this.arguments.equals(program.arguments) && this.environment.equals(program.environment)
    );
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.executable, this.arguments, this.environment);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
      .add("executable", this.executable)
      .add("arguments", this.arguments)
      .add("environment", this.environment)
      .toString();
  }
}
