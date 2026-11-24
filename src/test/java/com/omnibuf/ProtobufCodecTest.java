package com.omnibuf;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.omnibuf.wire.OmniBufEncoder;
import com.omnibuf.wire.PrefixVarint;
import org.junit.jupiter.api.Test;

class ProtobufCodecTest {

    @Test
    void protobufUnmarshalUnknownClosedProto2EnumIgnoresUnknownValue() {
        // DescriptorProtos.FieldDescriptorProto is a compiled proto2 GeneratedMessage;
        // field #5 (type) is a closed proto2 enum (FieldDescriptorProto.Type).
        OmniBufEncoder enc = new OmniBufEncoder();
        enc.writeString(1, "sample_field");
        enc.writeSint32(5, 999);
        byte[] pkt = enc.toByteArray();

        FieldDescriptorProto decoded = assertDoesNotThrow(
            () -> OmniBufProtobuf.unmarshal(pkt, FieldDescriptorProto.getDefaultInstance())
        );
        assertEquals("sample_field", decoded.getName());
    }

    @Test
    void protobufUnmarshalMissingProto2RequiredFieldThrowsOmniBufException() throws Exception {
        FileDescriptor fd = buildProto2RequiredDescriptor();
        Descriptor reqInner = fd.findMessageTypeByName("ReqInner");

        OmniBufEncoder enc = new OmniBufEncoder();
        enc.writeUint32(1, 10); // missing required field #2 ("b")
        byte[] pkt = enc.toByteArray();

        assertThrows(
            OmniBufException.class,
            () -> OmniBufProtobuf.unmarshal(pkt, DynamicMessage.getDefaultInstance(reqInner))
        );
    }

    @Test
    void protobufUnmarshalMergesSplitConcatenatedProto2RequiredSubMsg() throws Exception {
        FileDescriptor fd = buildProto2RequiredDescriptor();
        Descriptor reqOuter = fd.findMessageTypeByName("ReqOuter");
        Descriptor reqInner = fd.findMessageTypeByName("ReqInner");

        OmniBufEncoder enc = new OmniBufEncoder();
        // Segment 1 sets required field #1 (a = 10)
        enc.beginSubMsg(1);
        enc.writeUint32(1, 10);
        enc.endSubMsg();
        // Segment 2 sets required field #2 (b = "ok")
        enc.beginSubMsg(1);
        enc.writeString(2, "ok");
        enc.endSubMsg();
        byte[] splitPkt = enc.toByteArray();

        DynamicMessage decoded = assertDoesNotThrow(
            () -> OmniBufProtobuf.unmarshal(splitPkt, DynamicMessage.getDefaultInstance(reqOuter))
        );
        DynamicMessage inner = (DynamicMessage) decoded.getField(reqOuter.findFieldByName("inner"));
        assertEquals(10, inner.getField(reqInner.findFieldByName("a")));
        assertEquals("ok", inner.getField(reqInner.findFieldByName("b")));
    }

    @Test
    void protobufRejectsGroupAndPopulatedProto2Extensions() throws Exception {
        // Build a synthetic proto2 FileDescriptor with both a GROUP field and an extension field
        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
            .setName("legacy_proto2.proto")
            .setPackage("test.legacy")
            .setSyntax("proto2")
            .addMessageType(
                DescriptorProto.newBuilder()
                    .setName("GroupHolder")
                    .addField(
                        FieldDescriptorProto.newBuilder()
                            .setName("mygroup")
                            .setNumber(1)
                            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                            .setType(FieldDescriptorProto.Type.TYPE_GROUP)
                            .setTypeName(".test.legacy.GroupHolder.MyGroup")
                    )
                    .addNestedType(
                        DescriptorProto.newBuilder()
                            .setName("MyGroup")
                            .addField(
                                FieldDescriptorProto.newBuilder()
                                    .setName("x")
                                    .setNumber(2)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                    .setType(FieldDescriptorProto.Type.TYPE_INT32)
                            )
                    )
            )
            .addMessageType(
                DescriptorProto.newBuilder()
                    .setName("ExtendableMsg")
                    .addField(
                        FieldDescriptorProto.newBuilder()
                            .setName("id")
                            .setNumber(1)
                            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                            .setType(FieldDescriptorProto.Type.TYPE_UINT32)
                    )
                    .addExtensionRange(
                        DescriptorProto.ExtensionRange.newBuilder().setStart(100).setEnd(200)
                    )
            )
            .addExtension(
                FieldDescriptorProto.newBuilder()
                    .setName("ext_note")
                    .setExtendee(".test.legacy.ExtendableMsg")
                    .setNumber(105)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
            )
            .build();

        FileDescriptor fd = FileDescriptor.buildFrom(fileProto, new FileDescriptor[0]);

        // GROUP message rejected on both marshal and unmarshal
        Descriptor groupDesc = fd.findMessageTypeByName("GroupHolder");
        DynamicMessage groupMsg = DynamicMessage.getDefaultInstance(groupDesc);
        assertThrows(OmniBufException.class, () -> OmniBufProtobuf.marshal(groupMsg));

        OmniBufEncoder dummyWire = new OmniBufEncoder();
        dummyWire.writeUint(1, 5L);
        assertThrows(
            OmniBufException.class,
            () -> OmniBufProtobuf.unmarshal(dummyWire.toByteArray(), groupMsg)
        );

        // Populated proto2 extension rejected on marshal (never silently dropped!)
        Descriptor extDesc = fd.findMessageTypeByName("ExtendableMsg");
        FieldDescriptor extField = fd.findExtensionByName("ext_note");
        DynamicMessage populatedExt = DynamicMessage.newBuilder(extDesc)
            .setField(extDesc.findFieldByName("id"), 7)
            .setField(extField, "must-not-be-silently-dropped")
            .build();
        assertThrows(OmniBufException.class, () -> OmniBufProtobuf.marshal(populatedExt));

        assertEquals(1, PrefixVarint.encodedLength(0L));
    }

    private static FileDescriptor buildProto2RequiredDescriptor() throws Exception {
        DescriptorProto reqInner = DescriptorProto.newBuilder()
            .setName("ReqInner")
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("a")
                    .setNumber(1)
                    .setLabel(FieldDescriptorProto.Label.LABEL_REQUIRED)
                    .setType(FieldDescriptorProto.Type.TYPE_UINT32)
            )
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("b")
                    .setNumber(2)
                    .setLabel(FieldDescriptorProto.Label.LABEL_REQUIRED)
                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
            )
            .build();

        DescriptorProto reqOuter = DescriptorProto.newBuilder()
            .setName("ReqOuter")
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("inner")
                    .setNumber(1)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                    .setTypeName(".audit.p2.ReqInner")
            )
            .build();

        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
            .setName("audit_req.proto")
            .setPackage("audit.p2")
            .setSyntax("proto2")
            .addMessageType(reqInner)
            .addMessageType(reqOuter)
            .build();

        return FileDescriptor.buildFrom(fileProto, new FileDescriptor[0]);
    }
}
