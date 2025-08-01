package org.openjdbcproxy.grpc.server;

import com.google.protobuf.ByteString;
import com.openjdbcproxy.grpc.CallResourceRequest;
import com.openjdbcproxy.grpc.CallResourceResponse;
import com.openjdbcproxy.grpc.CallType;
import com.openjdbcproxy.grpc.ConnectionDetails;
import com.openjdbcproxy.grpc.DbName;
import com.openjdbcproxy.grpc.LobDataBlock;
import com.openjdbcproxy.grpc.LobReference;
import com.openjdbcproxy.grpc.LobType;
import com.openjdbcproxy.grpc.OpResult;
import com.openjdbcproxy.grpc.ReadLobRequest;
import com.openjdbcproxy.grpc.ResultType;
import com.openjdbcproxy.grpc.SessionInfo;
import com.openjdbcproxy.grpc.SessionTerminationStatus;
import com.openjdbcproxy.grpc.SqlErrorType;
import com.openjdbcproxy.grpc.StatementRequest;
import com.openjdbcproxy.grpc.StatementServiceGrpc;
import com.openjdbcproxy.grpc.TargetCall;
import com.openjdbcproxy.grpc.TransactionInfo;
import com.openjdbcproxy.grpc.TransactionStatus;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.input.ReaderInputStream;
import org.apache.commons.lang3.StringUtils;
import org.openjdbcproxy.constants.CommonConstants;
import org.openjdbcproxy.grpc.dto.OpQueryResult;
import org.openjdbcproxy.grpc.dto.Parameter;
import org.openjdbcproxy.grpc.server.utils.DateTimeUtils;
import org.openjdbcproxy.database.DatabaseUtils;
import org.openjdbcproxy.grpc.server.utils.DriverUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.openjdbcproxy.constants.CommonConstants.MAX_LOB_DATA_BLOCK_SIZE;
import static org.openjdbcproxy.grpc.SerializationHandler.deserialize;
import static org.openjdbcproxy.grpc.SerializationHandler.serialize;
import static org.openjdbcproxy.grpc.server.Constants.EMPTY_LIST;
import static org.openjdbcproxy.grpc.server.Constants.EMPTY_MAP;
import static org.openjdbcproxy.grpc.server.Constants.EMPTY_STRING;
import static org.openjdbcproxy.grpc.server.Constants.SHA_256;
import static org.openjdbcproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;

@Slf4j
@RequiredArgsConstructor
//TODO this became a GOD class, need to try to rdelegate some work to specialized other classes where possible, it is challenging because many GRPC callbacks rely on attributes present here to work.
public class StatementServiceImpl extends StatementServiceGrpc.StatementServiceImplBase {

    private final Map<String, HikariDataSource> datasourceMap = new ConcurrentHashMap<>();
    private final SessionManager sessionManager;
    private final CircuitBreaker circuitBreaker;
    private static final List<String> INPUT_STREAM_TYPES = Arrays.asList("RAW", "BINARY VARYING", "BYTEA");

    static {
        DriverUtils.registerDrivers();
    }

    @Override
    public void connect(ConnectionDetails connectionDetails, StreamObserver<SessionInfo> responseObserver) {
        String connHash = hashConnectionDetails(connectionDetails);
        log.info("connect connHash = " + connHash);

        HikariDataSource ds = this.datasourceMap.get(connHash);
        if (ds == null) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(this.parseUrl(connectionDetails.getUrl()));
            config.setUsername(connectionDetails.getUser());
            config.setPassword(connectionDetails.getPassword());

            // Configure HikariCP using client properties or defaults
            configureHikariPool(config, connectionDetails);

            ds = new HikariDataSource(config);
            this.datasourceMap.put(connHash, ds);
        }

        this.sessionManager.registerClientUUID(connHash, connectionDetails.getClientUUID());

