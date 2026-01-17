package org.openjproxy.grpc.server.action.connection;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.SessionInfo;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.database.DatabaseUtils;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.pool.ConnectionPoolConfigurer;
import org.openjproxy.grpc.server.pool.DataSourceConfigurationManager;
import org.openjproxy.grpc.server.utils.UrlParser;
import org.openjproxy.grpc.server.xa.XADataSourceFactory;

import javax.sql.XADataSource;
import java.sql.SQLException;
import java.util.Properties;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;

/**
 * Helper action for handling unpooled XA connections.
 * This is extracted from handleUnpooledXAConnection method.
 * 
 * This action is implemented as a singleton for thread-safety and memory efficiency.
 */
@Slf4j
public class HandleUnpooledXAConnectionAction {
    
    private static final HandleUnpooledXAConnectionAction INSTANCE = new HandleUnpooledXAConnectionAction();
    
    private HandleUnpooledXAConnectionAction() {
        // Private constructor prevents external instantiation
    }
    
    public static HandleUnpooledXAConnectionAction getInstance() {
        return INSTANCE;
    }
    
    public void execute(ActionContext context, ConnectionDetails connectionDetails, String connHash,
                       StreamObserver<SessionInfo> responseObserver) {
        try {
            // Parse URL to remove OJP-specific prefix
            String parsedUrl = UrlParser.parseUrl(connectionDetails.getUrl());
            
            // Get XA datasource configuration from client properties
            Properties clientProperties = ConnectionPoolConfigurer.extractClientProperties(connectionDetails);
            DataSourceConfigurationManager.XADataSourceConfiguration xaConfig = 
                    DataSourceConfigurationManager.getXAConfiguration(clientProperties);
            
            // Create XADataSource directly using XADataSourceFactory
            XADataSource xaDataSource = XADataSourceFactory.createXADataSource(
                    parsedUrl, 
                    connectionDetails);
            
            // Store the unpooled XADataSource for this connection
            context.getXaDataSourceMap().put(connHash, xaDataSource);
            
            log.info("Created unpooled XADataSource for connHash: {}, database: {}", 
                    connHash, DatabaseUtils.resolveDbName(connectionDetails.getUrl()));
            
            // Register client UUID
            context.getSessionManager().registerClientUUID(connHash, connectionDetails.getClientUUID());
            
            // Return session info (XAConnection will be created on demand when needed)
            SessionInfo sessionInfo = SessionInfo.newBuilder()
                    .setConnHash(connHash)
                    .setClientUUID(connectionDetails.getClientUUID())
                    .setIsXA(true)
                    .build();
            
            responseObserver.onNext(sessionInfo);
            context.getDbNameMap().put(connHash, DatabaseUtils.resolveDbName(connectionDetails.getUrl()));
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            log.error("Failed to create unpooled XADataSource for connection hash {}: {}", 
                    connHash, e.getMessage(), e);
            SQLException sqlException = new SQLException("Failed to create unpooled XADataSource: " + e.getMessage(), e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
        }
    }
}
