package in.virit.gracefuldrain;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a route target where users can be moved to a new application version without asking, e.g.
 * a dashboard or a list view without unsaved state. Users entering such a view while a new version is
 * available are moved to it as well.
 *
 * @see AutoUpgradeable for views where it depends on the state
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
public @interface AutoUpgrade {
}
