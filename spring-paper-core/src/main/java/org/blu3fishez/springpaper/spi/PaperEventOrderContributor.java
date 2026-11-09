package org.blu3fishez.springpaper.spi;

import java.util.List;
import org.blu3fishez.springpaper.listener.PaperEventRelation;

/**
 * Implemented by consumer composition modules, called once by the core before native registration.
 * Describes cross-feature relationships without making feature implementations depend on each other.
 * Return immutable metadata; this callback does not own Paper side effects or resources.
 */
@FunctionalInterface
public interface PaperEventOrderContributor {
    List<PaperEventRelation> relations();
}
