package com.omnibuf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.Edition;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FeatureSet;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.FileOptions;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.DescriptorProtos.OneofOptions;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.JavaFeaturesProto;
import com.google.protobuf.Message;
import com.google.protobuf.UnknownFieldSet;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import com.omnibuf.plugin.ProtocGenOmniBufJava;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real end-to-end tests for Protobuf Editions 2023, 2024, and 2026 compiled with {@code protoc},
 * exercising {@link ProtocGenOmniBufJava} generated models/codecs/opaque views alongside
 * {@link OmniBufProtobuf} reflection ({@link DynamicMessage} and {@code protoc --java_out}
 * {@link Message} types).
 */
class EditionsEndToEndTest {

    private static final String ED2023_PROTO = """
        edition = "2023";
        package omnibuf.ed23;

        import "google/protobuf/descriptor.proto";
        import "google/protobuf/java_features.proto";

        option java_package = "com.omnibuf.editions.e2023";
        option java_multiple_files = true;
        option features.(pb.java).utf8_validation = VERIFY;

        extend google.protobuf.MessageOptions {
          string custom_tag = 50001;
        }

        enum OpenStatus {
          OPEN_STATUS_UNSPECIFIED = 0;
          OPEN_STATUS_ACTIVE = 1;
          OPEN_STATUS_PAUSED = 2;
        }

        message Child {
          int32 code = 1 [default = 0];
          string label = 2 [default = ""];
          bool flag = 3 [default = false];
        }

        message LegacyJsonNote {
          option features.json_format = LEGACY_BEST_EFFORT;
          string note = 1;
        }

        message DelimNode {
          int32 id = 1;
          DelimNode next = 2 [features.message_encoding = DELIMITED];
        }

        message Ed2023Event {
          option (custom_tag) = "ed2023-ok";

          // Default EXPLICIT presence in Editions 2023 (with zero defaults allowed)
          int32 exp_i32 = 1 [default = 0];
          double exp_f64 = 2 [default = 0.0];
          bool exp_bool = 3 [default = false];
          string exp_str = 4 [default = ""];
          bytes exp_bytes = 5;
          OpenStatus exp_enum = 6 [default = OPEN_STATUS_UNSPECIFIED];

          // Field-level IMPLICIT overrides
          int32 imp_i32 = 7 [features.field_presence = IMPLICIT];
          bool imp_bool = 8 [features.field_presence = IMPLICIT];
          string imp_str = 9 [features.field_presence = IMPLICIT];

          // Repeated PACKED and EXPANDED, plus DELIMITED sub-messages
          repeated int32 packed_nums = 10 [features.repeated_field_encoding = PACKED];
          repeated int32 expanded_nums = 11 [features.repeated_field_encoding = EXPANDED];
          repeated Child children = 12;
          repeated Child delim_children = 13 [features.message_encoding = DELIMITED];

          // Maps
          map<string, int32> scores = 14;
          map<string, Child> child_map = 15;

          // Singular LENGTH_PREFIXED and DELIMITED sub-messages
          Child child = 16;
          Child delim_child = 17 [features.message_encoding = DELIMITED];

          // Oneof with scalars and DELIMITED sub-message
          oneof choice {
            int32 choice_i32 = 20;
            bool choice_bool = 21;
            string choice_str = 22;
            bytes choice_bytes = 23;
            Child choice_delim = 24 [features.message_encoding = DELIMITED];
          }

          LegacyJsonNote json_note = 25;
        }
        """;

    private static final String ED2024_PROTO = """
        edition = "2024";
        package omnibuf.ed24;

        import option "google/protobuf/java_features.proto";

        option java_package = "com.omnibuf.editions.e2024";
        option features.default_symbol_visibility = STRICT;
        option features.enforce_naming_style = STYLE2024;

        export enum Priority {
          PRIORITY_UNSPECIFIED = 0;
          PRIORITY_LOW = 1;
          PRIORITY_HIGH = 2;
        }

        message LocalDetail {
          int32 count = 1;
          string info = 2;
        }

        export message Ed2024Record {
          option features.(pb.java).nest_in_file_class = NO;

          int32 exp_i32 = 1;
          double exp_f64 = 2;
          bool exp_bool = 3;
          string exp_str = 4;
          bytes exp_bytes = 5;
          Priority exp_enum = 6;

          int32 imp_i32 = 7 [features.field_presence = IMPLICIT];
          bool imp_bool = 8 [features.field_presence = IMPLICIT];

          repeated int32 expanded_nums = 9 [features.repeated_field_encoding = EXPANDED];
          repeated LocalDetail delim_details = 10 [features.message_encoding = DELIMITED];
          map<string, int32> counters = 11;
          map<string, LocalDetail> detail_map = 12;

          LocalDetail delim_detail = 13 [features.message_encoding = DELIMITED];

          oneof payload {
            int32 p_i32 = 14;
            string p_str = 15;
            bool p_bool = 16;
          }
        }
        """;

    private static final String ED2026_PROTO = """
        edition = "2026";
        package omnibuf.ed26;

        option java_package = "com.omnibuf.editions.e2026";
        option features.enforce_naming_style = STYLE2026;
        option features.default_symbol_visibility = STRICT;
        option features.field_presence = IMPLICIT;

        export enum Mode {
          MODE_UNSPECIFIED = 0;
          MODE_FAST = 1;
          MODE_SAFE = 2;
        }

        export message SubItem {
          option features.enforce_proto_limits = PROTO_LIMITS2026;
          int32 weight = 1 [features.field_presence = EXPLICIT];
          string tag = 2;
        }

        export message Ed2026Payload {
          option features.enforce_proto_limits = PROTO_LIMITS2026;

          // Inherited IMPLICIT from file
          int32 imp_i32 = 1;
          bool imp_bool = 2;
          string imp_str = 3;
          Mode imp_mode = 4;

          // Overridden EXPLICIT on field
          int32 exp_i32 = 5 [features.field_presence = EXPLICIT];
          double exp_f64 = 6 [features.field_presence = EXPLICIT];
          bool exp_bool = 7 [features.field_presence = EXPLICIT];
          string exp_str = 8 [features.field_presence = EXPLICIT];
          bytes exp_bytes = 9 [features.field_presence = EXPLICIT];
          Mode exp_mode = 10 [features.field_presence = EXPLICIT];

          // Repeated, maps, DELIMITED, and oneof
          repeated int32 expanded_nums = 11 [features.repeated_field_encoding = EXPANDED];
          repeated SubItem delim_items = 12 [features.message_encoding = DELIMITED];
          map<string, int32> counts = 13;
          map<string, SubItem> item_map = 14;
          SubItem delim_sub = 15 [features.message_encoding = DELIMITED];

          oneof variant {
            int32 v_i32 = 16;
            bool v_bool = 17;
            string v_str = 18;
          }
        }
        """;

