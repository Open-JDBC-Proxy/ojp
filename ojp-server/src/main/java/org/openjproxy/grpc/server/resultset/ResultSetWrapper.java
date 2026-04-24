package org.openjproxy.grpc.server.resultset;

import com.google.protobuf.ByteString;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.ResultType;
import com.openjproxy.grpc.SessionInfo;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.dto.OpQueryResult;

import java.util.List;

/**
 * Utility class for wrapping result set data into OpResult objects.
 * Extracted from StatementServiceImpl to improve modularity.
 */
public class ResultSetWrapper {

    /**
     * Wraps result set data into an OpResult for GRPC response.
     *
     * @param sessionInfo        The session information
     * @param results           The result data rows
     * @param queryResultBuilder The query result builder
     * @param resultSetUUID     The result set UUID
     * @param resultSetMode     The result set mode flag
     * @return OpResult containing wrapped data
     */
    public static OpResult wrapResults(SessionInfo sessionInfo,
                                     List<Object[]> results,
                                     OpQueryResult.OpQueryResultBuilder queryResultBuilder,
                                     String resultSetUUID, String resultSetMode) {

        OpResult.Builder resultsBuilder = OpResult.newBuilder();
        resultsBuilder.setSession(sessionInfo);
        resultsBuilder.setType(ResultType.RESULT_SET_DATA);
        queryResultBuilder.resultSetUUID(resultSetUUID);
        queryResultBuilder.rows(results);
        resultsBuilder.setQueryResult(ProtoConverter.toProto(queryResultBuilder.build()));
        resultsBuilder.setFlag(resultSetMode);

        return resultsBuilder.build();
    }
}