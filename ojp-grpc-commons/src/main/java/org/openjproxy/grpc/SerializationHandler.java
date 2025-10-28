package org.openjproxy.grpc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;

/**
 * Handles serialization of java objects to and from byte arrays.
 * Special handling for java.sql.Date, java.sql.Time, and java.sql.Timestamp to avoid timezone issues.
 */
public class SerializationHandler {
    private static final byte TYPE_SQL_DATE = 1;
    private static final byte TYPE_SQL_TIME = 2;
    private static final byte TYPE_SQL_TIMESTAMP = 3;
    private static final byte TYPE_OTHER = 0;

    public static byte[] serialize(Object t) {
        try (ByteArrayOutputStream bo = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(bo)) {
            
            // Check if the object is a date/time type that needs special handling
            if (t instanceof Timestamp) {
                // Handle Timestamp first as it's a subclass of Date
                dos.writeByte(TYPE_SQL_TIMESTAMP);
                dos.writeLong(((Timestamp) t).getTime());
                dos.writeInt(((Timestamp) t).getNanos());
            } else if (t instanceof Date) {
                dos.writeByte(TYPE_SQL_DATE);
                dos.writeLong(((Date) t).getTime());
            } else if (t instanceof Time) {
                dos.writeByte(TYPE_SQL_TIME);
                dos.writeLong(((Time) t).getTime());
            } else {
                // Use standard Java serialization for other objects
                dos.writeByte(TYPE_OTHER);
                dos.flush();
                try (ObjectOutputStream so = new ObjectOutputStream(bo)) {
                    so.writeObject(t);
                    so.flush();
                }
            }
            return bo.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> T deserialize(byte[] byteArray, Class<T> type) {
        try (ByteArrayInputStream bi = new ByteArrayInputStream(byteArray);
             DataInputStream dis = new DataInputStream(bi)) {
            
            byte typeMarker = dis.readByte();
            
            switch (typeMarker) {
                case TYPE_SQL_DATE:
                    long dateTime = dis.readLong();
                    return type.cast(new Date(dateTime));
                
                case TYPE_SQL_TIMESTAMP:
                    long timestampTime = dis.readLong();
                    int nanos = dis.readInt();
                    Timestamp ts = new Timestamp(timestampTime);
                    ts.setNanos(nanos);
                    return type.cast(ts);
                
                case TYPE_SQL_TIME:
                    long timeTime = dis.readLong();
                    return type.cast(new Time(timeTime));
                
                case TYPE_OTHER:
                    // For other types, use standard deserialization
                    // We need to create a new stream that starts after the type marker
                    byte[] remainingBytes = new byte[byteArray.length - 1];
                    System.arraycopy(byteArray, 1, remainingBytes, 0, remainingBytes.length);
                    try (ByteArrayInputStream bi2 = new ByteArrayInputStream(remainingBytes);
                         ObjectInputStream si = new ObjectInputStream(bi2)) {
                        return type.cast(si.readObject());
                    }
                
                default:
                    throw new RuntimeException("Unknown type marker: " + typeMarker);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
