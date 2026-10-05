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
package me.brandonli.mcav.client;

import java.util.function.LongSupplier;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * The entry point of the MCV2 client mod on Fabric: it registers the {@code mcav:mcv2} payload, and on every client tick
 * tells the server whether Iris draws a shader pack, so MCV2 screens show this player the dithered maps while one is in
 * use.
 */
public final class FabricEntrypoint implements ClientModInitializer {

  private final PayloadTypeRegistry<RegistryFriendlyByteBuf> payloads;

  private final Event<ClientPlayConnectionEvents.Join> joins;

  private final Event<ClientTickEvents.EndTick> ticks;

  private final boolean irisInstalled;

  private final LongSupplier clock;

  /**
   * Constructs the entry point Fabric calls.
   */
  public FabricEntrypoint() {
    this(
      PayloadTypeRegistry.serverboundPlay(),
      ClientPlayConnectionEvents.JOIN,
      ClientTickEvents.END_CLIENT_TICK,
      FabricLoader.getInstance().isModLoaded(IrisShaders.MOD_ID),
      System::nanoTime
    );
  }

  FabricEntrypoint(
    final PayloadTypeRegistry<RegistryFriendlyByteBuf> payloads,
    final Event<ClientPlayConnectionEvents.Join> joins,
    final Event<ClientTickEvents.EndTick> ticks,
    final boolean irisInstalled,
    final LongSupplier clock
  ) {
    this.payloads = payloads;
    this.joins = joins;
    this.ticks = ticks;
    this.irisInstalled = irisInstalled;
    this.clock = clock;
  }

  @Override
  public void onInitializeClient() {
    this.payloads.register(Mcv2Payload.TYPE, Mcv2Payload.CODEC);
    final Mcv2Reporter reporter = new Mcv2Reporter(new IrisShaders(this.irisInstalled), new FabricChannel(), this.clock);
    this.joins.register((_, _, _) -> reporter.reset());
    this.ticks.register(_ -> reporter.tick());
  }
}
