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
package me.brandonli.mcav.voicechat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

final class EntityChannelSvcFilterTest {

  private static final int FRAME_SAMPLES = 960;

  private static final int MAXIMUM_FRAMES = 25;
  private static final int CONCURRENT_FRAMES = MAXIMUM_FRAMES - 5;

  private static final int BYTES_PER_STEREO_SAMPLE = 4;

  private static final long TIMEOUT_SECONDS = 10;

  private static final String ALICE = "alice";
  private static final String BOB = "bob";

  private static final String FROM_ENTITY = "fromEntity";
  private static final String CREATE_ENCODER = "createEncoder";
  private static final String CREATE_AUDIO_PLAYER = "createAudioPlayer";
  private static final String START_PLAYING = "startPlaying";

  private SvcModule module;
  private VoicechatServerApi api;
  private OriginalAudioMetadata metadata;
  private List<Object> convertedPlatformEntities;
  private List<UUID> channelIds;
  private List<EntityAudioChannel> channels;
  private List<OpusEncoder> encoders;
  private List<AudioPlayer> players;
  private List<Supplier<short[]>> suppliers;

  private IllegalStateException failure;

  private Object refusedEntity;
  private Object currentEntity;
  private Object failingEntity;
  private String failingStep;

  @BeforeEach
  void setUp() {
    this.module = new SvcModule();
    this.api = Mockito.mock(VoicechatServerApi.class);
    this.metadata = Mockito.mock(OriginalAudioMetadata.class);
    this.convertedPlatformEntities = new CopyOnWriteArrayList<>();
    this.channelIds = new CopyOnWriteArrayList<>();
    this.channels = new CopyOnWriteArrayList<>();
    this.encoders = new CopyOnWriteArrayList<>();
    this.players = new CopyOnWriteArrayList<>();
    this.suppliers = new CopyOnWriteArrayList<>();
    this.failure = new IllegalStateException("Voice chat failed!");

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
    this.failIfRequested(FROM_ENTITY);
    this.convertedPlatformEntities.add(platformEntity);

    final Entity entity = Mockito.mock(Entity.class);
    final Stubber platformEntityStubbing = Mockito.doReturn(platformEntity);
    final Entity stubbedEntity = platformEntityStubbing.when(entity);
    stubbedEntity.getEntity();

    return entity;
  }

  private void failIfRequested(final String step) {
    final boolean failing = this.isFailing(step);
    if (failing) {
      throw this.failure;
    }
  }

  private boolean isFailing(final String step) {
    return step.equals(this.failingStep) && Objects.equals(this.currentEntity, this.failingEntity);
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
    this.failIfRequested(CREATE_ENCODER);
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
    this.failIfRequested(CREATE_AUDIO_PLAYER);
    final Supplier<short[]> supplier = invocation.getArgument(2);
    this.suppliers.add(supplier);

    final AudioPlayer player = Mockito.mock(AudioPlayer.class);
    final boolean startFails = this.isFailing(START_PLAYING);
    if (startFails) {
      final Stubber failingStart = Mockito.doThrow(this.failure);
      final AudioPlayer stubbedPlayer = failingStart.when(player);
      stubbedPlayer.startPlaying();
    }

    this.players.add(player);

    return player;
  }

  @Test
  void shouldRejectInvalidDistances() {
    assertThrows(IllegalArgumentException.class, () -> SvcFilter.withDistance(0.0f, ALICE));
    assertThrows(IllegalArgumentException.class, () -> SvcFilter.withDistance(-1.0f, ALICE));
    assertThrows(IllegalArgumentException.class, () -> SvcFilter.withDistance(Float.NaN, ALICE));
    assertThrows(IllegalArgumentException.class, () -> SvcFilter.withDistance(Float.POSITIVE_INFINITY, ALICE));
  }

  @Test
  void shouldRejectMissingOrNullEntities() {
    assertThrows(NullPointerException.class, () -> SvcFilter.svc((Object[]) null));
    assertThrows(IllegalArgumentException.class, SvcFilter::svc);
    assertThrows(NullPointerException.class, () -> SvcFilter.svc(ALICE, null));
  }

