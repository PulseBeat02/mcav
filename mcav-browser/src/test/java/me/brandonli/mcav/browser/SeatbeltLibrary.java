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
package me.brandonli.mcav.browser;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// Java stand-ins for the Seatbelt functions, so a Seatbelt runs its real calls on any system without a sandbox.
// upcall stubs and native function handles are restricted; the tests run with native access enabled
@SuppressWarnings("restricted")
final class SeatbeltLibrary implements AutoCloseable {

  static final String SANDBOX_INIT = "sandbox_init";
  static final String FREE_ERROR = "sandbox_free_error";

  private static final FunctionDescriptor SANDBOX_INIT_DESCRIPTOR = FunctionDescriptor.of(
    ValueLayout.JAVA_INT,
    ValueLayout.ADDRESS,
    ValueLayout.JAVA_LONG,
    ValueLayout.ADDRESS
  );
  private static final FunctionDescriptor FREE_ERROR_DESCRIPTOR = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);

  private final Arena arena = Arena.ofConfined();
  private final Map<String, MemorySegment> functions = new HashMap<>();
  private final List<String> profiles = new ArrayList<>();
  private final List<Long> flags = new ArrayList<>();
  private final List<Long> freed = new ArrayList<>();

  private int answer;
  private MemorySegment reason = MemorySegment.NULL;

  SeatbeltLibrary(final String... names) throws ReflectiveOperationException {
    final MethodHandles.Lookup lookup = MethodHandles.lookup();
    for (final String name : names) {
      final boolean isSandboxInit = name.equals(SANDBOX_INIT);
      final FunctionDescriptor descriptor = isSandboxInit ? SANDBOX_INIT_DESCRIPTOR : FREE_ERROR_DESCRIPTOR;
      final Object function = isSandboxInit ? (SandboxInitFunction) this::init : (FreeErrorFunction) this::freeError;
      final Class<?> type = isSandboxInit ? SandboxInitFunction.class : FreeErrorFunction.class;
      final MethodHandle target = lookup.findVirtual(type, "call", descriptor.toMethodType()).bindTo(function);
      this.functions.put(name, Linker.nativeLinker().upcallStub(target, descriptor, this.arena));
    }
  }

  static SeatbeltLibrary complete() throws ReflectiveOperationException {
    return new SeatbeltLibrary(SANDBOX_INIT, FREE_ERROR);
  }

  SymbolLookup lookup() {
    return name -> Optional.ofNullable(this.functions.get(name));
  }

  Seatbelt seatbelt() {
    return new Seatbelt(this.lookup());
  }

  // sandbox_init refuses every later profile, without an error text
  void refuse(final int result) {
    this.answer = result;
    this.reason = MemorySegment.NULL;
  }

  void refuse(final int result, final String text) {
    this.answer = result;
    this.reason = this.arena.allocateFrom(text);
  }

  @Override
  public void close() {
    this.arena.close();
  }

  private int init(final MemorySegment profile, final long given, final MemorySegment error) {
    this.profiles.add(profile.reinterpret(Long.MAX_VALUE).getString(0));
    this.flags.add(given);
    error.reinterpret(ValueLayout.ADDRESS.byteSize()).set(ValueLayout.ADDRESS, 0, this.reason);

    return this.answer;
  }

  private void freeError(final MemorySegment error) {
    this.freed.add(error.address());
  }

  long reasonAddress() {
    return this.reason.address();
  }

  List<String> profiles() {
    return this.profiles;
  }

  List<Long> flags() {
    return this.flags;
  }

  List<Long> freed() {
    return this.freed;
  }

  // not private: the upcall finds its method by name
  @FunctionalInterface
  interface SandboxInitFunction {
    int call(final MemorySegment profile, final long given, final MemorySegment error);
  }

  // not private: the upcall finds its method by name
  @FunctionalInterface
  interface FreeErrorFunction {
    void call(final MemorySegment error);
  }
}
