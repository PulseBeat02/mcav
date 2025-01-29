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

import io.netty.channel.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import me.brandonli.mcav.bukkit.utils.versioning.ServerEnvironment;
import org.bukkit.Bukkit;
import org.bukkit.Server;

final class ReflectBukkitInjector {

  private static final String INJECTOR_SYSTEM_PROPERTY = "murderrun.resourcepack";
  private static final List<ChannelFuture> CONNECTIONS;

  static {
    try {
      CONNECTIONS = getConnections();
    } catch (final Throwable e) {
      throw new InjectorException(e.getMessage(), e);
    }
  }

  @SuppressWarnings("unchecked")
  private static List<ChannelFuture> getConnections() throws Throwable {
    final Object connection = getConnectionHandle();
    final VarHandle handle = getConnectionsVarHandle();
    return (List<ChannelFuture>) handle.get(connection);
  }

  private static VarHandle getConnectionsVarHandle() throws ClassNotFoundException, IllegalAccessException {
    final Class<?> target = Class.forName("net.minecraft.server.network.ServerConnection");
    final MethodHandles.Lookup lookup = MethodHandles.lookup();
    final MethodHandles.Lookup privateLookup = MethodHandles.privateLookupIn(target, lookup);
    try {
      return privateLookup.findVarHandle(target, "channels", List.class);
    } catch (final NoSuchFieldException | IllegalAccessException e) {
      try {
        return privateLookup.findVarHandle(target, "f", List.class);
      } catch (final NoSuchFieldException | IllegalAccessException ex) {
        throw new InjectorException(e.getMessage(), e);
      }
    }
  }

  private static Object getConnectionHandle() throws Throwable {
    final Server craftServer = Bukkit.getServer();
    final MethodHandle getServerHandle = getServerHandle();
    final MethodHandles.Lookup lookup = MethodHandles.lookup();
    final Object dedicatedServer = getServerHandle.invoke(craftServer);
    final Class<?> dedicatedServerClass = dedicatedServer.getClass();
    final String connectionName = "net.minecraft.server.network.ServerConnection";
    final Class<?> connectionClass = Class.forName(connectionName);
    final MethodType getConnectionType = MethodType.methodType(connectionClass);
    final MethodHandle getConnectionHandle = lookup.findVirtual(dedicatedServerClass, "ah", getConnectionType);
    return getConnectionHandle.invoke(dedicatedServer);
  }

  private static MethodHandle getServerHandle() throws NoSuchMethodException, IllegalAccessException, ClassNotFoundException {
    final String rev = ServerEnvironment.getNMSRevision();
    final String craftServerClass = "org.bukkit.craftbukkit.%s.CraftServer".formatted(rev);
    final Class<?> craftServerType = Class.forName(craftServerClass);
    final MethodHandles.Lookup lookup = MethodHandles.lookup();
    final String dedicatedServerClass = "net.minecraft.server.dedicated.DedicatedServer";
    final Class<?> dedicatedServerClassType = Class.forName(dedicatedServerClass);
    final MethodType methodType = MethodType.methodType(dedicatedServerClassType);
    return lookup.findVirtual(craftServerType, "getServer", methodType);
  }

  private final Path path;

  ReflectBukkitInjector(final Path path) {
    this.path = path;
  }

  void inject() {
    this.setZipProperty();
    this.install(this::installConsumer);
  }

  private void setZipProperty() {
    final Path absolute = this.path.toAbsolutePath();
    final String property = absolute.toString();
    System.setProperty(INJECTOR_SYSTEM_PROPERTY, property);
  }

  private void installConsumer(final Channel channel) {
    final ChannelPipeline pipeline = channel.pipeline();
    final ResourcePackInjector resourcePackInjector = new ResourcePackInjector();
    pipeline.addFirst(resourcePackInjector);
  }

  private void install(final Consumer<Channel> channelConsumer) {
    final ChannelInboundHandler serverHandler = this.injectServerAdapter(channelConsumer);
    for (final ChannelFuture channelFuture : CONNECTIONS) {
      final Channel channel = channelFuture.channel();
      final ChannelPipeline pipeline = channel.pipeline();
      pipeline.addFirst(serverHandler);
    }
  }

  private ChannelInboundHandlerAdapter injectServerAdapter(final Consumer<Channel> channelConsumer) {
    final ChannelInitializer<?> beginInitProtocol = this.getBeginInitializer(channelConsumer);
    return new ChannelInboundHandlerAdapter() {
      @Override
      public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
        final Channel channel = (Channel) msg;
        final ChannelPipeline pipeline = channel.pipeline();
        pipeline.addFirst(beginInitProtocol);
        ctx.fireChannelRead(msg);
      }
    };
  }

  private ChannelInitializer<Channel> getBeginInitializer(final Consumer<Channel> channelConsumer) {
    return new ChannelInitializer<>() {
      @Override
      protected void initChannel(final Channel channel) {
        channelConsumer.accept(channel);
      }
    };
  }
}
