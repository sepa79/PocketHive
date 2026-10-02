package io.pockethive.processor.mip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.pockethive.iso8583.Iso8583Codec;
import io.pockethive.iso8583.Iso8583Message;
import io.pockethive.processor.handler.Iso8583WireProfile;
import io.pockethive.work.api.IsoSchemaRef;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MipChannelTest {
  private final Iso8583Codec codec = new Iso8583Codec();
  private IsoSchemaRef schema;
  private MipSession session;
  private EmbeddedChannel channel;

  @BeforeEach void setUp() throws Exception {
    schema = new IsoSchemaRef(Path.of(getClass().getResource("/iso8583/schema-registry").toURI()).toString(),
        "synthetic", "1", "J8583_XML", "synthetic-j8583.xml");
    session = new MipSession(4);
    var network = new MipNetworkManagement(codec, schema, new MipNetworkLayout(
        Set.of("270", "081", "082"), Set.of(7, 11, 70), Map.of(39, "00")));
    var dispatcher = new MipMessageDispatcher(codec, schema, session, network);
    channel = new EmbeddedChannel(io.netty.channel.DefaultChannelId.newInstance(), new MipChannelInitializer(session, dispatcher, profile()));
  }

  @org.junit.jupiter.api.AfterEach void tearDown() {
    session.close();
    channel.finishAndReleaseAll();
  }

  @Test void echoesFragmentedFrameAndCoalescedFramesUsingInheritedLayouts() {
    byte[] frame = frame(0x0800, Map.of(7, "1002123045", 11, "000001", 70, "270"));
    assertThat(channel.writeInbound(Unpooled.wrappedBuffer(frame, 0, 1))).isFalse();
    assertThat((Object) channel.readOutbound()).isNull();
    channel.writeInbound(Unpooled.wrappedBuffer(frame, 1, frame.length - 1));
    assertThat(readOutbound()).isEqualTo(new Iso8583Message(0x0810,
        Map.of(7, "1002123045", 11, "000001", 39, "00", 70, "270")));
    var coalesced = Unpooled.buffer();
    coalesced.writeBytes(frame(0x0800, Map.of(7, "1002123046", 11, "000002", 70, "081")));
    coalesced.writeBytes(frame(0x0800, Map.of(7, "1002123047", 11, "000003", 70, "082")));
    channel.writeInbound(coalesced);
    assertThat(readOutbound().field(70)).isEqualTo("081");
    assertThat(readOutbound().field(70)).isEqualTo("082");
    assertThat((Object) channel.readOutbound()).isNull();
  }

  @Test void echoesWhileAuthorizationIsWaitingAndCompletesOnlyMatchingReply() throws Exception {
    byte[] request = codec.encode(0x0100, Map.of(3, "000000", 11, "000100"), schema);
    try (var executor = Executors.newSingleThreadExecutor()) {
      CompletableFuture<MipReply> result = CompletableFuture.supplyAsync(() -> {
        try { return session.exchange(request, codec.decode(request, schema), 5000); }
        catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
      }, executor);
      org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> {
        channel.runPendingTasks();
        return channel.outboundMessages().size() > 0;
      });
      assertThat(readOutbound().mti()).isEqualTo(0x0100);
      channel.writeInbound(Unpooled.wrappedBuffer(frame(0x0800,
          Map.of(7, "1002123045", 11, "000999", 70, "270"))));
      assertThat(readOutbound().mti()).isEqualTo(0x0810);
      assertThat(result).isNotDone();
      channel.writeInbound(Unpooled.wrappedBuffer(frame(0x0110, Map.of(11, "000101", 39, "00"))));
      assertThat(result).isNotDone();
      byte[] reply = codec.encode(0x0110, Map.of(11, "000100", 39, "05"), schema);
      channel.writeInbound(Unpooled.wrappedBuffer(profile().frame(reply)));
      assertThat(result.get(2, java.util.concurrent.TimeUnit.SECONDS).payload()).containsExactly(reply);
      assertThat(result.get().decoded().field(39)).isEqualTo("05");
    }
  }

  @Test void rejectsSecondPeerAndKeepsOriginalChannelUsable() {
    var network = new MipNetworkManagement(codec, schema, new MipNetworkLayout(
        Set.of("270"), Set.of(11, 70), Map.of(39, "00")));
    var dispatcher = new MipMessageDispatcher(codec, schema, session, network);
    var second = new EmbeddedChannel(io.netty.channel.DefaultChannelId.newInstance(), new MipChannelInitializer(session, dispatcher, profile()));
    assertThat(second.isActive()).isFalse();
    assertThat(channel.isActive()).isTrue();
    second.finishAndReleaseAll();
    channel.writeInbound(Unpooled.wrappedBuffer(frame(0x0800,
        Map.of(7, "1002123045", 11, "000002", 70, "270"))));
    assertThat(readOutbound().mti()).isEqualTo(0x0810);
  }

  @Test void unsupportedCodeFailsPeerExplicitly() {
    channel.writeInbound(Unpooled.wrappedBuffer(frame(0x0800,
        Map.of(7, "1002123045", 11, "000001", 70, "301"))));
    assertThat(channel.isActive()).isFalse();
    assertThat((Object) channel.readOutbound()).isNull();
  }

  @Test void rejectsTruncatedIsoPayload() {
    channel.writeInbound(Unpooled.wrappedBuffer(profile().frame(new byte[]{1, 2, 3})));
    assertThat(channel.isActive()).isFalse();
    assertThat((Object) channel.readOutbound()).isNull();
  }

  @Test void missingCopiedFieldFailsPeerExplicitly() {
    channel.writeInbound(Unpooled.wrappedBuffer(frame(0x0800, Map.of(11, "000001", 70, "270"))));
    assertThat(channel.isActive()).isFalse();
    assertThat((Object) channel.readOutbound()).isNull();
  }

  @Test void unexpectedAuthorizationRequestFailsPeerExplicitly() {
    channel.writeInbound(Unpooled.wrappedBuffer(frame(0x0100, Map.of(11, "000001"))));
    assertThat(channel.isActive()).isFalse();
    assertThat((Object) channel.readOutbound()).isNull();
  }

  @Test void copiedFieldsMustExistInBothGuidesBeforeListenerStarts() {
    assertThatThrownBy(() -> new MipNetworkManagement(codec, schema, new MipNetworkLayout(
        Set.of("270"), Set.of(7, 11, 12, 70), Map.of(39, "00"))))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("12");
  }

  @Test void layoutCannotOverwriteCopiedStanOrOmitResponseCode() {
    assertThatThrownBy(() -> new MipNetworkLayout(Set.of("270"), Set.of(11, 70), Map.of(11, "1", 39, "00")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MipNetworkLayout(Set.of("270"), Set.of(11, 70), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private byte[] frame(int mti, Map<Integer, String> fields) { return profile().frame(codec.encode(mti, fields, schema)); }
  private Iso8583Message readOutbound() {
    ByteBuf bytes = channel.readOutbound();
    assertThat(bytes).isNotNull();
    try {
      int length = bytes.readUnsignedShort();
      assertThat(bytes.readableBytes()).isEqualTo(length);
      byte[] payload = new byte[length];
      bytes.readBytes(payload);
      return codec.decode(payload, schema);
    } finally { bytes.release(); }
  }
  private static Iso8583WireProfile profile() { return Iso8583WireProfile.MC_2BYTE_LEN_BIN_BITMAP; }
}
