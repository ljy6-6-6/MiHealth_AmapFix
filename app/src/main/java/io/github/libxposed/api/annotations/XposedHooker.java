// 注意：这是一个“兼容性占位注解”，用于兼容某些第三方 LSPosed 分支仍然要求的 @XposedHooker 注解。
// 官方新版 libxposed 已移除此注解；但我们保留同名注解以避免在老实现中崩溃。
// 在官方实现中，这个注解不会被使用也不会产生副作用。
package io.github.libxposed.api.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface XposedHooker {}
