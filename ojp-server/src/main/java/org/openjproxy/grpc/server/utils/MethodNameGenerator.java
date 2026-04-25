package org.openjproxy.grpc.server.utils;

import com.openjproxy.grpc.TargetCall;

import java.sql.SQLException;

/**
 * Utility class for generating method names from TargetCall objects.
 * Extracted from StatementServiceImpl to improve modularity.
 */
public class MethodNameGenerator {

    /**
     * Generates a method name from a TargetCall.
     *
     * @param target The target call
     * @return The method name
     * @throws SQLException if call type is not supported
     */
    public static String methodName(TargetCall target) throws SQLException {
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
}
