package org.blu3fishez.springpaper.examples.jdbc;

import org.blu3fishez.springpaper.annotation.SpringPaperPluginApplication;
import org.blu3fishez.springpaper.bootstrap.SpringPaperPlugin;

@SpringPaperPluginApplication(name = "SpringPaperJdbc", configurations = JdbcExampleConfiguration.class)
public final class JdbcPlugin extends SpringPaperPlugin {}
