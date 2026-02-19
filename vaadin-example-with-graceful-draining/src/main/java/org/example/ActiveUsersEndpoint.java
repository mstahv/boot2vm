package org.example;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Actuator endpoint polled by the deploy script to check how many users are still pinned to this slot.
 * GET /actuator/active-users  →  {"count": N}
 * Return {"count": 0} when it is safe to shut down this instance.
 */
@Component
@Endpoint(id = "active-users")
public class ActiveUsersEndpoint {

    private final GracefulBlueGreenService service;

    public ActiveUsersEndpoint(GracefulBlueGreenService service) {
        this.service = service;
    }

    @ReadOperation
    public Map<String, Integer> activeUsers() {
        return Map.of("count", service.fixedCount());
    }
}
