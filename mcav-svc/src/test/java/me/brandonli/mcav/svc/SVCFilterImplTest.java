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
package me.brandonli.mcav.svc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.maxhenkel.voicechat.api.Entity;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Stubber;
import org.mockito.verification.VerificationMode;

/**
 * Tests {@link SVCFilterImpl} and the {@link SVCFilter} factories.
 */
final class SVCFilterImplTest {

  private static final int FRAME_SAMPLES = 960;
  private static final int MAX_FRAMES = 25;
  private static final int CONCURRENT_FRAMES = MAX_FRAMES - 5; // fewer than the queue holds, so none is dropped

  private final SVCModule module = new SVCModule();
  private final VoicechatServerApi api = Mockito.mock(VoicechatServerApi.class);
  private final OriginalAudioMetadata metadata = Mockito.mock(OriginalAudioMetadata.class);
  private final List<Object> convertedPlatformEntities = new CopyOnWriteArrayList<>();
  private final List<UUID> channelIds = new CopyOnWriteArrayList<>();
  private final List<EntityAudioChannel> channels = new CopyOnWriteArrayList<>();
  private final List<OpusEncoder> encoders = new CopyOnWriteArrayList<>();
  private final List<AudioPlayer> players = new CopyOnWriteArrayList<>();
  private final List<Supplier<short[]>> suppliers = new CopyOnWriteArrayList<>();

  private final IllegalStateException failure = new IllegalStateException("voice chat failed");

  private Object refusedEntity;
  private Object currentEntity;
  private Object failingEntity;
  private String failingStep;

  private boolean isFailing(final String step) {
    return step.equals(this.failingStep) && Objects.equals(this.currentEntity, this.failingEntity);
  }

  private void failIfRequested(final String step) {
    final boolean failing = this.isFailing(step);
    if (failing) {
      throw this.failure;
    }
  }

  @BeforeEach
  void injectAMockedVoiceChat() {
    this.stubEntityConversion();
    this.stubChannelCreation();
    this.stubEncoderCreation();
    this.stubPlayerCreation();
    this.module.inject(this.api);
  }

  @AfterEach
  void removeTheVoiceChat() {
    this.module.stop();
  }

  private void stubEntityConversion() {
    final Stubber conversion = Mockito.doAnswer(this::convertEntity);
    final VoicechatServerApi stubbedApi = conversion.when(this.api);
    stubbedApi.fromEntity(ArgumentMatchers.any());
  }

  private Entity convertEntity(final InvocationOnMock invocation) {
    final Object platformEntity = invocation.getArgument(0);
    this.currentEntity = platformEntity;
    this.failIfRequested("fromEntity");
    this.convertedPlatformEntities.add(platformEntity);

    final Entity entity = Mockito.mock(Entity.class);
    final Stubber platformEntityStubbing = Mockito.doReturn(platformEntity);
    final Entity stubbedEntity = platformEntityStubbing.when(entity);
    stubbedEntity.getEntity();
    return entity;
  }

  private void stubChannelCreation() {
    final Stubber channelCreation = Mockito.doAnswer(this::createChannel);
    final VoicechatServerApi stubbedApi = channelCreation.when(this.api);
    stubbedApi.createEntityAudioChannel(ArgumentMatchers.any(UUID.class), ArgumentMatchers.any(Entity.class));
  }

  private EntityAudioChannel createChannel(final InvocationOnMock invocation) {
    final UUID channelId = invocation.getArgument(0);
    final Entity entity = invocation.getArgument(1);
    final Object platformEntity = entity.getEntity();
    if (platformEntity.equals(this.refusedEntity)) {
      return null;
    }

    this.channelIds.add(channelId);
    final EntityAudioChannel channel = Mockito.mock(EntityAudioChannel.class);
    this.channels.add(channel);
    return channel;
  }

  private void stubEncoderCreation() {
    final Stubber encoderCreation = Mockito.doAnswer(_ -> this.createEncoder());
    final VoicechatServerApi stubbedApi = encoderCreation.when(this.api);
    stubbedApi.createEncoder();
  }

