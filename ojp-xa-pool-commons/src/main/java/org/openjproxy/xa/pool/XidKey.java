package org.openjproxy.xa.pool;

import javax.transaction.xa.Xid;
import java.io.Serializable;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Immutable identifier for an XA transaction branch.
 *
 * <p>An XA transaction ID (Xid) consists of three parts:</p>
 * <ul>
 *   <li>{@code formatId} - Format identifier (int)</li>
 *   <li>{@code gtrid} - Global transaction identifier (byte array)</li>
 *   <li>{@code bqual} - Branch qualifier (byte array)</li>
 * </ul>
 *
 * <p>This class provides stable {@code equals()} and {@code hashCode()} implementations
 * using {@link Arrays#hashCode} for byte array fields, making it suitable for use as
 * a map key in concurrent collections.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * XidKey key = new XidKey(1, gtridBytes, bqualBytes);
 * txContextMap.put(key, txContext);
 * }</pre>
 */
public final class XidKey implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int formatId;
    private final byte[] gtrid;
    private final byte[] bqual;
    private final int hashCode;

    /**
     * Creates a new XidKey.
     *
     * @param formatId the format identifier
     * @param gtrid the global transaction identifier (copied defensively)
     * @param bqual the branch qualifier (copied defensively)
     * @throws IllegalArgumentException if gtrid or bqual is null
     */
    public XidKey(int formatId, byte[] gtrid, byte[] bqual) {
        if (gtrid == null) {
            throw new IllegalArgumentException("gtrid cannot be null");
        }
        if (bqual == null) {
            throw new IllegalArgumentException("bqual cannot be null");
        }

        this.formatId = formatId;
        this.gtrid = gtrid.clone();
        this.bqual = bqual.clone();

        // Pre-compute hash code for performance
        int result = formatId;
        result = 31 * result + Arrays.hashCode(this.gtrid);
        result = 31 * result + Arrays.hashCode(this.bqual);
        this.hashCode = result;
    }

    /**
     * Creates an XidKey from a standard {@link Xid}.
     *
     * @param xid the XA transaction ID
     * @return a new XidKey
     * @throws IllegalArgumentException if xid is null or has null gtrid/bqual
     */
    public static XidKey from(Xid xid) {
        if (xid == null) {
            throw new IllegalArgumentException("xid cannot be null");
        }
        return new XidKey(xid.getFormatId(), xid.getGlobalTransactionId(), xid.getBranchQualifier());
    }

    /**
     * Converts this XidKey to a standard {@link Xid} instance.
     *
     * @return a Xid implementation containing this key's data
     */
    public Xid toXid() {
        return new SimpleXid(formatId, gtrid.clone(), bqual.clone());
    }

    /**
     * Gets the format identifier.
     *
     * @return the format identifier
     */
    public int getFormatId() {
        return formatId;
    }

    /**
     * Gets a defensive copy of the global transaction identifier.
     *
     * @return a copy of the gtrid byte array
     */
    public byte[] getGtrid() {
        return gtrid.clone();
    }

    /**
     * Gets a defensive copy of the branch qualifier.
     *
     * @return a copy of the bqual byte array
     */
    public byte[] getBqual() {
        return bqual.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        XidKey xidKey = (XidKey) o;

        if (formatId != xidKey.formatId) {
            return false;
        }
        if (!Arrays.equals(gtrid, xidKey.gtrid)) {
            return false;
        }
        return Arrays.equals(bqual, xidKey.bqual);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return "Xid[" +
                "fmt=" + formatId +
                ", gtrid=" + toHexString(gtrid) +
                ", bqual=" + toHexString(bqual) +
                ']';
    }

    /**
     * Returns a compact string representation suitable for logging.
     *
     * @return a compact hex string representation
     */
    public String toCompactString() {
        return formatId + ":" + toHexString(gtrid, 8) + ":" + toHexString(bqual, 8);
    }

    private static String toHexString(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static String toHexString(byte[] bytes, int maxBytes) {
        if (bytes.length <= maxBytes) {
            return toHexString(bytes);
        }
        byte[] truncated = Arrays.copyOf(bytes, maxBytes);
        return toHexString(truncated) + "...";
    }

    /**
     * Simple Xid implementation for converting XidKey back to Xid.
     */
    private static class SimpleXid implements Xid {
        private final int formatId;
        private final byte[] gtrid;
        private final byte[] bqual;

        SimpleXid(int formatId, byte[] gtrid, byte[] bqual) {
            this.formatId = formatId;
            this.gtrid = gtrid;
            this.bqual = bqual;
        }

        @Override
        public int getFormatId() {
            return formatId;
        }

        @Override
        public byte[] getGlobalTransactionId() {
            return gtrid;
        }

        @Override
        public byte[] getBranchQualifier() {
            return bqual;
        }
    }
}
