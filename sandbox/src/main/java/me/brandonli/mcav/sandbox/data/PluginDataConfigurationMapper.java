/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.sandbox.data;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import me.brandonli.mcav.sandbox.MCAV;
import me.brandonli.mcav.sandbox.locale.Locale;
import me.brandonli.mcav.utils.ExecutorUtils;
import org.bukkit.configuration.file.FileConfiguration;

public final class PluginDataConfigurationMapper {

  private static final String PLUGIN_LANGUAGE = "language";
  private static final String PACK_PROVIDER_FIELD = "pack-provider";
  private static final String SERVER_PORT_FIELD = "server.port";
  private static final String SERVER_HOST_FIELD = "server.host-name";

  private final ExecutorService service;
  private final MCAV plugin;
  private final Lock readLock;
  private final Lock writeLock;

  private Locale locale;
  private ProviderMethod providerMethod;
  private String hostName;
  private int port;

  public PluginDataConfigurationMapper(final MCAV plugin) {
    final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    this.plugin = plugin;
    this.readLock = lock.readLock();
    this.writeLock = lock.writeLock();
    this.service = Executors.newVirtualThreadPerTaskExecutor();
    this.plugin.saveDefaultConfig();
  }

  public synchronized void shutdown() {
    ExecutorUtils.shutdownExecutorGracefully(this.service);
  }

  public synchronized MCAV getPlugin() {
    return this.plugin;
  }

  public synchronized void deserialize() {
    this.readLock.lock();
    final FileConfiguration config = this.plugin.getConfig();
    this.plugin.saveConfig();
    this.locale = this.getLocale(config);
    this.hostName = this.getHostName(config);
    this.port = this.getPortServerPort(config);
    this.providerMethod = this.getProviderMethod(config);
    this.readLock.unlock();
  }

  private Locale getLocale(final FileConfiguration config) {
    return Locale.fromString(requireNonNull(config.getString(PLUGIN_LANGUAGE, "EN_US")));
  }

  private int getPortServerPort(final FileConfiguration config) {
    return config.getInt(SERVER_PORT_FIELD);
  }

  private String getHostName(final FileConfiguration config) {
    return requireNonNull(config.getString(SERVER_HOST_FIELD, "localhost"));
  }

  private ProviderMethod getProviderMethod(final FileConfiguration config) {
    return ProviderMethod.fromString(requireNonNull(config.getString(PACK_PROVIDER_FIELD, "MC_PACK_HOSTING")));
  }

  public synchronized Locale getLocale() {
    return this.locale;
  }

  public synchronized String getHostName() {
    return this.hostName;
  }

  public synchronized void serialize() {
    CompletableFuture.runAsync(this::internalSerialize, this.service);
  }

  private synchronized void internalSerialize() {
    this.writeLock.lock();
    final FileConfiguration config = this.plugin.getConfig();
    config.set(SERVER_HOST_FIELD, this.hostName);
    config.set(SERVER_PORT_FIELD, this.port);
    config.set(PACK_PROVIDER_FIELD, this.providerMethod.name());
    this.plugin.saveConfig();
    this.writeLock.unlock();
  }

  public synchronized int getPort() {
    return this.port;
  }

  public synchronized ProviderMethod getProviderMethod() {
    return this.providerMethod;
  }
}
