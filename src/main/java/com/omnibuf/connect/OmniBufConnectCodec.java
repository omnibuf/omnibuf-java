package com.omnibuf.connect;

import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.omnibuf.MarshalOptions;
import com.omnibuf.OmniBufException;
import com.omnibuf.OmniBufMapper;
import com.omnibuf.OmniBufProtobuf;
import com.omnibuf.UnmarshalOptions;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Connect RPC codec helper for OmniBuf (`application/omnibuf` and `application/x-omnibuf`).
 *
 * <p>Supports both {@code @OmniBufMessage}-annotated Java classes/records (via
 * {@link OmniBufMapper}) and standard {@link Message} protobuf types (via
 * {@link OmniBufProtobuf}).
 */
public final class OmniBufConnectCodec {

    public static final String CODEC_NAME = "omnibuf";
    public static final String SELF_DESCRIBING_CODEC_NAME = "x-omnibuf";

    private static final OmniBufConnectCodec COMPACT = new OmniBufConnectCodec(
        MarshalOptions.DEFAULT,
        UnmarshalOptions.DEFAULT
    );
    private static final OmniBufConnectCodec SELF_DESCRIBING = new OmniBufConnectCodec(
        MarshalOptions.SELF_DESCRIBING,
        UnmarshalOptions.DEFAULT
    );
    private static final OmniBufConnectCodec DETERMINISTIC = new OmniBufConnectCodec(
        MarshalOptions.DETERMINISTIC,
        UnmarshalOptions.DEFAULT
    );

    private final MarshalOptions marshalOptions;
    private final UnmarshalOptions unmarshalOptions;

    private OmniBufConnectCodec(MarshalOptions marshalOptions, UnmarshalOptions unmarshalOptions) {
        this.marshalOptions = Objects.requireNonNull(marshalOptions, "marshalOptions");
        this.unmarshalOptions = Objects.requireNonNull(unmarshalOptions, "unmarshalOptions");
    }

    public static OmniBufConnectCodec compact() {
        return COMPACT;
    }

    public static OmniBufConnectCodec selfDescribing() {
        return SELF_DESCRIBING;
    }

    public static OmniBufConnectCodec deterministic() {
        return DETERMINISTIC;
    }

    public static OmniBufConnectCodec of(MarshalOptions marshalOptions, UnmarshalOptions unmarshalOptions) {
        return new OmniBufConnectCodec(
            marshalOptions == null ? MarshalOptions.DEFAULT : marshalOptions,
            unmarshalOptions == null ? UnmarshalOptions.DEFAULT : unmarshalOptions
        );
    }

    public OmniBufConnectCodec withDeterministic(boolean deterministic) {
        return new OmniBufConnectCodec(
            this.marshalOptions.withDeterministic(deterministic),
            this.unmarshalOptions
        );
    }

    public OmniBufConnectCodec withSelfDescribing(boolean selfDescribing) {
        return new OmniBufConnectCodec(
            this.marshalOptions.withSelfDescribing(selfDescribing),
            this.unmarshalOptions
        );
    }

    public OmniBufConnectCodec withUnmarshalOptions(UnmarshalOptions options) {
        return new OmniBufConnectCodec(
            this.marshalOptions,
            options == null ? UnmarshalOptions.DEFAULT : options
        );
    }

    public String encodingName() {
        return this.marshalOptions.selfDescribing() ? SELF_DESCRIBING_CODEC_NAME : CODEC_NAME;
    }

    public MarshalOptions marshalOptions() {
        return this.marshalOptions;
    }

    public UnmarshalOptions unmarshalOptions() {
        return this.unmarshalOptions;
    }

    public byte[] serialize(Object message) {
        if (message == null) {
            throw new OmniBufException("omnibuf: cannot serialize null Connect message");
        }
        if (message instanceof MessageOrBuilder proto) {
            return OmniBufProtobuf.marshal(proto, this.marshalOptions);
        }
        return OmniBufMapper.marshal(message, this.marshalOptions);
    }

    @SuppressWarnings("unchecked")
    public <T> T deserialize(byte[] data, Class<T> type) {
        if (data == null) {
            throw new OmniBufException("omnibuf: null Connect payload bytes");
        }
        if (type == null) {
            throw new OmniBufException("omnibuf: null Connect target class");
        }
        if (Message.class.isAssignableFrom(type)) {
            Message defaultInstance = resolveDefaultInstance(type);
            return (T) OmniBufProtobuf.unmarshal(data, defaultInstance, this.unmarshalOptions);
        }
        return OmniBufMapper.unmarshal(data, type, this.unmarshalOptions);
    }

    public <M extends Message> M deserialize(byte[] data, M defaultInstance) {
        if (data == null) {
            throw new OmniBufException("omnibuf: null Connect payload bytes");
        }
        return OmniBufProtobuf.unmarshal(data, defaultInstance, this.unmarshalOptions);
    }

    private static Message resolveDefaultInstance(Class<?> type) {
        try {
            Method method = type.getMethod("getDefaultInstance");
            Object result = method.invoke(null);
            if (!(result instanceof Message msg)) {
                throw new OmniBufException(
                    "omnibuf: getDefaultInstance() on " + type.getName() + " did not return a Message"
                );
            }
            return msg;
        } catch (ReflectiveOperationException e) {
            throw new OmniBufException(
                "omnibuf: unable to obtain default instance for protobuf class " + type.getName(),
                e
            );
        }
    }
}
