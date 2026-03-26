package io.github.libxposed.api;

/**
 * Compile-time shim for the API 101 wrapper that receives the runtime
 * framework interface through attachFramework().
 */
public class XposedInterfaceWrapper implements XposedInterface {

    private XposedInterface base;

    public final void attachFramework(XposedInterface base) {
        this.base = base;
    }

    protected final XposedInterface getBase() {
        return base;
    }

    @Override
    public int getApiVersion() {
        return base != null ? base.getApiVersion() : 0;
    }

    @Override
    public String getFrameworkName() {
        return base != null ? base.getFrameworkName() : "";
    }

    @Override
    public String getFrameworkVersion() {
        return base != null ? base.getFrameworkVersion() : "";
    }

    @Override
    public long getFrameworkVersionCode() {
        return base != null ? base.getFrameworkVersionCode() : 0L;
    }

    @Override
    public void log(int priority, String tag, String msg) {
        if (base != null) {
            base.log(priority, tag, msg);
        }
    }

    @Override
    public void log(int priority, String tag, String msg, Throwable tr) {
        if (base != null) {
            base.log(priority, tag, msg, tr);
        }
    }
}
