package dev.pixelmotion.a10.recents;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class Reflect {
    static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            try {
                Field result = at.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    static Method method(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            try {
                Method result = at.getDeclaredMethod(name, parameters);
                result.setAccessible(true);
                return result;
            } catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    static Object get(Object object, String name) throws Throwable {
        return field(object.getClass(), name).get(object);
    }

    static Object call(Object object, String name) throws Throwable {
        return method(object.getClass(), name).invoke(object);
    }
}