  @Test
  void shouldRejectNullSamplesAndMetadata() {
    final SvcFilter filter = SvcFilter.svc(ALICE);
    final short[] silence = new short[FRAME_SAMPLES];
    final ByteBuffer stereo = this.stereoOf(silence);
    assertThrows(NullPointerException.class, () -> filter.applyFilter(null, this.metadata));
    assertThrows(NullPointerException.class, () -> filter.applyFilter(stereo, null));
  }

  @Test
  void shouldRefuseToBeCreatedWithoutTheVoiceChatApi() {
    this.module.stop();
    assertThrows(IllegalStateException.class, () -> SvcFilter.svc(ALICE));
  }

  @Test
  void shouldRefuseToStartWhenTheApiWasRemovedAfterCreation() {
    final SvcFilter filter = SvcFilter.svc(ALICE);
    this.module.stop();
    assertThrows(IllegalStateException.class, filter::start);

    final short[] silence = new short[FRAME_SAMPLES];
    this.assertStoppedFilterQueuesNothing(filter, silence);

    final boolean noPlayers = this.players.isEmpty();
    assertTrue(noPlayers);
  }

  @Test
  void shouldCreateOneSpeakerPerEntityWithTheDefaultDistance() {
    final SvcFilter filter = SvcFilter.svc(ALICE, BOB);
    assertInstanceOf(EntityChannelSvcFilter.class, filter);

    filter.start();

    final List<Object> expectedEntities = List.of(ALICE, BOB);
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
      verifiedChannel.setDistance(SvcFilter.DEFAULT_DISTANCE);
    }

    for (final AudioPlayer player : this.players) {
      final AudioPlayer verifiedPlayer = Mockito.verify(player);
      verifiedPlayer.startPlaying();
    }

    final float defaultDistance = SvcFilter.DEFAULT_DISTANCE;
    assertEquals(32.0f, defaultDistance);