        responseObserver.onNext(SessionInfo.newBuilder()
                .setConnHash(connHash)
                .setClientUUID(connectionDetails.getClientUUID())
                .build()
        );
        responseObserver.onCompleted();
    }

    @SneakyThrows
    @Override
    public void executeUpdate(StatementRequest request, StreamObserver<OpResult> responseObserver) {
        log.info("Executing update {}", request.getSql());
        String stmtHash = SqlStatementXXHash.hashSqlQuery(request.getSql());
        circuitBreaker.preCheck(stmtHash);
        int updated = 0;
        SessionInfo returnSessionInfo = request.getSession();
        ConnectionSessionDTO dto = ConnectionSessionDTO.builder().build();

        Statement stmt = null;
        String psUUID = "";
        OpResult.Builder opResultBuilder = OpResult.newBuilder();

        try {
            dto = sessionConnection(request.getSession(), this.isAddBatchOperation(request) || this.hasAutoGeneratedKeysFlag(request));
            returnSessionInfo = dto.getSession();

            List<Parameter> params = deserialize(request.getParameters().toByteArray(), List.class);
            PreparedStatement ps = dto.getSession() != null && StringUtils.isNotBlank(dto.getSession().getSessionUUID())
                    && StringUtils.isNoneBlank(request.getStatementUUID()) ?
                    sessionManager.getPreparedStatement(dto.getSession(), request.getStatementUUID()) : null;
            if (CollectionUtils.isNotEmpty(params) || ps != null) {
                if (StringUtils.isNotEmpty(request.getStatementUUID())) {
                    Collection<Object> lobs = sessionManager.getLobs(dto.getSession());
                    for (Object o : lobs) {
                        LobDataBlocksInputStream lobIS = (LobDataBlocksInputStream) o;
                        Map<String, Object> metadata = (Map<String, Object>) sessionManager.getAttr(dto.getSession(), lobIS.getUuid());
                        Integer parameterIndex = (Integer) metadata.get(CommonConstants.PREPARED_STATEMENT_BINARY_STREAM_INDEX);
                        ps.setBinaryStream(parameterIndex, lobIS);
                    }
                    if (!DbName.SQL_SERVER.equals(dto.getDbName())) {//SQL server treats binary streams differently
                        sessionManager.waitLobStreamsConsumption(dto.getSession());
                    }
                    if (ps != null) {
                        this.addParametersPreparedStatement(dto, ps, params);
                    }
                } else {
                    ps = this.createPreparedStatement(dto, request.getSql(), params, request);
                    if (this.hasAutoGeneratedKeysFlag(request)) {
                        String psNewUUID = sessionManager.registerPreparedStatement(dto.getSession(), ps);
                        opResultBuilder.setUuid(psNewUUID);
                    }
                }
                if (this.isAddBatchOperation(request)) {
                    ps.addBatch();
                    if (request.getStatementUUID().isBlank()) {
                        psUUID = sessionManager.registerPreparedStatement(dto.getSession(), ps);
                    } else {
                        psUUID = request.getStatementUUID();
                    }
                } else {
                    updated = ps.executeUpdate();
                }
                stmt = ps;
            } else {
                stmt = this.createStatement(dto.getConnection(), request);
                updated = stmt.executeUpdate(request.getSql());
            }

            if (this.isAddBatchOperation(request)) {
                responseObserver.onNext(opResultBuilder
                        .setType(ResultType.UUID_STRING)
                        .setSession(returnSessionInfo)
                        .setValue(ByteString.copyFrom(serialize(psUUID))).build());
            } else {
                responseObserver.onNext(opResultBuilder
                        .setType(ResultType.INTEGER)
                        .setSession(returnSessionInfo)
                        .setValue(ByteString.copyFrom(serialize(updated))).build());
            }
            responseObserver.onCompleted();
            circuitBreaker.onSuccess(stmtHash);
        } catch (SQLDataException e) {// Need a second catch just for the acquisition of the connection
            circuitBreaker.onFailure(stmtHash, e);
            log.error("SQL data failure during update execution: " + e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver, SqlErrorType.SQL_DATA_EXCEPTION);
        } catch (SQLException e) {// Need a second catch just for the acquisition of the connection
            circuitBreaker.onFailure(stmtHash, e);
            log.error("Failure during update execution: " + e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver);
        } finally {
            //If there is no session, close statement and connection
            if (dto.getSession() == null || StringUtils.isEmpty(dto.getSession().getSessionUUID())) {
                assert stmt != null;
                try {
                    stmt.close();
                } catch (SQLException e) {
                    log.error("Failure closing statement: " + e.getMessage(), e);
                }
                try {
                    stmt.getConnection().close();
                } catch (SQLException e) {
                    log.error("Failure closing connection: " + e.getMessage(), e);
                }
            }
        }
    }

    private boolean hasAutoGeneratedKeysFlag(StatementRequest request) {
        if (request.getProperties().isEmpty()) {
            return false;
        }
        Map<String, Object> properties = deserialize(request.getProperties().toByteArray(), Map.class);
        Integer autoGeneratedKeys = (Integer) properties.get(CommonConstants.STATEMENT_AUTO_GENERATED_KEYS_KEY);
        return autoGeneratedKeys != null && autoGeneratedKeys == Statement.RETURN_GENERATED_KEYS;
    }

    private boolean isAddBatchOperation(StatementRequest request) {
        if (request.getProperties().isEmpty()) {
            return false;
        }
        Map<String, Object> properties = deserialize(request.getProperties().toByteArray(), Map.class);
        Boolean batchFlag = (Boolean) properties.get(CommonConstants.PREPARED_STATEMENT_ADD_BATCH_FLAG);
        return batchFlag != null && batchFlag;
    }

    private Statement createStatement(Connection connection, StatementRequest request) throws SQLException {
        try {
            if (StringUtils.isNotEmpty(request.getStatementUUID())) {
                return this.sessionManager.getStatement(request.getSession(), request.getStatementUUID());
            }
            if (request.getProperties().isEmpty()) {
                return connection.createStatement();
            }
            Map<String, Object> properties = deserialize(request.getProperties().toByteArray(), Map.class);

            if (properties.isEmpty()) {
                return connection.createStatement();
            }
            if (properties.size() == 2) {
                return connection.createStatement(
                        (Integer) properties.get(CommonConstants.STATEMENT_RESULT_SET_TYPE_KEY),
                        (Integer) properties.get(CommonConstants.STATEMENT_RESULT_SET_CONCURRENCY_KEY));
            }
            if (properties.size() == 3) {
                return connection.createStatement(
                        (Integer) properties.get(CommonConstants.STATEMENT_RESULT_SET_TYPE_KEY),
                        (Integer) properties.get(CommonConstants.STATEMENT_RESULT_SET_CONCURRENCY_KEY),
                        (Integer) properties.get(CommonConstants.STATEMENT_RESULT_SET_HOLDABILITY_KEY));
            }
            throw new SQLException("Incorrect number of properties for creating a new statement.");
        } catch (RuntimeException re) {
            throw new SQLException("Unable to create statement: " + re.getMessage(), re);
        }
    }

    private PreparedStatement createPreparedStatement(ConnectionSessionDTO dto, String sql, List<Parameter> params,
                                                      StatementRequest request)
            throws SQLException {
        log.info("Creating prepared statement for {}", sql);

        PreparedStatement ps = null;
        Map<String, Object> properties = EMPTY_MAP;
        if (!request.getProperties().isEmpty()) {
            properties = deserialize(request.getProperties().toByteArray(), Map.class);
        }
        if (properties.isEmpty()) {
            ps = dto.getConnection().prepareStatement(sql);
        }
        if (properties.size() == 1) {
            int[] columnIndexes = (int[]) properties.get(CommonConstants.STATEMENT_COLUMN_INDEXES_KEY);
            String[] columnNames = (String[]) properties.get(CommonConstants.STATEMENT_COLUMN_INDEXES_KEY);
            Boolean isAddBatch = (Boolean) properties.get(CommonConstants.PREPARED_STATEMENT_ADD_BATCH_FLAG);
            Integer autoGeneratedKeys = (Integer) properties.get(CommonConstants.STATEMENT_AUTO_GENERATED_KEYS_KEY);
            if (columnIndexes != null) {
                ps = dto.getConnection().prepareStatement(sql, columnIndexes);
            } else if (columnNames != null) {
                ps = dto.getConnection().prepareStatement(sql, columnNames);
            } else if (isAddBatch != null && isAddBatch) {
                ps = dto.getConnection().prepareStatement(sql);
            } else if (autoGeneratedKeys != null) {
                ps = dto.getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            }
        }
        Integer resultSetType = (Integer) properties.get(CommonConstants.STATEMENT_RESULT_SET_TYPE_KEY);
        Integer resultSetConcurrency = (Integer) properties.get(CommonConstants.STATEMENT_RESULT_SET_CONCURRENCY_KEY);
        Integer resultSetHoldability = (Integer) properties.get(CommonConstants.STATEMENT_RESULT_SET_HOLDABILITY_KEY);

        if (resultSetType != null && resultSetConcurrency != null && resultSetHoldability == null) {
            ps = dto.getConnection().prepareStatement(sql, resultSetType, resultSetConcurrency);
        }
        if (resultSetType != null && resultSetConcurrency != null && resultSetHoldability != null) {
            ps = dto.getConnection().prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        }
        if (ps == null) {
            throw new SQLException("Incorrect number of properties for creating a new prepared statement.");
        }

        this.addParametersPreparedStatement(dto, ps, params);
        return ps;
    }

    private void addParametersPreparedStatement(ConnectionSessionDTO dto, PreparedStatement ps, List<Parameter> params)
            throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Parameter parameter = params.get(i);
            this.addParam(dto.getSession(), parameter.getIndex(), ps, params.get(i));
        }
    }

    @Override
    public void executeQuery(StatementRequest request, StreamObserver<OpResult> responseObserver) {
        log.info("Executing query for {}", request.getSql());
        if (request.getSql().contains("select oi1_0.id,oi1_0.order_id,oi1_0.product_id,oi1_0.quantity from order_items oi1_0")) {
            int stop = 0;
        }
        String stmtHash = SqlStatementXXHash.hashSqlQuery(request.getSql());
        try {
            circuitBreaker.preCheck(stmtHash);
            ConnectionSessionDTO dto = this.sessionConnection(request.getSession(), true);

            List<Parameter> params = deserialize(request.getParameters().toByteArray(), List.class);
            if (CollectionUtils.isNotEmpty(params)) {
                PreparedStatement ps = this.createPreparedStatement(dto, request.getSql(), params, request);
                String resultSetUUID = this.sessionManager.registerResultSet(dto.getSession(), ps.executeQuery());
                this.handleResultSet(dto.getSession(), resultSetUUID, responseObserver);
            } else {
                Statement stmt = this.createStatement(dto.getConnection(), request);
                String resultSetUUID = this.sessionManager.registerResultSet(dto.getSession(),
                        stmt.executeQuery(request.getSql()));
                this.handleResultSet(dto.getSession(), resultSetUUID, responseObserver);
            }
            circuitBreaker.onSuccess(stmtHash);
        } catch (SQLException e) {
            circuitBreaker.onFailure(stmtHash, e);
            log.error("Failure during query execution: " + e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver);
        }
    }

    @Override
    public StreamObserver<LobDataBlock> createLob(StreamObserver<LobReference> responseObserver) {
        log.info("Creating LOB");
        return new ServerCallStreamObserver<>() {
            private SessionInfo sessionInfo;
            private String lobUUID;
            private String stmtUUID;
            private LobType lobType;
            private LobDataBlocksInputStream lobDataBlocksInputStream = null;
            private final AtomicBoolean isFirstBlock = new AtomicBoolean(true);
            private final AtomicInteger countBytesWritten = new AtomicInteger(0);

            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void setOnCancelHandler(Runnable runnable) {

            }

            @Override
            public void setCompression(String s) {

            }

            @Override
            public boolean isReady() {
                return false;
            }

            @Override
            public void setOnReadyHandler(Runnable runnable) {

            }

            @Override
            public void request(int i) {

            }

            @Override
            public void setMessageCompression(boolean b) {

            }

            @Override
            public void disableAutoInboundFlowControl() {

            }

            @Override
            public void onNext(LobDataBlock lobDataBlock) {
                try {
                    this.lobType = lobDataBlock.getLobType();
                    log.info("lob data block received, lob type {}", this.lobType);
                    ConnectionSessionDTO dto = sessionConnection(lobDataBlock.getSession(), true);
                    Connection conn = dto.getConnection();
                    if (StringUtils.isEmpty(lobDataBlock.getSession().getSessionUUID()) || this.lobUUID == null) {
                        if (LobType.LT_BLOB.equals(this.lobType)) {
                            Blob newBlob = conn.createBlob();
                            this.lobUUID = UUID.randomUUID().toString();
                            sessionManager.registerLob(dto.getSession(), newBlob, this.lobUUID);
                        } else if (LobType.LT_CLOB.equals(this.lobType)) {
                            Clob newClob = conn.createClob();
                            this.lobUUID = UUID.randomUUID().toString();
                            sessionManager.registerLob(dto.getSession(), newClob, this.lobUUID);
                        }
                    }

                    int bytesWritten = 0;
                    switch (this.lobType) {
                        case LT_BLOB: {
                            Blob blob = sessionManager.getLob(dto.getSession(), this.lobUUID);
                            byte[] byteArrayData = lobDataBlock.getData().toByteArray();
                            bytesWritten = blob.setBytes(lobDataBlock.getPosition(), byteArrayData);
                            break;
                        }
                        case LT_CLOB: {
                            Clob clob = sessionManager.getLob(dto.getSession(), this.lobUUID);
                            byte[] byteArrayData = lobDataBlock.getData().toByteArray();
                            Writer writer = clob.setCharacterStream(lobDataBlock.getPosition());
                            writer.write(new String(byteArrayData, StandardCharsets.UTF_8).toCharArray());
                            bytesWritten = byteArrayData.length;
                            break;
                        }
                        case LT_BINARY_STREAM: {
                            if (this.lobUUID == null) {
                                byte[] metadataBytes = lobDataBlock.getMetadata().toByteArray();
                                if (metadataBytes == null && metadataBytes.length < 1) {
                                    throw new SQLException("Metadata empty for binary stream type.");
                                }
                                Map<Integer, Object> metadata = deserialize(metadataBytes, Map.class);
                                String sql = (String) metadata.get(CommonConstants.PREPARED_STATEMENT_BINARY_STREAM_SQL);
                                PreparedStatement ps;
                                String preparedStatementUUID = (String) metadata.get(CommonConstants.PREPARED_STATEMENT_UUID_BINARY_STREAM);
                                if (StringUtils.isNotEmpty(preparedStatementUUID)) {
                                    stmtUUID = preparedStatementUUID;
                                } else {
                                    ps = dto.getConnection().prepareStatement(sql);
                                    stmtUUID = sessionManager.registerPreparedStatement(dto.getSession(), ps);
                                }

                                //Add bite stream as parameter to the prepared statement
                                lobDataBlocksInputStream = new LobDataBlocksInputStream(lobDataBlock);
                                this.lobUUID = lobDataBlocksInputStream.getUuid();
                                //Only needs to be registered so we can wait it to receive all bytes before performing the update.
                                sessionManager.registerLob(dto.getSession(), lobDataBlocksInputStream, lobDataBlocksInputStream.getUuid());
                                sessionManager.registerAttr(dto.getSession(), lobDataBlocksInputStream.getUuid(), metadata);
                                //Need to first send the ref to the client before adding the stream as a parameter
                                sendLobRef(dto, lobDataBlock.getData().toByteArray().length);
                            } else {
                                lobDataBlocksInputStream.addBlock(lobDataBlock);
                            }
                            break;
                        }
                    }
                    this.countBytesWritten.addAndGet(bytesWritten);
                    this.sessionInfo = dto.getSession();

                    if (isFirstBlock.get()) {
                        sendLobRef(dto, bytesWritten);
                    }

                } catch (SQLException e) {
                    sendSQLExceptionMetadata(e, responseObserver);
                } catch (Exception e) {
                    sendSQLExceptionMetadata(new SQLException("Unable to write data: " + e.getMessage(), e), responseObserver);
                }
            }

            private void sendLobRef(ConnectionSessionDTO dto, int bytesWritten) {
                log.info("Returning lob ref {}", this.lobUUID);
                //Send one flag response to indicate that the Blob has been created successfully and the first
                // block fo data has been written successfully.
                LobReference.Builder lobRefBuilder = LobReference.newBuilder()
                        .setSession(dto.getSession())
                        .setUuid(this.lobUUID)
                        .setLobType(this.lobType)
                        .setBytesWritten(bytesWritten);
                if (this.stmtUUID != null) {
                    lobRefBuilder.setStmtUUID(this.stmtUUID);
                }
                responseObserver.onNext(lobRefBuilder.build());
                isFirstBlock.set(false);
            }

            @Override
            public void onError(Throwable throwable) {
                log.error("Failure lob stream: " + throwable.getMessage(), throwable);
                if (lobDataBlocksInputStream != null) {
                    lobDataBlocksInputStream.finish(true);
                }
            }

            @SneakyThrows
            @Override
            public void onCompleted() {
                if (lobDataBlocksInputStream != null) {
                    CompletableFuture.runAsync(() -> {
                        log.info("Finishing lob stream for lob ref {}", this.lobUUID);
                        lobDataBlocksInputStream.finish(true);
                    });
                }

                LobReference.Builder lobRefBuilder = LobReference.newBuilder()
                        .setSession(this.sessionInfo)
                        .setUuid(this.lobUUID)
                        .setLobType(this.lobType)
                        .setBytesWritten(this.countBytesWritten.get());
                if (this.stmtUUID != null) {
                    lobRefBuilder.setStmtUUID(this.stmtUUID);
                }

                //Send the final Lob reference with total count of written bytes.
                responseObserver.onNext(lobRefBuilder.build());
                responseObserver.onCompleted();
            }
        };
    }

    @Override
    public void readLob(ReadLobRequest request, StreamObserver<LobDataBlock> responseObserver) {
        log.debug("Reading lob {}", request.getLobReference().getUuid());
        try {
            LobReference lobRef = request.getLobReference();
            ReadLobContext readLobContext = this.findLobContext(request);
            InputStream inputStream = readLobContext.getInputStream();
            if (inputStream == null) {
                responseObserver.onNext(LobDataBlock.newBuilder()
                        .setSession(lobRef.getSession())
                        .setPosition(-1)
                        .setData(ByteString.copyFrom(new byte[0]))
                        .build());
                responseObserver.onCompleted();
                return;
            }
            //If the lob length is known the exact size of the next block is also known.
            boolean exactSizeKnown = readLobContext.getLobLength().isPresent() && readLobContext.getAvailableLength().isPresent();
            int nextByte = inputStream.read();
            int nextBlockSize = this.nextBlockSize(readLobContext, request.getPosition());
            byte[] nextBlock = new byte[nextBlockSize];
            int idx = -1;
            int currentPos = (int) request.getPosition();
            boolean nextBlockFullyEmpty = false;
            while (nextByte != -1) {
                nextBlock[++idx] = (byte) nextByte;
                nextBlockFullyEmpty = false;
                if (idx == nextBlockSize - 1) {
                    currentPos += (idx + 1);
                    log.info("Sending block of data size {} pos {}", idx + 1, currentPos);
                    //Send data to client in limited size blocks to safeguard server memory.
                    responseObserver.onNext(LobDataBlock.newBuilder()
                            .setSession(lobRef.getSession())
                            .setPosition(currentPos)
                            .setData(ByteString.copyFrom(nextBlock))
                            .build()
                    );
                    nextBlockSize = this.nextBlockSize(readLobContext, currentPos - 1);
                    if (nextBlockSize > 0) {//Might be a single small block then nextBlockSize will return negative.
                        nextBlock = new byte[nextBlockSize];
                    } else {
                        nextBlock = new byte[0];
                    }
                    nextBlockFullyEmpty = true;
                    idx = -1;
                }
                nextByte = inputStream.read();
            }

            //Send leftover bytes
            if (!nextBlockFullyEmpty && nextBlock.length > 0 && nextBlock[0] != -1) {

                byte[] adjustedSizeArray = (idx % MAX_LOB_DATA_BLOCK_SIZE != 0 && !exactSizeKnown) ?
                        trim(nextBlock) : nextBlock;
                currentPos = (int) request.getPosition() + idx;
                log.info("Sending leftover bytes size {} pos {}", idx, currentPos);
                responseObserver.onNext(LobDataBlock.newBuilder()
                        .setSession(lobRef.getSession())
                        .setPosition(currentPos)
                        .setData(ByteString.copyFrom(adjustedSizeArray))
                        .build()
                );
            }

            responseObserver.onCompleted();

        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] trim(byte[] nextBlock) {
        int lastBytePos = 0;
        for (int i = nextBlock.length - 1; i >= 0; i--) {
            int currentByte = nextBlock[i];
            if (currentByte != 0) {
                lastBytePos = i;
                break;
            }
        }

        byte[] trimmedArray = new byte[lastBytePos + 1];
        System.arraycopy(nextBlock, 0, trimmedArray, 0, lastBytePos + 1);
        return trimmedArray;
    }

    @Builder
    static class ReadLobContext {
        @Getter
        private InputStream inputStream;
        @Getter
        private Optional<Long> lobLength;
        @Getter
        private Optional<Integer> availableLength;
    }

    @SneakyThrows
    private ReadLobContext findLobContext(ReadLobRequest request) throws SQLException {
        InputStream inputStream = null;
        LobReference lobReference = request.getLobReference();
        ReadLobContext.ReadLobContextBuilder readLobContextBuilder = ReadLobContext.builder();
        switch (request.getLobReference().getLobType()) {
            case LT_BLOB: {
                inputStream = this.inputStreamFromBlob(sessionManager, lobReference, request, readLobContextBuilder);
                break;
            }
            case LT_BINARY_STREAM: {
                readLobContextBuilder.lobLength(Optional.empty());
                readLobContextBuilder.availableLength(Optional.empty());
                Object lobObj = sessionManager.getLob(lobReference.getSession(), lobReference.getUuid());
                if (lobObj instanceof Blob) {
                    inputStream = this.inputStreamFromBlob(sessionManager, lobReference, request, readLobContextBuilder);
                } else if (lobObj instanceof InputStream) {
                    inputStream = sessionManager.getLob(lobReference.getSession(), lobReference.getUuid());
                    inputStream.reset();//Might be a second read of the same stream, this guarantees that the position is at the start.
                    if (inputStream instanceof ByteArrayInputStream) {// Only used in SQL Server
                        ByteArrayInputStream bais = (ByteArrayInputStream) inputStream;
                        bais.reset();
                        readLobContextBuilder.lobLength(Optional.of((long) bais.available()));
                        readLobContextBuilder.availableLength(Optional.of(bais.available()));
                    }
                }
                break;
            }
            case LT_CLOB: {
                inputStream = this.inputStreamFromClob(sessionManager, lobReference, request, readLobContextBuilder);
                break;
            }
        }
        readLobContextBuilder.inputStream(inputStream);

        return readLobContextBuilder.build();
    }

    @SneakyThrows
    private InputStream inputStreamFromClob(SessionManager sessionManager, LobReference lobReference,
                                            ReadLobRequest request,
                                            ReadLobContext.ReadLobContextBuilder readLobContextBuilder) {
        Clob clob = sessionManager.getLob(lobReference.getSession(), lobReference.getUuid());
        long lobLength = clob.length();
        readLobContextBuilder.lobLength(Optional.of(lobLength));
        int availableLength = (request.getPosition() + request.getLength()) < lobLength ? request.getLength() :
                (int) (lobLength - request.getPosition() + 1);
        readLobContextBuilder.availableLength(Optional.of(availableLength));
        Reader reader = clob.getCharacterStream(request.getPosition(), availableLength);
        return ReaderInputStream.builder()
                .setReader(reader)
                .setCharset(StandardCharsets.UTF_8)
                .getInputStream();
    }

    private InputStream inputStreamFromBlob(SessionManager sessionManager, LobReference lobReference,
                                            ReadLobRequest request,
                                            ReadLobContext.ReadLobContextBuilder readLobContextBuilder)
            throws SQLException {
        Blob blob = sessionManager.getLob(lobReference.getSession(), lobReference.getUuid());
        long lobLength = blob.length();
        readLobContextBuilder.lobLength(Optional.of(lobLength));
        int availableLength = (request.getPosition() + request.getLength()) < lobLength ? request.getLength() :
                (int) (lobLength - request.getPosition() + 1);
        readLobContextBuilder.availableLength(Optional.of(availableLength));
        return blob.getBinaryStream(request.getPosition(), availableLength);
    }

    private int nextBlockSize(ReadLobContext readLobContext, long position) {

        //BinaryStreams do not have means to know the size of the lob like Blobs or Clobs.
        if (readLobContext.getAvailableLength().isEmpty() || readLobContext.getLobLength().isEmpty()) {
            return MAX_LOB_DATA_BLOCK_SIZE;
        }

        long lobLength = readLobContext.getLobLength().get();
        int length = readLobContext.getAvailableLength().get();

        //Single read situations
        int nextBlockSize = Math.min(MAX_LOB_DATA_BLOCK_SIZE, length);
        if ((int) lobLength == length && position == 1) {
            return length;
        }
        int nextPos = (int) (position + nextBlockSize);
        if (nextPos > lobLength) {
            nextBlockSize = Math.toIntExact(nextBlockSize - (nextPos - lobLength));
        } else if ((position + 1) % length == 0) {
            nextBlockSize = 0;
        }

        return nextBlockSize;
    }

    @Override
    public void terminateSession(SessionInfo sessionInfo, StreamObserver<SessionTerminationStatus> responseObserver) {
        try {
            log.info("Terminating session");
            this.sessionManager.terminateSession(sessionInfo);
            responseObserver.onNext(SessionTerminationStatus.newBuilder().setTerminated(true).build());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (Exception e) {
            sendSQLExceptionMetadata(new SQLException("Unable to terminate session: " + e.getMessage()), responseObserver);
        }
    }

    @Override
    public void startTransaction(SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        log.info("Starting transaction");
        try {
            SessionInfo activeSessionInfo = sessionInfo;

            //Start a session if none started yet.
            if (StringUtils.isEmpty(sessionInfo.getSessionUUID())) {
                Connection conn = this.datasourceMap.get(sessionInfo.getConnHash()).getConnection();
                activeSessionInfo = sessionManager.createSession(sessionInfo.getClientUUID(), conn);
            }
            Connection sessionConnection = sessionManager.getConnection(activeSessionInfo);
            //Start a transaction
            sessionConnection.setAutoCommit(Boolean.FALSE);

            TransactionInfo transactionInfo = TransactionInfo.newBuilder()
                    .setTransactionStatus(TransactionStatus.TRX_ACTIVE)
                    .setTransactionUUID(UUID.randomUUID().toString())
                    .build();

            SessionInfo.Builder sessionInfoBuilder = this.newBuilderFrom(activeSessionInfo);
            sessionInfoBuilder.setTransactionInfo(transactionInfo);

            responseObserver.onNext(sessionInfoBuilder.build());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (Exception e) {
            sendSQLExceptionMetadata(new SQLException("Unable to start transaction: " + e.getMessage()), responseObserver);
        }
    }

    @Override
    public void commitTransaction(SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        log.info("Commiting transaction");
        try {
            Connection conn = sessionManager.getConnection(sessionInfo);
            conn.commit();

            TransactionInfo transactionInfo = TransactionInfo.newBuilder()
                    .setTransactionStatus(TransactionStatus.TRX_COMMITED)
                    .setTransactionUUID(sessionInfo.getTransactionInfo().getTransactionUUID())
                    .build();

            SessionInfo.Builder sessionInfoBuilder = this.newBuilderFrom(sessionInfo);
            sessionInfoBuilder.setTransactionInfo(transactionInfo);

            responseObserver.onNext(sessionInfoBuilder.build());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (Exception e) {
            sendSQLExceptionMetadata(new SQLException("Unable to commit transaction: " + e.getMessage()), responseObserver);
        }
    }

    @Override
    public void rollbackTransaction(SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        log.info("Rollback transaction");
        try {
            Connection conn = sessionManager.getConnection(sessionInfo);
            conn.rollback();

            TransactionInfo transactionInfo = TransactionInfo.newBuilder()
                    .setTransactionStatus(TransactionStatus.TRX_ROLLBACK)
                    .setTransactionUUID(sessionInfo.getTransactionInfo().getTransactionUUID())
                    .build();

            SessionInfo.Builder sessionInfoBuilder = this.newBuilderFrom(sessionInfo);
            sessionInfoBuilder.setTransactionInfo(transactionInfo);

            responseObserver.onNext(sessionInfoBuilder.build());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (Exception e) {
            sendSQLExceptionMetadata(new SQLException("Unable to rollback transaction: " + e.getMessage()), responseObserver);
        }
    }

    @Override
    public void callResource(CallResourceRequest request, StreamObserver<CallResourceResponse> responseObserver) {
        try {
            if (!request.hasSession()) {
                throw new SQLException("No active session.");//TODO review might have to create a session in some cases
            }

            CallResourceResponse.Builder responseBuilder = CallResourceResponse.newBuilder();

            Object resource;
            switch (request.getResourceType()) {
                case RES_RESULT_SET:
                    resource = sessionManager.getResultSet(request.getSession(), request.getResourceUUID());
                    break;
                case RES_LOB:
                    resource = sessionManager.getLob(request.getSession(), request.getResourceUUID());
                    break;
                case RES_STATEMENT: {
                    ConnectionSessionDTO csDto = sessionConnection(request.getSession(), true);
                    responseBuilder.setSession(csDto.getSession());
                    Statement statement = null;
                    if (!request.getResourceUUID().isBlank()) {
                        statement = sessionManager.getStatement(csDto.getSession(), request.getResourceUUID());
                    } else {
                        statement = csDto.getConnection().createStatement();
                        String uuid = sessionManager.registerStatement(csDto.getSession(), statement);
                        responseBuilder.setResourceUUID(uuid);
                    }
                    resource = statement;
                    break;
                }
                case RES_PREPARED_STATEMENT: {
                    ConnectionSessionDTO csDto = sessionConnection(request.getSession(), true);
                    responseBuilder.setSession(csDto.getSession());
                    PreparedStatement ps = null;
                    if (!request.getResourceUUID().isBlank()) {
                        ps = sessionManager.getPreparedStatement(request.getSession(), request.getResourceUUID());
                    } else {
                        Map<String, Object> mapProperties = EMPTY_MAP;
                        if (!request.getProperties().isEmpty()) {
                            mapProperties = deserialize(request.getProperties().toByteArray(), Map.class);
                        }
                        ps = csDto.getConnection().prepareStatement((String) mapProperties.get(CommonConstants.PREPARED_STATEMENT_SQL_KEY));
                        String uuid = sessionManager.registerPreparedStatement(csDto.getSession(), ps);
                        responseBuilder.setResourceUUID(uuid);
                    }
                    resource = ps;
                    break;
                }
                case RES_CALLABLE_STATEMENT:
                    resource = sessionManager.getCallableStatement(request.getSession(), request.getResourceUUID());
                    break;
                case RES_CONNECTION: {
                    ConnectionSessionDTO csDto = sessionConnection(request.getSession(), true);
                    responseBuilder.setSession(csDto.getSession());
                    resource = csDto.getConnection();
                    break;
                }
                case RES_SAVEPOINT:
                    resource = sessionManager.getAttr(request.getSession(), request.getResourceUUID());
                    break;
                default:
                    throw new RuntimeException("Resource type invalid");
            }

            if (responseBuilder.getSession() == null || StringUtils.isBlank(responseBuilder.getSession().getSessionUUID())) {
                responseBuilder.setSession(request.getSession());
            }

            List<Object> paramsReceived = (request.getTarget().getParams().size() > 0) ?
                    deserialize(request.getTarget().getParams().toByteArray(), List.class) : EMPTY_LIST;
            Class<?> clazz = resource.getClass();
            if ((paramsReceived != null && paramsReceived.size() > 0) &&
                    ((CallType.CALL_RELEASE.equals(request.getTarget().getCallType()) &&
                            "Savepoint".equalsIgnoreCase(request.getTarget().getResourceName())) ||
                            (CallType.CALL_ROLLBACK.equals(request.getTarget().getCallType()))
                    )
            ) {
                Savepoint savepoint = (Savepoint) this.sessionManager.getAttr(request.getSession(),
                        (String) paramsReceived.get(0));
                paramsReceived.set(0, savepoint);
            }
            Method method = this.findMethodByName(JavaSqlInterfacesConverter.interfaceClass(clazz),
                    methodName(request.getTarget()), paramsReceived);
            java.lang.reflect.Parameter[] params = method.getParameters();
            Object resultFirstLevel = null;
            if (params != null && params.length > 0) {
                resultFirstLevel = method.invoke(resource, paramsReceived.toArray());
                if (resultFirstLevel instanceof CallableStatement) {
                    CallableStatement cs = (CallableStatement) resultFirstLevel;
                    resultFirstLevel = this.sessionManager.registerCallableStatement(responseBuilder.getSession(), cs);
                }
            } else {
                resultFirstLevel = method.invoke(resource);
                if (resultFirstLevel instanceof ResultSet) {
                    ResultSet rs = (ResultSet) resultFirstLevel;
                    resultFirstLevel = this.sessionManager.registerResultSet(responseBuilder.getSession(), rs);
                } else if (resultFirstLevel instanceof Array) {
                    Array array = (Array) resultFirstLevel;
                    String arrayUUID = UUID.randomUUID().toString();
                    this.sessionManager.registerAttr(responseBuilder.getSession(), arrayUUID, array);
                    resultFirstLevel = arrayUUID;
                }
            }
            if (resultFirstLevel instanceof Savepoint) {
                Savepoint sp = (Savepoint) resultFirstLevel;
                String uuid = UUID.randomUUID().toString();
                resultFirstLevel = uuid;
                this.sessionManager.registerAttr(responseBuilder.getSession(), uuid, sp);
            }
            if (request.getTarget().hasNextCall()) {
                //Second level calls, for cases like getMetadata().isAutoIncrement(int column)
                Class<?> clazzNext = resultFirstLevel.getClass();
                List<Object> paramsReceived2 = (request.getTarget().getNextCall().getParams().size() > 0) ?
                        deserialize(request.getTarget().getNextCall().getParams().toByteArray(), List.class) :
                        EMPTY_LIST;
                Method methodNext = this.findMethodByName(JavaSqlInterfacesConverter.interfaceClass(clazzNext),
                        methodName(request.getTarget().getNextCall()),
                        paramsReceived2);
                params = methodNext.getParameters();
                Object resultSecondLevel = null;
                if (params != null && params.length > 0) {
                    resultSecondLevel = methodNext.invoke(resultFirstLevel, paramsReceived2.toArray());
                } else {
                    resultSecondLevel = methodNext.invoke(resultFirstLevel);
                }
                if (resultSecondLevel instanceof ResultSet) {
                    ResultSet rs = (ResultSet) resultSecondLevel;
                    resultSecondLevel = this.sessionManager.registerResultSet(responseBuilder.getSession(), rs);
                }
                responseBuilder.setValues(ByteString.copyFrom(serialize(resultSecondLevel)));
            } else {
                responseBuilder.setValues(ByteString.copyFrom(serialize(resultFirstLevel)));
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (InvocationTargetException e) {
            if (e.getTargetException() instanceof SQLException) {
                SQLException sqlException = (SQLException) e.getTargetException();
                sendSQLExceptionMetadata(sqlException, responseObserver);
            } else {
                sendSQLExceptionMetadata(new SQLException("Unable to call resource: " + e.getTargetException().getMessage()),
                        responseObserver);
            }
        } catch (Exception e) {
            sendSQLExceptionMetadata(new SQLException("Unable to call resource: " + e.getMessage(), e), responseObserver);
        }
    }

    private Method findMethodByName(Class<?> clazz, String methodName, List<Object> params) {
        Method[] methods = clazz.getMethods();
        for (Method method : methods) {
            if (methodName.equalsIgnoreCase(method.getName())) {
                if (method.getParameters().length == params.size()) {
                    boolean paramTypesMatch = true;
                    for (int i = 0; i < params.size(); i++) {
                        java.lang.reflect.Parameter reflectParam = method.getParameters()[i];
                        Object receivedParam = params.get(i);
                        //TODO there is a potential issue here, if parameters are received null and more than one method recives the same amount of parameters there is no way to distinguish. Maybe send a Null object with the class type as an attribute and parse it back to null in server is a solution.
                        Class reflectType = this.getWrapperType(reflectParam.getType());
                        if (receivedParam != null && (!reflectType.equals(receivedParam.getClass()) &&
                                !reflectType.isAssignableFrom(receivedParam.getClass()))) {
                            paramTypesMatch = false;
                            break;
                        }
                    }
                    if (paramTypesMatch) {
                        return method;
                    }
                }
            }
        }
        throw new RuntimeException("Method " + methodName + " not found in class " + clazz.getName());
    }

    // Helper method to get the wrapper class for a primitive type
    private Class<?> getWrapperType(Class<?> primitiveType) {
        if (primitiveType == int.class) return Integer.class;
        if (primitiveType == boolean.class) return Boolean.class;
        if (primitiveType == byte.class) return Byte.class;
        if (primitiveType == char.class) return Character.class;
        if (primitiveType == double.class) return Double.class;
        if (primitiveType == float.class) return Float.class;
        if (primitiveType == long.class) return Long.class;
        if (primitiveType == short.class) return Short.class;
        return primitiveType; // for non-primitives
    }

    private String methodName(TargetCall target) throws SQLException {
        String prefix;
        switch (target.getCallType()) {
            case CALL_IS:
                prefix = "is";
                break;
            case CALL_GET:
                prefix = "get";
                break;
            case CALL_SET:
                prefix = "set";
                break;
            case CALL_ALL:
                prefix = "all";
                break;
            case CALL_NULLS:
                prefix = "nulls";
                break;
            case CALL_USES:
                prefix = "uses";
                break;
            case CALL_SUPPORTS:
                prefix = "supports";
                break;
            case CALL_STORES:
                prefix = "stores";
                break;
            case CALL_NULL:
                prefix = "null";
                break;
            case CALL_DOES:
                prefix = "does";
                break;
            case CALL_DATA:
                prefix = "data";
                break;
            case CALL_NEXT:
                prefix = "next";
                break;
            case CALL_CLOSE:
                prefix = "close";
                break;
            case CALL_WAS:
                prefix = "was";
                break;
            case CALL_CLEAR:
                prefix = "clear";
                break;
            case CALL_FIND:
                prefix = "find";
                break;
            case CALL_BEFORE:
                prefix = "before";
                break;
            case CALL_AFTER:
                prefix = "after";
                break;
            case CALL_FIRST:
                prefix = "first";
                break;
            case CALL_LAST:
                prefix = "last";
                break;
            case CALL_ABSOLUTE:
                prefix = "absolute";
                break;
            case CALL_RELATIVE:
                prefix = "relative";
                break;
            case CALL_PREVIOUS:
                prefix = "previous";
                break;
            case CALL_ROW:
                prefix = "row";
                break;
            case CALL_UPDATE:
                prefix = "update";
                break;
            case CALL_INSERT:
                prefix = "insert";
                break;
            case CALL_DELETE:
                prefix = "delete";
                break;
            case CALL_REFRESH:
                prefix = "refresh";
                break;
            case CALL_CANCEL:
                prefix = "cancel";
                break;
            case CALL_MOVE:
                prefix = "move";
                break;
            case CALL_OWN:
                prefix = "own";
                break;
            case CALL_OTHERS:
                prefix = "others";
                break;
            case CALL_UPDATES:
                prefix = "updates";
                break;
            case CALL_DELETES:
                prefix = "deletes";
                break;
            case CALL_INSERTS:
                prefix = "inserts";
                break;
            case CALL_LOCATORS:
                prefix = "locators";
                break;
            case CALL_AUTO:
                prefix = "auto";
                break;
            case CALL_GENERATED:
                prefix = "generated";
                break;
            case CALL_RELEASE:
                prefix = "release";
                break;
            case CALL_NATIVE:
                prefix = "native";
                break;
            case CALL_PREPARE:
                prefix = "prepare";
                break;
            case CALL_ROLLBACK:
                prefix = "rollback";
                break;
            case CALL_ABORT:
                prefix = "abort";
                break;
            case CALL_EXECUTE:
                prefix = "execute";
                break;
            case CALL_ADD:
                prefix = "add";
                break;
            case CALL_ENQUOTE:
                prefix = "enquote";
                break;
            case CALL_REGISTER:
                prefix = "register";
                break;
            case CALL_LENGTH:
                prefix = "length";
                break;
            case UNRECOGNIZED:
                throw new SQLException("CALL type not supported.");
            default:
                throw new SQLException("CALL type not supported.");
        }
        return prefix + target.getResourceName();
    }


    private SessionInfo.Builder newBuilderFrom(SessionInfo activeSessionInfo) {
        return SessionInfo.newBuilder()
                .setConnHash(activeSessionInfo.getConnHash())
                .setClientUUID(activeSessionInfo.getClientUUID())
                .setSessionUUID(activeSessionInfo.getSessionUUID())
                .setSessionStatus(activeSessionInfo.getSessionStatus())
                .setTransactionInfo(activeSessionInfo.getTransactionInfo());
    }

    /**
     * Finds a suitable connection for the current sessionInfo.
     * If there is a connection already in the sessionInfo reuse it, if not ge a fresh one from the data source.
     *
     * @param sessionInfo        - current sessionInfo object.
     * @param startSessionIfNone - if true will start a new sessionInfo if none exists.
     * @return ConnectionSessionDTO
     * @throws SQLException if connection not found or closed (by timeout or other reason)
     */
    private ConnectionSessionDTO sessionConnection(SessionInfo sessionInfo, boolean startSessionIfNone) throws SQLException {
        ConnectionSessionDTO.ConnectionSessionDTOBuilder dtoBuilder = ConnectionSessionDTO.builder();
        dtoBuilder.session(sessionInfo);
        Connection conn;
        if (StringUtils.isNotEmpty(sessionInfo.getSessionUUID())) {
            conn = this.sessionManager.getConnection(sessionInfo);
            if (conn == null) {
                throw new SQLException("Connection not found for this sessionInfo");
            }
            dtoBuilder.dbName(DatabaseUtils.resolveDbName(conn.getMetaData().getURL()));
            if (conn.isClosed()) {
                throw new SQLException("Connection is closed");
            }
        } else {
            //TODO check why reaches here and can't find the datasource sometimes, conn hash should never change for a single client
            //log.info("Lookup connection hash -> " + sessionInfo.getConnHash());
            conn = this.datasourceMap.get(sessionInfo.getConnHash()).getConnection();
            if (startSessionIfNone) {
                SessionInfo updatedSession = this.sessionManager.createSession(sessionInfo.getClientUUID(), conn);
                dtoBuilder.session(updatedSession);
            }
        }
        dtoBuilder.connection(conn);

        return dtoBuilder.build();
    }

    private void handleResultSet(SessionInfo session, String resultSetUUID, StreamObserver<OpResult> responseObserver)
            throws SQLException {
        ResultSet rs = this.sessionManager.getResultSet(session, resultSetUUID);
        OpQueryResult.OpQueryResultBuilder queryResultBuilder = OpQueryResult.builder();
        int columnCount = rs.getMetaData().getColumnCount();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < columnCount; i++) {
            labels.add(rs.getMetaData().getColumnName(i + 1));
        }
        queryResultBuilder.labels(labels);

        List<Object[]> results = new ArrayList<>();
        int row = 0;
        boolean justSent = false;
        while (rs.next()) {
            justSent = false;
            row++;
            Object[] rowValues = new Object[columnCount];
            for (int i = 0; i < columnCount; i++) {
                int colType = rs.getMetaData().getColumnType(i + 1);
                String colTypeName = rs.getMetaData().getColumnTypeName(i + 1);
                Object currentValue = null;
                //Postgres uses type BYTEA which translates to type VARBINARY
                switch (colType) {
                    case Types.VARBINARY: {
                        if ("BLOB".equalsIgnoreCase(colTypeName)) {
                            currentValue = this.treatAsBlob(session, rs, i);
                        } else {
                            currentValue = this.treatAsBinary(session, rs, i);
                        }
                        break;
                    }
                    case Types.BLOB, Types.LONGVARBINARY: {
                        currentValue = this.treatAsBlob(session, rs, i);
                        break;
                    }
                    case Types.CLOB: {
                        Clob clob = rs.getClob(i + 1);
                        if (clob == null) {
                            currentValue = null;
                        } else {
                            String clobUUID = UUID.randomUUID().toString();
                            //CLOB needs to be prefixed as per it can be read in the JDBC driver by getString method and it would be valid to return just a UUID as string
                            currentValue = CommonConstants.OJP_CLOB_PREFIX + clobUUID;
                            this.sessionManager.registerLob(session, clob, clobUUID);
                        }
                        break;
                    }
                    case Types.BINARY: {
                        currentValue = treatAsBinary(session, rs, i);
                        break;
                    }
                    case Types.DATE: {
                        Date date = rs.getDate(i + 1);
                        if ("YEAR".equalsIgnoreCase(colTypeName)) {
                            currentValue = date.toLocalDate().getYear();
                        } else {
                            currentValue = date;
                        }
                        break;
                    }
                    case Types.TIMESTAMP: {
                        currentValue = rs.getTimestamp(i + 1);
                        break;
                    }
                    default: {
                        currentValue = rs.getObject(i + 1);
                        //com.microsoft.sqlserver.jdbc.DateTimeOffset special case as per it does not implement any standar java.sql interface.
                        if ("datetimeoffset".equalsIgnoreCase(colTypeName) && colType == -155) {
                            currentValue = DateTimeUtils.extractOffsetDateTime(currentValue);
                        }
                        break;
                    }
                }
                rowValues[i] = currentValue;
            }
            results.add(rowValues);

            if (row % CommonConstants.ROWS_PER_RESULT_SET_DATA_BLOCK == 0) {
                justSent = true;
                //Send a block of records
                responseObserver.onNext(this.wrapResults(session, results, queryResultBuilder, resultSetUUID));
                queryResultBuilder = OpQueryResult.builder();// Recreate the builder to not send labels in every block.
                results = new ArrayList<>();
            }
        }

        if (!justSent) {
            //Send a block of remaining records
            responseObserver.onNext(this.wrapResults(session, results, queryResultBuilder, resultSetUUID));
        }

        responseObserver.onCompleted();

    }

    private Object treatAsBlob(SessionInfo session, ResultSet rs, int i) throws SQLException {
        Blob blob = rs.getBlob(i + 1);
        if (blob == null) {
            return null;
        }
        Object logUUID = UUID.randomUUID().toString();
        this.sessionManager.registerLob(session, blob, logUUID.toString());
        return logUUID;
    }

    @SneakyThrows
    private Object treatAsBinary(SessionInfo session, ResultSet rs, int i) throws SQLException {
        int precision = rs.getMetaData().getPrecision(i + 1);
        String catalogName = rs.getMetaData().getCatalogName(i + 1);
        String colClassName = rs.getMetaData().getColumnClassName(i + 1);
        String colTypeName = rs.getMetaData().getColumnTypeName(i + 1);
        colTypeName = colTypeName != null ? colTypeName : "";
        Object binaryValue = null;
        boolean sqlServerVarbinary = "VARBINARY".equalsIgnoreCase(colTypeName); //TODO add dbName check here
        if (precision == 1 && !"[B".equalsIgnoreCase(colClassName)) { //it is a single byte and is not of class byte array([B)
            binaryValue = rs.getByte(i + 1);
        } else if ((StringUtils.isNotEmpty(catalogName) || "[B".equalsIgnoreCase(colClassName)) &&
                !INPUT_STREAM_TYPES.contains(colTypeName.toUpperCase())) {
            binaryValue = rs.getBytes(i + 1);
        } else {
            InputStream inputStream = rs.getBinaryStream(i + 1);
            if (inputStream == null) {
                return null;
            }
            //TODO do it only for sql server as per sql server cannot move the cursor or read binary streams in multiple threads
            byte[] allBytes = inputStream.readAllBytes();
            inputStream = new ByteArrayInputStream(allBytes);

            binaryValue = UUID.randomUUID().toString();
            this.sessionManager.registerLob(session, inputStream, binaryValue.toString());
        }
        return binaryValue;
    }

    private OpResult wrapResults(SessionInfo sessionInfo,
                                 List<Object[]> results,
                                 OpQueryResult.OpQueryResultBuilder queryResultBuilder,
                                 String resultSetUUID) {

        OpResult.Builder resultsBuilder = OpResult.newBuilder();
        resultsBuilder.setSession(sessionInfo);
        resultsBuilder.setType(ResultType.RESULT_SET_DATA);
        queryResultBuilder.resultSetUUID(resultSetUUID);
        queryResultBuilder.rows(results);
        resultsBuilder.setValue(ByteString.copyFrom(serialize(queryResultBuilder.build())));

        return resultsBuilder.build();
    }

    private void addParam(SessionInfo session, int idx, PreparedStatement ps, Parameter param) throws SQLException {
        log.info("Adding parameter idx {} type {}", idx, param.getType().toString());
        switch (param.getType()) {
            case INT:
                ps.setInt(idx, (int) param.getValues().get(0));
                break;
            case DOUBLE:
                ps.setDouble(idx, (double) param.getValues().get(0));
                break;
            case STRING:
                ps.setString(idx, (String) param.getValues().get(0));
                break;
            case LONG:
                ps.setLong(idx, (long) param.getValues().get(0));
                break;
            case BOOLEAN:
                ps.setBoolean(idx, (boolean) param.getValues().get(0));
                break;
            case BIG_DECIMAL:
                ps.setBigDecimal(idx, (BigDecimal) param.getValues().get(0));
                break;
            case FLOAT:
                ps.setFloat(idx, (float) param.getValues().get(0));
                break;
            case BYTES:
                ps.setBytes(idx, (byte[]) param.getValues().get(0));
                break;
            case BYTE:
                ps.setByte(idx, ((byte[]) param.getValues().get(0))[0]);//Comes as an array of bytes with one element.
                break;
            case DATE:
                ps.setDate(idx, (Date) param.getValues().get(0));
                break;
            case TIME:
                ps.setTime(idx, (Time) param.getValues().get(0));
                break;
            case TIMESTAMP:
                ps.setTimestamp(idx, (Timestamp) param.getValues().get(0));
                break;
            //LOB types
            case BLOB:
                Object blobUUID = param.getValues().get(0);
                if (blobUUID == null) {
                    ps.setBlob(idx, (Blob) null);
                } else {
                    ps.setBlob(idx, this.sessionManager.<Blob>getLob(session, (String) blobUUID));
                }
                break;
            case CLOB: {
                Object clobUUID = param.getValues().get(0);
                if (clobUUID == null) {
                    ps.setBlob(idx, (Blob) null);
                } else {
                    ps.setBlob(idx, this.sessionManager.<Blob>getLob(session, (String) clobUUID));
                }
                Clob clob = this.sessionManager.getLob(session, (String) param.getValues().get(0));
                ps.setClob(idx, clob.getCharacterStream());
                break;
            }
            case BINARY_STREAM: {
                Object inputStreamValue = param.getValues().get(0);
                if (inputStreamValue == null) {
                    ps.setBinaryStream(idx, null);
                } else {
                    InputStream is = (InputStream) inputStreamValue;
                    if (param.getValues().size() > 1) {
                        Long size = (Long) param.getValues().get(1);
                        ps.setBinaryStream(idx, is, size);
                    } else {
                        ps.setBinaryStream(idx, is);
                    }
                }
                break;
            }
            case NULL: {
                int sqlType = (int) param.getValues().get(0);
                ps.setNull(idx, sqlType);
                break;
            }
            default:
                ps.setObject(idx, param.getValues().get(0));
                break;
        }
    }

    private String parseUrl(String url) {
        if (url == null) {
            return url;
        }
        return url.replaceAll(CommonConstants.OJP_REGEX_PATTERN + "_", EMPTY_STRING);
    }

    private String hashConnectionDetails(ConnectionDetails connectionDetails) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(SHA_256);
            messageDigest.update((connectionDetails.getUrl() + connectionDetails.getUser() + connectionDetails.getPassword())
                    .getBytes());
            return new String(messageDigest.digest());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void configureHikariPool(HikariConfig config, ConnectionDetails connectionDetails) {
        Properties clientProperties = null;

        // Try to deserialize properties from client if provided
        if (!connectionDetails.getProperties().isEmpty()) {
            try {
                clientProperties = deserialize(connectionDetails.getProperties().toByteArray(), Properties.class);
                log.info("Received {} properties from client for connection pool configuration", clientProperties.size());
            } catch (Exception e) {
                log.warn("Failed to deserialize client properties, using defaults: {}", e.getMessage());
            }
        }

        // Configure basic connection pool settings first
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        // Configure HikariCP pool settings using client properties or defaults
        config.setMaximumPoolSize(getIntProperty(clientProperties, "ojp.connection.pool.maximumPoolSize", CommonConstants.DEFAULT_MAXIMUM_POOL_SIZE));
        config.setMinimumIdle(getIntProperty(clientProperties, "ojp.connection.pool.minimumIdle", CommonConstants.DEFAULT_MINIMUM_IDLE));
        config.setIdleTimeout(getLongProperty(clientProperties, "ojp.connection.pool.idleTimeout", CommonConstants.DEFAULT_IDLE_TIMEOUT));
        config.setMaxLifetime(getLongProperty(clientProperties, "ojp.connection.pool.maxLifetime", CommonConstants.DEFAULT_MAX_LIFETIME));
        config.setConnectionTimeout(getLongProperty(clientProperties, "ojp.connection.pool.connectionTimeout", CommonConstants.DEFAULT_CONNECTION_TIMEOUT));

        log.info("HikariCP configured with maximumPoolSize={}, minimumIdle={}, poolName={}",
                config.getMaximumPoolSize(), config.getMinimumIdle(), config.getPoolName());
    }

    private int getIntProperty(Properties properties, String key, int defaultValue) {
        if (properties == null || !properties.containsKey(key)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(properties.getProperty(key));
        } catch (NumberFormatException e) {
            log.warn("Invalid integer value for property '{}': {}, using default: {}", key, properties.getProperty(key), defaultValue);
            return defaultValue;
        }
    }

    private long getLongProperty(Properties properties, String key, long defaultValue) {
        if (properties == null || !properties.containsKey(key)) {
            return defaultValue;
        }
        try {
            return Long.parseLong(properties.getProperty(key));
        } catch (NumberFormatException e) {
            log.warn("Invalid long value for property '{}': {}, using default: {}", key, properties.getProperty(key), defaultValue);
            return defaultValue;
        }
    }

    private boolean getBooleanProperty(Properties properties, String key, boolean defaultValue) {
        if (properties == null || !properties.containsKey(key)) {
            return defaultValue;
        }
        return Boolean.parseBoolean(properties.getProperty(key));
    }

    private String getStringProperty(Properties properties, String key, String defaultValue) {
        if (properties == null || !properties.containsKey(key)) {
            return defaultValue;
        }
        return properties.getProperty(key);
    }

}