package org.blu3fishez.springpaper.velocity;

import static org.junit.jupiter.api.Assertions.*;
import com.velocitypowered.api.command.SimpleCommand;
import org.blu3fishez.springpaper.velocity.annotation.VelocityCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.stereotype.Component;

class VelocityCommandMetadataTest {
    @Test void canonicalLabelIsAnExplicitSpringComponentNameAndRetainsNativeAliases() {
        var metadata = AnnotationMetadata.introspect(StatusCommand.class);
        assertEquals("velocity-status", metadata.getAnnotations().get(Component.class).getString("value"));
        var definition = new AnnotatedGenericBeanDefinition(StatusCommand.class);
        assertEquals("velocity-status", FullyQualifiedAnnotationBeanNameGenerator.INSTANCE
                .generateBeanName(definition, new DefaultListableBeanFactory()));
        var command = metadata.getAnnotations().get(VelocityCommand.class).synthesize();
        assertEquals("velocity-status", command.value());
        assertArrayEquals(new String[] {"vs", "status-v"}, command.aliases());
    }

    @VelocityCommand(value = "velocity-status", aliases = {"vs", "status-v"})
    static final class StatusCommand implements SimpleCommand {
        @Override public void execute(Invocation invocation) {}
    }
}
