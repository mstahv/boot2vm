package in.virit.gracefuldrain;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
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
            // Two rows, so that the button fits however narrow the notification is
            add(new VerticalLayout() {{
                setPadding(false);
                setSpacing(false);
                getStyle().setGap("0.5em");
                var text = new Div() {{
                    add(new Div(newVersionMessage()) {{
                        getStyle().setFontWeight("600");
                    }});
                    add(deadlineText(event));
                }};
                add(new HorizontalLayout(VaadinIcon.REFRESH.create(), text, new Button(VaadinIcon.CLOSE_SMALL.create(), e -> {
                    close();
                    event.getUI().add(new Reminder(event));
                }) {{
                    addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.SMALL);
                    getStyle().setColor("inherit");
                    setAriaLabel("Close");
                }}) {{
                    setWidthFull();
                    setAlignItems(FlexComponent.Alignment.START);
                    setFlexGrow(1, text);
                }});
                add(new Button(updateButtonText(), e -> event.upgradeNow()) {{
                    addThemeVariants(ButtonVariant.SMALL);
                    // Stands out on Lumo's primary colored notification, and on Aura's light one
                    getStyle().setBackgroundColor("white");
                    getStyle().setColor("var(--lumo-primary-text-color, var(--aura-accent-color, #0068d6))");
                    getStyle().setBorder("1px solid var(--aura-accent-color, transparent)");
                    setAlignSelf(FlexComponent.Alignment.END, this);
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
