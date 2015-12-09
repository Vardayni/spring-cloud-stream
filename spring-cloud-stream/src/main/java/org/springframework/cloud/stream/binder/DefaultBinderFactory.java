/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.stream.binder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.Banner.Mode;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Default {@link BinderFactory} implementation.
 *
 * @author Marius Bogoevici
 */
public class DefaultBinderFactory<T> implements BinderFactory<T>, DisposableBean, EnvironmentAware {

	private final Map<String, BinderConfiguration> binderConfigurations;

	private final Map<String, BinderInstanceHolder<T>> binderInstanceCache = new HashMap<>();

	private volatile Environment environment;

	private volatile String defaultBinder;

	public DefaultBinderFactory(Map<String, BinderConfiguration> binderConfigurations) {
		this.binderConfigurations = new HashMap<>(binderConfigurations);
	}

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	public void setDefaultBinder(String defaultBinder) {
		this.defaultBinder = defaultBinder;
	}

	@Override
	public void destroy() throws Exception {
		for (Map.Entry<String, BinderInstanceHolder<T>> entry : binderInstanceCache.entrySet()) {
			BinderInstanceHolder<T> binderInstanceHolder = entry.getValue();
			binderInstanceHolder.getBinderContext().close();
		}
	}

	@Override
	public synchronized Binder<T> getBinder(String name) {
		String configurationName;
		// Fall back to a default if no argument is provided
		if (StringUtils.isEmpty(name)) {
			if (binderConfigurations.size() == 0) {
				throw new IllegalStateException("A default binder has been requested, but there there is no binder available");
			}
			else if (binderConfigurations.size() == 1) {
				configurationName = binderConfigurations.keySet().iterator().next();
			}
			else {
				if (StringUtils.hasText(defaultBinder)) {
					configurationName = defaultBinder;
				}
				else {
					throw new IllegalStateException(
							"A default binder has been requested, but there is more than one binder available: "
									+ StringUtils.collectionToCommaDelimitedString(binderConfigurations.keySet()) + ", and"
									+ " no default binder has been set.");
				}
			}
		} else {
			configurationName = name;
		}
		if (!binderInstanceCache.containsKey(configurationName)) {
			BinderConfiguration binderConfiguration = binderConfigurations.get(configurationName);
			if (binderConfiguration == null) {
				throw new IllegalStateException("Unknown binder configuration: " + configurationName);
			}
			Properties binderProperties = binderConfiguration.getProperties();
			// Convert all properties to arguments, so that they receive maximum precedence
			ArrayList<String> args = new ArrayList<>();
			for (Map.Entry<Object, Object> property : binderProperties.entrySet()) {
				args.add(String.format("--%s=%s",property.getKey(),property.getValue()));
			}
			// Initialize the domain with a unique name based on the bootstrapping context setting
			String defaultDomain = environment != null ? environment.getProperty("spring.jmx.default-domain") : null;
			if (defaultDomain == null) {
				defaultDomain = "";
			}
			else {
				defaultDomain += ".";
			}
			args.add("--spring.jmx.default-domain=" + defaultDomain + "binder." + configurationName);
			args.add("--spring.application.admin.enabled=false");
			SpringApplicationBuilder springApplicationBuilder =
					new SpringApplicationBuilder()
							.sources(binderConfiguration.getBinderType().getConfigurationClasses())
							.sources(SeedConfiguration.class)
							.bannerMode(Mode.OFF)
							.web(false);
			ConfigurableApplicationContext binderProducingContext =
					springApplicationBuilder.run(args.toArray(new String[args.size()]));
			@SuppressWarnings("unchecked")
			Binder<T> binder = (Binder<T>) binderProducingContext.getBean(Binder.class);
			binderInstanceCache.put(configurationName, new BinderInstanceHolder<>(binder, binderProducingContext));
		}
		return binderInstanceCache.get(configurationName).getBinderInstance();
	}

	/**
	 * Utility class for storing {@link Binder} instances, along with their associated contexts.
	 *
	 * @param <T>
	 */
	private static class BinderInstanceHolder<T> {

		private final Binder<T> binderInstance;

		private final ConfigurableApplicationContext binderContext;

		public BinderInstanceHolder(Binder<T> binderInstance, ConfigurableApplicationContext binderContext) {
			this.binderInstance = binderInstance;
			this.binderContext = binderContext;
		}

		public Binder<T> getBinderInstance() {
			return binderInstance;
		}

		public ConfigurableApplicationContext getBinderContext() {
			return binderContext;
		}
	}

	/**
	 * Configuration class that enables autoconfiguration for the binders
	 */
	// TODO: Reconsider the use of autoconfiguration as part of binder configuration refactoring
	@EnableAutoConfiguration
	public static class SeedConfiguration {
	}
}
