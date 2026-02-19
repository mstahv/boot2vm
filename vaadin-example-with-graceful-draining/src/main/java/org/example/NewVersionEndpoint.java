package org.example;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Actuator endpoint called by the deploy script when a new version is live and traffic is being split.
 * POST /actuator/new-version
 * Optional JSON body: {"deadline":"2024-01-15T14:30:00Z"} — UTC timestamp when forced cutover will happen.
 */
@Component
@Endpoint(id = "new-version")
public class NewVersionEndpoint {

    private final GracefulBlueGreenService service;

    public NewVersionEndpoint(GracefulBlueGreenService service) {
        this.service = service;
    }

    @WriteOperation
    public void notifyNewVersion(@Nullable String deadline) {
        service.notifyUIs(deadline);
    }
}
