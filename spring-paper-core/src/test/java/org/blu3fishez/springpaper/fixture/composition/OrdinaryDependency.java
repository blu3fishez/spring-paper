package org.blu3fishez.springpaper.fixture.composition;

import org.springframework.stereotype.Component;

@Component
public final class OrdinaryDependency {
    public String value() { return "component"; }
}
