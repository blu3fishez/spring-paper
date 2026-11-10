package org.blu3fishez.springpaper.examples.scheduling;

import org.blu3fishez.springpaper.annotation.SpringPaperPluginApplication;
import org.blu3fishez.springpaper.bootstrap.SpringPaperPlugin;

@SpringPaperPluginApplication(name = "SpringPaperScheduling", configurations = SchedulingConfiguration.class)
public final class SchedulingPlugin extends SpringPaperPlugin {}
