/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.test.context.support;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySources;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.util.TestContextResourceUtils;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;

/**
 * Utility methods for working with {@link TestPropertySource @TestPropertySource}
 * and adding test {@link PropertySource PropertySources} to the {@code Environment}.
 *
 * <p>Primarily intended for use within the framework.
 *
 * @author Sam Brannen
 * @author Anatoliy Korovin
 * @since 4.1
 * @see TestPropertySource
 */
public abstract class TestPropertySourceUtils {

	/**
	 * The name of the {@link MapPropertySource} created from <em>inlined properties</em>.
	 * @since 4.1.5
	 * @see #addInlinedPropertiesToEnvironment
	 */
	public static final String INLINED_PROPERTIES_PROPERTY_SOURCE_NAME = "Inlined Test Properties";

	private static final Log logger = LogFactory.getLog(TestPropertySourceUtils.class);

	/**
	 * Compares {@link MergedAnnotation} instances (presumably within the same
	 * aggregate index) by their meta-distance, in reverse order.
	 * <p>Using this {@link Comparator} to sort according to reverse meta-distance
	 * ensures that directly present annotations take precedence over meta-present
	 * annotations (within a given aggregate index). In other words, this follows
	 * the last-one-wins principle of overriding properties.
	 * @see MergedAnnotation#getAggregateIndex()
	 * @see MergedAnnotation#getDistance()
	 */
	private static final Comparator<? super MergedAnnotation<?>> reversedMetaDistanceComparator =
			Comparator.<MergedAnnotation<?>> comparingInt(MergedAnnotation::getDistance).reversed();


	static MergedTestPropertySources buildMergedTestPropertySources(Class<?> testClass) {
		MergedAnnotations mergedAnnotations = MergedAnnotations.from(testClass, SearchStrategy.EXHAUSTIVE);
		return (mergedAnnotations.isPresent(TestPropertySource.class) ? mergeTestPropertySources(mergedAnnotations) :
				MergedTestPropertySources.empty());
	}

	private static MergedTestPropertySources mergeTestPropertySources(MergedAnnotations mergedAnnotations) {
		List<TestPropertySourceAttributes> attributesList = resolveTestPropertySourceAttributes(mergedAnnotations);
		return new MergedTestPropertySources(mergeLocations(attributesList), mergeProperties(attributesList));
	}

	private static List<TestPropertySourceAttributes> resolveTestPropertySourceAttributes(
			MergedAnnotations mergedAnnotations) {

		// Group by aggregate index to ensure proper separation of inherited and local annotations.
		Map<Integer, List<MergedAnnotation<TestPropertySource>>> aggregateIndexMap = mergedAnnotations
				.stream(TestPropertySource.class)
				.collect(Collectors.groupingBy(MergedAnnotation::getAggregateIndex, TreeMap::new,
					Collectors.mapping(x -> x, Collectors.toList())));

		// Stream the lists of annotations per aggregate index, merge each list into a
		// single TestPropertySourceAttributes instance, and collect the results.
		return aggregateIndexMap.values().stream()
				.map(TestPropertySourceUtils::createTestPropertySourceAttributes)
				.collect(Collectors.toList());
	}

