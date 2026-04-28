package org.openjproxy.jdbc;

import com.openjproxy.grpc.LobReference;
import org.openjproxy.grpc.client.StatementService;

import java.io.InputStream;
import java.sql.SQLException;

/**
 * Binary stream is not really a JDBC LOB type (as Blob or Clob), in JDBC it is possible to write a stream of bytes
 * directly to a PreparedStatement. In OJP spec every large object obeys by the Lob base class therefore this class
 * is used to bridge the two specs.
 */
public class BinaryStream extends Lob {

    private PreparedStatement preparedStatement;

    public BinaryStream(Connection conn, LobService lobService, StatementService statementService,
                        LobReference lobReference) throws SQLException {
        super(conn, lobService, statementService, lobReference);
    }

    public InputStream getBinaryStream() throws SQLException {
        return super.getBinaryStream(1, Integer.MAX_VALUE);
    }
}
