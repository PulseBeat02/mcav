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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * The entry point of the MCV2 client mod on NeoForge: it registers the {@code mcav:mcv2} payload, and on every client
 * tick tells the server whether Iris draws a shader pack, so MCV2 screens show this player the dithered maps while one
 * is in use.
 */
@Mod(value = NeoForgeEntrypoint.MOD_ID, dist = Dist.CLIENT)
public final class NeoForgeEntrypoint {

  /** The mod's id. */
  static final String MOD_ID = "mcav_mcv2";

  /** The version NeoForge compares when the server runs NeoForge too. */
  static final String PROTOCOL = "1";

  /**
   * Constructs the entry point NeoForge calls.
   *
   * @param modBus the mod's event bus
   */
  public NeoForgeEntrypoint(final IEventBus modBus) {
    this(modBus, NeoForge.EVENT_BUS, ModList.get().isLoaded(IrisShaders.MOD_ID), System::nanoTime);
  }

  NeoForgeEntrypoint(final IEventBus modBus, final IEventBus gameBus, final boolean irisInstalled, final LongSupplier clock) {
    final NeoForgeChannel channel = new NeoForgeChannel();
    final Mcv2Reporter reporter = new Mcv2Reporter(new IrisShaders(irisInstalled), channel, clock);
    // optional, so the client still joins servers without the channel, which are all servers but MCAV's; the server of
    // a single-player world has no MCV2 screens, and its handler drops the reports
    modBus.addListener(RegisterPayloadHandlersEvent.class, event ->
      event
        .registrar(PROTOCOL)
        .optional()
        .playToServer(Mcv2Payload.TYPE, Mcv2Payload.CODEC, (_, _) -> {})
    );
    gameBus.addListener(ClientPlayerNetworkEvent.LoggingIn.class, event -> {
      channel.open(event.getConnection());
      reporter.reset();
    });
    gameBus.addListener(ClientPlayerNetworkEvent.LoggingOut.class, _ -> channel.close());
    gameBus.addListener(ClientTickEvent.Post.class, _ -> reporter.tick());
  }
}
