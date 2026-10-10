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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforgespi.language.IModInfo;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

final class NeoForgeEntrypointTest {

  private final IEventBus modBus = mock(IEventBus.class);

  private final IEventBus gameBus = mock(IEventBus.class);

  /** The listener the entry point added to a bus for an event. */
  @SuppressWarnings("unchecked")
  private static <EventType extends Event> Consumer<EventType> listener(final IEventBus bus, final Class<EventType> event) {
    final ArgumentCaptor<Consumer<EventType>> listener = ArgumentCaptor.forClass(Consumer.class);
    verify(bus).addListener(eq(event), listener.capture());
    return listener.getValue();
  }

  @Test
  void registersAnOptionalPayloadWhoseServerHandlerDropsIt() {
    new NeoForgeEntrypoint(this.modBus, this.gameBus, Optional.empty(), System::nanoTime);
    final RegisterPayloadHandlersEvent event = mock(RegisterPayloadHandlersEvent.class);
    final PayloadRegistrar registrar = mock(PayloadRegistrar.class);
    when(event.registrar(NeoForgeEntrypoint.PROTOCOL)).thenReturn(registrar);
    when(registrar.optional()).thenReturn(registrar);
    listener(this.modBus, RegisterPayloadHandlersEvent.class).accept(event);

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<IPayloadHandler<Mcv2Payload>> handler = ArgumentCaptor.forClass(IPayloadHandler.class);
    verify(registrar).playToServer(eq(Mcv2Payload.TYPE), eq(Mcv2Payload.CODEC), handler.capture());
    final Mcv2Payload payload = mock(Mcv2Payload.class);
    handler.getValue().handle(payload, null);
    verifyNoInteractions(payload);
  }

  @Test
  void reportsOnTheTicksOfASessionTheServerOfWhichHasTheChannel() {
    final AtomicLong clock = new AtomicLong();
    new NeoForgeEntrypoint(this.modBus, this.gameBus, Optional.empty(), clock::get);
    final Consumer<ClientPlayerNetworkEvent.LoggingIn> loggingIn = listener(this.gameBus, ClientPlayerNetworkEvent.LoggingIn.class);
    final Consumer<ClientPlayerNetworkEvent.LoggingOut> loggingOut = listener(this.gameBus, ClientPlayerNetworkEvent.LoggingOut.class);
    final Consumer<ClientTickEvent.Post> ticks = listener(this.gameBus, ClientTickEvent.Post.class);
    final Connection connection = mock(Connection.class);
    final ClientPlayerNetworkEvent.LoggingIn login = mock(ClientPlayerNetworkEvent.LoggingIn.class);
    when(login.getConnection()).thenReturn(connection);
    final ClientPlayerNetworkEvent.LoggingOut logout = mock(ClientPlayerNetworkEvent.LoggingOut.class);
    try (
      final MockedStatic<NetworkRegistry> registry = mockStatic(NetworkRegistry.class);
      final MockedStatic<ClientPacketDistributor> distributor = mockStatic(ClientPacketDistributor.class)
    ) {
      registry.when(() -> NetworkRegistry.hasChannel(connection, ConnectionProtocol.PLAY, Mcv2Payload.TYPE.id())).thenReturn(true);
      ticks.accept(new ClientTickEvent.Post());
      registry.verifyNoInteractions();

      loggingIn.accept(login);
      ticks.accept(new ClientTickEvent.Post());
      clock.addAndGet(Mcv2Reporter.STEADY_NANOSECONDS);
      ticks.accept(new ClientTickEvent.Post());
      final ArgumentCaptor<Mcv2Payload> sent = ArgumentCaptor.forClass(Mcv2Payload.class);
      distributor.verify(() -> ClientPacketDistributor.sendToServer(sent.capture()));
      assertArrayEquals(new byte[] { 1, 0, 0, 0 }, sent.getValue().report(), "no Iris, no shader pack");

      loggingOut.accept(logout);
      loggingIn.accept(login);
      ticks.accept(new ClientTickEvent.Post());
      clock.addAndGet(Mcv2Reporter.STEADY_NANOSECONDS);
      ticks.accept(new ClientTickEvent.Post());
      distributor.verify(() -> ClientPacketDistributor.sendToServer(any()), times(2));

      loggingOut.accept(logout);
      clock.addAndGet(Mcv2Reporter.STEADY_NANOSECONDS);
      ticks.accept(new ClientTickEvent.Post());
      registry.verify(() -> NetworkRegistry.hasChannel(connection, ConnectionProtocol.PLAY, Mcv2Payload.TYPE.id()), times(4));
    }
  }

  @AfterEach
  void uninstall() {
    ShaderDecoder.install(Optional.empty());
  }

  @Test
  void theEntryPointNeoForgeCallsAsksNeoForgeForTheVersionOfIris() {
    final ModList mods = mock(ModList.class);
    final ModContainer iris = mock(ModContainer.class);
    final IModInfo irisMetadata = mock(IModInfo.class);
    doReturn(Optional.of(iris)).when(mods).getModContainerById("iris");
    when(iris.getModInfo()).thenReturn(irisMetadata);
    when(irisMetadata.getVersion()).thenReturn(new DefaultArtifactVersion("1.11.7+mc26.3"));
    try (final MockedStatic<ModList> modLists = mockStatic(ModList.class)) {
      modLists.when(ModList::get).thenReturn(mods);
      new NeoForgeEntrypoint(this.modBus);
      verify(mods).getModContainerById("iris");
      verify(this.modBus).addListener(eq(RegisterPayloadHandlersEvent.class), any());
    }
    assertTrue(ShaderDecoder.current().isPresent(), "the decoder the mixins report to");
    assertEquals(Optional.of("1.11.7+mc26.3"), NeoForgeEntrypoint.irisVersion(mods));
    doReturn(Optional.empty()).when(mods).getModContainerById("iris");
    assertEquals(Optional.empty(), NeoForgeEntrypoint.irisVersion(mods));
  }
}