  private OpusEncoder createEncoder() {
    this.failIfRequested("createEncoder");
    final OpusEncoder encoder = Mockito.mock(OpusEncoder.class);
    this.encoders.add(encoder);
    return encoder;
  }

  private void stubPlayerCreation() {
    final Stubber playerCreation = Mockito.doAnswer(this::createPlayer);
    final VoicechatServerApi stubbedApi = playerCreation.when(this.api);
    stubbedApi.createAudioPlayer(
      ArgumentMatchers.any(AudioChannel.class),
      ArgumentMatchers.any(OpusEncoder.class),
      ArgumentMatchers.<Supplier<short[]>>any()
    );
  }

  private AudioPlayer createPlayer(final InvocationOnMock invocation) {
    this.failIfRequested("createAudioPlayer");
    final Supplier<short[]> supplier = invocation.getArgument(2);
    this.suppliers.add(supplier);

    final AudioPlayer player = Mockito.mock(AudioPlayer.class);
    final boolean startFails = this.isFailing("startPlaying");
    if (startFails) {
      final Stubber failingStart = Mockito.doThrow(this.failure);
      final AudioPlayer stubbedPlayer = failingStart.when(player);
      stubbedPlayer.startPlaying();
    }
    this.players.add(player);
    return player;
  }

  private static ByteBuffer stereoOf(final short[] mono) {
    final ByteBuffer buffer = ByteBuffer.allocate(mono.length * 4);
    buffer.order(ByteOrder.LITTLE_ENDIAN);
    for (final short sample : mono) {
      buffer.putShort(sample);
      buffer.putShort(sample);
    }
    buffer.flip();
    return buffer;
  }

  private static short[] constant(final int length, final int value) {
    final short[] samples = new short[length];
    Arrays.fill(samples, (short) value);
    return samples;
  }

  private static short[] ramp(final int length, final int start) {
    final short[] samples = new short[length];
    for (int index = 0; index < length; index++) {
      samples[index] = (short) (start + index);
    }
    return samples;
  }

  private boolean feed(final SVCFilter filter, final short[] mono) {
    final ByteBuffer stereo = stereoOf(mono);
    return filter.applyFilter(stereo, this.metadata);
  }

  private short[] nextFrameOf(final int speaker) {
    final Supplier<short[]> supplier = this.suppliers.get(speaker);
    return supplier.get();
  }

  private void assertEverySpeakerStopped() {
    for (final AudioPlayer player : this.players) {
      final AudioPlayer verifiedPlayer = Mockito.verify(player);
      verifiedPlayer.stopPlaying();
    }
    for (final OpusEncoder encoder : this.encoders) {
      final OpusEncoder verifiedEncoder = Mockito.verify(encoder);
      verifiedEncoder.close();
    }
  }

  private void assertStoppedFilterOnlyPassesSamplesOn(final SVCFilter filter, final short[] mono) {
    final boolean passedOn = this.feed(filter, mono);
    final int queued = filter.getQueuedFrames();
    assertTrue(passedOn);
    assertEquals(0, queued);
  }

  @Test
  void rejectsInvalidDistances() {
    assertThrows(IllegalArgumentException.class, () -> SVCFilter.withDistance(0.0f, "alice"));
    assertThrows(IllegalArgumentException.class, () -> SVCFilter.withDistance(-1.0f, "alice"));
    assertThrows(IllegalArgumentException.class, () -> SVCFilter.withDistance(Float.NaN, "alice"));
    assertThrows(IllegalArgumentException.class, () -> SVCFilter.withDistance(Float.POSITIVE_INFINITY, "alice"));
  }

  @Test
  void rejectsMissingOrNullEntities() {
    assertThrows(NullPointerException.class, () -> SVCFilter.svc((Object[]) null));
    assertThrows(IllegalArgumentException.class, SVCFilter::svc);
    assertThrows(NullPointerException.class, () -> SVCFilter.svc("alice", null));
  }

