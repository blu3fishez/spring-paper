package org.blu3fishez.springpaper.gradle;

import org.gradle.api.file.RegularFileProperty;
import javax.inject.Inject;
import org.gradle.api.Action;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

public abstract class SpringPaperExtension {
    private final PaperTarget paper;
    private final PaperDevelopment development;
    @Inject public SpringPaperExtension(ObjectFactory objects) {
        paper = objects.newInstance(PaperTarget.class);
        development = objects.newInstance(PaperDevelopment.class);
    }
    public abstract RegularFileProperty getConfigurationFile();
    public PaperTarget getPaper() { return paper; }
    public PaperDevelopment getDevelopment() { return development; }
    public void paper(Action<? super PaperTarget> action) { action.execute(paper); }
    public void development(Action<? super PaperDevelopment> action) { action.execute(development); }
    public abstract static class PaperTarget {
        public abstract Property<String> getApiCoordinate();
        public abstract Property<String> getApiVersion();
    }
    public abstract static class PaperDevelopment extends DevelopmentOptions {
        public abstract Property<String> getMinecraftVersion();
        public abstract Property<Integer> getBuild();
    }
}
