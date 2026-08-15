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
        assertEquals("integer", XposedHelpers.callMethod(new Child(), "overloaded", 7));
    }
}