  @Test
  void rejectsNullSamplesAndMetadata() {
    final SVCFilter filter = SVCFilter.svc("alice");
    final short[] silence = new short[FRAME_SAMPLES];
    final ByteBuffer stereo = stereoOf(silence);
    assertThrows(NullPointerException.class, () -> filter.applyFilter(null, this.metadata));
    assertThrows(NullPointerException.class, () -> filter.applyFilter(stereo, null));
  }

  @Test
  void refusesToBeCreatedWithoutTheVoiceChatApi() {
    this.module.stop();
    assertThrows(IllegalStateException.class, () -> SVCFilter.svc("alice"));
  }

  @Test
  void refusesToStartWhenTheApiWasRemovedAfterCreation() {
    final SVCFilter filter = SVCFilter.svc("alice");
    this.module.stop();
    assertThrows(IllegalStateException.class, filter::start);
    final short[] silence = new short[FRAME_SAMPLES];
    this.assertStoppedFilterOnlyPassesSamplesOn(filter, silence);
    final boolean noPlayers = this.players.isEmpty();
    assertTrue(noPlayers);
  }

  @Test
  void createsOneSpeakerPerEntityWithTheDefaultDistance() {
    final SVCFilter filter = SVCFilter.svc("alice", "bob");
    assertInstanceOf(SVCFilterImpl.class, filter);
    filter.start();

    final List<Object> expectedEntities = List.of("alice", "bob");
    final int channelCount = this.channels.size();
    final int supplierCount = this.suppliers.size();
    final UUID firstId = this.channelIds.getFirst();
    final UUID secondId = this.channelIds.get(1);
    assertEquals(expectedEntities, this.convertedPlatformEntities);
    assertEquals(2, channelCount);
    assertEquals(2, supplierCount);
    assertNotEquals(firstId, secondId);

    for (final EntityAudioChannel channel : this.channels) {
      final EntityAudioChannel verifiedChannel = Mockito.verify(channel);
      verifiedChannel.setDistance(SVCFilter.DEFAULT_DISTANCE);
    }
    for (final AudioPlayer player : this.players) {
      final AudioPlayer verifiedPlayer = Mockito.verify(player);
      verifiedPlayer.startPlaying();
    }
    assertEquals(32.0f, SVCFilter.DEFAULT_DISTANCE);
    filter.release();
  }

  @Test
  void passesACustomDistanceAndTheConvertedEntityToVoiceChat() {
    final SVCFilter filter = SVCFilter.withDistance(12.5f, "alice");
    filter.start();
    final EntityAudioChannel channel = this.channels.getFirst();
    final EntityAudioChannel verifiedChannel = Mockito.verify(channel);
    verifiedChannel.setDistance(12.5f);

    final ArgumentCaptor<Entity> entityCaptor = ArgumentCaptor.forClass(Entity.class);
    final VoicechatServerApi verifiedChannelCreation = Mockito.verify(this.api);
    verifiedChannelCreation.createEntityAudioChannel(ArgumentMatchers.any(UUID.class), entityCaptor.capture());
    final Entity entity = entityCaptor.getValue();
    final Object platformEntity = entity.getEntity();
    assertEquals("alice", platformEntity);

    final OpusEncoder encoder = this.encoders.getFirst();
    final VoicechatServerApi verifiedPlayerCreation = Mockito.verify(this.api);
    verifiedPlayerCreation.createAudioPlayer(
      ArgumentMatchers.same(channel),
      ArgumentMatchers.same(encoder),
      ArgumentMatchers.<Supplier<short[]>>any()
    );
    filter.release();
  }

  @Test
  void copiesTheEntitiesOnCreation() {
    final Object[] entities = { "alice" };
    final SVCFilter filter = SVCFilter.svc(entities);
    entities[0] = "mallory";
    filter.start();
    final List<Object> expectedEntities = List.of("alice");
    assertEquals(expectedEntities, this.convertedPlatformEntities);
    filter.release();
  }

  @Test
  void startingTwiceKeepsTheExistingSpeakers() {
    final SVCFilter filter = SVCFilter.svc("alice");
    filter.start();
    filter.start();
    final int playerCount = this.players.size();
    assertEquals(1, playerCount);
    filter.release();
  }

