package sumicya.fcmself.libxposed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class XCMethodHookParamTest {
    @Test
    public void resultAndThrowableAreMutuallyExclusive() {
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        Object result = new Object();
        RuntimeException failure = new RuntimeException("failure");

        param.setResult(result);
        assertSame(result, param.getResult());
        assertFalse(param.hasThrowable());
        assertTrue(param.isReturnEarly());

        param.setThrowable(failure);
        assertNull(param.getResult());
        assertSame(failure, param.getThrowable());
        assertTrue(param.isReturnEarly());

        param.setResult(result);
        assertSame(result, param.getResult());
        assertFalse(param.hasThrowable());
    }
}
