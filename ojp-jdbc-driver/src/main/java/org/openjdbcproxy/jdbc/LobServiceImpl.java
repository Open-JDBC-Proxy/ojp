package org.openjdbcproxy.jdbc;

import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;
import com.openjdbcproxy.grpc.DbName;
import com.openjdbcproxy.grpc.LobDataBlock;
import com.openjdbcproxy.grpc.LobReference;
import com.openjdbcproxy.grpc.LobType;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.openjdbcproxy.grpc.client.StatementService;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.openjdbcproxy.constants.CommonConstants.MAX_LOB_DATA_BLOCK_SIZE;
import static org.openjdbcproxy.constants.CommonConstants.PREPARED_STATEMENT_BINARY_STREAM_LENGTH;
import static org.openjdbcproxy.grpc.SerializationHandler.serialize;

@Slf4j
@AllArgsConstructor
public class LobServiceImpl implements LobService {

    private Connection connection;
    private StatementService statementService;

    @Override
    public LobReference sendBytes(LobType lobType, long pos, InputStream is) throws SQLException {
        return this.sendBytes(lobType, pos, is, new HashMap<>());
    }


    @SneakyThrows
    @Override
    public LobReference sendBytes(LobType lobType, long pos, InputStream is, Map<Integer, Object> metadata) throws SQLException {

        BufferedInputStream bis = new BufferedInputStream(is);
        AtomicInteger transferredBytes = new AtomicInteger(0);
        long length = metadata.get(PREPARED_STATEMENT_BINARY_STREAM_LENGTH) != null ?
                (Long) metadata.get(PREPARED_STATEMENT_BINARY_STREAM_LENGTH) : -1l;
        byte[] metadataBytes = (metadata == null) ? new byte[]{} : serialize(metadata);

        Iterator<LobDataBlock> itLobDataBlocks = new Iterator<LobDataBlock>() {

            byte[] nextBytes = new byte[]{};
            boolean startBlockSent = false;

            @SneakyThrows
            @Override
            public synchronized boolean hasNext() {
                if (DbName.H2.equals(connection.getDbName())) {
                    startBlockSent = true; //H2 does not support partial binary streams
                }
                if (!startBlockSent) {
                    return  true;
                }
                //Read one next byte to know if the stream of bytes finished.
                nextBytes = bis.readNBytes(1);
                return nextBytes.length > 0;
            }

            @SneakyThrows
            @Override
            public synchronized LobDataBlock next() {
                if (DbName.H2.equals(connection.getDbName())) {
                    startBlockSent = true; //H2 does not support partial binary streams
                }
                // A start block with empty bytes is always sent for cases where an empty array is set.
                if (!startBlockSent) {
                    startBlockSent = true;
                    return LobDataBlock.newBuilder()
                            .setLobType(lobType)
                            .setSession(connection.getSession())
                            .setPosition(1)
                            .setData(ByteString.copyFrom(new byte[0]))
                            .setMetadata(ByteString.copyFrom(metadataBytes))
                            .build();
                }
                byte[] bytesRead;
                if (nextBytes.length > 0) {
                    //H2 does not support multiple writes to the same blob. All is written at once. H2 error = Feature not supported: "Allocate a new object to set its value." [50100-232]
                    if (DbName.H2.equals(connection.getDbName())) {
                        bytesRead = Bytes.concat(nextBytes, bis.readAllBytes());
                    } else {
                        //Concatenate the one byte already read in the hasNext method
                        bytesRead = Bytes.concat(nextBytes, bis.readNBytes(MAX_LOB_DATA_BLOCK_SIZE - 1));
                    }
                } else {
                    bytesRead = bis.readNBytes(MAX_LOB_DATA_BLOCK_SIZE);
                }
                transferredBytes.set(transferredBytes.get() + (MAX_LOB_DATA_BLOCK_SIZE));
                long updatedPosition = (transferredBytes.get() + pos) - MAX_LOB_DATA_BLOCK_SIZE;
                log.info("Sending the next block of bytes updatedPosition: {}", updatedPosition);

                bytesRead = this.maxLengthTrim(bytesRead, length, (updatedPosition + bytesRead.length - 1));

                return LobDataBlock.newBuilder()
                        .setLobType(lobType)
                        .setSession(connection.getSession())
                        .setPosition(updatedPosition)
                        .setData(ByteString.copyFrom(bytesRead))
                        .setMetadata(ByteString.copyFrom(metadataBytes))
                        .build();
            }

            private byte[] maxLengthTrim(byte[] bytesRead, long length, long bytesSendCount) {
                if (length == -1 || bytesSendCount <= length) {
                    return bytesRead;
                }
                int diff = (int) (bytesSendCount - length);
                int currentSize = bytesRead.length;
                return Arrays.copyOfRange(bytesRead, 0, (currentSize - diff));
            }
        };

        return this.statementService.createLob(this.connection, itLobDataBlocks);
    }

    @Override
    public InputStream parseReceivedBlocks(Iterator<LobDataBlock> itBlocks) {
        LobDataBlock lobDataBlock = itBlocks.next();
        if (lobDataBlock.getPosition() == -1 && lobDataBlock.getData().toByteArray().length < 1) {
            return null;
        }

        return new InputStream() {
            private int currentPos = -1;

            private byte[] currentBlock = lobDataBlock.getData().toByteArray();

            @Override
            public int read() throws IOException {
                if (currentPos >= (currentBlock.length - 1)) {
                    if (!itBlocks.hasNext()) {
                        return -1;// -1 means end of the stream.
                    }
                    currentBlock = itBlocks.next().getData().toByteArray();
                    currentPos = -1;
                }
                int currentByte = currentBlock[++currentPos];
                return currentByte & 0xFF;// Need to return unsigned byte (& 0xFF) to not incorrectly cause EOF if int representation of byte is -1
            }
        };
    }
}
