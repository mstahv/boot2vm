package in.virit.gracefuldrain;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasStyle;
import com.vaadin.flow.component.Tag;

import java.time.Duration;
import java.time.Instant;

/**
 * Shows the time left until a deadline as m:ss, ticking in the browser. The remaining time is
 * computed on the server, so a wrong clock in the client doesn't matter.
 */
@Tag("span")
public class Countdown extends Component implements HasStyle {

    public Countdown(Instant deadline) {
        getElement().setAttribute("role", "timer");
        addAttachListener(e -> getElement().executeJs("""
                const end = Date.now() + $0;
                clearInterval(this._countdown);
                const tick = () => {
                  const s = Math.max(0, Math.round((end - Date.now()) / 1000));
                  this.textContent = Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
                  if (s === 0) clearInterval(this._countdown);
                };
                tick();
                this._countdown = setInterval(tick, 1000);
                """, Duration.between(Instant.now(), deadline).toMillis()));
    }
}
