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
package me.brandonli.mcav.installer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import org.checkerframework.checker.nullness.qual.Nullable;

final class UnsafeInjector extends UrlClassLoaderInjector {

  private static final String UNSAFE_CLASS = "sun.misc.Unsafe";

  private static final String CLASS_PATH_FIELD = "ucp";
  private static final String UNOPENED_URLS_FIELD = "unopenedUrls";
  private static final String PATH_FIELD = "path";

  // found by name, so the code compiles without referencing the unsupported API
  static final @Nullable UnsafeAccess UNSAFE = UnsafeAccess.find(UNSAFE_CLASS);

  private final Collection<?> unopenedUrls;
  private final Collection<?> path;
  private final UrlSink unopenedUrlsSink;
  private final UrlSink pathSink;

  UnsafeInjector(final URLClassLoader classLoader, final UnsafeAccess unsafe) {
    super(classLoader);
    final Object classPath = unsafe.readField(URLClassLoader.class, classLoader, CLASS_PATH_FIELD);
    final Class<?> classPathType = classPath.getClass();
    this.unopenedUrls = readCollection(unsafe, classPathType, classPath, UNOPENED_URLS_FIELD);
    this.path = readCollection(unsafe, classPathType, classPath, PATH_FIELD);
    this.unopenedUrlsSink = unsafe.createSink(this.unopenedUrls);
    this.pathSink = unsafe.createSink(this.path);
  }

  static Collection<?> readCollection(final UnsafeAccess unsafe, final Class<?> type, final Object owner, final String name) {
    final Object value = unsafe.readField(type, owner, name);
    if (!(value instanceof final Collection<?> collection)) {
      final String typeName = type.getName();
      final String message = "Field %s of %s is not a collection!".formatted(name, typeName);
      throw new JarInjectorException(message);
    }

    return collection;
  }

  @Override
  void addUrl(final URL url) {
    // URLClassPath guards both collections with the lock of unopenedUrls
    synchronized (this.unopenedUrls) {
      final boolean known = this.path.contains(url);
      if (known) {
        return;
      }

      this.pathSink.add(url);
      this.unopenedUrlsSink.add(url);
    }
  }

  // public, because MethodHandleProxies only implements public interfaces
  public interface UrlSink {
    boolean add(final URL url);
  }

  static final class UnsafeAccess {

    private static final String INSTANCE_FIELD = "theUnsafe";

    private static final String OBJECT_FIELD_OFFSET_METHOD = "objectFieldOffset";
    private static final String GET_OBJECT_METHOD = "getObject";
    private static final String ADD_METHOD = "add";

    private final MethodHandle objectFieldOffset;
    private final MethodHandle getObject;
    private final MethodHandle collectionAdd;

    private UnsafeAccess(final MethodHandle objectFieldOffset, final MethodHandle getObject, final MethodHandle collectionAdd) {
      this.objectFieldOffset = objectFieldOffset;
      this.getObject = getObject;
      this.collectionAdd = collectionAdd;
    }

    static @Nullable UnsafeAccess find(final String className) {
      try {
        final Class<?> unsafeType = Class.forName(className);
        final Field instanceField = unsafeType.getDeclaredField(INSTANCE_FIELD);
        instanceField.setAccessible(true);

        final Object instance = instanceField.get(null);
        if (instance == null) {
          return null;
        }

        return bind(unsafeType, instance);
      } catch (final ReflectiveOperationException | RuntimeException exception) {
        return null;
      }
    }

    private static UnsafeAccess bind(final Class<?> unsafeType, final Object instance) throws ReflectiveOperationException {
      final MethodHandles.Lookup lookup = MethodHandles.lookup();
      final MethodType offsetType = MethodType.methodType(long.class, Field.class);
      final MethodHandle offset = lookup.findVirtual(unsafeType, OBJECT_FIELD_OFFSET_METHOD, offsetType);
      final MethodType getObjectType = MethodType.methodType(Object.class, Object.class, long.class);
      final MethodHandle getObject = lookup.findVirtual(unsafeType, GET_OBJECT_METHOD, getObjectType);
      final MethodType addType = MethodType.methodType(boolean.class, Object.class);
      final MethodHandle add = lookup.findVirtual(Collection.class, ADD_METHOD, addType);

      final MethodHandle boundOffset = offset.bindTo(instance);
      final MethodHandle boundGetObject = getObject.bindTo(instance);
      return new UnsafeAccess(boundOffset, boundGetObject, add);
    }

    private UrlSink createSink(final Collection<?> collection) {
      final MethodHandle boundAdd = this.collectionAdd.bindTo(collection);
      return MethodHandleProxies.asInterfaceInstance(UrlSink.class, boundAdd);
    }

    Object readField(final Class<?> type, final Object owner, final String name) {
      final @Nullable Object value = this.readValue(type, owner, name);
      if (value == null) {
        final String typeName = type.getName();
        final String message = "Field %s of %s is null!".formatted(name, typeName);
        throw new JarInjectorException(message);
      }

      return value;
    }

    private @Nullable Object readValue(final Class<?> type, final Object owner, final String name) {
      try {
        final Field field = type.getDeclaredField(name);
        final long offset = (long) this.objectFieldOffset.invoke(field);
        return this.getObject.invoke(owner, offset);
      } catch (final NoSuchFieldException exception) {
        final String typeName = type.getName();
        final String message = "Field %s is missing from %s in this Java version!".formatted(name, typeName);
        throw new JarInjectorException(message, exception);
      } catch (final VirtualMachineError exception) {
        throw exception;
      } catch (final Throwable exception) {
        // Unsafe may fail in any way on a new Java version, a LinkageError included; each failure rules this strategy out
        final String message = "Field %s could not be read!".formatted(name);
        throw new JarInjectorException(message, exception);
      }
    }
  }
}
