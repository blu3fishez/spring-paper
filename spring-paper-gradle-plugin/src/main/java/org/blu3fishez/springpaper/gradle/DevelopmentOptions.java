package org.blu3fishez.springpaper.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

/** Launch inputs are intentionally independent of compile and descriptor inputs. */
public abstract class DevelopmentOptions {
    public abstract DirectoryProperty getDirectory();
    public abstract Property<Boolean> getAcceptEula();
    public abstract MapProperty<String, String> getServerProperties();
    public abstract ListProperty<String> getJvmArgs();
}
