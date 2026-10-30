package in.virit.gracefuldrain;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;

/**
 * Hooks {@link GracefulDrain} to all UIs, registered with ServiceLoader so it works with and without
 * Spring or CDI.
 */
public class GracefulDrainServiceInitListener implements VaadinServiceInitListener {

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.getSource().addUIInitListener(e -> GracefulDrain.get().uiInit(e.getUI()));
    }
}
