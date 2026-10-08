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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

final class FabricEntrypointTest {

  @Test
  void registersThePayloadAndReportsOnTheTicksOfEachConnection() {
    final Event<ClientPlayConnectionEvents.Join> joins = EventFactory.createArrayBacked(
      ClientPlayConnectionEvents.Join.class,
      listeners -> (handler, sender, client) -> {
        for (final ClientPlayConnectionEvents.Join listener : listeners) {
          listener.onPlayReady(handler, sender, client);
        }
      }
    );
    final Event<ClientTickEvents.EndTick> ticks = EventFactory.createArrayBacked(ClientTickEvents.EndTick.class, listeners -> client -> {
      for (final ClientTickEvents.EndTick listener : listeners) {
        listener.onEndTick(client);
      }
    });
    @SuppressWarnings("unchecked")
    final PayloadTypeRegistry<RegistryFriendlyByteBuf> payloads = mock(PayloadTypeRegistry.class);
    final AtomicLong clock = new AtomicLong();
    try (final MockedStatic<ClientPlayNetworking> networking = mockStatic(ClientPlayNetworking.class)) {
      networking.when(() -> ClientPlayNetworking.canSend(Mcv2Payload.TYPE)).thenReturn(true);
      new FabricEntrypoint(payloads, joins, ticks, Optional.empty(), clock::get).onInitializeClient();
      verify(payloads).register(Mcv2Payload.TYPE, Mcv2Payload.CODEC);

      ticks.invoker().onEndTick(null);
      clock.addAndGet(Mcv2Reporter.STEADY_NANOS);
      ticks.invoker().onEndTick(null);
      final ArgumentCaptor<Mcv2Payload> sent = ArgumentCaptor.forClass(Mcv2Payload.class);
      networking.verify(() -> ClientPlayNetworking.send(sent.capture()));
      assertArrayEquals(new byte[] { 1, 0, 0, 0 }, sent.getValue().report(), "no Iris, no shader pack");

      joins.invoker().onPlayReady(null, null, null);
      ticks.invoker().onEndTick(null);
      clock.addAndGet(Mcv2Reporter.STEADY_NANOS);
      ticks.invoker().onEndTick(null);
      networking.verify(() -> ClientPlayNetworking.send(sent.capture()), times(2));
    }
  }

  @Test
  void theEntryPointFabricCallsAsksFabricForTheVersionOfIris() {
    final FabricLoader loader = mock(FabricLoader.class);
    final ModContainer iris = mock(ModContainer.class);
    final ModMetadata metadata = mock(ModMetadata.class);
    final Version version = mock(Version.class);
    when(iris.getMetadata()).thenReturn(metadata);
    when(metadata.getVersion()).thenReturn(version);
    when(version.getFriendlyString()).thenReturn("1.11.7+mc26.3");
    try (final MockedStatic<FabricLoader> loaders = mockStatic(FabricLoader.class)) {
      loaders.when(FabricLoader::getInstance).thenReturn(loader);
      when(loader.getModContainer(IrisShaders.MOD_ID)).thenReturn(Optional.of(iris));
      new FabricEntrypoint();
      verify(loader).getModContainer("iris");
    }
    assertEquals(Optional.of("1.11.7+mc26.3"), FabricEntrypoint.irisVersion(loader));
    when(loader.getModContainer(IrisShaders.MOD_ID)).thenReturn(Optional.empty());
    assertEquals(Optional.empty(), FabricEntrypoint.irisVersion(loader));
  }

  @AfterEach
  void uninstall() {
    ShaderDecoder.install(Optional.empty());
  }
}
