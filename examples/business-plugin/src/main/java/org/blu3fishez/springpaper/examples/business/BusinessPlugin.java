package org.blu3fishez.springpaper.examples.business;

import org.blu3fishez.springpaper.annotation.SpringPaperPluginApplication;
import org.blu3fishez.springpaper.bootstrap.SpringPaperPlugin;

@SpringPaperPluginApplication(name = "SpringPaperBusiness", configurations = BusinessConfiguration.class)
public final class BusinessPlugin extends SpringPaperPlugin {}
