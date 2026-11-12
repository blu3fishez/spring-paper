package org.blu3fishez.springpaper.gradle;

import javax.inject.Inject;
import org.gradle.api.Action;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

public abstract class SpringVelocityExtension {
    private final VelocityDevelopment development;
    @Inject public SpringVelocityExtension(ObjectFactory objects) {
        development = objects.newInstance(VelocityDevelopment.class);
    }
    public abstract Property<String> getApiCoordinate();
    public abstract RegularFileProperty getConfigurationFile();
    public VelocityDevelopment getDevelopment() { return development; }
    public void development(Action<? super VelocityDevelopment> action) { action.execute(development); }
    public abstract static class VelocityDevelopment extends DevelopmentOptions {
        public abstract Property<String> getVersion();
        public abstract Property<Integer> getBuildNumber();
    }
}
