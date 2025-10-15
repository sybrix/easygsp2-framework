package sybrix.easygsp2.anno;
import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Params  {
        Param[] value();
}