	/**
	 * Create a merged {@link TestPropertySourceAttributes} instance from all
	 * annotations in the supplied list for a given aggregate index as if there
	 * were only one such annotation.
	 * <p>Within the supplied list, sort according to reversed meta-distance of
	 * the annotations from the declaring class. This ensures that directly present
	 * annotations take precedence over meta-present annotations within the current
	 * aggregate index.
	 * <p>If a given {@link TestPropertySource @TestPropertySource} does not
	 * declare properties or locations, an attempt will be made to detect a default
	 * properties file.
	 */
	private static TestPropertySourceAttributes createTestPropertySourceAttributes(
			List<MergedAnnotation<TestPropertySource>> list) {

		list.sort(reversedMetaDistanceComparator);

		List<String> locations = new ArrayList<>();
		List<String> properties = new ArrayList<>();
		Class<?> declaringClass = null;
		Boolean inheritLocations = null;
		Boolean inheritProperties = null;

		// Merge all @TestPropertySource annotations within the current
		// aggregate index into a single TestPropertySourceAttributes instance,
		// simultaneously ensuring that all such annotations have the same
		// declaringClass, inheritLocations, and inheritProperties values.
		for (MergedAnnotation<TestPropertySource> mergedAnnotation : list) {
			Class<?> currentDeclaringClass = (Class<?>) mergedAnnotation.getSource();
			if (declaringClass != null && !declaringClass.equals(currentDeclaringClass)) {
				throw new IllegalStateException("Detected @TestPropertySource declarations within an aggregate index " +
						"with different declaring classes: " + declaringClass.getName() + " and " +
						currentDeclaringClass.getName());
			}
			declaringClass = currentDeclaringClass;

			TestPropertySource testPropertySource = mergedAnnotation.synthesize();
			if (logger.isTraceEnabled()) {
				logger.trace(String.format("Retrieved %s for declaring class [%s].", testPropertySource,
					declaringClass.getName()));
			}

			Boolean currentInheritLocations = testPropertySource.inheritLocations();
			assertConsistentValues(testPropertySource, declaringClass, "inheritLocations", inheritLocations,
				currentInheritLocations);
			inheritLocations = currentInheritLocations;

			Boolean currentInheritProperties = testPropertySource.inheritProperties();
			assertConsistentValues(testPropertySource, declaringClass, "inheritProperties", inheritProperties,
				currentInheritProperties);
			inheritProperties = currentInheritProperties;

			String[] currentLocations = testPropertySource.locations();
			String[] currentProperties = testPropertySource.properties();
			if (ObjectUtils.isEmpty(currentLocations) && ObjectUtils.isEmpty(currentProperties)) {
				locations.add(detectDefaultPropertiesFile(declaringClass));
			}
			else {
				Collections.addAll(locations, currentLocations);
				Collections.addAll(properties, currentProperties);
			}
		}

		TestPropertySourceAttributes attributes = new TestPropertySourceAttributes(declaringClass, locations,
			inheritLocations, properties, inheritProperties);
		if (logger.isTraceEnabled()) {
			logger.trace(String.format("Resolved @TestPropertySource attributes %s for declaring class [%s].",
				attributes, declaringClass.getName()));
		}
		return attributes;
	}

	private static void assertConsistentValues(TestPropertySource testPropertySource, Class<?> declaringClass,
			String attributeName, Object trackedValue, Object currentValue) {

		Assert.isTrue((trackedValue == null || trackedValue.equals(currentValue)),
			() -> String.format("%s on class [%s] must declare the same value for '%s' " +
					"as other directly present or meta-present @TestPropertySource annotations on [%2$s].",
					testPropertySource, declaringClass.getName(), attributeName));
	}

	/**
	 * Detect a default properties file for the supplied class, as specified
	 * in the class-level Javadoc for {@link TestPropertySource}.
	 */
	private static String detectDefaultPropertiesFile(Class<?> testClass) {
		String resourcePath = ClassUtils.convertClassNameToResourcePath(testClass.getName()) + ".properties";
		ClassPathResource classPathResource = new ClassPathResource(resourcePath);

		if (classPathResource.exists()) {
			String prefixedResourcePath = ResourceUtils.CLASSPATH_URL_PREFIX + resourcePath;
			if (logger.isInfoEnabled()) {
				logger.info(String.format("Detected default properties file \"%s\" for test class [%s]",
					prefixedResourcePath, testClass.getName()));
			}
			return prefixedResourcePath;
		}
		else {
			String msg = String.format("Could not detect default properties file for test class [%s]: " +
					"%s does not exist. Either declare the 'locations' or 'properties' attributes " +
					"of @TestPropertySource or make the default properties file available.", testClass.getName(),
					classPathResource);
			logger.error(msg);
			throw new IllegalStateException(msg);
		}
	}

	private static String[] mergeLocations(List<TestPropertySourceAttributes> attributesList) {
		List<String> locations = new ArrayList<>();
		for (TestPropertySourceAttributes attrs : attributesList) {
			if (logger.isTraceEnabled()) {
				logger.trace(String.format("Processing locations for TestPropertySource attributes %s", attrs));
			}
			String[] locationsArray = TestContextResourceUtils.convertToClasspathResourcePaths(
					attrs.getDeclaringClass(), attrs.getLocations());
			locations.addAll(0, Arrays.asList(locationsArray));
			if (!attrs.isInheritLocations()) {
				break;
			}
		}
		return StringUtils.toStringArray(locations);
	}

