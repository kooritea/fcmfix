package sumicya.fcmself.libxposed;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class XposedHelpersTest {
    private static class Parent {
        private String inherited(Number value) {
            return "parent:" + value;
        }
    }

    private static final class Child extends Parent {
        private String overloaded(Number value) {
            return "number";
        }

        private String overloaded(Integer value) {
            return "integer";
        }

        private String assignable(Object value) {
            return "object";
        }

        private String assignable(Number value) {
            return "number";
        }

        private String nullable(Object value) {
            return "object";
        }

        private String nullable(int value) {
            return "primitive";
        }
    }

    @Test
    public void callMethodFindsInheritedMethods() {
        assertEquals("parent:7", XposedHelpers.callMethod(new Child(), "inherited", 7));
    }

    @Test
    public void findMethodExactFindsInheritedMethods() {
        assertEquals(Parent.class, XposedHelpers
                .findMethodExact(Child.class, "inherited", Number.class)
                .getDeclaringClass());
    }

    @Test
    public void callMethodSelectsMostSpecificCompatibleOverload() {
        Child child = new Child();
        assertEquals("integer", XposedHelpers.callMethod(child, "overloaded", 7));
        assertEquals("number", XposedHelpers.callMethod(child, "assignable", 7));
        // Resolve the same signatures again through the runtime-signature cache.
        assertEquals("integer", XposedHelpers.callMethod(child, "overloaded", 8));
        assertEquals("number", XposedHelpers.callMethod(child, "assignable", 8));
    }

    @Test
    public void callMethodDoesNotSelectPrimitiveOverloadForNull() {
        assertEquals("object", XposedHelpers.callMethod(new Child(), "nullable", (Object) null));
    }
}
