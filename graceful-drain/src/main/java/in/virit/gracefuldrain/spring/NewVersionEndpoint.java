package in.virit.gracefuldrain.spring;

import in.virit.gracefuldrain.GracefulDrain;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

import java.time.Instant;

/**
 * Called by the deploy script when the new version is live and traffic is split.
 * POST /actuator/new-version with an optional JSON body {"deadline":"2024-01-15T14:30:00Z"}, the UTC
 * timestamp of the forced cutover.
 */
@Endpoint(id = "new-version")
public class NewVersionEndpoint {

    private final GracefulDrain drain;

    public NewVersionEndpoint(GracefulDrain drain) {
        this.drain = drain;
    }

    @WriteOperation
    public void newVersion(@Nullable String deadline) {
        drain.newVersionAvailable(deadline == null || deadline.isBlank() ? null : Instant.parse(deadline));
    }
}
