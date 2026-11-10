package org.blu3fishez.springpaper.examples.approval;

import org.blu3fishez.springpaper.annotation.SpringPaperPluginApplication;
import org.blu3fishez.springpaper.bootstrap.SpringPaperPlugin;

@SpringPaperPluginApplication(name = "SpringPaperApproval", configurations = ApprovalConfiguration.class)
public final class ApprovalPlugin extends SpringPaperPlugin {}