    @Test
    @SuppressWarnings("unchecked")
    void edition2023EndToEndPluginReflectionOpaqueAndByteEquivalence(@TempDir Path tempDir) throws Exception {
        CompiledProtoBundle bundle = compileProtoEndToEnd(tempDir, "ed2023.proto", ED2023_PROTO, true);
        assertEquals(3L, bundle.pluginResponse.getSupportedFeatures());
        assertEquals(Edition.EDITION_2023_VALUE, bundle.pluginResponse.getMinimumEdition());
        assertEquals(Edition.EDITION_2026_VALUE, bundle.pluginResponse.getMaximumEdition());

        Descriptor eventDesc = bundle.fileDescriptor.findMessageTypeByName("Ed2023Event");
        Descriptor childDesc = bundle.fileDescriptor.findMessageTypeByName("Child");
        Descriptor delimNodeDesc = bundle.fileDescriptor.findMessageTypeByName("DelimNode");
        EnumDescriptor statusDesc = bundle.fileDescriptor.findEnumTypeByName("OpenStatus");

        // Verify protoc actually encoded DELIMITED fields as FieldDescriptor.Type.GROUP
        assertEquals(FieldDescriptor.Type.GROUP, eventDesc.findFieldByName("delim_child").getType());
        assertEquals(FieldDescriptor.Type.GROUP, eventDesc.findFieldByName("delim_children").getType());
        assertEquals(FieldDescriptor.Type.GROUP, eventDesc.findFieldByName("choice_delim").getType());

        try (URLClassLoader loader = bundle.newClassLoader()) {
            Class<?> modelCls = loader.loadClass("com.omnibuf.editions.e2023.OmniBufEd2023Event");
            Class<?> codecCls = loader.loadClass("com.omnibuf.editions.e2023.Ed2023EventOmniBuf");
            Class<?> opaqueCls = loader.loadClass("com.omnibuf.editions.e2023.OpaqueEd2023Event");
            Class<?> childModelCls = loader.loadClass("com.omnibuf.editions.e2023.OmniBufChild");
            Class<?> opaqueChildCls = loader.loadClass("com.omnibuf.editions.e2023.OpaqueChild");
            Class<?> pbEventCls = loader.loadClass("com.omnibuf.editions.e2023.Ed2023Event");
            Class<?> pbChildCls = loader.loadClass("com.omnibuf.editions.e2023.Child");

            Method marshalOpts = codecCls.getMethod("marshal", modelCls, MarshalOptions.class);
            Method unmarshalBytes = codecCls.getMethod("unmarshal", byte[].class);

            // 1. Absent vs explicit-zero/false/empty/enum-0 presence & byte equivalence
            Object emptyModel = modelCls.getConstructor().newInstance();
            byte[] emptyT1 = (byte[]) marshalOpts.invoke(null, emptyModel, MarshalOptions.DETERMINISTIC);
            DynamicMessage emptyDyn = DynamicMessage.getDefaultInstance(eventDesc);
            byte[] emptyT2Dyn = OmniBufProtobuf.marshal(emptyDyn, MarshalOptions.DETERMINISTIC);
            Message emptyPb = (Message) pbEventCls.getMethod("getDefaultInstance").invoke(null);
            byte[] emptyT2Pb = OmniBufProtobuf.marshal(emptyPb, MarshalOptions.DETERMINISTIC);
            assertArrayEquals(emptyT1, emptyT2Dyn);
            assertArrayEquals(emptyT1, emptyT2Pb);

            // Explicit zero/false/""/byte[0]/enum-0 on all EXPLICIT fields
            Object zeroModel = modelCls.getConstructor().newInstance();
            modelCls.getMethod("setExpI32", int.class).invoke(zeroModel, 0);
            modelCls.getMethod("setExpF64", double.class).invoke(zeroModel, 0.0d);
            modelCls.getMethod("setExpBool", boolean.class).invoke(zeroModel, false);
            modelCls.getMethod("setExpStr", String.class).invoke(zeroModel, "");
            modelCls.getMethod("setExpBytes", byte[].class).invoke(zeroModel, new byte[0]);
            modelCls.getMethod("setExpEnum", int.class).invoke(zeroModel, 0);
            byte[] zeroT1 = (byte[]) marshalOpts.invoke(null, zeroModel, MarshalOptions.DETERMINISTIC);

            DynamicMessage zeroDyn = DynamicMessage.newBuilder(eventDesc)
                .setField(eventDesc.findFieldByName("exp_i32"), 0)
                .setField(eventDesc.findFieldByName("exp_f64"), 0.0d)
                .setField(eventDesc.findFieldByName("exp_bool"), false)
                .setField(eventDesc.findFieldByName("exp_str"), "")
                .setField(eventDesc.findFieldByName("exp_bytes"), ByteString.EMPTY)
                .setField(eventDesc.findFieldByName("exp_enum"), statusDesc.findValueByNumber(0))
                .build();
            byte[] zeroT2Dyn = OmniBufProtobuf.marshal(zeroDyn, MarshalOptions.DETERMINISTIC);

            Message.Builder zeroPbBuilder = emptyPb.newBuilderForType();
            zeroPbBuilder.setField(zeroPbBuilder.getDescriptorForType().findFieldByName("exp_i32"), 0);
            zeroPbBuilder.setField(zeroPbBuilder.getDescriptorForType().findFieldByName("exp_f64"), 0.0d);
            zeroPbBuilder.setField(zeroPbBuilder.getDescriptorForType().findFieldByName("exp_bool"), false);
            zeroPbBuilder.setField(zeroPbBuilder.getDescriptorForType().findFieldByName("exp_str"), "");
            zeroPbBuilder.setField(zeroPbBuilder.getDescriptorForType().findFieldByName("exp_bytes"), ByteString.EMPTY);
            zeroPbBuilder.setField(
                zeroPbBuilder.getDescriptorForType().findFieldByName("exp_enum"),
                zeroPbBuilder.getDescriptorForType().findFieldByName("exp_enum").getEnumType().findValueByNumber(0)
            );
            byte[] zeroT2Pb = OmniBufProtobuf.marshal(zeroPbBuilder.build(), MarshalOptions.DETERMINISTIC);

            // Wire bytes MUST differ between absent and explicit-zero
            assertFalse(Arrays.equals(emptyT1, zeroT1), "explicit zero fields must produce different wire bytes than absent");
            assertArrayEquals(zeroT1, zeroT2Dyn);
            assertArrayEquals(zeroT1, zeroT2Pb);

            // Round-trip absent -> has*() is false
            Object decEmptyT1 = unmarshalBytes.invoke(null, (Object) emptyT1);
            assertFalse((Boolean) modelCls.getMethod("hasExpI32").invoke(decEmptyT1));
            assertFalse((Boolean) modelCls.getMethod("hasExpF64").invoke(decEmptyT1));
            assertFalse((Boolean) modelCls.getMethod("hasExpBool").invoke(decEmptyT1));
            assertFalse((Boolean) modelCls.getMethod("hasExpStr").invoke(decEmptyT1));
            assertFalse((Boolean) modelCls.getMethod("hasExpBytes").invoke(decEmptyT1));
            assertFalse((Boolean) modelCls.getMethod("hasExpEnum").invoke(decEmptyT1));

            DynamicMessage decEmptyDyn = OmniBufProtobuf.unmarshal(emptyT1, emptyDyn);
            assertFalse(decEmptyDyn.hasField(eventDesc.findFieldByName("exp_i32")));
            assertFalse(decEmptyDyn.hasField(eventDesc.findFieldByName("exp_bool")));
            assertFalse(decEmptyDyn.hasField(eventDesc.findFieldByName("exp_str")));
            assertFalse(decEmptyDyn.hasField(eventDesc.findFieldByName("exp_bytes")));
            assertFalse(decEmptyDyn.hasField(eventDesc.findFieldByName("exp_enum")));

            // Round-trip explicit-zero -> has*() is true and values are zero/false/""/empty/0
            Object decZeroT1 = unmarshalBytes.invoke(null, (Object) zeroT1);
            assertTrue((Boolean) modelCls.getMethod("hasExpI32").invoke(decZeroT1));
            assertEquals(0, modelCls.getMethod("getExpI32").invoke(decZeroT1));
            assertTrue((Boolean) modelCls.getMethod("hasExpF64").invoke(decZeroT1));
            assertEquals(0.0d, modelCls.getMethod("getExpF64").invoke(decZeroT1));
            assertTrue((Boolean) modelCls.getMethod("hasExpBool").invoke(decZeroT1));
            assertEquals(false, modelCls.getMethod("getExpBool").invoke(decZeroT1));
            assertTrue((Boolean) modelCls.getMethod("hasExpStr").invoke(decZeroT1));
            assertEquals("", modelCls.getMethod("getExpStr").invoke(decZeroT1));
            assertTrue((Boolean) modelCls.getMethod("hasExpBytes").invoke(decZeroT1));
            assertArrayEquals(new byte[0], (byte[]) modelCls.getMethod("getExpBytes").invoke(decZeroT1));
            assertTrue((Boolean) modelCls.getMethod("hasExpEnum").invoke(decZeroT1));
            assertEquals(0, modelCls.getMethod("getExpEnum").invoke(decZeroT1));

            DynamicMessage decZeroDyn = OmniBufProtobuf.unmarshal(zeroT1, emptyDyn);
            assertTrue(decZeroDyn.hasField(eventDesc.findFieldByName("exp_i32")));
            assertEquals(0, decZeroDyn.getField(eventDesc.findFieldByName("exp_i32")));
            assertTrue(decZeroDyn.hasField(eventDesc.findFieldByName("exp_bool")));
            assertEquals(false, decZeroDyn.getField(eventDesc.findFieldByName("exp_bool")));
            assertTrue(decZeroDyn.hasField(eventDesc.findFieldByName("exp_str")));
            assertEquals("", decZeroDyn.getField(eventDesc.findFieldByName("exp_str")));
            assertTrue(decZeroDyn.hasField(eventDesc.findFieldByName("exp_bytes")));
            assertEquals(ByteString.EMPTY, decZeroDyn.getField(eventDesc.findFieldByName("exp_bytes")));
            assertTrue(decZeroDyn.hasField(eventDesc.findFieldByName("exp_enum")));
            assertEquals(0, ((com.google.protobuf.Descriptors.EnumValueDescriptor)
                decZeroDyn.getField(eventDesc.findFieldByName("exp_enum"))).getNumber());

            Message decZeroPb = OmniBufProtobuf.unmarshal(zeroT1, emptyPb);
            assertTrue((Boolean) pbEventCls.getMethod("hasExpI32").invoke(decZeroPb));
            assertTrue((Boolean) pbEventCls.getMethod("hasExpBool").invoke(decZeroPb));
            assertEquals(false, pbEventCls.getMethod("getExpBool").invoke(decZeroPb));
            assertTrue((Boolean) pbEventCls.getMethod("hasExpStr").invoke(decZeroPb));
            assertEquals("", pbEventCls.getMethod("getExpStr").invoke(decZeroPb));

            // 2. Full rich payload: open unknown enum (99), packed/expanded repeated, maps,
            //    DELIMITED singular & repeated, oneof switching & active zero/empty/delimited
            Object c1 = childModelCls.getConstructor().newInstance();
            childModelCls.getMethod("setCode", int.class).invoke(c1, 0);
            childModelCls.getMethod("setLabel", String.class).invoke(c1, "alpha");
            childModelCls.getMethod("setFlag", boolean.class).invoke(c1, false);

            Object c2 = childModelCls.getConstructor().newInstance();
            childModelCls.getMethod("setCode", int.class).invoke(c2, 77);
            childModelCls.getMethod("setLabel", String.class).invoke(c2, "beta");
            childModelCls.getMethod("setFlag", boolean.class).invoke(c2, true);

            Object rich = modelCls.getConstructor().newInstance();
            modelCls.getMethod("setExpI32", int.class).invoke(rich, -42);
            modelCls.getMethod("setExpF64", double.class).invoke(rich, 3.25d);
            modelCls.getMethod("setExpBool", boolean.class).invoke(rich, true);
            modelCls.getMethod("setExpStr", String.class).invoke(rich, "ed2023");
            modelCls.getMethod("setExpBytes", byte[].class).invoke(rich, new byte[] {1, 2, 3});
            modelCls.getMethod("setExpEnum", int.class).invoke(rich, 99); // unknown open enum value
            modelCls.getMethod("setImpI32", int.class).invoke(rich, 0);
            modelCls.getMethod("setImpBool", boolean.class).invoke(rich, false);
            modelCls.getMethod("setImpStr", String.class).invoke(rich, "imp");
            modelCls.getMethod("setPackedNums", List.class).invoke(rich, List.of(1, -2, 3));
            modelCls.getMethod("setExpandedNums", List.class).invoke(rich, List.of(10, -20, 30));
            modelCls.getMethod("setChildren", List.class).invoke(rich, List.of(c1));
            modelCls.getMethod("setDelimChildren", List.class).invoke(rich, List.of(c1, c2));
            Map<String, Integer> scores = new LinkedHashMap<>();
            scores.put("b", 2);
            scores.put("a", 1);
            modelCls.getMethod("setScores", Map.class).invoke(rich, scores);
            Map<String, Object> childMap = new LinkedHashMap<>();
            childMap.put("k1", c1);
            childMap.put("k2", c2);
            modelCls.getMethod("setChildMap", Map.class).invoke(rich, childMap);
            modelCls.getMethod("setChild", childModelCls).invoke(rich, c1);
            modelCls.getMethod("setDelimChild", childModelCls).invoke(rich, c2);
            // Switch oneof: set choice_i32 = 0 first, then switch to choice_delim = c1
            modelCls.getMethod("setChoiceI32", int.class).invoke(rich, 0);
            assertEquals(20, modelCls.getMethod("getChoiceCase").invoke(rich));
            modelCls.getMethod("setChoiceDelim", childModelCls).invoke(rich, c1);
            assertEquals(24, modelCls.getMethod("getChoiceCase").invoke(rich));
            assertFalse((Boolean) modelCls.getMethod("hasChoiceI32").invoke(rich));

            byte[] richT1 = (byte[]) marshalOpts.invoke(null, rich, MarshalOptions.DETERMINISTIC);
            byte[] richSelfDescT1 = (byte[]) marshalOpts.invoke(null, rich, MarshalOptions.SELF_DESCRIBING);

            // Build identical DynamicMessage and GeneratedMessage
            DynamicMessage dynC1 = DynamicMessage.newBuilder(childDesc)
                .setField(childDesc.findFieldByName("code"), 0)
                .setField(childDesc.findFieldByName("label"), "alpha")
                .setField(childDesc.findFieldByName("flag"), false)
                .build();
            DynamicMessage dynC2 = DynamicMessage.newBuilder(childDesc)
                .setField(childDesc.findFieldByName("code"), 77)
                .setField(childDesc.findFieldByName("label"), "beta")
                .setField(childDesc.findFieldByName("flag"), true)
                .build();

            DynamicMessage.Builder dynB = DynamicMessage.newBuilder(eventDesc)
                .setField(eventDesc.findFieldByName("exp_i32"), -42)
                .setField(eventDesc.findFieldByName("exp_f64"), 3.25d)
                .setField(eventDesc.findFieldByName("exp_bool"), true)
                .setField(eventDesc.findFieldByName("exp_str"), "ed2023")
                .setField(eventDesc.findFieldByName("exp_bytes"), ByteString.copyFrom(new byte[] {1, 2, 3}))
                .setField(eventDesc.findFieldByName("exp_enum"), statusDesc.findValueByNumberCreatingIfUnknown(99))
                .setField(eventDesc.findFieldByName("imp_i32"), 0)
                .setField(eventDesc.findFieldByName("imp_bool"), false)
                .setField(eventDesc.findFieldByName("imp_str"), "imp")
                .addRepeatedField(eventDesc.findFieldByName("packed_nums"), 1)
                .addRepeatedField(eventDesc.findFieldByName("packed_nums"), -2)
                .addRepeatedField(eventDesc.findFieldByName("packed_nums"), 3)
                .addRepeatedField(eventDesc.findFieldByName("expanded_nums"), 10)
                .addRepeatedField(eventDesc.findFieldByName("expanded_nums"), -20)
                .addRepeatedField(eventDesc.findFieldByName("expanded_nums"), 30)
                .addRepeatedField(eventDesc.findFieldByName("children"), dynC1)
                .addRepeatedField(eventDesc.findFieldByName("delim_children"), dynC1)
                .addRepeatedField(eventDesc.findFieldByName("delim_children"), dynC2)
                .setField(eventDesc.findFieldByName("child"), dynC1)
                .setField(eventDesc.findFieldByName("delim_child"), dynC2)
                .setField(eventDesc.findFieldByName("choice_delim"), dynC1);
            addMapEntry(dynB, eventDesc.findFieldByName("scores"), "b", 2);
            addMapEntry(dynB, eventDesc.findFieldByName("scores"), "a", 1);
            addMapEntry(dynB, eventDesc.findFieldByName("child_map"), "k1", dynC1);
            addMapEntry(dynB, eventDesc.findFieldByName("child_map"), "k2", dynC2);
            DynamicMessage richDyn = dynB.build();

            byte[] richT2Dyn = OmniBufProtobuf.marshal(richDyn, MarshalOptions.DETERMINISTIC);
            byte[] richSelfDescT2Dyn = OmniBufProtobuf.marshal(richDyn, MarshalOptions.SELF_DESCRIBING);
            assertArrayEquals(richT1, richT2Dyn);
            assertArrayEquals(richSelfDescT1, richSelfDescT2Dyn);

            // Cross-decode into generated protoc --java_out Message and re-marshal byte-for-byte
            Message decRichPb = OmniBufProtobuf.unmarshal(richT1, emptyPb);
            assertEquals(99, pbEventCls.getMethod("getExpEnumValue").invoke(decRichPb));
            assertEquals(List.of(10, -20, 30), pbEventCls.getMethod("getExpandedNumsList").invoke(decRichPb));
            assertEquals(2, ((List<?>) pbEventCls.getMethod("getDelimChildrenList").invoke(decRichPb)).size());
            Object pbDelimChild = pbEventCls.getMethod("getDelimChild").invoke(decRichPb);
            assertEquals(77, pbChildCls.getMethod("getCode").invoke(pbDelimChild));
            assertEquals("beta", pbChildCls.getMethod("getLabel").invoke(pbDelimChild));
            assertArrayEquals(richT1, OmniBufProtobuf.marshal(decRichPb, MarshalOptions.DETERMINISTIC));

            // Verify blind decode with Field #0 resolves DELIMITED child field names
            Map<String, Object> blind = OmniBufBlind.decodeBlind(richSelfDescT2Dyn);
            Map<String, Object> blindDelimChild = (Map<String, Object>) blind.get("delim_child");
            assertEquals(77L, blindDelimChild.get("code"));
            assertEquals("beta", blindDelimChild.get("label"));
            List<Map<String, Object>> blindDelimChildren = (List<Map<String, Object>>) blind.get("delim_children");
            assertEquals(2, blindDelimChildren.size());
            assertEquals("alpha", blindDelimChildren.get(0).get("label"));

            // 3. Opaque view: absent vs present-zero, setting explicit zero/false/empty, clearing, splicing
            Object opaqueZero = opaqueCls.getConstructor(byte[].class).newInstance((Object) zeroT1);
            assertTrue((Boolean) opaqueCls.getMethod("hasExpI32").invoke(opaqueZero));
            assertEquals(0, opaqueCls.getMethod("getExpI32").invoke(opaqueZero));
            assertTrue((Boolean) opaqueCls.getMethod("hasExpBool").invoke(opaqueZero));
            assertEquals(false, opaqueCls.getMethod("getExpBool").invoke(opaqueZero));
            assertTrue((Boolean) opaqueCls.getMethod("hasExpStr").invoke(opaqueZero));
            assertEquals("", opaqueCls.getMethod("getExpStr").invoke(opaqueZero));
            assertTrue((Boolean) opaqueCls.getMethod("hasExpBytes").invoke(opaqueZero));
            assertArrayEquals(new byte[0], (byte[]) opaqueCls.getMethod("getExpBytes").invoke(opaqueZero));
            assertTrue((Boolean) opaqueCls.getMethod("hasExpEnum").invoke(opaqueZero));
            assertEquals(0, opaqueCls.getMethod("getExpEnum").invoke(opaqueZero));
            assertFalse((Boolean) opaqueCls.getMethod("hasDelimChild").invoke(opaqueZero));

            Object opaqueEmpty = opaqueCls.getConstructor(byte[].class).newInstance((Object) emptyT1);
            assertFalse((Boolean) opaqueCls.getMethod("hasExpI32").invoke(opaqueEmpty));
            assertFalse((Boolean) opaqueCls.getMethod("hasExpBool").invoke(opaqueEmpty));
            assertFalse((Boolean) opaqueCls.getMethod("hasExpStr").invoke(opaqueEmpty));
            assertFalse((Boolean) opaqueCls.getMethod("hasExpBytes").invoke(opaqueEmpty));
            assertFalse((Boolean) opaqueCls.getMethod("hasExpEnum").invoke(opaqueEmpty));

            // Mutate opaqueEmpty: set explicit zero/false/""/byte[0]/unknown-enum + active oneof empty string
            opaqueCls.getMethod("setExpI32", int.class).invoke(opaqueEmpty, 0);
            opaqueCls.getMethod("setExpBool", boolean.class).invoke(opaqueEmpty, false);
            opaqueCls.getMethod("setExpStr", String.class).invoke(opaqueEmpty, "");
            opaqueCls.getMethod("setExpBytes", byte[].class).invoke(opaqueEmpty, new byte[0]);
            opaqueCls.getMethod("setExpEnum", int.class).invoke(opaqueEmpty, 99);
            opaqueCls.getMethod("setChoiceStr", String.class).invoke(opaqueEmpty, "");
            assertTrue((Boolean) opaqueCls.getMethod("hasExpI32").invoke(opaqueEmpty));
            assertTrue((Boolean) opaqueCls.getMethod("hasExpBool").invoke(opaqueEmpty));
            assertEquals(22, opaqueCls.getMethod("getChoiceCase").invoke(opaqueEmpty));
            byte[] splicedEmpty = (byte[]) opaqueCls.getMethod("marshalValidated").invoke(opaqueEmpty);

            Object afterEmptySplice = unmarshalBytes.invoke(null, (Object) splicedEmpty);
            assertTrue((Boolean) modelCls.getMethod("hasExpI32").invoke(afterEmptySplice));
            assertEquals(0, modelCls.getMethod("getExpI32").invoke(afterEmptySplice));
            assertTrue((Boolean) modelCls.getMethod("hasExpBool").invoke(afterEmptySplice));
            assertEquals(false, modelCls.getMethod("getExpBool").invoke(afterEmptySplice));
            assertTrue((Boolean) modelCls.getMethod("hasExpStr").invoke(afterEmptySplice));
            assertEquals("", modelCls.getMethod("getExpStr").invoke(afterEmptySplice));
            assertTrue((Boolean) modelCls.getMethod("hasExpBytes").invoke(afterEmptySplice));
            assertArrayEquals(new byte[0], (byte[]) modelCls.getMethod("getExpBytes").invoke(afterEmptySplice));
            assertTrue((Boolean) modelCls.getMethod("hasExpEnum").invoke(afterEmptySplice));
            assertEquals(99, modelCls.getMethod("getExpEnum").invoke(afterEmptySplice));
            assertEquals(22, modelCls.getMethod("getChoiceCase").invoke(afterEmptySplice));
            assertEquals("", modelCls.getMethod("getChoiceStr").invoke(afterEmptySplice));

            // Mutate opaqueRich: read DELIMITED child, clear explicit scalars & DELIMITED child, switch oneof to bool=false
            Object opaqueRich = opaqueCls.getConstructor(byte[].class).newInstance((Object) richT1);
            assertTrue((Boolean) opaqueCls.getMethod("hasDelimChild").invoke(opaqueRich));
            Object opDelimChild = opaqueCls.getMethod("getDelimChild").invoke(opaqueRich);
            assertEquals(77, opaqueChildCls.getMethod("getCode").invoke(opDelimChild));
            assertEquals(99, opaqueCls.getMethod("getExpEnum").invoke(opaqueRich));

            opaqueCls.getMethod("clearExpI32").invoke(opaqueRich);
            opaqueCls.getMethod("clearExpStr").invoke(opaqueRich);
            opaqueCls.getMethod("clearDelimChild").invoke(opaqueRich);
            opaqueCls.getMethod("setChoiceBool", boolean.class).invoke(opaqueRich, false);
            assertFalse((Boolean) opaqueCls.getMethod("hasExpI32").invoke(opaqueRich));
            assertFalse((Boolean) opaqueCls.getMethod("hasExpStr").invoke(opaqueRich));
            assertFalse((Boolean) opaqueCls.getMethod("hasDelimChild").invoke(opaqueRich));
            assertEquals(21, opaqueCls.getMethod("getChoiceCase").invoke(opaqueRich));
            assertEquals(false, opaqueCls.getMethod("getChoiceBool").invoke(opaqueRich));

            byte[] splicedRich = (byte[]) opaqueCls.getMethod("marshalValidated").invoke(opaqueRich);
            Object afterRichSplice = unmarshalBytes.invoke(null, (Object) splicedRich);
            assertFalse((Boolean) modelCls.getMethod("hasExpI32").invoke(afterRichSplice));
            assertFalse((Boolean) modelCls.getMethod("hasExpStr").invoke(afterRichSplice));
            assertNull(modelCls.getMethod("getDelimChild").invoke(afterRichSplice));
            assertEquals(21, modelCls.getMethod("getChoiceCase").invoke(afterRichSplice));
            assertEquals(false, modelCls.getMethod("getChoiceBool").invoke(afterRichSplice));
            assertNull(modelCls.getMethod("getChoiceDelim").invoke(afterRichSplice));
            assertEquals(99, modelCls.getMethod("getExpEnum").invoke(afterRichSplice));

            // 4. Cyclic DELIMITED message: cycle-safe validation + value SchemaMeta
            DynamicMessage leaf = DynamicMessage.newBuilder(delimNodeDesc)
                .setField(delimNodeDesc.findFieldByName("id"), 2)
                .build();
            DynamicMessage root = DynamicMessage.newBuilder(delimNodeDesc)
                .setField(delimNodeDesc.findFieldByName("id"), 1)
                .setField(delimNodeDesc.findFieldByName("next"), leaf)
                .build();
            byte[] cyclicBytes = OmniBufProtobuf.marshal(root, MarshalOptions.SELF_DESCRIBING);
            DynamicMessage decCyclic = OmniBufProtobuf.unmarshal(cyclicBytes, DynamicMessage.getDefaultInstance(delimNodeDesc));
            assertEquals(1, decCyclic.getField(delimNodeDesc.findFieldByName("id")));
            DynamicMessage decNext = (DynamicMessage) decCyclic.getField(delimNodeDesc.findFieldByName("next"));
            assertEquals(2, decNext.getField(delimNodeDesc.findFieldByName("id")));
            Map<String, Object> blindCyclic = OmniBufBlind.decodeBlind(cyclicBytes);
            assertEquals(1L, blindCyclic.get("id"));
            assertEquals(2L, ((Map<String, Object>) blindCyclic.get("next")).get("id"));
        }
    }

