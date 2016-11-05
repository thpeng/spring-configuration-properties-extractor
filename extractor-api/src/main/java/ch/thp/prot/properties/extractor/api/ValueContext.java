package ch.thp.prot.properties.extractor.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * adds context to a field, param (constructor or method). should be used with a spring @Value annotation
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.PARAMETER})
public @interface ValueContext {
    public PropertyIsApplicableFor propertyIsApplicableFor() default PropertyIsApplicableFor.NOT_SPECIFIED;

    public String description() default "";

}
