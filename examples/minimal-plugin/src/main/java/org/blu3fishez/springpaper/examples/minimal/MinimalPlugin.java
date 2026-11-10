package org.blu3fishez.springpaper.examples.minimal;

import org.blu3fishez.springpaper.annotation.SpringPaperPluginApplication;
import org.blu3fishez.springpaper.bootstrap.SpringPaperPlugin;

@SpringPaperPluginApplication(name = "SpringPaperMinimal", configurations = MinimalConfiguration.class,
        initializers = MinimalInitializer.class)
public final class MinimalPlugin extends SpringPaperPlugin {}
