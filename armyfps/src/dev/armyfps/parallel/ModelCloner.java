package dev.armyfps.parallel;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.class_630;

/**
 * Deep copy of a model (and its whole ModelPart tree) for one worker thread. Models are shared and
 * get posed per entity, so every thread poses its own copy. Cuboids (the geometry) stay shared;
 * they are never changed after loading. Every other field is copied as it is, so fields that other
 * mods add to models or parts come along too.
 */
final class ModelCloner {
    private static final sun.misc.Unsafe UNSAFE = unsafe();
    private static final Map<Class<?>, Field[]> FIELDS = new ConcurrentHashMap<>();

    private ModelCloner() {
    }

    static Object cloneModel(Object model) throws ReflectiveOperationException {
        IdentityHashMap<class_630, class_630> parts = new IdentityHashMap<>();
        Object copy = UNSAFE.allocateInstance(model.getClass());
        for (Field field : fields(model.getClass())) {
            field.set(copy, remap(field.get(model), parts));
        }

        return copy;
    }

    private static Object remap(Object value, IdentityHashMap<class_630, class_630> parts) throws ReflectiveOperationException {
        if (value instanceof class_630 part) {
            return clonePart(part, parts);
        }

        if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof class_630) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object element : list) {
                copy.add(remap(element, parts));
            }

            return copy;
        }

        if (value instanceof class_630[] array) {
            class_630[] copy = (class_630[])Array.newInstance(class_630.class, array.length);
            for (int i = 0; i < array.length; i++) {
                copy[i] = array[i] == null ? null : clonePart(array[i], parts);
            }

            return copy;
        }

        return value;
    }

    private static class_630 clonePart(class_630 part, IdentityHashMap<class_630, class_630> parts) throws ReflectiveOperationException {
        class_630 done = parts.get(part);
        if (done != null) {
            return done;
        }

        class_630 copy = (class_630)UNSAFE.allocateInstance(class_630.class);
        parts.put(part, copy);
        for (Field field : fields(class_630.class)) {
            Object value = field.get(part);
            if (value instanceof Map<?, ?> children && field.getName().equals("field_3661")) {
                // Same iteration order as the original, so vertices come out in the same order.
                Map<String, class_630> copied = new LinkedHashMap<>();
                for (Map.Entry<?, ?> child : children.entrySet()) {
                    copied.put((String)child.getKey(), clonePart((class_630)child.getValue(), parts));
                }

                field.set(copy, copied);
            } else {
                field.set(copy, value);
            }
        }

        return copy;
    }

    private static Field[] fields(Class<?> type) {
        return FIELDS.computeIfAbsent(type, t -> {
            List<Field> all = new ArrayList<>();
            for (Class<?> c = t; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) {
                        field.setAccessible(true);
                        all.add(field);
                    }
                }
            }

            return all.toArray(new Field[0]);
        });
    }

    private static sun.misc.Unsafe unsafe() {
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (sun.misc.Unsafe)field.get(null);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }
}
