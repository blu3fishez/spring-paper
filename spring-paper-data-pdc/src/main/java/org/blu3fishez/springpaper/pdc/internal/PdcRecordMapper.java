package org.blu3fishez.springpaper.pdc.internal;

import io.papermc.paper.persistence.PersistentDataContainerView;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.blu3fishez.springpaper.pdc.PdcDocument;
import org.blu3fishez.springpaper.pdc.PdcField;
import org.blu3fishez.springpaper.pdc.PdcReadProblem;
import org.blu3fishez.springpaper.pdc.PdcReadResult;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.java.JavaPlugin;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.util.ReflectionUtils;

/** Immutable metadata. Mapping failures are startup errors; malformed stored values are read results. */
public final class PdcRecordMapper<T> {
    private final Class<T> model;
    private final Constructor<T> constructor;
    private final List<Field> fields;
    private final NamespacedKey marker;

    public PdcRecordMapper(Class<T> model, JavaPlugin plugin, BeanFactory beans) {
        this.model = model;
        PdcDocument document = model.getAnnotation(PdcDocument.class);
        if (!model.isRecord() || document == null) throw new IllegalArgumentException("PDC model must be an @PdcDocument record: " + model.getName());
        var components = model.getRecordComponents();
        if (components.length == 0) throw new IllegalArgumentException("PDC document needs at least one field");
        var mapped = new ArrayList<Field>();
        var keys = new HashSet<NamespacedKey>();
        NamespacedKey identity = null;
        for (var component : components) {
            PdcField field = component.getAnnotation(PdcField.class);
            if (field == null) throw new IllegalArgumentException("Missing @PdcField: " + component.getName());
            if (!field.required() && component.getType().isPrimitive()) throw new IllegalArgumentException("Optional PDC fields must use nullable reference types");
            NamespacedKey key = NamespacedKey.fromString(field.key(), plugin);
            if (key == null) throw new IllegalArgumentException("Invalid mapped PDC key: " + field.key());
            if (!keys.add(key)) throw new IllegalArgumentException("Duplicate mapped PDC key: " + key);
            Method accessor = component.getAccessor();
            ReflectionUtils.makeAccessible(accessor);
            mapped.add(new Field(key, accessor, field.required(), PdcTypes.resolve(component.getType(), field, beans)));
            if (component.getName().equals(document.marker())) identity = key;
        }
        if (!document.marker().isEmpty() && identity == null) throw new IllegalArgumentException("Unknown PDC marker component: " + document.marker());
        marker = identity;
        fields = List.copyOf(mapped);
        try {
            constructor = model.getDeclaredConstructor(java.util.Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new));
            ReflectionUtils.makeAccessible(constructor);
        } catch (NoSuchMethodException invalidRecord) { throw new IllegalArgumentException("Missing record constructor", invalidRecord); }
    }

    public boolean exists(PersistentDataContainerView data) {
        return marker != null ? data.has(marker) : fields.stream().anyMatch(field -> data.has(field.key));
    }

    public PdcReadResult<T> read(PersistentDataContainerView data) {
        if (fields.stream().noneMatch(field -> data.has(field.key))) return PdcReadResult.absent();
        var problems = new ArrayList<PdcReadProblem>();
        Object[] values = new Object[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            if (!data.has(field.key)) {
                if (field.required) problems.add(problem(field, PdcReadProblem.Kind.MISSING_FIELD, "Required key is absent"));
                continue;
            }
            try { values[i] = field.binding.read(data, field.key); }
            catch (PdcValueBinding.StoredTypeMismatch invalid) {
                problems.add(problem(field, PdcReadProblem.Kind.TYPE_MISMATCH, message(invalid)));
            }
            catch (IllegalArgumentException invalid) {
                problems.add(problem(field, PdcReadProblem.Kind.INVALID_VALUE, message(invalid)));
            }
        }
        if (!problems.isEmpty()) return PdcReadResult.invalid(problems);
        try { return PdcReadResult.present(constructor.newInstance(values)); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Error fatal) throw fatal;
            return PdcReadResult.invalid(List.of(new PdcReadProblem("", PdcReadProblem.Kind.INVALID_DOCUMENT, message(failure.getCause()))));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("PDC mapping became inaccessible", failure); }
    }

    public void write(PersistentDataContainer data, T document) {
        java.util.Objects.requireNonNull(document, "PDC document");
        if (!model.isInstance(document)) throw new IllegalArgumentException("Expected " + model.getName());
        // Entity PDC is live; encoding directly into it can leave half a document if a later codec fails.
        // Stage every value first. This prevents conversion/validation partial writes, not a disk transaction.
        PersistentDataContainer staged = data.getAdapterContext().newPersistentDataContainer();
        var removed = new ArrayList<NamespacedKey>();
        for (Field field : fields) {
            Object value = ReflectionUtils.invokeMethod(field.accessor, document);
            if (value == null) {
                if (field.required) throw new IllegalArgumentException("Required PDC value is null: " + field.key);
                removed.add(field.key);
            } else field.binding.write(staged, field.key, value);
        }
        staged.copyTo(data, true);
        removed.forEach(data::remove);
    }

    public void remove(PersistentDataContainer data) { fields.forEach(field -> data.remove(field.key)); }

    private static PdcReadProblem problem(Field field, PdcReadProblem.Kind kind, String message) {
        return new PdcReadProblem(field.key.asString(), kind, message);
    }
    private static String message(Throwable error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    private record Field(NamespacedKey key, Method accessor, boolean required, PdcValueBinding<?, ?> binding) {}
}
