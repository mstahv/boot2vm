package in.virit.gracefuldrain.spring;

import com.vaadin.flow.server.VaadinService;
import in.virit.gracefuldrain.GracefulDrain;
import in.virit.gracefuldrain.UpgradeNotifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures {@link GracefulDrain} from {@code graceful-drain.*} properties and an optional
 * {@link UpgradeNotifier} bean, and exposes the actuator endpoints the boot2vm deploy script calls.
 * The endpoints still need to be exposed by the app, e.g.
 * {@code management.endpoints.web.exposure.include=health,new-version,active-users}.
 */
@AutoConfiguration
@ConditionalOnClass(VaadinService.class)
@EnableConfigurationProperties(GracefulDrainProperties.class)
public class GracefulDrainAutoConfiguration {

    @Bean
    GracefulDrain gracefulDrain(GracefulDrainProperties properties, ObjectProvider<UpgradeNotifier> notifier) {
        GracefulDrain drain = GracefulDrain.get();
        if (properties.slot() != null) {
            drain.setSlot(properties.slot());
        }
        if (properties.slotCookie() != null) {
            drain.setSlotCookie(properties.slotCookie());
        }
        if (properties.pinAllUis() != null) {
            drain.setPinAllUis(properties.pinAllUis());
        }
        notifier.ifAvailable(drain::setNotifier);
        return drain;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Endpoint.class)
    static class Endpoints {

        @Bean
        @ConditionalOnMissingBean
        NewVersionEndpoint newVersionEndpoint(GracefulDrain drain) {
            return new NewVersionEndpoint(drain);
        }

        @Bean
        @ConditionalOnMissingBean
        ActiveUsersEndpoint activeUsersEndpoint(GracefulDrain drain) {
            return new ActiveUsersEndpoint(drain);
        }
    }
}