  @Test
  void passesSamplesOnWithoutPlayingThemUntilStartedAndAfterRelease() {
    final SVCFilter filter = SVCFilter.svc("alice");
    final short[] silence = new short[FRAME_SAMPLES];
    this.assertStoppedFilterOnlyPassesSamplesOn(filter, silence);

    filter.start();
    filter.release();
    this.assertStoppedFilterOnlyPassesSamplesOn(filter, silence);
    final short[] afterReleaseFrame = this.nextFrameOf(0);
    assertArrayEquals(silence, afterReleaseFrame);
  }

  @Test
  void everySpeakerPlaysEveryDownmixedFrame() {
    final SVCFilter filter = SVCFilter.svc("alice", "bob");
    filter.start();
    final short[] mono = ramp(FRAME_SAMPLES, -100);
    final boolean accepted = this.feed(filter, mono);
    final int queued = filter.getQueuedFrames();
    assertTrue(accepted);
    assertEquals(1, queued);

    final short[] aliceFrame = this.nextFrameOf(0);
    assertArrayEquals(mono, aliceFrame);
    final int queuedWhileBobIsBehind = filter.getQueuedFrames();
    assertEquals(1, queuedWhileBobIsBehind);

    final short[] bobFrame = this.nextFrameOf(1);
    assertArrayEquals(mono, bobFrame);
    final int queuedAfterBoth = filter.getQueuedFrames();
    assertEquals(0, queuedAfterBoth);
    filter.release();
  }

  @Test
  void averagesStereoChannelsIntoTheFrame() {
    final SVCFilter filter = SVCFilter.svc("alice");
    filter.start();
    final ByteBuffer stereo = ByteBuffer.allocate(FRAME_SAMPLES * 4);
    stereo.order(ByteOrder.LITTLE_ENDIAN);
    for (int index = 0; index < FRAME_SAMPLES; index++) {
      stereo.putShort((short) 100);
      stereo.putShort((short) 301);
    }
    stereo.flip();

    filter.applyFilter(stereo, this.metadata);
    final short[] frame = this.nextFrameOf(0);
    final short[] expected = constant(FRAME_SAMPLES, 200);
    assertArrayEquals(expected, frame);
    filter.release();
  }

  @Test
  void collectsPartialInputUntilAFrameIsComplete() {
    final SVCFilter filter = SVCFilter.svc("alice");
    filter.start();
    final short[] first = constant(500, 1);
    this.feed(filter, first);
    final int queuedAfterFirst = filter.getQueuedFrames();
    final short[] second = constant(FRAME_SAMPLES - 500 + 100, 2);
    this.feed(filter, second);
    final int queuedAfterSecond = filter.getQueuedFrames();
    assertEquals(0, queuedAfterFirst);
    assertEquals(1, queuedAfterSecond);
    final short[] firstFrame = this.nextFrameOf(0);
    assertEquals(1, firstFrame[0]);
    assertEquals(1, firstFrame[499]);
    assertEquals(2, firstFrame[500]);
    assertEquals(2, firstFrame[FRAME_SAMPLES - 1]);

    final short[] third = constant(FRAME_SAMPLES - 100, 3);
    this.feed(filter, third);
    final short[] secondFrame = this.nextFrameOf(0);
    assertEquals(2, secondFrame[0]);
    assertEquals(2, secondFrame[99]);
    assertEquals(3, secondFrame[100]);
    assertEquals(3, secondFrame[FRAME_SAMPLES - 1]);
    filter.release();
  }

  @Test
  void splitsLargeInputIntoSeveralFrames() {
    final SVCFilter filter = SVCFilter.svc("alice");
    filter.start();
    final short[] mono = ramp(FRAME_SAMPLES * 3 + 7, 0);
    this.feed(filter, mono);
    final int queued = filter.getQueuedFrames();
    assertEquals(3, queued);

    for (int frameIndex = 0; frameIndex < 3; frameIndex++) {
      final short[] frame = this.nextFrameOf(0);
      final int from = frameIndex * FRAME_SAMPLES;
      final short[] expected = Arrays.copyOfRange(mono, from, from + FRAME_SAMPLES);
      assertArrayEquals(expected, frame);
    }
    filter.release();
  }

