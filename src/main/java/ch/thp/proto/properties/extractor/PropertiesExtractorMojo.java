package ch.thp.proto.properties.extractor;

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

    private static final Pattern AT_VALUE_FORMAT = Pattern.compile("\\{(?<expression>.*)\\}");

    @Parameter(property = "package.scan")
    private String packageScan;


    @Component
    private MavenProject project;

    public void execute() throws MojoExecutionException {
        getLog().info("firing up");
        List<String> elementsToBeScanned = null;

        try {
            List runtimeClasspathElements = project.getRuntimeClasspathElements();
            URL[] runtimeUrls = new URL[runtimeClasspathElements.size()];
            for (int i = 0; i < runtimeClasspathElements.size(); i++) {
                String element = (String) runtimeClasspathElements.get(i);
                runtimeUrls[i] = new File(element).toURI().toURL();
            }
            URLClassLoader runtimeLoader = new URLClassLoader(runtimeUrls,
                    Thread.currentThread().getContextClassLoader());

            List compileClasspathElements = project.getCompileClasspathElements();
            URL[] compileTimeUrl = new URL[compileClasspathElements.size()];
            for (int i = 0; i < compileClasspathElements.size(); i++) {
                String element = (String) compileClasspathElements.get(i);
                compileTimeUrl[i] = new File(element).toURI().toURL();
            }
            URLClassLoader compileTimeLoader = new URLClassLoader(compileTimeUrl,
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
            classLoadersList.add(runtimeLoader);
            classLoadersList.add(compileTimeLoader);
            getLog().info("ctx classloader: " + ClasspathHelper.contextClassLoader());
            getLog().info("static classloader: " + ClasspathHelper.staticClassLoader());
            getLog().info("runtimeLoader : " + runtimeLoader);
            getLog().info("compileTimeLoader : " + compileTimeLoader);


            System.out.print("packagescan: " + packageScan + " projectClasspath" + projectClasspathList.stream().map(s -> s.toString()).collect(Collectors.toList()));
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
            Set<String> propertyExpressions = reflections.getAllTypes();
            for (String s : propertyExpressions) {
                getLog().info(s);
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
                    propertyExpressions.add(method.getAnnotation(springAtValueAnnotation).value());
                } else {
                    for (java.lang.reflect.Parameter parameter : parameters) {
                        if (parameter.isAnnotationPresent(springAtValueAnnotation)) {
                            getLog().debug("found atValue on methodParam");

                            propertyExpressions.add(parameter.getAnnotation(springAtValueAnnotation).value());
                        }
                    }
                }
            }
            for (Constructor constructor : constructors) {
                java.lang.reflect.Parameter[] parameters = constructor.getParameters();
                for (java.lang.reflect.Parameter parameter : parameters) {
                    if (parameter.isAnnotationPresent(springAtValueAnnotation)) {
                        getLog().debug("found atValue on constructorParam");

                        propertyExpressions.add(parameter.getAnnotation(springAtValueAnnotation).value());
                    }
                }
            }
            propertyExpressions.addAll(fields.stream().map(field -> field.getAnnotation(springAtValueAnnotation).value()).collect(Collectors.toList()));
            Map<String, PropertyExpressionData> expressions = new HashMap<>();
            for (String expression : propertyExpressions) {
                Matcher matcher = AT_VALUE_FORMAT.matcher(expression);
                if (matcher.find()) {
                    String found = matcher.group("expression");
                    String[] resultAfterSplit = found.split(":");
                    PropertyExpressionData data = new PropertyExpressionData(resultAfterSplit[0]);
                    if (resultAfterSplit.length == 2) {
                        data.pushDefaultValue(resultAfterSplit[1]);
                    }
                    if (expressions.containsKey(resultAfterSplit[0]) && data.hasDefaultValues()) {
                        expressions.get(resultAfterSplit[0]).pushDefaultValue(data.getDefaultValuesAsString());
                    } else {
                        expressions.put(resultAfterSplit[0], data);
                    }
                }
            }
            getLog().info("found raw:  " + propertyExpressions.size());

            getLog().info("found:  " + expressions.size());
            String outBase = project.getBuild().getOutputDirectory();
            File out = new File(outBase + "/../template.properties");
            for (PropertyExpressionData data : expressions.values()) {
                Files.append(data.render(), out, Charset.forName("UTF-8"));
            }

        } catch (DependencyResolutionRequiredException e) {
            new MojoExecutionException("Dependency resolution failed", e);
        } catch (IOException e) {
            new MojoExecutionException("IO  failed", e);
        }
    }

    private static class PropertyExpressionData {

        private static final String REPLACE_ME = "#<replace-me>";

        private static final String FORMAT = "#default values are: %1$s\n" +
                "%2$s%3$s=@%3$s@\n";
        private String expression;
        private Set<String> defaultValues = new HashSet<>();

        PropertyExpressionData(String expression) {
            Assert.notNull(expression);
            this.expression = expression;
        }

        public boolean hasDefaultValues() {
            return !defaultValues.isEmpty();
        }

        public void pushDefaultValue(String value) {
            this.defaultValues.add(value);
        }

        public String getDefaultValuesAsString() {
            return Joiner.on(", ").join(defaultValues);
        }

        public String render() {
            return String.format(FORMAT, hasDefaultValues() ? getDefaultValuesAsString() : "none set", hasDefaultValues() ? REPLACE_ME : "", expression);
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
