package org.blu3fishez.springpaper.pdc;

import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.mockito.Answers;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** Native host boundary only. Repository, mapping, conversion and Spring discovery are real. */
final class PdcHost {
    final JavaPlugin plugin = mock(JavaPlugin.class);
    final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    final Entity entity = mock(Entity.class);
    final ItemStack item = mock(ItemStack.class);
    final PersistentDataContainer data = container();
    boolean primary = true;

    PdcHost() {
        when(plugin.getName()).thenReturn("SimpleExtraction");
        when(plugin.namespace()).thenReturn("simpleextraction");
        when(entity.getPersistentDataContainer()).thenReturn(data);
        when(item.getPersistentDataContainer()).thenReturn(data);
        doAnswer(call -> {
            PersistentDataContainer staged = container();
            data.copyTo(staged, true);
            call.<Consumer<PersistentDataContainer>>getArgument(0).accept(staged);
            for (var key : Set.copyOf(data.getKeys())) data.remove(key);
            staged.copyTo(data, true);
            return true;
        }).when(item).editPersistentDataContainer(any());
    }
    PdcRepositoryFactory factory() { return new PdcRepositoryFactory(plugin, () -> primary, beans); }
    static NamespacedKey key(String name) { return new NamespacedKey("simpleextraction", name); }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PersistentDataContainer container() {
        record Stored(Class<?> primitive, Object value) {}
        Map<NamespacedKey, Stored> values = new HashMap<>();
        var context = mock(PersistentDataAdapterContext.class);
        when(context.newPersistentDataContainer()).thenAnswer(call -> container());
        return mock(PersistentDataContainer.class, call -> {
            String method = call.getMethod().getName();
            Object[] args = call.getArguments();
            if (method.equals("getAdapterContext")) return context;
            if (method.equals("getKeys")) return Set.copyOf(values.keySet());
            if (method.equals("isEmpty")) return values.isEmpty();
            if (method.equals("copyTo")) {
                PersistentDataContainer target = (PersistentDataContainer) args[0];
                boolean replace = (boolean) args[1];
                values.forEach((key, stored) -> {
                    if (replace || !target.has(key)) target.set(key, new IdentityType(stored.primitive), stored.value);
                });
                return null;
            }
            if (args.length > 0 && args[0] instanceof NamespacedKey key) {
                Stored stored = values.get(key);
                if (method.equals("remove")) { values.remove(key); return null; }
                if (method.equals("has")) return stored != null && (args.length == 1
                        || stored.primitive == ((PersistentDataType) args[1]).getPrimitiveType());
                if (method.equals("set")) {
                    PersistentDataType type = (PersistentDataType) args[1];
                    values.put(key, new Stored(type.getPrimitiveType(), type.toPrimitive(args[2], context)));
                    return null;
                }
                if (method.equals("get") || method.equals("getOrDefault")) {
                    if (stored == null) return method.equals("get") ? null : args[2];
                    PersistentDataType type = (PersistentDataType) args[1];
                    if (stored.primitive != type.getPrimitiveType()) throw new IllegalArgumentException("Wrong primitive PDC type");
                    return type.fromPrimitive(stored.value, context);
                }
            }
            if (call.getMethod().isDefault()) return call.callRealMethod();
            return Answers.RETURNS_DEFAULTS.answer(call);
        });
    }
    private record IdentityType<T>(Class<T> primitive) implements PersistentDataType<T, T> {
        @Override public Class<T> getPrimitiveType() { return primitive; }
        @Override public Class<T> getComplexType() { return primitive; }
        @Override public T toPrimitive(T value, PersistentDataAdapterContext context) { return value; }
        @Override public T fromPrimitive(T value, PersistentDataAdapterContext context) { return value; }
    }
}
