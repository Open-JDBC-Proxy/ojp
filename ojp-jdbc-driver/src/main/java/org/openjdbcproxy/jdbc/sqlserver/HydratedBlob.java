package org.openjdbcproxy.jdbc.sqlserver;

import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialException;
import java.sql.Blob;
import java.sql.SQLException;

/**
 * Handle SqlServer and DB2 BLOB in memory due to the fact that these databases invalidate the LOB object once the cursor moves,
 * therefore all the bytes have to be read in advance.
 */
public class HydratedBlob extends SerialBlob {
    public HydratedBlob(byte[] b) throws SerialException, SQLException {
        super(b);
    }

    public HydratedBlob(Blob blob) throws SerialException, SQLException {
        super(blob);
    }

    @Override
    public byte[] getBytes(long pos, int length) throws SerialException {
        if (length == 0) {
            return new byte[0];
        } else {
            return super.getBytes(pos, length);
        }
    }
}
