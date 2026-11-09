package dev.xpramt.bahamutenhancer;

import android.util.Log;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

/**
 * Thin helpers over the libxposed API (102): before/after style hooks on top of {@code hook().intercept()}, and
 * the few reflection helpers the hooks need. A callback never throws into the hooked process: its exception is
 * logged and the original call goes on.
 */
final class Xp {
    private static final String TAG = "BahamutAnimeEnhancer";

    private static volatile XposedModule module;

    private Xp() { }

    static void init(XposedModule instance) {
        module = instance;
    }

    static void log(String message) {
        String line = "[" + BuildConfig.BUILD_MARKER + "] " + message;
        XposedModule current = module;
        if (current != null) {
            current.log(Log.INFO, TAG, line);
        } else {
            Log.i(TAG, line);
        }
    }

    /** One call of a hooked method; a result set in before skips the original. */
    static final class Param {
        final Object thisObject;
        final Object[] args;
        private Object result;
        private boolean done;

        Param(Object thisObject, Object[] args) {
            this.thisObject = thisObject;
            this.args = args;
        }

        void setResult(Object value) {
            result = value;
            done = true;
        }
    }

    abstract static class MethodHook {
        protected void before(Param param) throws Throwable { }

        protected void after(Param param) throws Throwable { }
    }

    static HookHandle hookMethod(Member member, MethodHook callback) {
        XposedModule current = module;
        if (current == null) {
            throw new IllegalStateException("module not loaded");
        }
        return current.hook((Executable) member)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> run(chain, callback));
    }

    private static Object run(XposedInterface.Chain chain, MethodHook callback) throws Throwable {
        Param param = new Param(chain.getThisObject(), chain.getArgs().toArray());
        try {
            callback.before(param);
        } catch (Throwable t) {
            log("hook before failed in " + chain.getExecutable().getName() + ": " + Log.getStackTraceString(t));
            param.result = null;
            param.done = false;
        }
        if (!param.done) {
            param.result = chain.proceed(param.args);
        }
        try {
            callback.after(param);
        } catch (Throwable t) {
            log("hook after failed in " + chain.getExecutable().getName() + ": " + Log.getStackTraceString(t));
        }
        return param.result;
    }

    static HookHandle findAndHookMethod(Class<?> clazz, String name, Object... typesThenHook) {
        MethodHook callback = (MethodHook) typesThenHook[typesThenHook.length - 1];
        Class<?>[] types = new Class<?>[typesThenHook.length - 1];
        for (int i = 0; i < types.length; i++) {
            types[i] = (Class<?>) typesThenHook[i];
        }
        return hookMethod(findMethodExact(clazz, name, types), callback);
    }

    static Method findMethodExact(Class<?> clazz, String name, Class<?>... types) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Method method = c.getDeclaredMethod(name, types);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // try the superclass
            }
        }
        throw new NoSuchMethodError(clazz.getName() + "#" + name);
    }

    static Class<?> findClass(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException e) {
            throw new NoClassDefFoundError(name);
        }
    }

    private static Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // try the superclass
            }
        }
        throw new NoSuchFieldError(clazz.getName() + "#" + name);
    }

    static Object getObjectField(Object object, String name) {
        try {
            return findField(object.getClass(), name).get(object);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static int getIntField(Object object, String name) {
        try {
            return findField(object.getClass(), name).getInt(object);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean getBooleanField(Object object, String name) {
        try {
            return findField(object.getClass(), name).getBoolean(object);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static void setIntField(Object object, String name, int value) {
        try {
            findField(object.getClass(), name).setInt(object, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Calls a no-argument method found by name on the object's class hierarchy. */
    static Object callMethod(Object object, String name) {
        Method method = findMethodExact(object.getClass(), name);
        try {
            return method.invoke(object);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(name + " threw " + e.getCause(), e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