    @Test
    void edition2024EndToEndStrictVisibilityJavaFeaturesAndByteEquivalence(@TempDir Path tempDir) throws Exception {
        CompiledProtoBundle bundle = compileProtoEndToEnd(tempDir, "ed2024.proto", ED2024_PROTO, true);
        Descriptor recDesc = bundle.fileDescriptor.findMessageTypeByName("Ed2024Record");
        Descriptor detailDesc = bundle.fileDescriptor.findMessageTypeByName("LocalDetail");
        EnumDescriptor prioDesc = bundle.fileDescriptor.findEnumTypeByName("Priority");

        assertEquals(FieldDescriptor.Type.GROUP, recDesc.findFieldByName("delim_detail").getType());
        assertEquals(FieldDescriptor.Type.GROUP, recDesc.findFieldByName("delim_details").getType());

        try (URLClassLoader loader = bundle.newClassLoader()) {
            Class<?> modelCls = loader.loadClass("com.omnibuf.editions.e2024.OmniBufEd2024Record");
            Class<?> codecCls = loader.loadClass("com.omnibuf.editions.e2024.Ed2024RecordOmniBuf");
            Class<?> opaqueCls = loader.loadClass("com.omnibuf.editions.e2024.OpaqueEd2024Record");
            Class<?> detailModelCls = loader.loadClass("com.omnibuf.editions.e2024.OmniBufLocalDetail");
            Class<?> pbRecCls = loader.loadClass("com.omnibuf.editions.e2024.Ed2024Record");

            Method marshalOpts = codecCls.getMethod("marshal", modelCls, MarshalOptions.class);
            Method unmarshalBytes = codecCls.getMethod("unmarshal", byte[].class);

            Object det = detailModelCls.getConstructor().newInstance();
            detailModelCls.getMethod("setCount", int.class).invoke(det, 0);
            detailModelCls.getMethod("setInfo", String.class).invoke(det, "local-detail");

            Object rec = modelCls.getConstructor().newInstance();
            modelCls.getMethod("setExpI32", int.class).invoke(rec, 0);
            modelCls.getMethod("setExpF64", double.class).invoke(rec, 0.0d);
            modelCls.getMethod("setExpBool", boolean.class).invoke(rec, false);
            modelCls.getMethod("setExpStr", String.class).invoke(rec, "");
            modelCls.getMethod("setExpBytes", byte[].class).invoke(rec, new byte[0]);
            modelCls.getMethod("setExpEnum", int.class).invoke(rec, 123); // unknown open enum
            modelCls.getMethod("setImpI32", int.class).invoke(rec, 0);
            modelCls.getMethod("setImpBool", boolean.class).invoke(rec, false);
            modelCls.getMethod("setExpandedNums", List.class).invoke(rec, List.of(5, 6));
            modelCls.getMethod("setDelimDetails", List.class).invoke(rec, List.of(det));
            modelCls.getMethod("setCounters", Map.class).invoke(rec, Map.of("x", 10));
            modelCls.getMethod("setDetailMap", Map.class).invoke(rec, Map.of("d", det));
            modelCls.getMethod("setDelimDetail", detailModelCls).invoke(rec, det);
            modelCls.getMethod("setPBool", boolean.class).invoke(rec, false);

            byte[] wireT1 = (byte[]) marshalOpts.invoke(null, rec, MarshalOptions.DETERMINISTIC);

            DynamicMessage dynDet = DynamicMessage.newBuilder(detailDesc)
                .setField(detailDesc.findFieldByName("count"), 0)
                .setField(detailDesc.findFieldByName("info"), "local-detail")
                .build();
            DynamicMessage.Builder dynB = DynamicMessage.newBuilder(recDesc)
                .setField(recDesc.findFieldByName("exp_i32"), 0)
                .setField(recDesc.findFieldByName("exp_f64"), 0.0d)
                .setField(recDesc.findFieldByName("exp_bool"), false)
                .setField(recDesc.findFieldByName("exp_str"), "")
                .setField(recDesc.findFieldByName("exp_bytes"), ByteString.EMPTY)
                .setField(recDesc.findFieldByName("exp_enum"), prioDesc.findValueByNumberCreatingIfUnknown(123))
                .setField(recDesc.findFieldByName("imp_i32"), 0)
                .setField(recDesc.findFieldByName("imp_bool"), false)
                .addRepeatedField(recDesc.findFieldByName("expanded_nums"), 5)
                .addRepeatedField(recDesc.findFieldByName("expanded_nums"), 6)
                .addRepeatedField(recDesc.findFieldByName("delim_details"), dynDet)
                .setField(recDesc.findFieldByName("delim_detail"), dynDet)
                .setField(recDesc.findFieldByName("p_bool"), false);
            addMapEntry(dynB, recDesc.findFieldByName("counters"), "x", 10);
            addMapEntry(dynB, recDesc.findFieldByName("detail_map"), "d", dynDet);

            byte[] wireT2Dyn = OmniBufProtobuf.marshal(dynB.build(), MarshalOptions.DETERMINISTIC);
            assertArrayEquals(wireT1, wireT2Dyn);

            // Cross-decode into real protoc --java_out Edition 2024 GeneratedMessage (top-level class!)
            Message defaultPb = (Message) pbRecCls.getMethod("getDefaultInstance").invoke(null);
            Message decPb = OmniBufProtobuf.unmarshal(wireT1, defaultPb);
            assertTrue((Boolean) pbRecCls.getMethod("hasExpI32").invoke(decPb));
            assertEquals(0, pbRecCls.getMethod("getExpI32").invoke(decPb));
            assertTrue((Boolean) pbRecCls.getMethod("hasExpBool").invoke(decPb));
            assertEquals(false, pbRecCls.getMethod("getExpBool").invoke(decPb));
            assertEquals(123, pbRecCls.getMethod("getExpEnumValue").invoke(decPb));
            assertTrue((Boolean) pbRecCls.getMethod("hasPBool").invoke(decPb));
            assertEquals(false, pbRecCls.getMethod("getPBool").invoke(decPb));
            assertArrayEquals(wireT1, OmniBufProtobuf.marshal(decPb, MarshalOptions.DETERMINISTIC));

            // Opaque clear & splice on Edition 2024
            Object op = opaqueCls.getConstructor(byte[].class).newInstance((Object) wireT1);
            assertTrue((Boolean) opaqueCls.getMethod("hasExpBool").invoke(op));
            assertEquals(16, opaqueCls.getMethod("getPayloadCase").invoke(op));
            opaqueCls.getMethod("clearExpBool").invoke(op);
            opaqueCls.getMethod("clearPayload").invoke(op);
            byte[] spliced = (byte[]) opaqueCls.getMethod("marshalValidated").invoke(op);
            Object decSpliced = unmarshalBytes.invoke(null, (Object) spliced);
            assertFalse((Boolean) modelCls.getMethod("hasExpBool").invoke(decSpliced));
            assertEquals(0, modelCls.getMethod("getPayloadCase").invoke(decSpliced));
            assertEquals(123, modelCls.getMethod("getExpEnum").invoke(decSpliced));
        }
    }

