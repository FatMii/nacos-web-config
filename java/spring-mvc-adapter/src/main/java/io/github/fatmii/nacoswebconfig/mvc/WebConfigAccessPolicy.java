package io.github.fatmii.nacoswebconfig.mvc;

import java.security.Principal;

/** Decides whether the current host-authenticated identity may open a configuration stream. */
@FunctionalInterface
public interface WebConfigAccessPolicy {
    /** Returns true when the request may reach the stream service. */
    boolean permits(Principal principal);
}
