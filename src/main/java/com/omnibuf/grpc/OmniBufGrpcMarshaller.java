package com.omnibuf.grpc;

import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.omnibuf.MarshalOptions;
import com.omnibuf.OmniBufException;
import com.omnibuf.OmniBufMapper;
import com.omnibuf.OmniBufProtobuf;
import com.omnibuf.UnmarshalOptions;
import io.grpc.MethodDescriptor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Detached {@link MethodDescriptor.Marshaller} implementation for OmniBuf.
 *
 * <p>{@code io.grpc:grpc-api} is declared with {@code provided} scope in {@code pom.xml}, so core
 * {@code omnibuf-java} users do not pull gRPC transitively unless they use this package.
 *
 * <p>Supports both {@code @OmniBufMessage}-annotated Java classes/records (via
 * {@link OmniBufMapper}) and standard {@link Message} protobuf types (via
 * {@link OmniBufProtobuf}).
 *
 * @param <T> message type
 */
public final class OmniBufGrpcMarshaller<T> implements MethodDescriptor.Marshaller<T> {

    public static final String CODEC_NAME = "omnibuf";
    public static final String SELF_DESCRIBING_CODEC_NAME = "x-omnibuf";
    public static final String CONTENT_TYPE_GRPC = "application/grpc+omnibuf";

    private static final int STREAM_CHUNK_SIZE = 4096;

    private final Class<T> messageClass;
    private final Message protobufDefaultInstance;
    private final MarshalOptions marshalOptions;
    private final UnmarshalOptions unmarshalOptions;

    private OmniBufGrpcMarshaller(
            Class<T> messageClass,
            Message protobufDefaultInstance,
            MarshalOptions marshalOptions,
            UnmarshalOptions unmarshalOptions) {
        this.messageClass = messageClass;
        this.protobufDefaultInstance = protobufDefaultInstance;
        this.marshalOptions = Objects.requireNonNull(marshalOptions, "marshalOptions");
        this.unmarshalOptions = Objects.requireNonNull(unmarshalOptions, "unmarshalOptions");
    }

    public static <T> OmniBufGrpcMarshaller<T> forClass(Class<T> messageClass) {
        return forClass(messageClass, MarshalOptions.DEFAULT, UnmarshalOptions.DEFAULT);
    }

    public static <T> OmniBufGrpcMarshaller<T> forClass(
            Class<T> messageClass,
            MarshalOptions marshalOptions,
            UnmarshalOptions unmarshalOptions) {
        if (messageClass == null) {
            throw new OmniBufException("omnibuf: null gRPC message class");
        }
        Message defaultInstance = Message.class.isAssignableFrom(messageClass)
            ? resolveDefaultInstance(messageClass)
            : null;
        return new OmniBufGrpcMarshaller<>(
            messageClass,
            defaultInstance,
            marshalOptions == null ? MarshalOptions.DEFAULT : marshalOptions,
            unmarshalOptions == null ? UnmarshalOptions.DEFAULT : unmarshalOptions
        );
    }

    public static <M extends Message> OmniBufGrpcMarshaller<M> forProtobuf(M defaultInstance) {
        return forProtobuf(defaultInstance, MarshalOptions.DEFAULT, UnmarshalOptions.DEFAULT);
    }

    @SuppressWarnings("unchecked")
    public static <M extends Message> OmniBufGrpcMarshaller<M> forProtobuf(
            M defaultInstance,
            MarshalOptions marshalOptions,
            UnmarshalOptions unmarshalOptions) {
        if (defaultInstance == null) {
            throw new OmniBufException("omnibuf: null protobuf defaultInstance");
        }
        return new OmniBufGrpcMarshaller<>(
            (Class<M>) defaultInstance.getClass(),
            defaultInstance,
            marshalOptions == null ? MarshalOptions.DEFAULT : marshalOptions,
            unmarshalOptions == null ? UnmarshalOptions.DEFAULT : unmarshalOptions
        );
    }

    public OmniBufGrpcMarshaller<T> withDeterministic(boolean deterministic) {
        return new OmniBufGrpcMarshaller<>(
            this.messageClass,
            this.protobufDefaultInstance,
            this.marshalOptions.withDeterministic(deterministic),
            this.unmarshalOptions
        );
    }

    public OmniBufGrpcMarshaller<T> withSelfDescribing(boolean selfDescribing) {
        return new OmniBufGrpcMarshaller<>(
            this.messageClass,
            this.protobufDefaultInstance,
            this.marshalOptions.withSelfDescribing(selfDescribing),
            this.unmarshalOptions
        );
    }

    public OmniBufGrpcMarshaller<T> withUnmarshalOptions(UnmarshalOptions options) {
        return new OmniBufGrpcMarshaller<>(
            this.messageClass,
            this.protobufDefaultInstance,
            this.marshalOptions,
            options == null ? UnmarshalOptions.DEFAULT : options
        );
    }

    public String codecName() {
        return this.marshalOptions.selfDescribing() ? SELF_DESCRIBING_CODEC_NAME : CODEC_NAME;
    }

    public MarshalOptions marshalOptions() {
        return this.marshalOptions;
    }

    public UnmarshalOptions unmarshalOptions() {
        return this.unmarshalOptions;
    }

    @Override
    public InputStream stream(T value) {
        if (value == null) {
            throw new OmniBufException("omnibuf: cannot serialize null gRPC message");
        }
        byte[] bytes;
        if (value instanceof MessageOrBuilder proto) {
            bytes = OmniBufProtobuf.marshal(proto, this.marshalOptions);
        } else {
            bytes = OmniBufMapper.marshal(value, this.marshalOptions);
        }
        return new ByteArrayInputStream(bytes);
    }

    @Override
    @SuppressWarnings("unchecked")
    public T parse(InputStream stream) {
        if (stream == null) {
            throw new OmniBufException("omnibuf: null gRPC payload stream");
        }
        byte[] data = readBounded(stream, this.unmarshalOptions.maxMessageBytes());
        if (this.protobufDefaultInstance != null) {
            return (T) OmniBufProtobuf.unmarshal(data, this.protobufDefaultInstance, this.unmarshalOptions);
        }
        return OmniBufMapper.unmarshal(data, this.messageClass, this.unmarshalOptions);
    }

    private static byte[] readBounded(InputStream stream, int maxBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[STREAM_CHUNK_SIZE];
        int total = 0;
        try {
            int n;
            while ((n = stream.read(buf)) != -1) {
                if (n > maxBytes - total) {
                    throw new OmniBufException(
                        "omnibuf: gRPC payload exceeds maxMessageBytes (" + maxBytes + ")"
                    );
                }
                out.write(buf, 0, n);
                total += n;
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new OmniBufException("omnibuf: failed to read gRPC payload stream", e);
        }
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
