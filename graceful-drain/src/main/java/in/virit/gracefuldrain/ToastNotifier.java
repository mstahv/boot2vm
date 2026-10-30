package in.virit.gracefuldrain;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.dom.Style;

/**
 * The default {@link UpgradeNotifier}: a notification with a countdown and an "Update now" button
 * that stays until the user updates or closes it. A closed notification leaves a small reminder in
 * the corner of the screen, which opens it again. Works in any layout.
 */
public class ToastNotifier extends AbstractUpgradeNotifier {

    @Override
    public void newVersionAvailable(NewVersionEvent event) {
        new NewVersionNotification(event).open();
    }

    protected class NewVersionNotification extends Notification {

        public NewVersionNotification(NewVersionEvent event) {
            setPosition(getNotificationPosition());
            setDuration(0);
            addThemeName("primary");
            add(new HorizontalLayout() {{
                setAlignItems(FlexComponent.Alignment.CENTER);
                add(VaadinIcon.REFRESH.create());
                add(new Div() {{
                    setMinWidth("16em");
                    add(new Div(newVersionMessage()) {{
                        getStyle().setFontWeight("600");
                    }});
                    add(deadlineText(event));
                }});
                add(new Button(updateButtonText(), e -> event.upgradeNow()) {{
                    addThemeVariants(ButtonVariant.SMALL);
                    // stands out on the primary colored notification, in Lumo and Aura
                    getStyle().setBackgroundColor("white");
                    getStyle().setColor("var(--lumo-primary-text-color, var(--aura-accent-color, #0068d6))");
                }});
                add(new Button(VaadinIcon.CLOSE_SMALL.create(), e -> {
                    close();
                    event.getUI().add(new Reminder(event));
                }) {{
                    addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.SMALL);
                    getStyle().setColor("inherit");
                    setAriaLabel("Close");
                }});
            }});
        }
    }

    /**
     * A small button with the countdown, fixed to the corner of the screen, that brings the
     * notification back.
     */
    protected class Reminder extends Button {

        public Reminder(NewVersionEvent event) {
            super(VaadinIcon.REFRESH.create(), e -> {
                e.getSource().removeFromParent();
                new NewVersionNotification(event).open();
            });
            event.getDeadline().ifPresent(deadline -> setSuffixComponent(new Countdown(deadline)));
            addThemeVariants(ButtonVariant.PRIMARY, ButtonVariant.SMALL);
            setAriaLabel(newVersionMessage());
            setTooltipText(newVersionMessage());
            Style style = getStyle();
            style.setPosition(Style.Position.FIXED);
            style.setBottom("1rem");
            style.setRight("1rem");
            style.setZIndex(1000);
            style.setBorderRadius("999px");
            style.setBoxShadow("0 2px 8px rgba(0, 0, 0, 0.25)");
        }
    }
}
