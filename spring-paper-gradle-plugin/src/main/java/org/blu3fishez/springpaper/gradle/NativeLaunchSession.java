package org.blu3fishez.springpaper.gradle;

import java.io.Serializable;

/** In-memory snapshot exists only between this run task's successful preparation and process exit. */
final class NativeLaunchSession implements Serializable {
    transient OwnedRunDirectory.Snapshot snapshot;
}
