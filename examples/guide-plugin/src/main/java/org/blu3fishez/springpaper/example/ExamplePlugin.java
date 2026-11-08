package org.blu3fishez.springpaper.example;

import org.blu3fishez.springpaper.annotation.SpringPaperPluginApplication;
import org.blu3fishez.springpaper.bootstrap.SpringPaperPlugin;

@SpringPaperPluginApplication(name = "SpringPaperExample", description = "Spring Paper integration verification",
        authors = "blu3fishez", configurations = ExampleConfiguration.class)
public final class ExamplePlugin extends SpringPaperPlugin {}
