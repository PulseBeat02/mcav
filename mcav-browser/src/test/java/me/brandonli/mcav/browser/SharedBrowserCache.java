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

import com.google.common.base.Equivalence;
import java.io.PrintStream;
import me.brandonli.mcav.utils.ThrowableUtils;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

/**
 * Retains each ordinary test class's browser across separate JUnit executions in the same JVM. Test runners may
 * select one method per execution; therefore BeforeAll/AfterAll alone do not define the expensive resource's life.
 * Page/context resets and player release still occur for every test. Destructive scenarios explicitly evict both
 * cached browsers before taking ownership of their own processes. Failed tests cannot lend a contaminated browser
 * to the next execution. There is no runner-specific behavior or assertion suppression.
 */
final class SharedBrowserCache implements TestWatcher {

  private static @Nullable SharedChrome chrome;
  private static @Nullable SharedPlaywright playwright;

  static {
    final Runtime runtime = Runtime.getRuntime();
    final Thread cleanup = new Thread(SharedBrowserCache::closeAtShutdown, "mcav-test-browser-cleanup");
    runtime.addShutdownHook(cleanup);
  }

  static synchronized SharedChrome chrome() {
    SharedChrome current = chrome;
    if (current == null) {
      current = new SharedChrome();
      chrome = current;
    }
    return current;
  }

  static synchronized SharedPlaywright playwright() {
    SharedPlaywright current = playwright;
    if (current == null) {
      current = new SharedPlaywright();
      playwright = current;
    }
    return current;
  }

  static void finishedChrome(final SharedChrome current) {
    try {
      current.assertReleased();
    } catch (final RuntimeException | Error failure) {
      evictAfterFailure(failure);
      throw failure;
    }
  }

  static void finishedPlaywright(final SharedPlaywright current) {
    try {
      current.assertReleased();
    } catch (final RuntimeException | Error failure) {
      evictAfterFailure(failure);
      throw failure;
    }
  }

  static synchronized void evictAll() {
    final SharedChrome oldChrome = chrome;
    final SharedPlaywright oldPlaywright = playwright;
    // Forget failed resources before closing, so cleanup failure cannot lend them to another test execution.
    chrome = null;
    playwright = null;
    try {
      if (oldChrome != null) {
        oldChrome.close();
      }
    } catch (final RuntimeException | Error failure) {
      if (oldPlaywright != null) {
        try {
          oldPlaywright.close();
        } catch (final RuntimeException | Error cleanupFailure) {
          suppressDistinct(failure, cleanupFailure);
        }
      }
      throw failure;
    }
    if (oldPlaywright != null) {
      oldPlaywright.close();
    }
  }

  @Override
  public void testFailed(final ExtensionContext context, final Throwable cause) {
    final String uniqueId = context.getUniqueId();
    final String displayName = context.getDisplayName();
    final PrintStream diagnostics = System.err;
    diagnostics.println("Browser test failed: " + uniqueId + " (" + displayName + ")");
    cause.printStackTrace(diagnostics);
    evictAfterFailure(cause);
  }

  @Override
  public void testAborted(final ExtensionContext context, final Throwable cause) {
    evictAfterFailure(cause);
  }

  private static void evictAfterFailure(final Throwable failure) {
    try {
      evictAll();
    } catch (final RuntimeException | Error cleanupFailure) {
      suppressDistinct(failure, cleanupFailure);
    }
  }

  private static void suppressDistinct(final Throwable failure, final Throwable cleanupFailure) {
    ThrowableUtils.throwIfFatal(cleanupFailure);
    final Equivalence<Object> identity = Equivalence.identity();
    if (!identity.equivalent(failure, cleanupFailure)) {
      failure.addSuppressed(cleanupFailure);
    }
  }

  private static void closeAtShutdown() {
    try {
      evictAll();
    } catch (final RuntimeException | Error failure) {
      failure.printStackTrace(System.err);
    }
  }
}
