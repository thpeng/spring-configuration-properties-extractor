package ch.thp.prot.properties.extractor.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Created by Thierry on 05-Nov-16.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.PARAMETER})
public @interface ValueContext {
    public PropertyIsApplicableFor propertyIsApplicableFor() default PropertyIsApplicableFor.NOT_SPECIFIED;

    public String description() default "";

}
