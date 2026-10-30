package in.virit.gracefuldrain.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param slot      the deployment slot, defaults to the APP_SLOT environment variable
 * @param slotCookie the cookie the reverse proxy uses to keep users on the old slot (SLOT_COOKIE in boot2vm)
 * @param pinAllUis whether every UI is pinned to its slot on creation, true by default
 */
@ConfigurationProperties("graceful-drain")
public record GracefulDrainProperties(String slot, String slotCookie, Boolean pinAllUis) {
}
