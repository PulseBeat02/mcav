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
package me.brandonli.mcav.mod;

import java.util.Optional;
import java.util.function.LongSupplier;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * The entry point of the MCV2 client mod on Fabric: it registers the {@code mcav:mcv2} payload, starts the decoder of
 * MCV2 under shader packs, and on every client tick asks Iris whether it draws a shader pack, so MCV2 screens show this
 * player the dithered maps only while one is in use that MCV2 does not decode under.
 */
public final class FabricEntrypoint implements ClientModInitializer {

  private final PayloadTypeRegistry<RegistryFriendlyByteBuf> payloads;

  private final Event<ClientPlayConnectionEvents.Join> joins;

  private final Event<ClientTickEvents.EndTick> ticks;

  private final Optional<String> irisVersion;

  private final LongSupplier clock;

  /**
   * Constructs the entry point Fabric calls.
   */
  public FabricEntrypoint() {
    this(
      PayloadTypeRegistry.serverboundPlay(),
      ClientPlayConnectionEvents.JOIN,
      ClientTickEvents.END_CLIENT_TICK,
      irisVersion(FabricLoader.getInstance()),
      System::nanoTime
    );
  }

  FabricEntrypoint(
    final PayloadTypeRegistry<RegistryFriendlyByteBuf> payloads,
    final Event<ClientPlayConnectionEvents.Join> joins,
    final Event<ClientTickEvents.EndTick> ticks,
    final Optional<String> irisVersion,
    final LongSupplier clock
  ) {
    this.payloads = payloads;
    this.joins = joins;
    this.ticks = ticks;
    this.irisVersion = irisVersion;
    this.clock = clock;
  }

  /** The version of the Iris Fabric loaded, empty without Iris. */
  static Optional<String> irisVersion(final FabricLoader loader) {
    return loader.getModContainer(IrisShaders.MOD_ID).map(iris -> iris.getMetadata().getVersion().getFriendlyString());
  }

  @Override
  public void onInitializeClient() {
    this.payloads.register(Mcv2Payload.TYPE, Mcv2Payload.CODEC);
    final IrisShaders iris = new IrisShaders(this.irisVersion.isPresent(), Mcv2Shaders.start(this.irisVersion));
    final Mcv2Reporter reporter = new Mcv2Reporter(iris, new FabricChannel(), this.clock);
    this.joins.register((_, _, _) -> reporter.reset());
    this.ticks.register(_ -> reporter.tick());
  }
}
