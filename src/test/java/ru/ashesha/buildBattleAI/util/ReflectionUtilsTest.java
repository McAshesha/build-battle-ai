package ru.ashesha.buildBattleAI.util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class ReflectionUtilsTest {


    // ── findField ──────────────────────────────────────────────────────────

    @Test
    void findFieldFindsFieldOnClass() {
        Field field = ReflectionUtils.findField(Child.class, "childField");
        assertEquals("childField", field.getName());
        assertTrue(field.isAccessible());
    }

    @Test
    void findFieldFindsFieldOnSuperclass() {
        Field field = ReflectionUtils.findField(Child.class, "parentField");
        assertEquals("parentField", field.getName());
    }


    @Test
    void findFieldThrowsForMissingField() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> ReflectionUtils.findField(Child.class, "noSuchField"));
        assertTrue(ex.getMessage().contains("noSuchField"));
    }


    // ── getFieldValue (instance) ───────────────────────────────────────────

    @Test
    void getFieldValueReadsInstanceField() {
        Child child = new Child();
        int value = ReflectionUtils.getFieldValue(child, "childField");
        assertEquals(42, value);
    }

    @Test
    void getFieldValueReadsInheritedField() {
        Child child = new Child();
        String value = ReflectionUtils.getFieldValue(child, "parentField");
        assertEquals("parentValue", value);
    }


    // ── getStaticFieldValue ────────────────────────────────────────────────

    @Test
    void getStaticFieldValueReadsStaticField() {
        String value = ReflectionUtils.getStaticFieldValue(Child.class, "childStatic");
        assertEquals("childStaticValue", value);
    }

    @Test
    void getStaticFieldValueReadsInheritedStaticField() {
        String value = ReflectionUtils.getStaticFieldValue(Child.class, "parentStatic");
        assertEquals("parentStaticValue", value);
    }


    // ── getFieldValue (Field handle) ───────────────────────────────────────

    @Test
    void getFieldValueWithHandleReadsValue() {
        Child child = new Child();
        Field field = ReflectionUtils.findField(Child.class, "childField");
        int value = ReflectionUtils.getFieldValue(field, child);
        assertEquals(42, value);
    }


    // ── setFieldValue (instance) ───────────────────────────────────────────

    @Test
    void setFieldValueModifiesInstanceField() {
        Child child = new Child();
        ReflectionUtils.setFieldValue(child, "childField", 99);
        int value = ReflectionUtils.getFieldValue(child, "childField");
        assertEquals(99, value);
    }


    @Test
    void setFieldValueModifiesInheritedField() {
        Child child = new Child();
        ReflectionUtils.setFieldValue(child, "parentField", "modified");
        String value = ReflectionUtils.getFieldValue(child, "parentField");
        assertEquals("modified", value);
    }


    // ── setStaticFieldValue ────────────────────────────────────────────────

    @Test
    void setStaticFieldValueModifiesStaticField() {
        String original = ReflectionUtils.getStaticFieldValue(Child.class, "childStatic");
        try {
            ReflectionUtils.setStaticFieldValue(Child.class, "childStatic", "newValue");
            String value = ReflectionUtils.getStaticFieldValue(Child.class, "childStatic");
            assertEquals("newValue", value);
        } finally {
            ReflectionUtils.setStaticFieldValue(Child.class, "childStatic", original);
        }
    }


    // ── setFieldValue (Field handle) ───────────────────────────────────────

    @Test
    void setFieldValueWithHandleSetsValue() {
        Child child = new Child();
        Field field = ReflectionUtils.findField(Child.class, "childField");
        ReflectionUtils.setFieldValue(field, child, 77);
        int value = ReflectionUtils.getFieldValue(field, child);
        assertEquals(77, value);
    }


    // ── findMethod ─────────────────────────────────────────────────────────

    @Test
    void findMethodFindsMethodOnClass() {
        Method method = ReflectionUtils.findMethod(Child.class, "childMethod", String.class);
        assertEquals("childMethod", method.getName());
        assertTrue(method.isAccessible());
    }


    @Test
    void findMethodFindsMethodOnSuperclass() {
        Method method = ReflectionUtils.findMethod(Child.class, "parentMethod");
        assertEquals("parentMethod", method.getName());
    }


    @Test
    void findMethodThrowsForMissingMethod() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> ReflectionUtils.findMethod(Child.class, "noSuchMethod"));
        assertTrue(ex.getMessage().contains("noSuchMethod"));
    }


    // ── invokeMethod ───────────────────────────────────────────────────────

    @Test
    void invokeMethodCallsInstanceMethod() {
        Child child = new Child();
        Method method = ReflectionUtils.findMethod(Child.class, "childMethod", String.class);
        String result = ReflectionUtils.invokeMethod(child, method, "hello");
        assertEquals("echo:hello", result);
    }

    @Test
    void invokeMethodCallsInheritedMethod() {
        Child child = new Child();
        Method method = ReflectionUtils.findMethod(Child.class, "parentMethod");
        String result = ReflectionUtils.invokeMethod(child, method);
        assertEquals("fromParent", result);
    }


    // ── invokeStaticMethod ─────────────────────────────────────────────────

    @Test
    void invokeStaticMethodCallsStaticMethod() {
        Method method = ReflectionUtils.findMethod(Parent.class, "parentStaticMethod", int.class);
        int result = ReflectionUtils.invokeStaticMethod(method, 5);
        assertEquals(10, result);
    }


    // ── Test fixtures ──────────────────────────────────────────────────────

    @SuppressWarnings("unused")
    static class Parent {
        private String parentField = "parentValue";
        private static String parentStatic = "parentStaticValue";

        private String parentMethod() {
            return "fromParent";
        }

        private static int parentStaticMethod(int x) {
            return x * 2;
        }
    }

    @SuppressWarnings("unused")
    static class Child extends Parent {
        private int childField = 42;
        private static String childStatic = "childStaticValue";

        private String childMethod(String arg) {
            return "echo:" + arg;
        }
    }
}
