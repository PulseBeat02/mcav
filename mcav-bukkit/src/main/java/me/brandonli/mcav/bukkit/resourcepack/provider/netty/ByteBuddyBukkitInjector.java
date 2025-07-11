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
package me.brandonli.mcav.bukkit.resourcepack.provider.netty;

import static java.util.Objects.requireNonNull;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.loading.ClassInjector;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import org.checkerframework.checker.initialization.qual.UnderInitialization;

final class ByteBuddyBukkitInjector {

  private static final String INJECTOR_SYSTEM_PROPERTY = "murderrun.resourcepack";

  private final Path path;
  private final Class<?> clazz;

  ByteBuddyBukkitInjector(final Path path) {
    this.path = path;
    this.clazz = this.getConnectionClass();
  }

  private Class<?> getConnectionClass(@UnderInitialization ByteBuddyBukkitInjector this) {
    try {
      return Class.forName("net.minecraft.network.NetworkManager");
    } catch (final ClassNotFoundException e) {
      throw new InjectorException(e.getMessage(), e);
    }
  }

  void injectAgentIntoServer() {
    ByteBuddyAgent.install();
    this.setZipProperty();
    this.injectClassesIntoClassLoader();
    this.injectIntoConnectionMethod();
  }

  private void injectIntoConnectionMethod() {
    final ClassLoader classLoader = requireNonNull(this.clazz.getClassLoader());
    final ElementMatcher.Junction<NamedElement> matcher = ElementMatchers.named("configureSerialization");
    final Advice advice = Advice.to(ConnectionInterceptor.class);
    final ClassReloadingStrategy classLoadingStrategy = ClassReloadingStrategy.fromInstalledAgent();
    new ByteBuddy().rebase(this.clazz).method(matcher).intercept(advice).make().load(classLoader, classLoadingStrategy);
  }

  private void injectClassesIntoClassLoader() {
    final ClassLoader classLoader = requireNonNull(this.clazz.getClassLoader());
    new ClassInjector.UsingReflection(classLoader).inject(this.getInjections());
  }

  private void setZipProperty() {
    final Path absolute = this.path.toAbsolutePath();
    final String property = absolute.toString();
    System.setProperty(INJECTOR_SYSTEM_PROPERTY, property); // get around bytebuddy issues with passing args
  }

  private Map<TypeDescription, byte[]> getInjections() {
    final LinkedHashMap<TypeDescription, byte[]> injections = new LinkedHashMap<>();
    this.addInjectionEntry(injections, Injector.class);
    this.addInjectionEntry(injections, InjectorContext.class);
    this.addInjectionEntry(injections, HttpInjector.class);
    this.addInjectionEntry(injections, ResourcePackInjector.class);
    this.addInjectionEntry(injections, HttpRequest.class);
    this.addInjectionEntry(injections, HttpByteBuf.class);
    return injections;
  }

  private void addInjectionEntry(final Map<TypeDescription, byte[]> map, final Class<?> clazz) {
    final TypeDescription typeDescription = new TypeDescription.ForLoadedType(clazz);
    final byte[] classFile = ClassFileLocator.ForClassLoader.read(clazz);
    map.put(typeDescription, classFile);
  }

  public static class ConnectionInterceptor {

    private ConnectionInterceptor() {
      throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    @Advice.OnMethodExit
    @RuntimeType
    public static void intercept(@Advice.AllArguments final Object[] allArguments) {
      final ChannelPipeline pipeline = (ChannelPipeline) allArguments[0];
      final Channel channel = pipeline.channel();
      final ChannelPipeline pipeline1 = channel.pipeline();
      final ResourcePackInjector injector = new ResourcePackInjector();
      pipeline1.addFirst(injector);
    }
  }
}