	private static String[] mergeProperties(List<TestPropertySourceAttributes> attributesList) {
		List<String> properties = new ArrayList<>();
		for (TestPropertySourceAttributes attrs : attributesList) {
			if (logger.isTraceEnabled()) {
				logger.trace(String.format("Processing inlined properties for TestPropertySource attributes %s", attrs));
			}
			String[] attrProps = attrs.getProperties();
			if (attrProps != null) {
				properties.addAll(0, Arrays.asList(attrProps));
			}
			if (!attrs.isInheritProperties()) {
				break;
			}
		}
		return StringUtils.toStringArray(properties);
	}

	/**
	 * Add the {@link Properties} files from the given resource {@code locations}
	 * to the {@link Environment} of the supplied {@code context}.
	 * <p>This method simply delegates to
	 * {@link #addPropertiesFilesToEnvironment(ConfigurableEnvironment, ResourceLoader, String...)}.
	 * @param context the application context whose environment should be updated;
	 * never {@code null}
	 * @param locations the resource locations of {@code Properties} files to add
	 * to the environment; potentially empty but never {@code null}
	 * @throws IllegalStateException if an error occurs while processing a properties file
	 * @since 4.1.5
	 * @see ResourcePropertySource
	 * @see TestPropertySource#locations
	 * @see #addPropertiesFilesToEnvironment(ConfigurableEnvironment, ResourceLoader, String...)
	 */
	public static void addPropertiesFilesToEnvironment(ConfigurableApplicationContext context, String... locations) {
		Assert.notNull(context, "'context' must not be null");
		Assert.notNull(locations, "'locations' must not be null");
		addPropertiesFilesToEnvironment(context.getEnvironment(), context, locations);
	}

	/**
	 * Add the {@link Properties} files from the given resource {@code locations}
	 * to the supplied {@link ConfigurableEnvironment environment}.
	 * <p>Property placeholders in resource locations (i.e., <code>${...}</code>)
	 * will be {@linkplain Environment#resolveRequiredPlaceholders(String) resolved}
	 * against the {@code Environment}.
	 * <p>Each properties file will be converted to a {@link ResourcePropertySource}
	 * that will be added to the {@link PropertySources} of the environment with
	 * highest precedence.
	 * @param environment the environment to update; never {@code null}
	 * @param resourceLoader the {@code ResourceLoader} to use to load each resource;
	 * never {@code null}
	 * @param locations the resource locations of {@code Properties} files to add
	 * to the environment; potentially empty but never {@code null}
	 * @throws IllegalStateException if an error occurs while processing a properties file
	 * @since 4.3
	 * @see ResourcePropertySource
	 * @see TestPropertySource#locations
	 * @see #addPropertiesFilesToEnvironment(ConfigurableApplicationContext, String...)
	 */
	public static void addPropertiesFilesToEnvironment(ConfigurableEnvironment environment,
			ResourceLoader resourceLoader, String... locations) {

		Assert.notNull(environment, "'environment' must not be null");
		Assert.notNull(resourceLoader, "'resourceLoader' must not be null");
		Assert.notNull(locations, "'locations' must not be null");
		try {
			for (String location : locations) {
				String resolvedLocation = environment.resolveRequiredPlaceholders(location);
				Resource resource = resourceLoader.getResource(resolvedLocation);
				environment.getPropertySources().addFirst(new ResourcePropertySource(resource));
			}
		}
		catch (IOException ex) {
			throw new IllegalStateException("Failed to add PropertySource to Environment", ex);
		}
	}