  @Test
  void playsSilenceInsteadOfEndingWhenNoFrameIsQueued() {
    final SVCFilter filter = SVCFilter.svc("alice");
    filter.start();
    final short[] frame = this.nextFrameOf(0);
    final short[] again = this.nextFrameOf(0);
    final short[] silence = new short[FRAME_SAMPLES];
    assertArrayEquals(silence, frame);
    assertSame(frame, again);
    filter.release();
  }

  @Test
  void dropsTheOldestFramesOfASpeakerThatFallsBehind() {
    final SVCFilter filter = SVCFilter.svc("alice", "bob");
    filter.start();
    for (int frameIndex = 0; frameIndex < MAX_FRAMES + 2; frameIndex++) {
      final short[] frame = constant(FRAME_SAMPLES, frameIndex);
      this.feed(filter, frame);
      this.nextFrameOf(1);
    }

    final int queued = filter.getQueuedFrames();
    assertEquals(MAX_FRAMES, queued);
    final short[] oldest = this.nextFrameOf(0);
    assertEquals(2, oldest[0]);
    final short[] bobFrame = this.nextFrameOf(1);
    final short[] silence = new short[FRAME_SAMPLES];
    assertArrayEquals(silence, bobFrame);
    filter.release();
  }

  @Test
  void releaseStopsEveryPlayerClosesEveryEncoderAndDropsQueuedAudio() {
    final SVCFilter filter = SVCFilter.svc("alice", "bob");
    filter.start();
    final short[] input = constant(FRAME_SAMPLES + 10, 4);
    this.feed(filter, input);
    filter.release();
    this.assertEverySpeakerStopped();

    final short[] silence = new short[FRAME_SAMPLES];
    final short[] afterRelease = this.nextFrameOf(0);
    assertArrayEquals(silence, afterRelease);
    final int queued = filter.getQueuedFrames();
    assertEquals(0, queued);

    filter.release();
    final AudioPlayer firstPlayer = this.players.getFirst();
    final VerificationMode once = Mockito.times(1);
    final AudioPlayer verifiedFirstPlayer = Mockito.verify(firstPlayer, once);
    verifiedFirstPlayer.stopPlaying();
  }

  @Test
  void restartingCreatesNewSpeakersAndForgetsThePartialFrame() {
    final SVCFilter filter = SVCFilter.svc("alice");
    filter.start();
    final short[] almostAFrame = constant(FRAME_SAMPLES - 1, 8);
    this.feed(filter, almostAFrame);
    filter.release();
    filter.start();
    final int playerCount = this.players.size();
    assertEquals(2, playerCount);

    final short[] oneSample = constant(1, 9);
    this.feed(filter, oneSample);
    final int queuedAfterOneSample = filter.getQueuedFrames();
    assertEquals(0, queuedAfterOneSample);
    final short[] rest = constant(FRAME_SAMPLES - 1, 9);
    this.feed(filter, rest);
    final short[] frame = this.nextFrameOf(1);
    final short[] expected = constant(FRAME_SAMPLES, 9);
    assertArrayEquals(expected, frame);
    filter.release();
  }

  @Test
  void stopsTheCreatedSpeakersWhenVoiceChatRefusesAChannel() {
    this.refusedEntity = "bob";
    final SVCFilter filter = SVCFilter.svc("alice", "bob");
    final PlayerException exception = assertThrows(PlayerException.class, filter::start);
    final String message = exception.getMessage();
    assertEquals("Simple Voice Chat refused to create an audio channel for bob", message);
    final int playerCount = this.players.size();
    final int encoderCount = this.encoders.size();
    assertEquals(1, playerCount);
    assertEquals(1, encoderCount);
    this.assertEverySpeakerStopped();
    final short[] silence = new short[FRAME_SAMPLES];
    this.assertStoppedFilterOnlyPassesSamplesOn(filter, silence);

    this.refusedEntity = null;
    filter.start();
    final int playerCountAfterRestart = this.players.size();
    assertEquals(3, playerCountAfterRestart);
    filter.release();
  }