    @Test
    void edition2026EndToEndFileImplicitFieldExplicitOverridesAndByteEquivalence(@TempDir Path tempDir)
            throws Exception {
        CompiledProtoBundle bundle = compileProtoEndToEnd(tempDir, "ed2026.proto", ED2026_PROTO, true);
        Descriptor payloadDesc = bundle.fileDescriptor.findMessageTypeByName("Ed2026Payload");
        Descriptor subDesc = bundle.fileDescriptor.findMessageTypeByName("SubItem");
        EnumDescriptor modeDesc = bundle.fileDescriptor.findEnumTypeByName("Mode");

        // File sets field_presence = IMPLICIT; verify inherited vs overridden presence
        assertFalse(payloadDesc.findFieldByName("imp_i32").hasPresence());
        assertFalse(payloadDesc.findFieldByName("imp_bool").hasPresence());
        assertFalse(payloadDesc.findFieldByName("imp_str").hasPresence());
        assertFalse(payloadDesc.findFieldByName("imp_mode").hasPresence());
        assertTrue(payloadDesc.findFieldByName("exp_i32").hasPresence());
        assertTrue(payloadDesc.findFieldByName("exp_bool").hasPresence());
        assertTrue(payloadDesc.findFieldByName("exp_str").hasPresence());
        assertTrue(payloadDesc.findFieldByName("exp_mode").hasPresence());
        assertTrue(subDesc.findFieldByName("weight").hasPresence());
        assertFalse(subDesc.findFieldByName("tag").hasPresence());

        try (URLClassLoader loader = bundle.newClassLoader()) {
            Class<?> modelCls = loader.loadClass("com.omnibuf.editions.e2026.OmniBufEd2026Payload");
            Class<?> codecCls = loader.loadClass("com.omnibuf.editions.e2026.Ed2026PayloadOmniBuf");
            Class<?> opaqueCls = loader.loadClass("com.omnibuf.editions.e2026.OpaqueEd2026Payload");
            Class<?> subModelCls = loader.loadClass("com.omnibuf.editions.e2026.OmniBufSubItem");
            Class<?> pbPayloadCls = loader.loadClass("com.omnibuf.editions.e2026.Ed2026Payload");

            Method marshalOpts = codecCls.getMethod("marshal", modelCls, MarshalOptions.class);
            Method unmarshalBytes = codecCls.getMethod("unmarshal", byte[].class);

            // Empty payload: IMPLICIT fields are always emitted; EXPLICIT fields are omitted
            Object emptyModel = modelCls.getConstructor().newInstance();
            byte[] emptyT1 = (byte[]) marshalOpts.invoke(null, emptyModel, MarshalOptions.DETERMINISTIC);
            DynamicMessage emptyDyn = DynamicMessage.getDefaultInstance(payloadDesc);
            byte[] emptyT2Dyn = OmniBufProtobuf.marshal(emptyDyn, MarshalOptions.DETERMINISTIC);
            Message emptyPb = (Message) pbPayloadCls.getMethod("getDefaultInstance").invoke(null);
            byte[] emptyT2Pb = OmniBufProtobuf.marshal(emptyPb, MarshalOptions.DETERMINISTIC);
            assertArrayEquals(emptyT1, emptyT2Dyn);
            assertArrayEquals(emptyT1, emptyT2Pb);
            assertTrue(emptyT1.length > 0, "implicit-presence fields must be emitted on the wire");

            // Verify implicit fields do NOT generate has*() on model, while explicit fields DO
            assertThrows(NoSuchMethodException.class, () -> modelCls.getMethod("hasImpI32"));
            assertNotNull(modelCls.getMethod("hasExpI32"));
            assertNotNull(subModelCls.getMethod("hasWeight"));
            assertThrows(NoSuchMethodException.class, () -> subModelCls.getMethod("hasTag"));

            // Populated with explicit zeros + unknown open enum + DELIMITED + oneof
            Object sub = subModelCls.getConstructor().newInstance();
            subModelCls.getMethod("setWeight", int.class).invoke(sub, 0);
            subModelCls.getMethod("setTag", String.class).invoke(sub, "s26");

            Object full = modelCls.getConstructor().newInstance();
            modelCls.getMethod("setImpI32", int.class).invoke(full, 7);
            modelCls.getMethod("setImpBool", boolean.class).invoke(full, true);
            modelCls.getMethod("setImpStr", String.class).invoke(full, "file-imp");
            modelCls.getMethod("setImpMode", int.class).invoke(full, 404); // unknown open enum
            modelCls.getMethod("setExpI32", int.class).invoke(full, 0);
            modelCls.getMethod("setExpF64", double.class).invoke(full, 0.0d);
            modelCls.getMethod("setExpBool", boolean.class).invoke(full, false);
            modelCls.getMethod("setExpStr", String.class).invoke(full, "");
            modelCls.getMethod("setExpBytes", byte[].class).invoke(full, new byte[0]);
            modelCls.getMethod("setExpMode", int.class).invoke(full, 0);
            modelCls.getMethod("setExpandedNums", List.class).invoke(full, List.of(100, 200));
            modelCls.getMethod("setDelimItems", List.class).invoke(full, List.of(sub));
            modelCls.getMethod("setCounts", Map.class).invoke(full, Map.of("k", 3));
            modelCls.getMethod("setItemMap", Map.class).invoke(full, Map.of("m", sub));
            modelCls.getMethod("setDelimSub", subModelCls).invoke(full, sub);
            modelCls.getMethod("setVI32", int.class).invoke(full, 0);

            byte[] fullT1 = (byte[]) marshalOpts.invoke(null, full, MarshalOptions.DETERMINISTIC);
            assertNotEquals(emptyT1.length, fullT1.length);

            DynamicMessage dynSub = DynamicMessage.newBuilder(subDesc)
                .setField(subDesc.findFieldByName("weight"), 0)
                .setField(subDesc.findFieldByName("tag"), "s26")
                .build();
            DynamicMessage.Builder dynB = DynamicMessage.newBuilder(payloadDesc)
                .setField(payloadDesc.findFieldByName("imp_i32"), 7)
                .setField(payloadDesc.findFieldByName("imp_bool"), true)
                .setField(payloadDesc.findFieldByName("imp_str"), "file-imp")
                .setField(payloadDesc.findFieldByName("imp_mode"), modeDesc.findValueByNumberCreatingIfUnknown(404))
                .setField(payloadDesc.findFieldByName("exp_i32"), 0)
                .setField(payloadDesc.findFieldByName("exp_f64"), 0.0d)
                .setField(payloadDesc.findFieldByName("exp_bool"), false)
                .setField(payloadDesc.findFieldByName("exp_str"), "")
                .setField(payloadDesc.findFieldByName("exp_bytes"), ByteString.EMPTY)
                .setField(payloadDesc.findFieldByName("exp_mode"), modeDesc.findValueByNumber(0))
                .addRepeatedField(payloadDesc.findFieldByName("expanded_nums"), 100)
                .addRepeatedField(payloadDesc.findFieldByName("expanded_nums"), 200)
                .addRepeatedField(payloadDesc.findFieldByName("delim_items"), dynSub)
                .setField(payloadDesc.findFieldByName("delim_sub"), dynSub)
                .setField(payloadDesc.findFieldByName("v_i32"), 0);
            addMapEntry(dynB, payloadDesc.findFieldByName("counts"), "k", 3);
            addMapEntry(dynB, payloadDesc.findFieldByName("item_map"), "m", dynSub);

            byte[] fullT2Dyn = OmniBufProtobuf.marshal(dynB.build(), MarshalOptions.DETERMINISTIC);
            assertArrayEquals(fullT1, fullT2Dyn);

            // Cross-decode into protoc --java_out Edition 2026 GeneratedMessage
            Message decPb = OmniBufProtobuf.unmarshal(fullT1, emptyPb);
            assertEquals(404, pbPayloadCls.getMethod("getImpModeValue").invoke(decPb));
            assertTrue((Boolean) pbPayloadCls.getMethod("hasExpMode").invoke(decPb));
            assertEquals(0, pbPayloadCls.getMethod("getExpModeValue").invoke(decPb));
            assertTrue((Boolean) pbPayloadCls.getMethod("hasVI32").invoke(decPb));
            assertEquals(0, pbPayloadCls.getMethod("getVI32").invoke(decPb));
            assertArrayEquals(fullT1, OmniBufProtobuf.marshal(decPb, MarshalOptions.DETERMINISTIC));

            // Opaque splice switching oneof and clearing explicit zero
            Object op = opaqueCls.getConstructor(byte[].class).newInstance((Object) fullT1);
            assertTrue((Boolean) opaqueCls.getMethod("hasExpI32").invoke(op));
            opaqueCls.getMethod("clearExpI32").invoke(op);
            opaqueCls.getMethod("setVStr", String.class).invoke(op, "");
            byte[] spliced = (byte[]) opaqueCls.getMethod("marshalValidated").invoke(op);
            Object decSpliced = unmarshalBytes.invoke(null, (Object) spliced);
            assertFalse((Boolean) modelCls.getMethod("hasExpI32").invoke(decSpliced));
            assertEquals(18, modelCls.getMethod("getVariantCase").invoke(decSpliced));
            assertEquals("", modelCls.getMethod("getVStr").invoke(decSpliced));
            assertEquals(404, modelCls.getMethod("getImpMode").invoke(decSpliced));
        }
    }

