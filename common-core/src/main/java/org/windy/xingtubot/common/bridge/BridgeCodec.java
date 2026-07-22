package org.windy.xingtubot.common.bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨服 Plugin Message 的编解码：[类型名][字段数][字段...]，全部 UTF。
 * 两端（Velocity / Spigot）共用，纯 JDK IO，无需 Guava。
 */
public final class  BridgeCodec {

    private BridgeCodec() {
    }

    public static byte[] encode(CrossServerProtocol.Type type, String... fields) {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bout)) {
            out.writeUTF(type.name());
            out.writeInt(fields.length);
            for (String f : fields) out.writeUTF(f == null ? "" : f);
        } catch (IOException e) {
            throw new RuntimeException("编码跨服消息失败", e);
        }
        return bout.toByteArray();
    }

    /** 解码；失败返回 null。 */
    public static Decoded decode(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            CrossServerProtocol.Type type = CrossServerProtocol.Type.valueOf(in.readUTF());
            int n = in.readInt();
            List<String> fields = new ArrayList<>(n);
            for (int i = 0; i < n; i++) fields.add(in.readUTF());
            return new Decoded(type, fields.toArray(new String[0]));
        } catch (Exception e) {
            return null;
        }
    }

    /** 解码结果。 */
    public static final class Decoded {
        public final CrossServerProtocol.Type type;
        public final String[] fields;

        Decoded(CrossServerProtocol.Type type, String[] fields) {
            this.type = type;
            this.fields = fields;
        }

        public String field(int i) {
            return i < fields.length ? fields[i] : null;
        }
    }
}
