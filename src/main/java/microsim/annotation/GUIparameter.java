package microsim.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/**
 * Annotates a simulation-manager field for display and editing in the GUI.
 * By default a GUIparameter can also be modified at runtime; set
 * {@link #runtimeModifiable()} to {@code false} for parameters that must remain
 * fixed after the simulation is built. This annotation was previously called
 * ModelParameter, but that name was considered misleading.
 * 
 * @author ross richardson
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.FIELD })
public @interface GUIparameter {

    public String section() default "";

    public String name() default "";

    public String description() default "";

    /**
     * Whether this parameter may be changed after the simulation has been built.
     *
     * @return {@code true} when runtime modification is allowed
     */
    public boolean runtimeModifiable() default true;

}