    @Test
    void inheritedFeatureOverridesAcrossFileMessageNestedOneofAndField(@TempDir Path tempDir) throws Exception {
        // Synthetic Editions 2024 descriptor exercising inheritance across
        // file (IMPLICIT, UTF8 NONE) -> message (EXPLICIT, UTF8 VERIFY) -> nested (IMPLICIT)
        // -> oneof override -> field override (IMPLICIT / EXPLICIT)
        DescriptorProto nestedMsg = DescriptorProto.newBuilder()
            .setName("Inner")
            .setOptions(MessageOptions.newBuilder().setFeatures(
                FeatureSet.newBuilder().setFieldPresence(FeatureSet.FieldPresence.IMPLICIT)
            ))
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("inner_imp")
                    .setNumber(1)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_INT32)
            )
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("inner_exp")
                    .setNumber(2)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_INT32)
                    .setOptions(FieldOptions.newBuilder().setFeatures(
                        FeatureSet.newBuilder().setFieldPresence(FeatureSet.FieldPresence.EXPLICIT)
                    ))
            )
            .build();

        DescriptorProto outerMsg = DescriptorProto.newBuilder()
            .setName("Outer")
            .setOptions(MessageOptions.newBuilder().setFeatures(
                FeatureSet.newBuilder()
                    .setFieldPresence(FeatureSet.FieldPresence.EXPLICIT)
                    .setUtf8Validation(FeatureSet.Utf8Validation.VERIFY)
            ))
            .addNestedType(nestedMsg)
            .addOneofDecl(
                OneofDescriptorProto.newBuilder()
                    .setName("pick")
                    .setOptions(OneofOptions.newBuilder().setFeatures(
                        FeatureSet.newBuilder().setUtf8Validation(FeatureSet.Utf8Validation.VERIFY)
                    ))
            )
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("msg_exp")
                    .setNumber(1)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_INT32)
            )
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("field_imp")
                    .setNumber(2)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_INT32)
                    .setOptions(FieldOptions.newBuilder().setFeatures(
                        FeatureSet.newBuilder().setFieldPresence(FeatureSet.FieldPresence.IMPLICIT)
                    ))
            )
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("oneof_str")
                    .setNumber(3)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                    .setOneofIndex(0)
            )
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("inner")
                    .setNumber(4)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                    .setTypeName(".inh.test.Outer.Inner")
            )
            .build();

        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
            .setName("inheritance.proto")
            .setPackage("inh.test")
            .setSyntax("editions")
            .setEdition(Edition.EDITION_2024)
            .setOptions(FileOptions.newBuilder()
                .setJavaPackage("com.omnibuf.editions.inh")
                .setFeatures(
                    FeatureSet.newBuilder()
                        .setFieldPresence(FeatureSet.FieldPresence.IMPLICIT)
                        .setUtf8Validation(FeatureSet.Utf8Validation.NONE)
                ))
            .addMessageType(outerMsg)
            .build();

        CodeGeneratorResponse resp = ProtocGenOmniBufJava.generate(
            CodeGeneratorRequest.newBuilder()
                .addProtoFile(fileProto)
                .addFileToGenerate("inheritance.proto")
                .build()
        );
        assertFalse(resp.hasError(), () -> "Unexpected plugin error: " + resp.getError());

        Path classesDir = compileJavaSources(resp, tempDir);
        FileDescriptor fd = FileDescriptor.buildFrom(fileProto, new FileDescriptor[0]);
        Descriptor outerDesc = fd.findMessageTypeByName("Outer");
        Descriptor innerDesc = outerDesc.findNestedTypeByName("Inner");

        assertTrue(outerDesc.findFieldByName("msg_exp").hasPresence());
        assertFalse(outerDesc.findFieldByName("field_imp").hasPresence());
        assertFalse(innerDesc.findFieldByName("inner_imp").hasPresence());
        assertTrue(innerDesc.findFieldByName("inner_exp").hasPresence());

        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {classesDir.toUri().toURL()},
                EditionsEndToEndTest.class.getClassLoader())) {
            Class<?> outerModelCls = loader.loadClass("com.omnibuf.editions.inh.OmniBufOuter");
            Class<?> outerCodecCls = loader.loadClass("com.omnibuf.editions.inh.OuterOmniBuf");
            Class<?> innerModelCls = loader.loadClass("com.omnibuf.editions.inh.OmniBufOuter_Inner");

            assertNotNull(outerModelCls.getMethod("hasMsgExp"));
            assertThrows(NoSuchMethodException.class, () -> outerModelCls.getMethod("hasFieldImp"));
            assertThrows(NoSuchMethodException.class, () -> innerModelCls.getMethod("hasInnerImp"));
            assertNotNull(innerModelCls.getMethod("hasInnerExp"));

            Object inner = innerModelCls.getConstructor().newInstance();
            innerModelCls.getMethod("setInnerExp", int.class).invoke(inner, 0);
            Object outer = outerModelCls.getConstructor().newInstance();
            outerModelCls.getMethod("setMsgExp", int.class).invoke(outer, 0);
            outerModelCls.getMethod("setOneofStr", String.class).invoke(outer, "");
            outerModelCls.getMethod("setInner", innerModelCls).invoke(outer, inner);

            byte[] t1 = (byte[]) outerCodecCls
                .getMethod("marshal", outerModelCls, MarshalOptions.class)
                .invoke(null, outer, MarshalOptions.DETERMINISTIC);

            DynamicMessage dynInner = DynamicMessage.newBuilder(innerDesc)
                .setField(innerDesc.findFieldByName("inner_exp"), 0)
                .build();
            DynamicMessage dynOuter = DynamicMessage.newBuilder(outerDesc)
                .setField(outerDesc.findFieldByName("msg_exp"), 0)
                .setField(outerDesc.findFieldByName("oneof_str"), "")
                .setField(outerDesc.findFieldByName("inner"), dynInner)
                .build();
            byte[] t2 = OmniBufProtobuf.marshal(dynOuter, MarshalOptions.DETERMINISTIC);
            assertArrayEquals(t1, t2);
        }
    }

    @Test
    void negativeTestsUnsupportedEditionsRejectedInPluginAndReflection() throws Exception {
        // 1. edition = 0 (EDITION_UNKNOWN)
        FileDescriptorProto ed0 = FileDescriptorProto.newBuilder()
            .setName("ed0.proto")
            .setPackage("bad.ed0")
            .setSyntax("editions")
            .setEdition(Edition.EDITION_UNKNOWN)
            .addMessageType(simpleIntMessage("M"))
            .build();
        assertPluginErrorContains(List.of(ed0), "ed0.proto", "unsupported protobuf edition");

        // 2. edition = 1003 (unknown enum value encoded into field #14 -> UnknownFieldSet)
        FileDescriptorProto ed1003 = withRawEditionNumber(ed0.toBuilder().setName("ed1003.proto").build(), 1003);
        assertPluginErrorContains(List.of(ed1003), "ed1003.proto", "unsupported protobuf edition");

        // 3. edition = 9999 (EDITION_UNSTABLE)
        FileDescriptorProto ed9999 = FileDescriptorProto.newBuilder()
            .setName("ed9999.proto")
            .setPackage("bad.ed9999")
            .setSyntax("editions")
            .setEdition(Edition.EDITION_UNSTABLE)
            .addMessageType(simpleIntMessage("M"))
            .build();
        assertPluginErrorContains(List.of(ed9999), "ed9999.proto", "unsupported protobuf edition");

        // Verify OmniBufProtobuf reflection also rejects unsupported editions that protobuf-java's
        // FileDescriptor.buildFrom accepts (EDITION_UNSTABLE = 9999 and syntax="editions" with EDITION_PROTO2 = 998)
        FileDescriptor fd9999 = FileDescriptor.buildFrom(ed9999, new FileDescriptor[0]);
        assertReflectionRejected(fd9999.findMessageTypeByName("M"), "unsupported protobuf edition");

        FileDescriptorProto ed998 = FileDescriptorProto.newBuilder()
            .setName("ed998.proto")
            .setPackage("bad.ed998")
            .setSyntax("editions")
            .setEdition(Edition.EDITION_PROTO2)
            .addMessageType(simpleIntMessage("M"))
            .build();
        assertPluginErrorContains(List.of(ed998), "ed998.proto", "unsupported protobuf edition");
        FileDescriptor fd998 = FileDescriptor.buildFrom(ed998, new FileDescriptor[0]);
        assertReflectionRejected(fd998.findMessageTypeByName("M"), "unsupported protobuf edition");
    }

    @Test
    void negativeTestsClosedEnumsLegacyRequiredUtf8NoneCustomDefaultsAndExtensions(
            @TempDir Path tempDir) throws Exception {
        // 1. Declared CLOSED enum (top-level and nested)
        String closedTopProto = """
            edition = "2023";
            package bad.closed1;
            enum ClosedTop {
              option features.enum_type = CLOSED;
              CLOSED_TOP_UNSPECIFIED = 0;
            }
            message Holder {
              int32 x = 1;
            }
            """;
        assertBothRejected(tempDir.resolve("c1"), "closed1.proto", closedTopProto, "Holder", "CLOSED");

        String closedNestedProto = """
            edition = "2023";
            package bad.closed2;
            message Holder {
              enum ClosedInner {
                option features.enum_type = CLOSED;
                CLOSED_INNER_UNSPECIFIED = 0;
              }
              ClosedInner e = 1;
            }
            """;
        assertBothRejected(tempDir.resolve("c2"), "closed2.proto", closedNestedProto, "Holder", "CLOSED");

        // 2. Cross-file reference to a proto2 closed enum (singular & map value!)
        Path crossDir = tempDir.resolve("cross");
        Files.createDirectories(crossDir);
        Files.writeString(crossDir.resolve("p2_enum.proto"), """
            syntax = "proto2";
            package bad.p2;
            enum LegacyClosed {
              LEGACY_ZERO = 0;
              LEGACY_ONE = 1;
            }
            """, StandardCharsets.UTF_8);
        Files.writeString(crossDir.resolve("uses_p2_enum.proto"), """
            edition = "2024";
            package bad.cross;
            import "p2_enum.proto";
            message UsesClosed {
              bad.p2.LegacyClosed status = 1;
            }
            message UsesClosedMap {
              map<string, bad.p2.LegacyClosed> statuses = 1;
            }
            """, StandardCharsets.UTF_8);
        FileDescriptorSet crossFds = runProtocDescriptorSet(crossDir, "uses_p2_enum.proto");
        assertPluginErrorContains(crossFds.getFileList(), "uses_p2_enum.proto", "CLOSED");
        FileDescriptor crossFd = buildFileDescriptor(crossFds, "uses_p2_enum.proto");
        assertReflectionRejected(crossFd.findMessageTypeByName("UsesClosed"), "CLOSED");
        assertReflectionRejected(crossFd.findMessageTypeByName("UsesClosedMap"), "CLOSED");

        // 3. Cross-file reference to an Editions CLOSED enum
        Path crossEdDir = tempDir.resolve("cross_ed");
        Files.createDirectories(crossEdDir);
        Files.writeString(crossEdDir.resolve("ed_closed.proto"), """
            edition = "2023";
            package bad.edclosed;
            enum ClosedEd {
              option features.enum_type = CLOSED;
              CLOSED_ED_ZERO = 0;
            }
            """, StandardCharsets.UTF_8);
        Files.writeString(crossEdDir.resolve("uses_ed_closed.proto"), """
            edition = "2026";
            package bad.cross2;
            import "ed_closed.proto";
            message UsesEdClosed {
              repeated bad.edclosed.ClosedEd items = 1;
            }
            """, StandardCharsets.UTF_8);
        FileDescriptorSet crossEdFds = runProtocDescriptorSet(crossEdDir, "uses_ed_closed.proto");
        assertPluginErrorContains(crossEdFds.getFileList(), "uses_ed_closed.proto", "CLOSED");
        FileDescriptor crossEdFd = buildFileDescriptor(crossEdFds, "uses_ed_closed.proto");
        assertReflectionRejected(crossEdFd.findMessageTypeByName("UsesEdClosed"), "CLOSED");

        // 4. LEGACY_REQUIRED
        String legacyReqProto = """
            edition = "2023";
            package bad.req;
            message HasLegacyReq {
              int32 must_set = 1 [features.field_presence = LEGACY_REQUIRED];
            }
            """;
        assertBothRejected(tempDir.resolve("req"), "legacy_req.proto", legacyReqProto, "HasLegacyReq", "LEGACY_REQUIRED");

        // 5. UTF-8 NONE: singular string, repeated string, map<string, int32>, map<int32, string>
        String utf8SingularProto = """
            edition = "2023";
            package bad.utf8a;
            message BadStr {
              string s = 1 [features.utf8_validation = NONE];
            }
            """;
        assertBothRejected(tempDir.resolve("u1"), "u1.proto", utf8SingularProto, "BadStr", "UTF-8 NONE");

        String utf8RepeatedProto = """
            edition = "2024";
            package bad.utf8b;
            message BadRepStr {
              repeated string items = 1 [features.utf8_validation = NONE];
            }
            """;
        assertBothRejected(tempDir.resolve("u2"), "u2.proto", utf8RepeatedProto, "BadRepStr", "UTF-8 NONE");

        String utf8MapFileProto = """
            edition = "2023";
            package bad.utf8c;
            option features.utf8_validation = NONE;
            message BadMapKey {
              map<string, int32> m = 1;
            }
            """;
        assertBothRejected(tempDir.resolve("u3"), "u3.proto", utf8MapFileProto, "BadMapKey", "UTF-8 NONE");

        String utf8MapValProto = """
            edition = "2026";
            package bad.utf8d;
            message BadMapVal {
              map<int32, string> m = 1 [features.utf8_validation = NONE];
            }
            """;
        assertBothRejected(tempDir.resolve("u4"), "u4.proto", utf8MapValProto, "BadMapVal", "UTF-8 NONE");

        // Verify file-level utf8_validation = NONE with field-level VERIFY override IS allowed
        String utf8OverrideOkProto = """
            edition = "2023";
            package ok.utf8;
            option features.utf8_validation = NONE;
            message VerifiedOnly {
              int32 num = 1;
              string s = 2 [features.utf8_validation = VERIFY];
            }
            """;
        Path uOkDir = tempDir.resolve("u_ok");
        Files.createDirectories(uOkDir);
        Files.writeString(uOkDir.resolve("u_ok.proto"), utf8OverrideOkProto, StandardCharsets.UTF_8);
        FileDescriptorSet uOkFds = runProtocDescriptorSet(uOkDir, "u_ok.proto");
        CodeGeneratorResponse uOkResp = ProtocGenOmniBufJava.generate(
            CodeGeneratorRequest.newBuilder()
                .addAllProtoFile(uOkFds.getFileList())
                .addFileToGenerate("u_ok.proto")
                .build()
        );
        assertFalse(uOkResp.hasError(), () -> "Expected field-level VERIFY override to succeed: " + uOkResp.getError());
        FileDescriptor uOkFd = buildFileDescriptor(uOkFds, "u_ok.proto");
        DynamicMessage okMsg = DynamicMessage.newBuilder(uOkFd.findMessageTypeByName("VerifiedOnly"))
            .setField(uOkFd.findMessageTypeByName("VerifiedOnly").findFieldByName("s"), "valid")
            .build();
        byte[] okBytes = OmniBufProtobuf.marshal(okMsg);
        assertEquals("valid", OmniBufProtobuf.unmarshal(okBytes, okMsg.getDefaultInstanceForType())
            .getField(uOkFd.findMessageTypeByName("VerifiedOnly").findFieldByName("s")));

        // 6. Custom non-zero defaults (int, string, bool, enum, -0.0)
        String nonZeroIntDefault = """
            edition = "2023";
            package bad.def1;
            message BadDefInt {
              int32 retries = 1 [default = 3];
            }
            """;
        assertBothRejected(tempDir.resolve("d1"), "d1.proto", nonZeroIntDefault, "BadDefInt", "default");

        String nonZeroStrDefault = """
            edition = "2024";
            package bad.def2;
            message BadDefStr {
              string name = 1 [default = "nonempty"];
            }
            """;
        assertBothRejected(tempDir.resolve("d2"), "d2.proto", nonZeroStrDefault, "BadDefStr", "default");

        String nonZeroEnumDefault = """
            edition = "2026";
            package bad.def3;
            enum Kind {
              KIND_UNSPECIFIED = 0;
              KIND_SPECIAL = 1;
            }
            message BadDefEnum {
              Kind kind = 1 [default = KIND_SPECIAL];
            }
            """;
        assertBothRejected(tempDir.resolve("d3"), "d3.proto", nonZeroEnumDefault, "BadDefEnum", "default");

        String negZeroFloatDefault = """
            edition = "2023";
            package bad.def4;
            message BadDefNegZero {
              double val = 1 [default = -0.0];
            }
            """;
        assertBothRejected(tempDir.resolve("d4"), "d4.proto", negZeroFloatDefault, "BadDefNegZero", "default");

        // 7. Message extension range and file-level non-option extension in Editions
        String extRangeProto = """
            edition = "2023";
            package bad.ext1;
            message HasExtRange {
              int32 id = 1;
              extensions 100 to 200;
            }
            """;
        assertBothRejected(tempDir.resolve("e1"), "e1.proto", extRangeProto, "HasExtRange", "extension");

        String nonOptExtProto = """
            edition = "2023";
            package bad.ext2;
            message Target {
              int32 id = 1;
              extensions 100 to 200;
            }
            extend Target {
              string extra = 101;
            }
            message Wrapper {
              int32 x = 1;
            }
            """;
        assertBothRejected(tempDir.resolve("e2"), "e2.proto", nonOptExtProto, "Wrapper", "extension");

        // 8. Nested invalid sub-message caught recursively by BOTH marshal & unmarshal and plugin!
        String nestedBadProto = """
            edition = "2023";
            package bad.nested;
            message InnerBad {
              int32 req = 1 [features.field_presence = LEGACY_REQUIRED];
            }
            message OuterClean {
              InnerBad child = 1;
            }
            """;
        assertBothRejected(tempDir.resolve("nb"), "nb.proto", nestedBadProto, "OuterClean", "LEGACY_REQUIRED");
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private record CompiledProtoBundle(
        FileDescriptor fileDescriptor,
        CodeGeneratorResponse pluginResponse,
        Path classesDir
    ) {
        URLClassLoader newClassLoader() throws Exception {
            return new URLClassLoader(
                new URL[] {classesDir.toUri().toURL()},
                EditionsEndToEndTest.class.getClassLoader()
            );
        }
    }

    private static CompiledProtoBundle compileProtoEndToEnd(
            Path rootDir, String fileName, String protoContent, boolean invokeRealProtocPlugin) throws Exception {
        Path workDir = rootDir.resolve(fileName.replace(".proto", ""));
        Path protoDir = workDir.resolve("proto");
        Path javaOutDir = workDir.resolve("pb_java");
        Path pluginOutDir = workDir.resolve("omnibuf_java");
        Path classesDir = workDir.resolve("classes");
        Files.createDirectories(protoDir);
        Files.createDirectories(javaOutDir);
        Files.createDirectories(pluginOutDir);
        Files.createDirectories(classesDir);

        Files.writeString(protoDir.resolve(fileName), protoContent, StandardCharsets.UTF_8);
        Path descOut = workDir.resolve("descriptor.pb");

        List<String> cmd = new ArrayList<>();
        cmd.add("protoc");
        cmd.add("-I" + protoDir);
        cmd.add("--include_imports");
        cmd.add("--descriptor_set_out=" + descOut);
        cmd.add("--java_out=" + javaOutDir);
        if (invokeRealProtocPlugin) {
            Path wrapper = createPluginWrapperScript(workDir);
            cmd.add("--plugin=protoc-gen-omnibuf-java=" + wrapper);
            cmd.add("--omnibuf-java_out=" + pluginOutDir);
        }
        cmd.add(fileName);

        runProcess(cmd, workDir);

        ExtensionRegistry extReg = ExtensionRegistry.newInstance();
        JavaFeaturesProto.registerAllExtensions(extReg);
        FileDescriptorSet fds = FileDescriptorSet.parseFrom(Files.readAllBytes(descOut), extReg);

        CodeGeneratorResponse resp = ProtocGenOmniBufJava.generate(
            CodeGeneratorRequest.newBuilder()
                .addAllProtoFile(fds.getFileList())
                .addFileToGenerate(fileName)
                .build()
        );
        assertFalse(resp.hasError(), () -> "Plugin returned error for " + fileName + ": " + resp.getError());
        assertTrue(resp.getFileCount() > 0, "Plugin must emit generated files for " + fileName);

        // Collect all Java sources: protoc --java_out AND omnibuf-java generated files
        List<Path> javaFiles = new ArrayList<>();
        try (var stream = Files.walk(javaOutDir)) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(javaFiles::add);
        }
        if (invokeRealProtocPlugin) {
            List<Path> pluginFiles = new ArrayList<>();
            try (var stream = Files.walk(pluginOutDir)) {
                stream.filter(p -> p.toString().endsWith(".java")).forEach(pluginFiles::add);
            }
            assertEquals(resp.getFileCount(), pluginFiles.size(), "Real protoc plugin output count mismatch");
            javaFiles.addAll(pluginFiles);
        } else {
            Path srcDir = workDir.resolve("omnibuf_direct");
            for (CodeGeneratorResponse.File f : resp.getFileList()) {
                Path p = srcDir.resolve(f.getName());
                Files.createDirectories(p.getParent());
                Files.writeString(p, f.getContent(), StandardCharsets.UTF_8);
                javaFiles.add(p);
            }
        }

        // Compile OmniBuf generated files strictly with -Xlint:all,-this-escape -Werror first
        List<Path> omnibufFiles = javaFiles.stream()
            .filter(p -> !p.startsWith(javaOutDir))
            .toList();
        List<Path> pbFiles = javaFiles.stream()
            .filter(p -> p.startsWith(javaOutDir))
            .toList();
        compileWithJavac(omnibufFiles, classesDir, true);
        compileWithJavac(pbFiles, classesDir, false);

        FileDescriptor fd = buildFileDescriptor(fds, fileName);
        return new CompiledProtoBundle(fd, resp, classesDir);
    }

    private static Path createPluginWrapperScript(Path workDir) throws Exception {
        Path script = workDir.resolve("protoc-gen-omnibuf-java-test");
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("java.class.path");
        String content = "#!/usr/bin/env bash\nexec \"" + javaBin + "\" -cp \"" + cp
            + "\" com.omnibuf.plugin.ProtocGenOmniBufJava \"$@\"\n";
        Files.writeString(script, content, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(
            script,
            Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            )
        );
        return script;
    }

    private static FileDescriptorSet runProtocDescriptorSet(Path protoDir, String fileName) throws Exception {
        Path descOut = protoDir.resolve(fileName + ".pb");
        runProcess(
            List.of(
                "protoc",
                "-I" + protoDir,
                "--include_imports",
                "--descriptor_set_out=" + descOut,
                fileName
            ),
            protoDir
        );
        ExtensionRegistry extReg = ExtensionRegistry.newInstance();
        JavaFeaturesProto.registerAllExtensions(extReg);
        return FileDescriptorSet.parseFrom(Files.readAllBytes(descOut), extReg);
    }

    private static void runProcess(List<String> command, Path cwd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = proc.waitFor();
        assertEquals(0, exit, () -> "Command failed (" + command + "):\n" + output);
    }

    private static FileDescriptor buildFileDescriptor(FileDescriptorSet fds, String targetName) throws Exception {
        Map<String, FileDescriptorProto> byName = new LinkedHashMap<>();
        for (FileDescriptorProto f : fds.getFileList()) {
            byName.put(f.getName(), f);
        }
        Map<String, FileDescriptor> built = new LinkedHashMap<>();
        built.put(DescriptorProtos.getDescriptor().getName(), DescriptorProtos.getDescriptor());
        built.put(JavaFeaturesProto.getDescriptor().getName(), JavaFeaturesProto.getDescriptor());
        return buildFileDescriptorRecursive(targetName, byName, built);
    }

    private static FileDescriptor buildFileDescriptorRecursive(
            String name, Map<String, FileDescriptorProto> byName, Map<String, FileDescriptor> built)
            throws Exception {
        FileDescriptor existing = built.get(name);
        if (existing != null) {
            return existing;
        }
        FileDescriptorProto proto = byName.get(name);
        assertNotNull(proto, "Missing dependency descriptor: " + name);
        FileDescriptor[] deps = new FileDescriptor[proto.getDependencyCount()];
        for (int i = 0; i < proto.getDependencyCount(); i++) {
            deps[i] = buildFileDescriptorRecursive(proto.getDependency(i), byName, built);
        }
        FileDescriptor fd = FileDescriptor.buildFrom(proto, deps);
        built.put(name, fd);
        return fd;
    }

    private static Path compileJavaSources(CodeGeneratorResponse resp, Path tempDir) throws Exception {
        Path srcDir = tempDir.resolve("gen_src");
        Path classesDir = tempDir.resolve("gen_classes");
        Files.createDirectories(classesDir);
        List<Path> javaFiles = new ArrayList<>();
        for (CodeGeneratorResponse.File f : resp.getFileList()) {
            Path p = srcDir.resolve(f.getName());
            Files.createDirectories(p.getParent());
            Files.writeString(p, f.getContent(), StandardCharsets.UTF_8);
            javaFiles.add(p);
        }
        compileWithJavac(javaFiles, classesDir, true);
        return classesDir;
    }

    private static void compileWithJavac(List<Path> javaFiles, Path classesDir, boolean strictWarnings)
            throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK JavaCompiler must be available");
        StringWriter diagOut = new StringWriter();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Iterable<? extends javax.tools.JavaFileObject> units = fm.getJavaFileObjectsFromPaths(javaFiles);
            List<String> options = new ArrayList<>();
            options.add("-classpath");
            options.add(System.getProperty("java.class.path") + ":" + classesDir);
            options.add("-d");
            options.add(classesDir.toString());
            if (strictWarnings) {
                options.add("-Xlint:all,-this-escape");
                options.add("-Werror");
            }
            boolean ok = compiler.getTask(diagOut, fm, null, options, null, units).call();
            assertTrue(ok, () -> "javac failed:\n" + diagOut);
        }
    }

    private static void assertBothRejected(
            Path dir, String fileName, String protoContent, String messageName, String expectedFragment)
            throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(fileName), protoContent, StandardCharsets.UTF_8);
        FileDescriptorSet fds = runProtocDescriptorSet(dir, fileName);

        assertPluginErrorContains(fds.getFileList(), fileName, expectedFragment);

        FileDescriptor fd = buildFileDescriptor(fds, fileName);
        Descriptor md = fd.findMessageTypeByName(messageName);
        assertNotNull(md, "Missing message " + messageName + " in " + fileName);
        assertReflectionRejected(md, expectedFragment);
    }

    private static void assertPluginErrorContains(
            List<FileDescriptorProto> files, String targetFile, String expectedFragment) {
        CodeGeneratorResponse resp = ProtocGenOmniBufJava.generate(
            CodeGeneratorRequest.newBuilder()
                .addAllProtoFile(files)
                .addFileToGenerate(targetFile)
                .build()
        );
        assertTrue(resp.hasError(), () -> "Expected plugin rejection for " + targetFile);
        assertEquals(0, resp.getFileCount());
        assertEquals(3L, resp.getSupportedFeatures());
        assertEquals(Edition.EDITION_2023_VALUE, resp.getMinimumEdition());
        assertEquals(Edition.EDITION_2026_VALUE, resp.getMaximumEdition());
        assertTrue(
            resp.getError().toLowerCase().contains(expectedFragment.toLowerCase()),
            () -> "Expected error containing \"" + expectedFragment + "\", got: " + resp.getError()
        );
    }

    private static void assertReflectionRejected(Descriptor md, String expectedFragment) {
        DynamicMessage msg = DynamicMessage.getDefaultInstance(md);
        OmniBufException marshalEx = assertThrows(
            OmniBufException.class,
            () -> OmniBufProtobuf.marshal(msg),
            () -> "Expected OmniBufProtobuf.marshal to reject " + md.getFullName()
        );
        assertTrue(
            marshalEx.getMessage().toLowerCase().contains(expectedFragment.toLowerCase()),
            () -> "Expected marshal error containing \"" + expectedFragment + "\", got: " + marshalEx.getMessage()
        );

        OmniBufException unmarshalEx = assertThrows(
            OmniBufException.class,
            () -> OmniBufProtobuf.unmarshal(new byte[0], msg),
            () -> "Expected OmniBufProtobuf.unmarshal to reject " + md.getFullName()
        );
        assertTrue(
            unmarshalEx.getMessage().toLowerCase().contains(expectedFragment.toLowerCase()),
            () -> "Expected unmarshal error containing \"" + expectedFragment + "\", got: " + unmarshalEx.getMessage()
        );
    }

    private static void addMapEntry(DynamicMessage.Builder parent, FieldDescriptor mapFd, Object key, Object val) {
        Descriptor entryDesc = mapFd.getMessageType();
        DynamicMessage entry = DynamicMessage.newBuilder(entryDesc)
            .setField(entryDesc.findFieldByNumber(1), key)
            .setField(entryDesc.findFieldByNumber(2), val)
            .build();
        parent.addRepeatedField(mapFd, entry);
    }

    private static DescriptorProto simpleIntMessage(String name) {
        return DescriptorProto.newBuilder()
            .setName(name)
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("x")
                    .setNumber(1)
                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                    .setType(FieldDescriptorProto.Type.TYPE_INT32)
            )
            .build();
    }

    private static FileDescriptorProto withRawEditionNumber(FileDescriptorProto base, int rawEdition)
            throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);
        base.toBuilder().clearEdition().build().writeTo(cos);
        cos.writeEnum(FileDescriptorProto.EDITION_FIELD_NUMBER, rawEdition);
        cos.flush();
        FileDescriptorProto parsed = FileDescriptorProto.parseFrom(baos.toByteArray());
        // Verify raw edition landed in unknown fields as expected for unlisted edition numbers
        UnknownFieldSet ufs = parsed.getUnknownFields();
        assertTrue(parsed.hasEdition() || ufs.hasField(FileDescriptorProto.EDITION_FIELD_NUMBER));
        return parsed;
    }
}
