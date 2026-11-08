package org.blu3fishez.springpaper.gradle;

import org.gradle.api.file.RegularFileProperty;

public abstract class SpringPaperExtension {
    public abstract RegularFileProperty getConfigurationFile();
}