  @ParameterizedTest
  @ValueSource(strings = { "fromEntity", "createEncoder", "createAudioPlayer", "startPlaying" })
  void releasesEverythingItCreatedWhenStartingFailsAndCanStartAgain(final String step) {
    this.failingEntity = "bob";
    this.failingStep = step;
    final SVCFilter filter = SVCFilter.svc("alice", "bob");
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, filter::start);
    assertSame(this.failure, thrown, "the failure of voice chat is passed on unchanged");
    final int playersBefore = this.players.size();
    this.assertOnlyReachedSpeakerPartsWereCreated(step, playersBefore);
    this.assertEverySpeakerStopped();
    final short[] frame = constant(FRAME_SAMPLES, 5);
    this.assertStoppedFilterOnlyPassesSamplesOn(filter, frame);

    this.failingStep = null;
    filter.start();
    this.assertRestartedSpeakersPlay(filter, frame, playersBefore);
    filter.release();
    this.assertEverySpeakerStopped();
  }

  @Test
  void releasesEverythingItCreatedWhenVoiceChatCannotLinkItsCodec() {
    final UnsatisfiedLinkError linkFailure = new UnsatisfiedLinkError("no opus in java.library.path");
    this.failEncoderCreationForBob(linkFailure);
    final SVCFilter filter = SVCFilter.svc("alice", "bob");
    final UnsatisfiedLinkError thrown = assertThrows(UnsatisfiedLinkError.class, filter::start);
    assertSame(linkFailure, thrown, "the failure of voice chat is passed on unchanged");
    final int playerCount = this.players.size();
    assertEquals(1, playerCount);
    this.assertEverySpeakerStopped();
    final short[] frame = constant(FRAME_SAMPLES, 5);
    this.assertStoppedFilterOnlyPassesSamplesOn(filter, frame);
  }

  @Test
  void passesErrorsOfTheVirtualMachineOnUnchanged() {
    final InternalError fatal = new InternalError("out of native memory");
    this.failEncoderCreationForBob(fatal);
    final SVCFilter filter = SVCFilter.svc("alice", "bob");
    final InternalError thrown = assertThrows(InternalError.class, filter::start);
    assertSame(fatal, thrown, "an error of the virtual machine is never replaced or hidden");
    this.assertNoSpeakerWasRolledBack();
  }

  /**
   * Asserts that nothing was stopped or closed, which is what an error of the virtual machine must leave alone: the
   * machine is broken, so running more third-party code to clean up would only hide the failure.
   */
  private void assertNoSpeakerWasRolledBack() {
    final VerificationMode never = Mockito.never();
    for (final AudioPlayer player : this.players) {
      final AudioPlayer verifiedPlayer = Mockito.verify(player, never);
      verifiedPlayer.stopPlaying();
    }
    for (final OpusEncoder encoder : this.encoders) {
      final OpusEncoder verifiedEncoder = Mockito.verify(encoder, never);
      verifiedEncoder.close();
    }
  }

  @Test
  void leavesTheSpeakerAloneWhenStartingItsPlayerFailsFatally() {
    final InternalError fatal = new InternalError("the codec died");
    this.failPlayerCreationForBob(fatal);
    final SVCFilter filter = SVCFilter.svc("alice", "bob");
    final InternalError thrown = assertThrows(InternalError.class, filter::start);
    assertSame(fatal, thrown, "an error of the virtual machine is never replaced or hidden");
    final int encoderCount = this.encoders.size();
    assertEquals(2, encoderCount, "the encoder of the speaker that failed was created");
    this.assertNoSpeakerWasRolledBack();
  }

  private void failPlayerCreationForBob(final Error failure) {
    final Stubber playerCreation = Mockito.doAnswer(invocation -> this.createPlayerUnlessBob(invocation, failure));
    final VoicechatServerApi stubbedApi = playerCreation.when(this.api);
    stubbedApi.createAudioPlayer(
      ArgumentMatchers.any(AudioChannel.class),
      ArgumentMatchers.any(OpusEncoder.class),
      ArgumentMatchers.<Supplier<short[]>>any()
    );
  }

  private AudioPlayer createPlayerUnlessBob(final InvocationOnMock invocation, final Error failure) {
    final boolean bob = "bob".equals(this.currentEntity);
    if (bob) {
      throw failure;
    }
    return this.createPlayer(invocation);
  }

  private void failEncoderCreationForBob(final Error failure) {
    final Stubber encoderCreation = Mockito.doAnswer(_ -> this.createEncoderUnlessBob(failure));
    final VoicechatServerApi stubbedApi = encoderCreation.when(this.api);
    stubbedApi.createEncoder();
  }

  private OpusEncoder createEncoderUnlessBob(final Error failure) {
    final boolean bob = "bob".equals(this.currentEntity);
    if (bob) {
      throw failure;
    }
    return this.createEncoder();
  }

  private void assertOnlyReachedSpeakerPartsWereCreated(final String failingStep, final int playerCount) {
    final int encoderCount = this.encoders.size();
    final int expectedPlayers = failingStep.equals("startPlaying") ? 2 : 1;
    final boolean bobHasAnEncoder = failingStep.equals("createAudioPlayer") || failingStep.equals("startPlaying");
    final int expectedEncoders = bobHasAnEncoder ? 2 : 1;
    assertEquals(expectedPlayers, playerCount);
    assertEquals(expectedEncoders, encoderCount);
  }

  private void assertRestartedSpeakersPlay(final SVCFilter filter, final short[] frame, final int playersBefore) {
    final int playersAfter = this.players.size();
    assertEquals(playersBefore + 2, playersAfter, "one new speaker per entity, no leftovers of the failed start");
    this.feed(filter, frame);
    final int queuedAfterRestart = filter.getQueuedFrames();
    assertEquals(1, queuedAfterRestart);
    for (int index = playersBefore; index < playersAfter; index++) {
      final short[] played = this.nextFrameOf(index);
      assertArrayEquals(frame, played);
    }
  }

  @Test
  void deliversFramesInOrderWhileVoiceChatPullsConcurrently() throws Exception {
    final SVCFilter filter = SVCFilter.svc("alice");
    filter.start();
    final Supplier<short[]> supplier = this.suppliers.getFirst();
    final List<Short> received;
    try (final ExecutorService executor = Executors.newFixedThreadPool(2)) {
      final Future<List<Short>> consumer = executor.submit(() -> receiveFrames(supplier));
      final Future<?> producer = executor.submit(() -> this.produceFrames(filter));
      producer.get(10, TimeUnit.SECONDS);
      received = consumer.get(10, TimeUnit.SECONDS);
    } finally {
      filter.release();
    }

    final int receivedCount = received.size();
    assertEquals(CONCURRENT_FRAMES, receivedCount);
    for (int index = 0; index < CONCURRENT_FRAMES; index++) {
      final short value = received.get(index);
      assertEquals(index + 1, value);
    }
  }

  private static List<Short> receiveFrames(final Supplier<short[]> supplier) {
    final List<Short> received = new ArrayList<>();
    final long timeout = TimeUnit.SECONDS.toNanos(10);
    final long deadline = System.nanoTime() + timeout;
    while (received.size() < CONCURRENT_FRAMES && System.nanoTime() < deadline) {
      final short[] frame = supplier.get();
      final short first = frame[0];
      for (final short sample : frame) {
        assertEquals(first, sample, "a frame must not mix samples of different inputs");
      }
      if (first != 0) {
        received.add(first);
      } else {
        Thread.onSpinWait();
      }
    }
    return received;
  }

  private void produceFrames(final SVCFilter filter) {
    for (int frameIndex = 1; frameIndex <= CONCURRENT_FRAMES; frameIndex++) {
      final short[] frame = constant(FRAME_SAMPLES, frameIndex);
      this.feed(filter, frame);
    }
  }
}