	/**
	 * Add the given <em>inlined properties</em> to the {@link Environment} of the
	 * supplied {@code context}.
	 * <p>This method simply delegates to
	 * {@link #addInlinedPropertiesToEnvironment(ConfigurableEnvironment, String[])}.
	 * @param context the application context whose environment should be updated;
	 * never {@code null}
	 * @param inlinedProperties the inlined properties to add to the environment;
	 * potentially empty but never {@code null}
	 * @since 4.1.5
	 * @see TestPropertySource#properties
	 * @see #addInlinedPropertiesToEnvironment(ConfigurableEnvironment, String[])
	 */
	public static void addInlinedPropertiesToEnvironment(ConfigurableApplicationContext context, String... inlinedProperties) {
		Assert.notNull(context, "'context' must not be null");
		Assert.notNull(inlinedProperties, "'inlinedProperties' must not be null");
		addInlinedPropertiesToEnvironment(context.getEnvironment(), inlinedProperties);
	}

	/**
	 * Add the given <em>inlined properties</em> (in the form of <em>key-value</em>
	 * pairs) to the supplied {@link ConfigurableEnvironment environment}.
	 * <p>All key-value pairs will be added to the {@code Environment} as a
	 * single {@link MapPropertySource} with the highest precedence.
	 * <p>For details on the parsing of <em>inlined properties</em>, consult the
	 * Javadoc for {@link #convertInlinedPropertiesToMap}.
	 * @param environment the environment to update; never {@code null}
	 * @param inlinedProperties the inlined properties to add to the environment;
	 * potentially empty but never {@code null}
	 * @since 4.1.5
	 * @see MapPropertySource
	 * @see #INLINED_PROPERTIES_PROPERTY_SOURCE_NAME
	 * @see TestPropertySource#properties
	 * @see #convertInlinedPropertiesToMap
	 */
	public static void addInlinedPropertiesToEnvironment(ConfigurableEnvironment environment, String... inlinedProperties) {
		Assert.notNull(environment, "'environment' must not be null");
		Assert.notNull(inlinedProperties, "'inlinedProperties' must not be null");
		if (!ObjectUtils.isEmpty(inlinedProperties)) {
			if (logger.isDebugEnabled()) {
				logger.debug("Adding inlined properties to environment: " +
						ObjectUtils.nullSafeToString(inlinedProperties));
			}
			MapPropertySource ps = (MapPropertySource)
					environment.getPropertySources().get(INLINED_PROPERTIES_PROPERTY_SOURCE_NAME);
			if (ps == null) {
				ps = new MapPropertySource(INLINED_PROPERTIES_PROPERTY_SOURCE_NAME, new LinkedHashMap<>());
				environment.getPropertySources().addFirst(ps);
			}
			ps.getSource().putAll(convertInlinedPropertiesToMap(inlinedProperties));
		}
	}

	/**
	 * Convert the supplied <em>inlined properties</em> (in the form of <em>key-value</em>
	 * pairs) into a map keyed by property name, preserving the ordering of property names
	 * in the returned map.
	 * <p>Parsing of the key-value pairs is achieved by converting all pairs
	 * into <em>virtual</em> properties files in memory and delegating to
	 * {@link Properties#load(java.io.Reader)} to parse each virtual file.
	 * <p>For a full discussion of <em>inlined properties</em>, consult the Javadoc
	 * for {@link TestPropertySource#properties}.
	 * @param inlinedProperties the inlined properties to convert; potentially empty
	 * but never {@code null}
	 * @return a new, ordered map containing the converted properties
	 * @throws IllegalStateException if a given key-value pair cannot be parsed, or if
	 * a given inlined property contains multiple key-value pairs
	 * @since 4.1.5
	 * @see #addInlinedPropertiesToEnvironment(ConfigurableEnvironment, String[])
	 */
	public static Map<String, Object> convertInlinedPropertiesToMap(String... inlinedProperties) {
		Assert.notNull(inlinedProperties, "'inlinedProperties' must not be null");
		Map<String, Object> map = new LinkedHashMap<>();
		Properties props = new Properties();

		for (String pair : inlinedProperties) {
			if (!StringUtils.hasText(pair)) {
				continue;
			}
			try {
				props.load(new StringReader(pair));
			}
			catch (Exception ex) {
				throw new IllegalStateException("Failed to load test environment property from [" + pair + "]", ex);
			}
			Assert.state(props.size() == 1, () -> "Failed to load exactly one test environment property from [" + pair + "]");
			for (String name : props.stringPropertyNames()) {
				map.put(name, props.getProperty(name));
			}
			props.clear();
		}

		return map;
	}

}