    filter.release();
  }

  @Test
  void shouldPassACustomDistanceAndTheConvertedEntityToVoiceChat() {
    final SvcFilter filter = SvcFilter.withDistance(12.5f, ALICE);
    filter.start();
    final EntityAudioChannel channel = this.channels.getFirst();
    final EntityAudioChannel verifiedChannel = Mockito.verify(channel);
    verifiedChannel.setDistance(12.5f);

    final ArgumentCaptor<Entity> entityCaptor = ArgumentCaptor.forClass(Entity.class);
    final VoicechatServerApi verifiedChannelCreation = Mockito.verify(this.api);
    verifiedChannelCreation.createEntityAudioChannel(ArgumentMatchers.any(UUID.class), entityCaptor.capture());
    final Entity entity = entityCaptor.getValue();
    final Object platformEntity = entity.getEntity();
    assertEquals(ALICE, platformEntity);

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
  void shouldCopyTheEntitiesOnCreation() {
    final Object[] entities = { ALICE };
    final SvcFilter filter = SvcFilter.svc(entities);
    entities[0] = "mallory";
    filter.start();
    final List<Object> expectedEntities = List.of(ALICE);
    assertEquals(expectedEntities, this.convertedPlatformEntities);

    filter.release();
  }

  @Test
  void shouldKeepTheExistingSpeakersWhenStartedTwice() {
    final SvcFilter filter = SvcFilter.svc(ALICE);
    filter.start();
    filter.start();
    final int playerCount = this.players.size();
    assertEquals(1, playerCount);

    filter.release();
  }

  @Test
  void shouldPassTheSamplesOnUnchangedWhetherStartedOrNot() {
    final SvcFilter filter = SvcFilter.svc(ALICE);
    final short[] mono = this.ramp(FRAME_SAMPLES, -100);
    this.assertPassedOnUnchanged(filter, mono);

    filter.start();
    this.assertPassedOnUnchanged(filter, mono);

    filter.release();
    this.assertPassedOnUnchanged(filter, mono);
  }

  @Test
  void shouldPlayEveryDownmixedFrameOnEverySpeaker() {
    final SvcFilter filter = SvcFilter.svc(ALICE, BOB);
    filter.start();
    final short[] mono = this.ramp(FRAME_SAMPLES, -100);
    final boolean reportedChange = this.feed(filter, mono);
    final int queued = filter.getQueuedFrames();
    assertFalse(reportedChange, "the filter only reads the samples, so it reports no change");
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
  void shouldAverageStereoChannelsIntoTheFrame() {
    final SvcFilter filter = SvcFilter.svc(ALICE);
    filter.start();
    final ByteBuffer stereo = ByteBuffer.allocate(FRAME_SAMPLES * BYTES_PER_STEREO_SAMPLE);
    stereo.order(ByteOrder.LITTLE_ENDIAN);
    for (int index = 0; index < FRAME_SAMPLES; index++) {
      stereo.putShort((short) 100);
      stereo.putShort((short) 301);
    }

    stereo.flip();

    filter.applyFilter(stereo, this.metadata);
    final short[] frame = this.nextFrameOf(0);
    final short[] expected = this.constant(FRAME_SAMPLES, 200);
    assertArrayEquals(expected, frame);

    filter.release();
  }

  @Test
  void shouldCollectPartialInputUntilAFrameIsComplete() {
    final SvcFilter filter = SvcFilter.svc(ALICE);
    filter.start();
    final short[] first = this.constant(500, 1);
    this.feed(filter, first);
    final int queuedAfterFirst = filter.getQueuedFrames();
    final short[] second = this.constant(FRAME_SAMPLES - 500 + 100, 2);
    this.feed(filter, second);
    final int queuedAfterSecond = filter.getQueuedFrames();
    assertEquals(0, queuedAfterFirst);
    assertEquals(1, queuedAfterSecond);

    final short[] firstFrame = this.nextFrameOf(0);
    assertEquals(1, firstFrame[0]);
    assertEquals(1, firstFrame[499]);
    assertEquals(2, firstFrame[500]);
    assertEquals(2, firstFrame[FRAME_SAMPLES - 1]);

    final short[] third = this.constant(FRAME_SAMPLES - 100, 3);
    this.feed(filter, third);
    final short[] secondFrame = this.nextFrameOf(0);
    assertEquals(2, secondFrame[0]);
    assertEquals(2, secondFrame[99]);
    assertEquals(3, secondFrame[100]);
    assertEquals(3, secondFrame[FRAME_SAMPLES - 1]);

    filter.release();
  }

  @Test
  void shouldSplitLargeInputIntoSeveralFrames() {
    final SvcFilter filter = SvcFilter.svc(ALICE);
    filter.start();
    final short[] mono = this.ramp(FRAME_SAMPLES * 3 + 7, 0);
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
  void shouldPlaySilenceInsteadOfEndingWhenNoFrameIsQueued() {
    final SvcFilter filter = SvcFilter.svc(ALICE);
    filter.start();
    final short[] frame = this.nextFrameOf(0);
    final short[] again = this.nextFrameOf(0);
    final short[] silence = new short[FRAME_SAMPLES];
    assertArrayEquals(silence, frame);
    assertSame(frame, again);

    filter.release();
  }

  @Test
  void shouldDropTheOldestFramesOfASpeakerThatFallsBehind() {
    final SvcFilter filter = SvcFilter.svc(ALICE, BOB);
    filter.start();
    for (int frameIndex = 0; frameIndex < MAXIMUM_FRAMES + 2; frameIndex++) {
      final short[] frame = this.constant(FRAME_SAMPLES, frameIndex);
      this.feed(filter, frame);
      this.nextFrameOf(1);
    }

    final int queued = filter.getQueuedFrames();
    assertEquals(MAXIMUM_FRAMES, queued);

    final short[] oldest = this.nextFrameOf(0);
    assertEquals(2, oldest[0]);

    final short[] bobFrame = this.nextFrameOf(1);
    final short[] silence = new short[FRAME_SAMPLES];
    assertArrayEquals(silence, bobFrame);

    filter.release();
  }

  @Test
  void shouldStopEveryPlayerCloseEveryEncoderAndDropQueuedAudioOnRelease() {
    final SvcFilter filter = SvcFilter.svc(ALICE, BOB);
    filter.start();
    final short[] input = this.constant(FRAME_SAMPLES + 10, 4);
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
  void shouldAttemptEveryResourceAndPreserveCleanupFailuresOnRelease() {
    final SvcFilter filter = SvcFilter.svc(ALICE, BOB);
    filter.start();
    final short[] input = this.constant(FRAME_SAMPLES + 10, 4);
    this.feed(filter, input);
    final IllegalStateException stopping = new IllegalStateException("Player stop failed!");
    final UnsatisfiedLinkError closing = new UnsatisfiedLinkError("Encoder close failed!");
    final IllegalArgumentException later = new IllegalArgumentException("Second player stop failed!");
    final AudioPlayer alice = this.players.get(0);
    final AudioPlayer bob = this.players.get(1);
    final OpusEncoder aliceEncoder = this.encoders.get(0);
    final Stubber failedStop = Mockito.doThrow(stopping);
    final AudioPlayer stubbedAlice = failedStop.when(alice);
    stubbedAlice.stopPlaying();
    final Stubber failedClose = Mockito.doThrow(closing);
    final OpusEncoder stubbedEncoder = failedClose.when(aliceEncoder);
    stubbedEncoder.close();
    final Stubber laterStop = Mockito.doThrow(later);
    final AudioPlayer stubbedBob = laterStop.when(bob);
    stubbedBob.stopPlaying();

    final IllegalStateException thrown = assertThrows(IllegalStateException.class, filter::release);
    final Throwable[] suppressed = thrown.getSuppressed();
    final Throwable[] expected = { closing, later };
    final int queued = filter.getQueuedFrames();
    final short[] silence = new short[FRAME_SAMPLES];
    final short[] aliceAfterRelease = this.nextFrameOf(0);
    final short[] bobAfterRelease = this.nextFrameOf(1);
    assertSame(stopping, thrown);
    assertArrayEquals(expected, suppressed);
    assertEquals(0, queued);
    assertArrayEquals(silence, aliceAfterRelease);
    assertArrayEquals(silence, bobAfterRelease);
    this.assertEverySpeakerStopped();

    filter.release();
    final VerificationMode once = Mockito.times(1);
    final AudioPlayer checkedAlice = Mockito.verify(alice, once);
    checkedAlice.stopPlaying();

    filter.start();
    final short[] almostAFrame = this.constant(FRAME_SAMPLES - 1, 8);
    this.feed(filter, almostAFrame);
    final int queuedAfterRestart = filter.getQueuedFrames();
    assertEquals(0, queuedAfterRestart, "failed cleanup must also discard the previous partial frame");

    filter.release();
  }

  @Test
  void shouldPreserveTheStartFailureWhenRollingBackSpeakersAlsoFails() {
    this.failingEntity = BOB;
    this.failingStep = CREATE_ENCODER;
    final IllegalArgumentException cleanupFailure = new IllegalArgumentException("Stop failed!");
    final Stubber playerCreation = Mockito.doAnswer(invocation -> this.createPlayerThatFailsToStop(invocation, cleanupFailure));
    final VoicechatServerApi stubbedApi = playerCreation.when(this.api);
    stubbedApi.createAudioPlayer(
      ArgumentMatchers.any(AudioChannel.class),
      ArgumentMatchers.any(OpusEncoder.class),
      ArgumentMatchers.<Supplier<short[]>>any()
    );
    final SvcFilter filter = SvcFilter.svc(ALICE, BOB);
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, filter::start);
    final Throwable[] suppressed = thrown.getSuppressed();
    final Throwable[] expected = { cleanupFailure };
    assertSame(this.failure, thrown);
    assertArrayEquals(expected, suppressed);
    this.assertEverySpeakerStopped();

    filter.release();
  }

  @Test
  void shouldSuppressTheEncoderCloseFailureWhenCreatingAPlayerFails() {
    this.failingEntity = BOB;
    this.failingStep = CREATE_AUDIO_PLAYER;
    final IllegalArgumentException closeFailure = new IllegalArgumentException("Close failed!");
    final Stubber encoderCreation = Mockito.doAnswer(_ -> this.createEncoderThatFailsToCloseForBob(closeFailure));
    final VoicechatServerApi stubbedApi = encoderCreation.when(this.api);
    stubbedApi.createEncoder();
    final SvcFilter filter = SvcFilter.svc(ALICE, BOB);
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, filter::start);
    final Throwable[] suppressed = thrown.getSuppressed();
    final Throwable[] expected = { closeFailure };
    assertSame(this.failure, thrown);
    assertArrayEquals(expected, suppressed);
    this.assertEverySpeakerStopped();

    filter.release();
  }

  @Test
  void shouldNeverSuppressACleanupExceptionOntoItself() {
    final SvcFilter filter = SvcFilter.svc(ALICE, BOB);
    filter.start();
    final IllegalStateException sharedFailure = new IllegalStateException("Shared cleanup failure!");
    for (final AudioPlayer player : this.players) {
      final Stubber failedStop = Mockito.doThrow(sharedFailure);
      final AudioPlayer stubbedPlayer = failedStop.when(player);
      stubbedPlayer.stopPlaying();
    }

    for (final OpusEncoder encoder : this.encoders) {
      final Stubber failedClose = Mockito.doThrow(sharedFailure);
      final OpusEncoder stubbedEncoder = failedClose.when(encoder);
      stubbedEncoder.close();
    }

    final IllegalStateException thrown = assertThrows(IllegalStateException.class, filter::release);
    final Throwable[] suppressed = thrown.getSuppressed();
    final int suppressedCount = suppressed.length;
    assertSame(sharedFailure, thrown);
    assertEquals(0, suppressedCount);
    this.assertEverySpeakerStopped();

    filter.release();
  }

  @Test
  void shouldRethrowTheFirstRecoverableCleanupErrorAfterStoppingEverySpeaker() {
    final SvcFilter filter = SvcFilter.svc(ALICE, BOB);
    filter.start();
    final UnsatisfiedLinkError failure = new UnsatisfiedLinkError("Native encoder cleanup failed!");
    final OpusEncoder first = this.encoders.getFirst();
    final Stubber failedClose = Mockito.doThrow(failure);
    final OpusEncoder stubbedEncoder = failedClose.when(first);
    stubbedEncoder.close();
    final UnsatisfiedLinkError thrown = assertThrows(UnsatisfiedLinkError.class, filter::release);
    assertSame(failure, thrown);
    this.assertEverySpeakerStopped();

    final int queued = filter.getQueuedFrames();
    assertEquals(0, queued);

    filter.release();
    final VerificationMode once = Mockito.times(1);
    final OpusEncoder checked = Mockito.verify(first, once);
    checked.close();
  }

  @Test
  void shouldPropagateFatalCleanupErrorsWithoutAttemptingMoreThirdPartyCalls() {
    final SvcFilter filter = SvcFilter.svc(ALICE, BOB);
    filter.start();
    final InternalError fatal = new InternalError("The virtual machine cannot continue cleanup!");
    final AudioPlayer first = this.players.get(0);
    final AudioPlayer second = this.players.get(1);
    final Stubber failedStop = Mockito.doThrow(fatal);
    final AudioPlayer stubbedPlayer = failedStop.when(first);
    stubbedPlayer.stopPlaying();
    final InternalError thrown = assertThrows(InternalError.class, filter::release);
    assertSame(fatal, thrown);

    final VerificationMode never = Mockito.never();
    final AudioPlayer checkedSecond = Mockito.verify(second, never);
    checkedSecond.stopPlaying();
    for (final OpusEncoder encoder : this.encoders) {
      final OpusEncoder checkedEncoder = Mockito.verify(encoder, never);
      checkedEncoder.close();
    }
  }

  @Test
  void shouldCreateNewSpeakersAndForgetThePartialFrameWhenRestarted() {
    final SvcFilter filter = SvcFilter.svc(ALICE);
    filter.start();
    final short[] almostAFrame = this.constant(FRAME_SAMPLES - 1, 8);
    this.feed(filter, almostAFrame);
    filter.release();
    filter.start();
    final int playerCount = this.players.size();
    assertEquals(2, playerCount);

    final short[] oneSample = this.constant(1, 9);
    this.feed(filter, oneSample);
    final int queuedAfterOneSample = filter.getQueuedFrames();
    assertEquals(0, queuedAfterOneSample);

    final short[] rest = this.constant(FRAME_SAMPLES - 1, 9);
    this.feed(filter, rest);
    final short[] frame = this.nextFrameOf(1);
    final short[] expected = this.constant(FRAME_SAMPLES, 9);
    assertArrayEquals(expected, frame);

    filter.release();
  }

  @Test
  void shouldStopTheCreatedSpeakersWhenVoiceChatRefusesAChannel() {
    this.refusedEntity = BOB;
    final SvcFilter filter = SvcFilter.svc(ALICE, BOB);
    final SvcException exception = assertThrows(SvcException.class, filter::start);
    final String message = exception.getMessage();
    assertEquals("Entity bob was refused an audio channel by Simple Voice Chat!", message);

    final int playerCount = this.players.size();
    final int encoderCount = this.encoders.size();
    assertEquals(1, playerCount);
    assertEquals(1, encoderCount);
    this.assertEverySpeakerStopped();

    final short[] silence = new short[FRAME_SAMPLES];
    this.assertStoppedFilterQueuesNothing(filter, silence);

    this.refusedEntity = null;
    filter.start();
    final int playerCountAfterRestart = this.players.size();
    assertEquals(3, playerCountAfterRestart);

    filter.release();
  }

  @ParameterizedTest
  @ValueSource(strings = { FROM_ENTITY, CREATE_ENCODER, CREATE_AUDIO_PLAYER, START_PLAYING })
  void shouldReleaseEverythingItCreatedWhenStartingFailsAndStartAgain(final String step) {
    this.failingEntity = BOB;
    this.failingStep = step;
    final SvcFilter filter = SvcFilter.svc(ALICE, BOB);
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, filter::start);
    assertSame(this.failure, thrown, "the failure of voice chat is passed on unchanged");

    final int playersBefore = this.players.size();
    this.assertOnlyReachedSpeakerPartsWereCreated(step, playersBefore);
    this.assertEverySpeakerStopped();

    final short[] frame = this.constant(FRAME_SAMPLES, 5);
    this.assertStoppedFilterQueuesNothing(filter, frame);

    this.failingStep = null;
    filter.start();
    this.assertRestartedSpeakersPlay(filter, frame, playersBefore);

    filter.release();
    this.assertEverySpeakerStopped();
  }

  @Test
  void shouldReleaseEverythingItCreatedWhenVoiceChatCannotLinkItsCodec() {
    final UnsatisfiedLinkError linkFailure = new UnsatisfiedLinkError("No opus in java.library.path!");
    this.failEncoderCreationForBob(linkFailure);
    final SvcFilter filter = SvcFilter.svc(ALICE, BOB);
    final UnsatisfiedLinkError thrown = assertThrows(UnsatisfiedLinkError.class, filter::start);
    assertSame(linkFailure, thrown, "the failure of voice chat is passed on unchanged");

    final int playerCount = this.players.size();
    assertEquals(1, playerCount);
    this.assertEverySpeakerStopped();

    final short[] frame = this.constant(FRAME_SAMPLES, 5);
    this.assertStoppedFilterQueuesNothing(filter, frame);
  }

  @Test
  void shouldPassErrorsOfTheVirtualMachineOnUnchanged() {
    final InternalError fatal = new InternalError("Out of native memory!");
    this.failEncoderCreationForBob(fatal);
    final SvcFilter filter = SvcFilter.svc(ALICE, BOB);
    final InternalError thrown = assertThrows(InternalError.class, filter::start);
    assertSame(fatal, thrown, "an error of the virtual machine is never replaced or hidden");
    this.assertNoSpeakerWasRolledBack();
  }

  @Test
  void shouldLeaveTheSpeakerAloneWhenStartingItsPlayerFailsFatally() {
    final InternalError fatal = new InternalError("The codec died!");
    this.failPlayerCreationForBob(fatal);
    final SvcFilter filter = SvcFilter.svc(ALICE, BOB);
    final InternalError thrown = assertThrows(InternalError.class, filter::start);
    assertSame(fatal, thrown, "an error of the virtual machine is never replaced or hidden");

    final int encoderCount = this.encoders.size();
    assertEquals(2, encoderCount, "the encoder of the speaker that failed was created");
    this.assertNoSpeakerWasRolledBack();
  }

  @Test
  void shouldDeliverFramesInOrderWhileVoiceChatPullsConcurrently() throws Exception {
    final SvcFilter filter = SvcFilter.svc(ALICE);
    filter.start();
    final Supplier<short[]> supplier = this.suppliers.getFirst();
    final List<Short> received;
    try (final ExecutorService executor = Executors.newFixedThreadPool(2)) {
      final Future<List<Short>> consumer = executor.submit(() -> this.receiveFrames(supplier));
      final Future<?> producer = executor.submit(() -> this.produceFrames(filter));
      producer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      received = consumer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
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

  private ByteBuffer stereoOf(final short[] mono) {
    final ByteBuffer buffer = ByteBuffer.allocate(mono.length * BYTES_PER_STEREO_SAMPLE);
    buffer.order(ByteOrder.LITTLE_ENDIAN);
    for (final short sample : mono) {
      buffer.putShort(sample);
      buffer.putShort(sample);
    }

    buffer.flip();

    return buffer;
  }

  private void assertStoppedFilterQueuesNothing(final SvcFilter filter, final short[] mono) {
    final boolean reportedChange = this.feed(filter, mono);
    final int queued = filter.getQueuedFrames();
    assertFalse(reportedChange, "the filter only reads the samples, so it reports no change");
    assertEquals(0, queued);
  }

  private void assertPassedOnUnchanged(final SvcFilter filter, final short[] mono) {
    final ByteBuffer samples = this.stereoOf(mono);
    final ByteBuffer original = this.stereoOf(mono);
    final boolean reportedChange = filter.applyFilter(samples, this.metadata);
    assertFalse(reportedChange);
    assertEquals(original, samples);
  }

  private boolean feed(final SvcFilter filter, final short[] mono) {
    final ByteBuffer stereo = this.stereoOf(mono);
    return filter.applyFilter(stereo, this.metadata);
  }

  private short[] nextFrameOf(final int speaker) {
    final Supplier<short[]> supplier = this.suppliers.get(speaker);
    return supplier.get();
  }

  private short[] ramp(final int length, final int start) {
    final short[] samples = new short[length];
    for (int index = 0; index < length; index++) {
      samples[index] = (short) (start + index);
    }

    return samples;
  }

  private short[] constant(final int length, final int value) {
    final short[] samples = new short[length];
    Arrays.fill(samples, (short) value);

    return samples;
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

  private AudioPlayer createPlayerThatFailsToStop(final InvocationOnMock invocation, final RuntimeException stopFailure) {
    final AudioPlayer player = this.createPlayer(invocation);
    final Stubber failedStop = Mockito.doThrow(stopFailure);
    final AudioPlayer stubbedPlayer = failedStop.when(player);
    stubbedPlayer.stopPlaying();

    return player;
  }

  private OpusEncoder createEncoderThatFailsToCloseForBob(final RuntimeException closeFailure) {
    final OpusEncoder encoder = this.createEncoder();
    final boolean creatingForBob = BOB.equals(this.currentEntity);
    if (creatingForBob) {
      final Stubber failedClose = Mockito.doThrow(closeFailure);
      final OpusEncoder stubbedEncoder = failedClose.when(encoder);
      stubbedEncoder.close();
    }

    return encoder;
  }

  private void assertOnlyReachedSpeakerPartsWereCreated(final String failingStep, final int playerCount) {
    final int encoderCount = this.encoders.size();
    final int expectedPlayers = failingStep.equals(START_PLAYING) ? 2 : 1;
    final boolean bobHasAnEncoder = failingStep.equals(CREATE_AUDIO_PLAYER) || failingStep.equals(START_PLAYING);
    final int expectedEncoders = bobHasAnEncoder ? 2 : 1;
    assertEquals(expectedPlayers, playerCount);
    assertEquals(expectedEncoders, encoderCount);
  }

  private void assertRestartedSpeakersPlay(final SvcFilter filter, final short[] frame, final int playersBefore) {
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

  private void failEncoderCreationForBob(final Error failure) {
    final Stubber encoderCreation = Mockito.doAnswer(_ -> this.createEncoderUnlessBob(failure));
    final VoicechatServerApi stubbedApi = encoderCreation.when(this.api);
    stubbedApi.createEncoder();
  }

  private OpusEncoder createEncoderUnlessBob(final Error failure) {
    final boolean creatingForBob = BOB.equals(this.currentEntity);
    if (creatingForBob) {
      throw failure;
    }

    return this.createEncoder();
  }

  // after an error of the virtual machine, more third-party cleanup code would only hide the failure
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
    final boolean creatingForBob = BOB.equals(this.currentEntity);
    if (creatingForBob) {
      throw failure;
    }

    return this.createPlayer(invocation);
  }

  private List<Short> receiveFrames(final Supplier<short[]> supplier) {
    final List<Short> received = new ArrayList<>();
    final long timeout = TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
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

  private void produceFrames(final SvcFilter filter) {
    for (int frameIndex = 1; frameIndex <= CONCURRENT_FRAMES; frameIndex++) {
      final short[] frame = this.constant(FRAME_SAMPLES, frameIndex);
      this.feed(filter, frame);
    }
  }
}
