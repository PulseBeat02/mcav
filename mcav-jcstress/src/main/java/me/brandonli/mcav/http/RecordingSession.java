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
package me.brandonli.mcav.http;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.socket.WebSocketSession;

/**
 * A WebSocket session that is open, sends nothing anywhere, and records whether it was closed on the thread that
 * registered it. {@code HttpResultImpl} closes a refused session right there, and every other session on a thread of
 * its own named {@code mcav-http-close-<id>}, so the two can be told apart.
 */
final class RecordingSession implements InvocationHandler {

  private static final String ID = "jcstress";
  private static final String CLOSING_THREAD_PREFIX = "mcav-http-close-";

  private final AtomicBoolean closedSynchronously;
  private final WebSocketSession proxy;

  RecordingSession() {
    this.closedSynchronously = new AtomicBoolean();
    final ClassLoader loader = WebSocketSession.class.getClassLoader();
    final Class<?>[] interfaces = { WebSocketSession.class };
    this.proxy = (WebSocketSession) Proxy.newProxyInstance(loader, interfaces, this);
  }

  WebSocketSession proxy() {
    return this.proxy;
  }

  boolean wasClosedSynchronously() {
    return this.closedSynchronously.get();
  }

  @Override
  public Object invoke(final Object target, final Method method, final Object[] arguments) {
    final String name = method.getName();
    return switch (name) {
      case "getId" -> ID;
      case "isOpen" -> true;
      case "close" -> this.recordClose();
      case "hashCode" -> System.identityHashCode(target);
      // no code under test compares sessions, so equality is never asked for
      case "equals" -> false;
      case "toString" -> "RecordingSession";
      default -> null;
    };
  }

  private Object recordClose() {
    final Thread thread = Thread.currentThread();
    final String threadName = thread.getName();
    final boolean asynchronous = threadName.startsWith(CLOSING_THREAD_PREFIX);
    if (!asynchronous) {
      this.closedSynchronously.set(true);
    }
    return null;
  }
}
