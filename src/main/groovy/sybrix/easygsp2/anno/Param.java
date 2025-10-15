package sybrix.easygsp2.anno;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.PARAMETER})
@Repeatable(Params.class)
public @interface Param {
        String name() default "";
        String description() default "";
        String type() default "string";
        boolean required() default false;
        String enumVals() default "";
        boolean queryString() default false;
}
