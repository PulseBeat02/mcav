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

/**
 * Stand-ins written in Java for the functions of Seatbelt, handed over as native functions, which record their calls:
 * a {@link Seatbelt} of this library runs its real calls on any system without putting anything in a sandbox.
 */
@SuppressWarnings("restricted")
final class SeatbeltLibrary implements AutoCloseable {

  static final String INIT = "sandbox_init";
  static final String FREE_ERROR = "sandbox_free_error";

  private static final FunctionDescriptor INIT_DESCRIPTOR = FunctionDescriptor.of(
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

  /**
   * Creates the library with the stand-ins of some functions.
   *
   * @param names the functions it has, {@link #INIT} and {@link #FREE_ERROR}
   * @throws ReflectiveOperationException if a stand-in cannot be found, which is a bug of this class
   */
  SeatbeltLibrary(final String... names) throws ReflectiveOperationException {
    final MethodHandles.Lookup lookup = MethodHandles.lookup();
    for (final String name : names) {
      final boolean isInit = name.equals(INIT);
      final FunctionDescriptor descriptor = isInit ? INIT_DESCRIPTOR : FREE_ERROR_DESCRIPTOR;
      final Object function = isInit ? (InitFunction) this::init : (FreeErrorFunction) this::freeError;
      final Class<?> type = isInit ? InitFunction.class : FreeErrorFunction.class;
      final MethodHandle target = lookup.findVirtual(type, "call", descriptor.toMethodType()).bindTo(function);
      this.functions.put(name, Linker.nativeLinker().upcallStub(target, descriptor, this.arena));
    }
  }

  /**
   * Creates the library with both functions.
   *
   * @return the library
   * @throws ReflectiveOperationException if a stand-in cannot be found, which is a bug of this class
   */
  static SeatbeltLibrary complete() throws ReflectiveOperationException {
    return new SeatbeltLibrary(INIT, FREE_ERROR);
  }

  /**
   * Gets the functions as a library.
   *
   * @return the library
   */
  SymbolLookup lookup() {
    return name -> Optional.ofNullable(this.functions.get(name));
  }

  /**
   * Gets the sandbox of this library.
   *
   * @return the sandbox
   */
  Seatbelt seatbelt() {
    return new Seatbelt(this.lookup());
  }

  /**
   * Makes sandbox_init refuse every profile from now on, without a reason.
   *
   * @param result the result it returns, not 0
   */
  void refuse(final int result) {
    this.answer = result;
    this.reason = MemorySegment.NULL;
  }

  /**
   * Makes sandbox_init refuse every profile from now on, with a reason.
   *
   * @param result the result it returns, not 0
   * @param text   the reason it gives
   */
  void refuse(final int result, final String text) {
    this.answer = result;
    this.reason = this.arena.allocateFrom(text);
  }

  /**
   * Gets the address of the reason sandbox_init gives.
   *
   * @return the address, 0 for none
   */
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

  @Override
  public void close() {
    this.arena.close();
  }

  // sandbox_init: takes the text of a profile, its flags and where the reason of a refusal goes
  private int init(final MemorySegment profile, final long given, final MemorySegment error) {
    this.profiles.add(profile.reinterpret(Long.MAX_VALUE).getString(0));
    this.flags.add(given);
    error.reinterpret(ValueLayout.ADDRESS.byteSize()).set(ValueLayout.ADDRESS, 0, this.reason);
    return this.answer;
  }

  // sandbox_free_error: takes back the reason
  private void freeError(final MemorySegment error) {
    this.freed.add(error.address());
  }

  /** The signature of sandbox_init; not private, as the upcall finds its method by name. */
  @FunctionalInterface
  interface InitFunction {
    int call(MemorySegment profile, long given, MemorySegment error);
  }

  /** The signature of sandbox_free_error; not private, as the upcall finds its method by name. */
  @FunctionalInterface
  interface FreeErrorFunction {
    void call(MemorySegment error);
  }
}
