package com.hmdp.annotation;

import com.hmdp.enums.BlogState;
import org.springframework.statemachine.annotation.OnTransition;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 用状态枚举声明转换回调，与文章中的 StatesOnTransition 对应。 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@OnTransition
public @interface StatesOnTransition {
    BlogState[] source() default {};

    BlogState[] target() default {};
}
