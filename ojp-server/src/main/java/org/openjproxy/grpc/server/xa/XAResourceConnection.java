package org.openjproxy.grpc.server.xa;

import javax.sql.XAConnection;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * XAResourceConnection is a dynamic proxy that implements Connection, XAConnection, and XAResource.
 * It wraps the driver-provided XAConnection/XAResource/Connection triple to enable HikariCP pooling
 * of XA connections while preserving XA pass-through semantics.
 * 
 * This allows HikariCP to pool what appears to be a regular Connection, but which also exposes
 * XAConnection and XAResource interfaces for distributed transaction support.
 */
public class XAResourceConnection implements InvocationHandler {
    
    private final XAConnection xaConnection;
    private final XAResource xaResource;
    private final Connection connection;
    
    private XAResourceConnection(XAConnection xaConnection, XAResource xaResource, Connection connection) {
        this.xaConnection = xaConnection;
        this.xaResource = xaResource;
        this.connection = connection;
    }
    
    /**
     * Creates a proxy that implements Connection, XAConnection, and XAResource interfaces.
     * 
     * @param xaConnection The XAConnection from the driver
     * @param xaResource The XAResource from the XAConnection
     * @param connection The Connection from the XAConnection
     * @return A proxy implementing all three interfaces
     */
    public static Connection wrap(XAConnection xaConnection, XAResource xaResource, Connection connection) {
        XAResourceConnection handler = new XAResourceConnection(xaConnection, xaResource, connection);
        
        return (Connection) Proxy.newProxyInstance(
            XAResourceConnection.class.getClassLoader(),
            new Class<?>[] { Connection.class, XAConnection.class, XAResource.class },
            handler
        );
    }
    
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();
        Class<?> declaringClass = method.getDeclaringClass();
        
        // Special handling for getConnection() and getXAResource() from XAConnection
        if ("getConnection".equals(methodName) && method.getParameterCount() == 0) {
            return proxy; // Return the proxy itself, not the underlying connection
        } else if ("getXAResource".equals(methodName)) {
            return proxy; // Return the proxy itself, which implements XAResource
        }
        
        // Route method calls to the appropriate underlying object
        if (declaringClass == XAResource.class) {
            // XAResource methods go to xaResource
            return method.invoke(xaResource, args);
        } else if (declaringClass == XAConnection.class) {
            // Other XAConnection methods go to xaConnection
            return method.invoke(xaConnection, args);
        } else {
            // Connection methods go to connection
            return method.invoke(connection, args);
        }
    }
}
