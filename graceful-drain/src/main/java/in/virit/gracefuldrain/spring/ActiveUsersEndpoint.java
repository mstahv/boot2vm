package in.virit.gracefuldrain.spring;

import in.virit.gracefuldrain.GracefulDrain;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.Map;

/**
 * Polled by the deploy script during the drain: GET /actuator/active-users returns {"count": N}, the
 * old version is shut down when N reaches 0.
 */
@Endpoint(id = "active-users")
public class ActiveUsersEndpoint {

    private final GracefulDrain drain;

    public ActiveUsersEndpoint(GracefulDrain drain) {
        this.drain = drain;
    }

    @ReadOperation
    public Map<String, Integer> activeUsers() {
        return Map.of("count", drain.getPinnedUiCount());
    }
}
