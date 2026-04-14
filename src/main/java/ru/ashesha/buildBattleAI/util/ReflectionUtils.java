package ru.ashesha.buildBattleAI.util;

import lombok.experimental.UtilityClass;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Generic reflection utilities for field access, method lookup, and invocation.
 * All methods handle {@code setAccessible(true)} automatically and wrap
 * checked reflection exceptions into unchecked {@link RuntimeException}.
 * <p>
 * Field and method lookups traverse the full class hierarchy (superclasses),
 * so private fields/methods declared in parent classes are reachable.
 */
@UtilityClass
public class ReflectionUtils {

    // ── Field lookup ────────────────────────────────────────────────────────

    /**
     * Finds a declared field by name, traversing the class hierarchy upward.
     * The returned field is already {@linkplain Field#setAccessible(boolean) accessible}.
     *
     * @param clazz     the class to start searching from
     * @param fieldName the field name
     * @return the accessible field
     * @throws RuntimeException if the field is not found in any superclass
     */
    public Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new RuntimeException("Field '" + fieldName + "' not found in "
                + clazz.getName() + " or its superclasses");
    }

    // ── Field get ───────────────────────────────────────────────────────────

    /**
     * Gets the value of an instance field by name.
     * The field is looked up by traversing the object's class hierarchy.
     *
     * @param instance  the object to read from
     * @param fieldName the field name
     * @param <T>       the expected return type (unchecked cast)
     * @return the field value
     */
    @SuppressWarnings("unchecked")
    public <T> T getFieldValue(Object instance, String fieldName) {
        try {
            return (T) findField(instance.getClass(), fieldName).get(instance);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to get field '" + fieldName + "'", e);
        }
    }

    /**
     * Gets the value of a static field by name.
     *
     * @param clazz     the class owning the field
     * @param fieldName the field name
     * @param <T>       the expected return type (unchecked cast)
     * @return the field value
     */
    @SuppressWarnings("unchecked")
    public <T> T getStaticFieldValue(Class<?> clazz, String fieldName) {
        try {
            return (T) findField(clazz, fieldName).get(null);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to get static field '" + fieldName + "'", e);
        }
    }

    /**
     * Gets the value from an already-resolved {@link Field} handle.
     *
     * @param field    the field handle (must be accessible)
     * @param instance the object to read from, or {@code null} for static fields
     * @param <T>      the expected return type (unchecked cast)
     * @return the field value
     */
    @SuppressWarnings("unchecked")
    public <T> T getFieldValue(Field field, Object instance) {
        try {
            return (T) field.get(instance);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to get field '" + field.getName() + "'", e);
        }
    }

    // ── Field set ───────────────────────────────────────────────────────────

    /**
     * Sets the value of an instance field by name.
     *
     * @param instance  the object to modify
     * @param fieldName the field name
     * @param value     the new value
     */
    public void setFieldValue(Object instance, String fieldName, Object value) {
        try {
            findField(instance.getClass(), fieldName).set(instance, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to set field '" + fieldName + "'", e);
        }
    }

    /**
     * Sets the value of a static field by name.
     *
     * @param clazz     the class owning the field
     * @param fieldName the field name
     * @param value     the new value
     */
    public void setStaticFieldValue(Class<?> clazz, String fieldName, Object value) {
        try {
            findField(clazz, fieldName).set(null, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to set static field '" + fieldName + "'", e);
        }
    }

    /**
     * Sets a value via an already-resolved {@link Field} handle.
     *
     * @param field    the field handle (must be accessible)
     * @param instance the object to modify, or {@code null} for static fields
     * @param value    the new value
     */
    public void setFieldValue(Field field, Object instance, Object value) {
        try {
            field.set(instance, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to set field '" + field.getName() + "'", e);
        }
    }

    // ── Method lookup ───────────────────────────────────────────────────────

    /**
     * Finds a declared method by name and parameter types, traversing the class
     * hierarchy upward. The returned method is already accessible.
     *
     * @param clazz      the class to start searching from
     * @param methodName the method name
     * @param paramTypes the method parameter types
     * @return the accessible method
     * @throws RuntimeException if the method is not found in any superclass
     */
    public Method findMethod(Class<?> clazz, String methodName, Class<?>... paramTypes) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(methodName, paramTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        throw new RuntimeException("Method '" + methodName + "' not found in "
                + clazz.getName() + " or its superclasses");
    }

    // ── Method invocation ───────────────────────────────────────────────────

    /**
     * Invokes a method on an instance and returns the result.
     *
     * @param instance the object to invoke the method on
     * @param method   the method handle
     * @param args     the method arguments
     * @param <T>      the expected return type (unchecked cast)
     * @return the method return value, or {@code null} for void methods
     */
    @SuppressWarnings("unchecked")
    public <T> T invokeMethod(Object instance, Method method, Object... args) {
        try {
            return (T) method.invoke(instance, args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to invoke '" + method.getName() + "'", e);
        }
    }

    /**
     * Invokes a static method and returns the result.
     *
     * @param method the method handle
     * @param args   the method arguments
     * @param <T>    the expected return type (unchecked cast)
     * @return the method return value, or {@code null} for void methods
     */
    @SuppressWarnings("unchecked")
    public <T> T invokeStaticMethod(Method method, Object... args) {
        try {
            return (T) method.invoke(null, args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to invoke static '" + method.getName() + "'", e);
        }
    }
}
