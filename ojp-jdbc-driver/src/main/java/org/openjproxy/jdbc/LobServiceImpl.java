package org.openjproxy.jdbc;

import com.google.protobuf.ByteString;
import com.openjproxy.grpc.LobDataBlock;
import com.openjproxy.grpc.LobReference;
import com.openjproxy.grpc.LobType;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.client.StatementService;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static org.openjproxy.constants.CommonConstants.PREPARED_STATEMENT_BINARY_STREAM_LENGTH;

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
        long length = metadata.get(PREPARED_STATEMENT_BINARY_STREAM_LENGTH) != null ?
                (Long) metadata.get(PREPARED_STATEMENT_BINARY_STREAM_LENGTH) : -1L;

        // Convert metadata Map<Integer, Object> to Map<String, Object> for ProtoConverter
        Map<String, Object> metadataStringKey = new HashMap<>();
        for (Map.Entry<Integer, Object> entry : metadata.entrySet()) {
            metadataStringKey.put(entry.getKey().toString(), entry.getValue());
        }

        // Hydrated approach: Read all bytes at once instead of streaming
        // This provides consistent behavior across all databases and eliminates streaming complexity
        final byte[] allBytes;
        try {
            byte[] readBytes = bis.readAllBytes();

            // Apply length limit if specified
            if (length != -1 && readBytes.length > length) {
                byte[] limitedBytes = new byte[(int) length];
                System.arraycopy(readBytes, 0, limitedBytes, 0, (int) length);
                allBytes = limitedBytes;
            } else {
                allBytes = readBytes;
            }
        } catch (IOException e) {
            throw new SQLException("Failed to read LOB data: " + e.getMessage(), e);
        }

        // Create a simple iterator that returns all data in a single block
        Iterator<LobDataBlock> itLobDataBlocks = new Iterator<LobDataBlock>() {
            private boolean sent = false;

            @Override
            public boolean hasNext() {
                return !sent;
            }

            @Override
            public LobDataBlock next() {
                sent = true;
                return LobDataBlock.newBuilder()
                        .setLobType(lobType)
                        .setSession(connection.getSession())
                        .setPosition(pos)
                        .setData(ByteString.copyFrom(allBytes))
                        .addAllMetadata(ProtoConverter.propertiesToProto(metadataStringKey))
                        .build();
            }
        };

        return this.statementService.createLob(this.connection, itLobDataBlocks);
    }

    @Override
    public InputStream parseReceivedBlocks(Iterator<LobDataBlock> itBlocks) {
        // In the hydrated approach, we expect to receive all data in a single block
        // instead of multiple streaming blocks
        if (!itBlocks.hasNext()) {
            return null;
        }

        LobDataBlock lobDataBlock = itBlocks.next();
        if (lobDataBlock.getPosition() == -1 && lobDataBlock.getData().toByteArray().length < 1) {
            return null;
        }

        // Convert the single data block directly to an InputStream
        // Note: In hydrated approach, all remaining blocks should contain the complete data
        byte[] allData = lobDataBlock.getData().toByteArray();

        // If there are more blocks (shouldn't happen in hydrated approach), concatenate them
        while (itBlocks.hasNext()) {
            LobDataBlock nextBlock = itBlocks.next();
            byte[] nextData = nextBlock.getData().toByteArray();
            byte[] combined = new byte[allData.length + nextData.length];
            System.arraycopy(allData, 0, combined, 0, allData.length);
            System.arraycopy(nextData, 0, combined, allData.length, nextData.length);
            allData = combined;
        }

        return new java.io.ByteArrayInputStream(allData);
    }
}
