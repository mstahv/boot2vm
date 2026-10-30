package in.virit.gracefuldrain;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;

/**
 * The default {@link UpgradeNotifier}: a notification with a countdown and an "Update now" button
 * that stays until the user closes it. Extend to change texts or position.
 */
public class ToastNotifier implements UpgradeNotifier {

    @Override
    public void newVersionAvailable(NewVersionEvent event) {
        new NewVersionNotification(event).open();
    }

    @Override
    public void arrived(UI ui, MigrationType type) {
        Notification.show(arrivalMessage(type), 5000, getPosition());
    }

    protected String arrivalMessage(MigrationType type) {
        return switch (type) {
            case AUTO -> "You were automatically moved to a new version of the application.";
            case USER -> "Welcome to the new version!";
            case FORCED -> "The application was updated to a new version, sorry for the interruption.";
        };
    }

    protected String newVersionMessage() {
        return "A new version is available.";
    }

    protected String deadlineMessage() {
        return "Save your work, the update is forced in";
    }

    protected String updateButtonText() {
        return "Update now";
    }

    protected Notification.Position getPosition() {
        return Notification.Position.TOP_END;
    }

    protected class NewVersionNotification extends Notification {

        public NewVersionNotification(NewVersionEvent event) {
            setPosition(getPosition());
            setDuration(0);
            add(new HorizontalLayout() {{
                setAlignItems(FlexComponent.Alignment.CENTER);
                add(new Div(new Div(newVersionMessage())) {{
                    event.getDeadline().ifPresent(deadline ->
                            add(new Div(new Span(deadlineMessage() + " "), new Countdown(deadline))));
                }});
                add(new Button(updateButtonText(), e -> event.upgradeNow()) {{
                    addThemeVariants(ButtonVariant.PRIMARY);
                }});
                add(new Button(VaadinIcon.CLOSE_SMALL.create(), e -> close()) {{
                    addThemeVariants(ButtonVariant.TERTIARY);
                    setAriaLabel("Close");
                }});
            }});
        }
    }
}
