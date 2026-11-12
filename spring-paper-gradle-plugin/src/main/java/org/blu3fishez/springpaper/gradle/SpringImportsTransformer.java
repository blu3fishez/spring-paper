package org.blu3fishez.springpaper.gradle;

import com.github.jengelman.gradle.plugins.shadow.transformers.AppendingTransformer;
import com.github.jengelman.gradle.plugins.shadow.transformers.CacheableTransformer;
import com.github.jengelman.gradle.plugins.shadow.transformers.PatternFilterableResourceTransformer;
import com.github.jengelman.gradle.plugins.shadow.transformers.TransformerContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.tools.zip.ZipOutputStream;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.util.PatternSet;

/** Appends each Spring imports path independently, including contributions without a trailing newline. */
@CacheableTransformer
final class SpringImportsTransformer extends PatternFilterableResourceTransformer {
    private final ObjectFactory objectFactory;
    private final Map<String, AppendingTransformer> imports = new LinkedHashMap<>();

    SpringImportsTransformer(ObjectFactory objectFactory) {
        super(new PatternSet().include("META-INF/spring/*.imports"));
        this.objectFactory = objectFactory;
    }

    @Override @Internal public ObjectFactory getObjectFactory() { return objectFactory; }

    @Override public void transform(TransformerContext context) {
        imports.computeIfAbsent(context.getPath(), path -> {
            var transformer = new AppendingTransformer(objectFactory);
            transformer.getResource().set(path);
            return transformer;
        }).transform(context);
    }

    @Override public boolean hasTransformedResource() {
        return imports.values().stream().anyMatch(AppendingTransformer::hasTransformedResource);
    }

    @Override public void modifyOutputStream(ZipOutputStream output, boolean preserveFileTimestamps) {
        for (var transformer : imports.values()) {
            if (transformer.hasTransformedResource()) transformer.modifyOutputStream(output, preserveFileTimestamps);
        }
        imports.clear();
    }
}
