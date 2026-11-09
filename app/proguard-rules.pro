# The framework instantiates the module entry from META-INF/xposed/java_init.list by name.
-keep class dev.xpramt.bahamutenhancer.HookEntry { *; }
# libxposed's API classes are provided by the framework at run time.
-dontwarn io.github.libxposed.api.**
