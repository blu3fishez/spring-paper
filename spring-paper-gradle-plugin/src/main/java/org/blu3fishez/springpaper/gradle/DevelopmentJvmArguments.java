package org.blu3fishez.springpaper.gradle;

import java.io.Serializable;
import java.util.List;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.process.CommandLineArgumentProvider;

final class DevelopmentJvmArguments implements CommandLineArgumentProvider, Serializable {
    private final Provider<List<String>> values;
    DevelopmentJvmArguments(Provider<List<String>> values) { this.values = values; }
    @Input public List<String> getValues() { return values.getOrElse(List.of()); }
    @Override public Iterable<String> asArguments() { return getValues(); }
}
