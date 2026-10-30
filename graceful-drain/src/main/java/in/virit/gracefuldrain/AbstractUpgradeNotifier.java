package in.virit.gracefuldrain;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;

/**
 * Texts and the arrival greeting shared by the built-in notifiers. Override the text methods to
 * change or translate them.
 */
public abstract class AbstractUpgradeNotifier implements UpgradeNotifier {

    @Override
    public void arrived(UI ui, MigrationType type) {
        Notification notification = Notification.show(arrivalMessage(type), 5000, getNotificationPosition());
        if (type != MigrationType.FORCED) {
            notification.addThemeName("success");
        }
    }

    protected String arrivalMessage(MigrationType type) {
        return switch (type) {
            case AUTO -> "You were automatically moved to a new version of the application.";
            case USER -> "Welcome to the new version!";
            case FORCED -> "The application was updated to a new version, sorry for the interruption.";
        };
    }

    protected String newVersionMessage() {
        return "A new version is available";
    }

    protected String deadlineMessage() {
        return "Save your work, the update is forced in";
    }

    protected String updateButtonText() {
        return "Update now";
    }

    protected Notification.Position getNotificationPosition() {
        return Notification.Position.TOP_END;
    }

    /**
     * @return "Save your work, the update is forced in m:ss", or an empty component without a
     * deadline
     */
    protected Div deadlineText(NewVersionEvent event) {
        Div div = new Div();
        div.getStyle().setFontSize("0.875em");
        event.getDeadline().ifPresent(deadline ->
                div.add(new Span(deadlineMessage() + " "), new Countdown(deadline) {{
                    getStyle().setFontWeight("600");
                }}));
        return div;
    }
}
