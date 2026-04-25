package org.openjproxy.jdbc;

import lombok.experimental.UtilityClass;

import java.util.UUID;

@UtilityClass
public class ClientUUID {
    private static final String CLIENT_UUID = UUID.randomUUID().toString();

    /**
     * Return the current client UUID, every time the application restarts a new UUID is generated and lasts while the
     * application is not shutdown.
     * @return Client UUID
     */
    public String getUUID() {
        return CLIENT_UUID;
    }
}
