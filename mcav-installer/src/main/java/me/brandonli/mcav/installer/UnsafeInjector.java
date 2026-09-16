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

/**
 * Appends to the internal URL lists of a {@link URLClassLoader} through {@code sun.misc.Unsafe}. Used only when
 * the reflective injector is denied. Unsafe is looked up by name, so the installer compiles without referencing
 * the unsupported API; newer Java versions print a warning the first time it is used.
 */
final class UnsafeInjector extends URLClassLoaderInjector {

  private static final String UNSAFE_CLASS = "sun.misc.Unsafe";
  /**
   * The Unsafe access of this runtime, or null if {@code sun.misc.Unsafe} is not usable.
   */
  static final @Nullable UnsafeAccess UNSAFE = UnsafeAccess.find(UNSAFE_CLASS);

  private final Collection<?> unopenedUrls;
  private final Collection<?> path;
  private final UrlSink unopenedUrlsSink;
  private final UrlSink pathSink;

  /**
   * Creates an injector.
   *
   * @param classLoader the class loader to add jars to
   * @param unsafe      the Unsafe access, normally {@link #UNSAFE}
   * @throws JarInjectorException if the class path fields of this Java version are unknown
   */
  UnsafeInjector(final URLClassLoader classLoader, final UnsafeAccess unsafe) {
    super(classLoader);
    final Object classPath = unsafe.readField(URLClassLoader.class, classLoader, "ucp");
    final Class<?> classPathType = classPath.getClass();
    this.unopenedUrls = readCollection(unsafe, classPathType, classPath, "unopenedUrls");
    this.path = readCollection(unsafe, classPathType, classPath, "path");
    this.unopenedUrlsSink = unsafe.createSink(this.unopenedUrls);
    this.pathSink = unsafe.createSink(this.path);
  }

  /**
   * Reads a collection field. Visible for testing.
   *
   * @param unsafe the Unsafe access
   * @param type   the class that declares the field
   * @param owner  the object that holds the field
   * @param name   the name of the field
   * @return the collection
   * @throws JarInjectorException if the field is missing, null, or not a collection
   */
  static Collection<?> readCollection(final UnsafeAccess unsafe, final Class<?> type, final Object owner, final String name) {
    final Object value = unsafe.readField(type, owner, name);
    if (!(value instanceof final Collection<?> collection)) {
      final String typeName = type.getName();
      throw new JarInjectorException("The field " + name + " of " + typeName + " is not a collection");
    }
    return collection;
  }

  @Override
  void addURL(final URL url) {
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

  /**
   * Adds URLs to one of the internal collections. The JDK declares both collections with URL elements, which a
   * runtime check cannot confirm, so the URLs are added through {@link Collection#add(Object)} instead of through
   * a collection of URLs. Public because {@link MethodHandleProxies} only implements public interfaces.
   */
  public interface UrlSink {
    /**
     * Adds a URL to the collection.
     *
     * @param url the URL to add
     * @return true if the collection changed
     */
    boolean add(URL url);
  }

  /**
   * The two Unsafe methods the injector needs, bound to the Unsafe instance, and the method that adds to a
   * collection.
   */
  static final class UnsafeAccess {

    private final MethodHandle objectFieldOffset;
    private final MethodHandle getObject;
    private final MethodHandle collectionAdd;

    private UnsafeAccess(final MethodHandle objectFieldOffset, final MethodHandle getObject, final MethodHandle collectionAdd) {
      this.objectFieldOffset = objectFieldOffset;
      this.getObject = getObject;
      this.collectionAdd = collectionAdd;
    }

    /**
     * Looks up an Unsafe class with a static {@code theUnsafe} instance.
     *
     * @param className the name of the class, normally {@code sun.misc.Unsafe}
     * @return the access, or null if the class, its instance, or one of the methods is missing
     */
    static @Nullable UnsafeAccess find(final String className) {
      try {
        final Class<?> unsafeType = Class.forName(className);
        final Field instanceField = unsafeType.getDeclaredField("theUnsafe");
        instanceField.setAccessible(true);
        // theUnsafe is static, so the field is read without an owner
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
      final MethodHandle offset = lookup.findVirtual(unsafeType, "objectFieldOffset", offsetType);
      final MethodType getObjectType = MethodType.methodType(Object.class, Object.class, long.class);
      final MethodHandle getObject = lookup.findVirtual(unsafeType, "getObject", getObjectType);
      final MethodType addType = MethodType.methodType(boolean.class, Object.class);
      final MethodHandle add = lookup.findVirtual(Collection.class, "add", addType);

      final MethodHandle boundOffset = offset.bindTo(instance);
      final MethodHandle boundGetObject = getObject.bindTo(instance);
      return new UnsafeAccess(boundOffset, boundGetObject, add);
    }

    /**
     * Creates a sink that adds URLs to a collection. Exceptions thrown by the collection reach the caller
     * unchanged.
     *
     * @param collection the collection to add to
     * @return the sink
     */
    UrlSink createSink(final Collection<?> collection) {
      final MethodHandle boundAdd = this.collectionAdd.bindTo(collection);
      return MethodHandleProxies.asInterfaceInstance(UrlSink.class, boundAdd);
    }

    /**
     * Reads an instance field, ignoring access checks.
     *
     * @param type  the class that declares the field
     * @param owner the object that holds the field
     * @param name  the name of the field
     * @return the value of the field
     * @throws JarInjectorException if the field is missing, static, or null
     */
    Object readField(final Class<?> type, final Object owner, final String name) {
      final String typeName = type.getName();
      try {
        final Field field = type.getDeclaredField(name);
        final long offset = (long) this.objectFieldOffset.invoke(field);
        final Object value = this.getObject.invoke(owner, offset);
        if (value == null) {
          throw new JarInjectorException("The field " + name + " of " + typeName + " is null");
        }
        return value;
      } catch (final NoSuchFieldException exception) {
        throw new JarInjectorException("This Java version has no field " + name + " in " + typeName, exception);
      } catch (final JarInjectorException exception) {
        // thrown above for a field that holds null, so it is passed on as it is
        throw exception;
      } catch (final VirtualMachineError fatal) {
        // an error of the virtual machine is no failure of this strategy, so it must not be hidden behind one
        throw fatal;
      } catch (final Throwable throwable) {
        // Unsafe is an unsupported API that can fail in any other way on a new Java version, a LinkageError included,
        // and every such failure means that this strategy cannot be used
        final String message = throwable.getMessage();
        throw new JarInjectorException("Failed to read " + name + ": " + message, throwable);
      }
    }
  }
}
