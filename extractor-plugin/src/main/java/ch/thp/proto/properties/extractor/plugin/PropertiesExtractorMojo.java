package ch.thp.proto.properties.extractor.plugin;

import com.google.common.base.Joiner;
import com.google.common.io.Files;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.reflections.Reflections;
import org.reflections.scanners.FieldAnnotationsScanner;
import org.reflections.scanners.MethodAnnotationsScanner;
import org.reflections.scanners.MethodParameterScanner;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.Assert;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


@Mojo(name = "extract", defaultPhase = LifecyclePhase.VERIFY, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class PropertiesExtractorMojo extends AbstractMojo {

    private static final String VALUE_ANNOTATION = Value.class.getCanonicalName();

    private static final Pattern AT_VALUE_FORMAT = Pattern.compile("\\$\\{(?<expression>.*)\\}");

    @Parameter(property = "package.scan")
    private String packageScan;


    @Component
    private MavenProject project;

    public void execute() throws MojoExecutionException {
        getLog().info("firing up");
        List<String> elementsToBeScanned = null;
        try {
                        List combinedRunTimeAndCompileTimeClasspathElements = project.getCompileClasspathElements();
            combinedRunTimeAndCompileTimeClasspathElements.addAll(project.getRuntimeClasspathElements());
            URL[] classpathUrls = new URL[combinedRunTimeAndCompileTimeClasspathElements.size()];
            for (int i = 0; i < combinedRunTimeAndCompileTimeClasspathElements.size(); i++) {
                String element = (String) combinedRunTimeAndCompileTimeClasspathElements.get(i);
                classpathUrls[i] = new File(element).toURI().toURL();
            }
            URLClassLoader combinedLoader = new URLClassLoader(classpathUrls,
                    Thread.currentThread().getContextClassLoader());
            elementsToBeScanned = project.getCompileClasspathElements();
            elementsToBeScanned.addAll(project.getRuntimeClasspathElements());
            List<URL> projectClasspathList = new ArrayList<>();
            for (String element : elementsToBeScanned) {
                try {
                    projectClasspathList.add(new File(element).toURI().toURL());
                } catch (MalformedURLException e) {
                    throw new MojoExecutionException(element + " is an invalid classpath element", e);
                }
            }
            List<ClassLoader> classLoadersList = new LinkedList<ClassLoader>();
            classLoadersList.add(ClasspathHelper.contextClassLoader());
            classLoadersList.add(ClasspathHelper.staticClassLoader());
            classLoadersList.add(combinedLoader);
            getLog().debug("ctx classloader: " + ClasspathHelper.contextClassLoader());
            getLog().debug("static classloader: " + ClasspathHelper.staticClassLoader());
            getLog().debug("combinedLoader : " + combinedLoader);


            getLog().debug("packagescan: " + packageScan + " projectClasspath" + projectClasspathList.stream().map(s -> s.toString()).collect(Collectors.toList()));
            Class<Value> springAtValueAnnotation = Value.class;
            Reflections reflections = new Reflections(new ConfigurationBuilder()

                    .filterInputsBy(new FilterBuilder().includePackage(packageScan))
                    .setUrls(projectClasspathList)
                    .addClassLoaders(classLoadersList.toArray(new ClassLoader[0]))
                    .setScanners(new org.reflections.scanners.Scanner[]{
                            new MethodParameterScanner().filterResultsBy(input -> VALUE_ANNOTATION.equals(input)),
                            new MethodAnnotationsScanner().filterResultsBy(input -> VALUE_ANNOTATION.equals(input)),
                            new FieldAnnotationsScanner().filterResultsBy(input -> VALUE_ANNOTATION.equals(input)),
                            new SubTypesScanner(false)
                    }));
            Set<ClassExpressionTuple> propertyExpressions = new HashSet<>();
            for (ClassExpressionTuple s : propertyExpressions) {
                getLog().info("raw: " + s.toString());
            }


            Set<Method> methods = reflections.getMethodsWithAnyParamAnnotated(springAtValueAnnotation);
            methods.addAll(reflections.getMethodsAnnotatedWith(springAtValueAnnotation));
            Set<Field> fields = reflections.getFieldsAnnotatedWith(springAtValueAnnotation);
            Set<Constructor> constructors = reflections.getConstructorsAnnotatedWith(springAtValueAnnotation);
            constructors.addAll(reflections.getConstructorsWithAnyParamAnnotated(springAtValueAnnotation));

            for (Method method : methods) {
                java.lang.reflect.Parameter[] parameters = method.getParameters();
                if (method.getParameters().length == 1 && method.isAnnotationPresent(springAtValueAnnotation)) {
                    getLog().debug("found atValue on method");
                    propertyExpressions.add(new ClassExpressionTuple(method.getDeclaringClass(), method.getAnnotation(springAtValueAnnotation).value()));
                } else {
                    for (java.lang.reflect.Parameter parameter : parameters) {
                        if (parameter.isAnnotationPresent(springAtValueAnnotation)) {
                            getLog().debug("found atValue on methodParam");

                            propertyExpressions.add(new ClassExpressionTuple(method.getDeclaringClass(), parameter.getAnnotation(springAtValueAnnotation).value()));
                        }
                    }
                }
            }
            for (Constructor constructor : constructors) {
                java.lang.reflect.Parameter[] parameters = constructor.getParameters();
                for (java.lang.reflect.Parameter parameter : parameters) {
                    if (parameter.isAnnotationPresent(springAtValueAnnotation)) {
                        getLog().debug("found atValue on constructorParam");

                        propertyExpressions.add(new ClassExpressionTuple(constructor.getDeclaringClass(), parameter.getAnnotation(springAtValueAnnotation).value()));
                    }
                }
            }
            propertyExpressions.addAll(fields.stream().map(field -> new ClassExpressionTuple(field.getDeclaringClass(), field.getAnnotation(springAtValueAnnotation).value())).collect(Collectors.toList()));
            Map<String, PropertyExpressionData> expressions = new HashMap<>();
            for (ClassExpressionTuple expression : propertyExpressions) {
                Matcher matcher = AT_VALUE_FORMAT.matcher(expression.getExpression());
                if (matcher.find()) {
                    String found = matcher.group("expression");
                    String[] resultAfterSplit = found.split(":");
                    PropertyExpressionData data = new PropertyExpressionData(resultAfterSplit[0]);
                    if (resultAfterSplit.length == 2) {
                        data.pushDefaultValue(resultAfterSplit[1]).pushClass(expression.getCls().getSimpleName());
                    }
                    if (expressions.containsKey(resultAfterSplit[0])) {
                        expressions.get(resultAfterSplit[0]).pushClass(expression.getCls().getSimpleName());
                        if (data.hasDefaultValues()) {
                            expressions.get(resultAfterSplit[0]).pushDefaultValue(data.getDefaultValuesAsString());
                        }
                    } else {
                        expressions.put(resultAfterSplit[0], data.pushClass(expression.getCls().getSimpleName()));
                    }
                }
            }
            getLog().info("found raw:  " + propertyExpressions.size());

            getLog().info("found:  " + expressions.size());
            String outBase = project.getBuild().getOutputDirectory();
            File out = new File(outBase + "/../template.properties");
            List<String> sortedExpression = expressions.keySet().stream().sorted().collect(Collectors.toList());
            for (String key : sortedExpression) {
                Files.append(expressions.get(key).render(), out, Charset.forName("UTF-8"));
            }

        } catch (DependencyResolutionRequiredException e) {
            new MojoExecutionException("Dependency resolution failed", e);
        } catch (IOException e) {
            new MojoExecutionException("IO  failed", e);
        }
    }

    private static class ClassExpressionTuple {
        private Class<?> cls;
        private String expression;

        ClassExpressionTuple(Class<?> cls, String expression) {
            this.cls = cls;
            this.expression = expression;
        }

        public Class<?> getCls() {
            return cls;
        }

        public String getExpression() {
            return expression;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ClassExpressionTuple that = (ClassExpressionTuple) o;

            if (cls != null ? !cls.equals(that.cls) : that.cls != null) return false;
            return expression != null ? expression.equals(that.expression) : that.expression == null;

        }

        @Override
        public int hashCode() {
            int result = cls != null ? cls.hashCode() : 0;
            result = 31 * result + (expression != null ? expression.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "ClassExpressionTuple{" +
                    "cls=" + cls +
                    ", expression='" + expression + '\'' +
                    '}';
        }
    }

    private static class PropertyExpressionData {

        private static final String REPLACE_ME = "#<replace-me>";

        private static final String FORMAT = "#default values are: '%1$s', found in classes: %2$s\n" +
                "%3$s%4$s=@%4$s@\n";
        private String expression;
        private Set<String> defaultValues = new HashSet<>();
        private Set<String> classes = new HashSet<>();


        PropertyExpressionData(String expression) {
            Assert.notNull(expression);
            this.expression = expression;
        }

        public boolean hasDefaultValues() {
            return !defaultValues.isEmpty();
        }

        public PropertyExpressionData pushDefaultValue(String value) {
            this.defaultValues.add(value);
            return this;
        }

        public PropertyExpressionData pushClass(String cls) {
            this.classes.add(cls);
            return this;
        }


        public String getDefaultValuesAsString() {
            return Joiner.on("', '").join(defaultValues);
        }

        public String render() {
            return String.format(FORMAT,
                    hasDefaultValues() ? getDefaultValuesAsString() : "none set",
                    Joiner.on(", ").join(classes),
                    hasDefaultValues() ? REPLACE_ME : "",
                    expression);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            PropertyExpressionData that = (PropertyExpressionData) o;

            return expression != null ? expression.equals(that.expression) : that.expression == null;

        }

        @Override
        public int hashCode() {
            return expression != null ? expression.hashCode() : 0;
        }
    }


}
