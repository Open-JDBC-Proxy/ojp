package org.openjproxy.grpc;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * Utility class for serializing and deserializing BigDecimal objects
 * using a compact, language-neutral wire format.
 *
 * Wire format (binary, big-endian):
 * - Presence flag: byte (0 = null, 1 = non-null)
 * - If non-null:
 *   - int32: length of UTF-8 bytes of unscaled_value (signed 32-bit, big-endian)
 *   - bytes: UTF-8 encoding of the BigInteger unscaledValue as a decimal string
 *   - int32: scale (signed 32-bit, big-endian)
 */
public final class BigDecimalWire {

    // Maximum length for unscaled value string to prevent DOS attacks
    private static final int MAX_UNSCALED_LENGTH = 10_000_000;

    private BigDecimalWire() {
        // Utility class, prevent instantiation
    }

    /**
     * Write a BigDecimal to a DataOutput stream using the wire format.
     *
     * @param out the DataOutput stream to write to
     * @param value the BigDecimal value to write (may be null)
     * @throws IOException if an I/O error occurs
     */
    public static void writeBigDecimal(DataOutput out, BigDecimal value) throws IOException {
        if (value == null) {
            out.writeByte(0);
            return;
        }

        out.writeByte(1);
        BigInteger unscaled = value.unscaledValue();
        String unscaledStr = unscaled.toString();
        byte[] bytes = unscaledStr.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);         // length of unscaled string
        out.write(bytes);                   // utf-8 bytes
        out.writeInt(value.scale());        // scale as int32
    }

    /**
     * Read a BigDecimal from a DataInput stream using the wire format.
     *
     * @param in the DataInput stream to read from
     * @return the BigDecimal value read (may be null)
     * @throws IOException if an I/O error occurs or if the data is invalid
     */
    public static BigDecimal readBigDecimal(DataInput in) throws IOException {
        byte present = in.readByte();
        if (present == 0) {
            return null;
        }

        int len = in.readInt();
        if (len < 0) {
            throw new IOException("Negative length for BigDecimal unscaled string: " + len);
        }
        if (len > MAX_UNSCALED_LENGTH) {
            throw new IOException("BigDecimal unscaled string length exceeds maximum: " + len + " > " + MAX_UNSCALED_LENGTH);
        }

        // Handle zero-length case (represents BigDecimal zero with scale)
        byte[] bytes = new byte[len];
        if (len > 0) {
            in.readFully(bytes);
        }
        String unscaledStr = new String(bytes, StandardCharsets.UTF_8);
        int scale = in.readInt();

        try {
            BigInteger unscaled = new BigInteger(unscaledStr);
            return new BigDecimal(unscaled, scale);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid BigDecimal unscaled value: " + unscaledStr, e);
        }
    }
}
