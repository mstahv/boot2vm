package in.virit.gracefuldrain;

import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.dom.Style;

import java.util.Optional;

/**
 * Shows a persistent badge with a countdown and an "Update now" button at the end of the
 * {@link AppLayout} drawer, plus a short notification. Falls back to {@link ToastNotifier} when
 * the route has no AppLayout or the drawer is closed, e.g. on mobile.
 */
public class DrawerBadgeNotifier extends ToastNotifier {

    @Override
    public void newVersionAvailable(NewVersionEvent event) {
        Optional<AppLayout> appLayout = event.getUI().getActiveRouterTargetsChain().stream()
                .filter(AppLayout.class::isInstance)
                .map(AppLayout.class::cast)
                .findFirst();
        if (appLayout.isEmpty() || !appLayout.get().isDrawerOpened()) {
            super.newVersionAvailable(event);
            return;
        }
        appLayout.get().addToDrawer(new NewVersionBadge(event));
        Notification.show(newVersionMessage(), 5000, getNotificationPosition());
    }

    protected class NewVersionBadge extends VerticalLayout {

        public NewVersionBadge(NewVersionEvent event) {
            setAlignItems(Alignment.CENTER);
            setSpacing(false);
            setPadding(true);
            setWidth("auto");
            // Lumo's properties first, then Aura's and the base styles', so that both themes look right
            Style style = getStyle();
            style.setMargin("var(--lumo-space-m, var(--vaadin-padding-m, 1rem))");
            style.setBorderRadius("var(--lumo-border-radius-l, var(--vaadin-radius-l, 12px))");
            style.setBackgroundColor("var(--lumo-primary-color-10pct, "
                    + "color-mix(in srgb, var(--aura-accent-color, #0068d6) 12%, transparent))");
            style.setTextAlign(Style.TextAlign.CENTER);
            style.setGap("var(--lumo-space-s, var(--vaadin-gap-s, 0.5rem))");
            add(VaadinIcon.REFRESH.create());
            add(new Div(newVersionMessage()) {{
                getStyle().setFontWeight("600");
            }});
            add(deadlineText(event));
            add(new Button(updateButtonText(), e -> event.upgradeNow()) {{
                addThemeVariants(ButtonVariant.PRIMARY);
            }});
        }
    }
}
