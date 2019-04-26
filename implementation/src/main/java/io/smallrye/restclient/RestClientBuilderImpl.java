/**
 * Copyright 2015-2017 Red Hat, Inc, and individual contributors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.smallrye.restclient;

import io.smallrye.restclient.async.AsyncInvocationInterceptorHandler;
import io.smallrye.restclient.header.ClientHeaderProviders;
import io.smallrye.restclient.header.ClientHeadersRequestFilter;
import io.smallrye.restclient.impl.VertxEngine;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.RestClientDefinitionException;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.ext.AsyncInvocationInterceptorFactory;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Priorities;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.Configuration;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.ParamConverterProvider;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class RestClientBuilderImpl implements RestClientBuilder {

    private static Pattern parameterPattern = Pattern.compile("\\{(.*?)\\}");

    private static final String RESTEASY_PROPERTY_PREFIX = "resteasy.";

    private static final String DEFAULT_MAPPER_PROP = "microprofile.rest.client.disable.default.mapper";

    private static final DefaultMediaTypeFilter DEFAULT_MEDIA_TYPE_FILTER = new DefaultMediaTypeFilter();
    public static final MethodInjectionFilter METHOD_INJECTION_FILTER = new MethodInjectionFilter();
    public static final ClientHeadersRequestFilter HEADERS_REQUEST_FILTER = new ClientHeadersRequestFilter();

    private final ResteasyClientBuilder builderDelegate;

    private final Config config;

    private ExecutorService executorService;

    private URI baseURI;

    private final List<AsyncInvocationInterceptorFactory> asyncInterceptorFactories = new ArrayList<>();

    RestClientBuilderImpl() {
        ClientBuilder availableBuilder = ClientBuilder.newBuilder();

        if (availableBuilder instanceof ResteasyClientBuilder) {
            builderDelegate = (ResteasyClientBuilder) availableBuilder;
            config = ConfigProvider.getConfig();
        } else {
            throw new IllegalStateException("Unable to load ResteasyClientBuilder, found " + availableBuilder.getClass() + " instead.");
        }
    }

    @Override
    public RestClientBuilder baseUrl(URL url) {
        try {
            baseURI = url.toURI();
            return this;
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    @Override
    public RestClientBuilder baseUri(URI uri) {
        baseURI = uri;
        return this;
    }

    @Override
    public RestClientBuilder connectTimeout(long time, TimeUnit unit) {
        if (time < 0) {
            throw new IllegalArgumentException("connectTimeout can not be negative.");
        }
        builderDelegate.connectTimeout(time, unit);
        return this;
    }

    @Override
    public RestClientBuilder readTimeout(long time, TimeUnit unit) {
        if (time < 0) {
            throw new IllegalArgumentException("readTimeout can not be negative.");
        }
        builderDelegate.readTimeout(time, unit);
        return this;
    }

    @Override
    public RestClientBuilder executorService(ExecutorService executor) {
        if (executor == null) {
            throw new IllegalArgumentException("ExecutorService must not be null");
        }
        executorService = executor;
        return this;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T build(Class<T> aClass) throws IllegalStateException, RestClientDefinitionException {

        RestClientListeners.get().forEach(listener -> listener.onNewClient(aClass, this));

        // Interface validity
        verifyInterface(aClass);

        if (baseURI == null) {
            throw new IllegalStateException("Neither baseUri nor baseUrl was specified");
        }

        // Provider annotations
        RegisterProvider[] providers = aClass.getAnnotationsByType(RegisterProvider.class);

        for (RegisterProvider provider : providers) {
            register(provider.value(), provider.priority());
        }

        // Default exception mapper
        if (!isMapperDisabled()) {
            register(DefaultResponseExceptionMapper.class);
        }

        //TODO Fix this
//        builderDelegate.register(new ExceptionMapping(localProviderInstances), 1);

        ClassLoader classLoader = aClass.getClassLoader();

        List<String> noProxyHosts = Arrays.asList(
                System.getProperty("http.nonProxyHosts", "localhost|127.*|[::1]").split("|"));
        String proxyHost = System.getProperty("http.proxyHost");

        T actualClient;
        ResteasyClient client;

        ResteasyClientBuilder resteasyClientBuilder;
        if (proxyHost != null && !noProxyHosts.contains(baseURI.getHost())) {
            // Use proxy, if defined
            resteasyClientBuilder = builderDelegate.defaultProxy(
                    proxyHost,
                    Integer.parseInt(System.getProperty("http.proxyPort", "80")));
        } else {
            resteasyClientBuilder = builderDelegate;
        }
        // this is rest easy default
        ExecutorService executorService = this.executorService != null ? this.executorService : Executors.newFixedThreadPool(10);

        ExecutorService executor = AsyncInvocationInterceptorHandler.wrapExecutorService(executorService);
        resteasyClientBuilder.executorService(executor);
        resteasyClientBuilder.register(DEFAULT_MEDIA_TYPE_FILTER);
        resteasyClientBuilder.register(METHOD_INJECTION_FILTER);
        resteasyClientBuilder.register(HEADERS_REQUEST_FILTER);

        client = resteasyClientBuilder
                .httpEngine(new VertxEngine())
                .build();

        actualClient = client.target(baseURI)
                .proxyBuilder(aClass)
                .classloader(classLoader)
                .defaultConsumes(MediaType.WILDCARD)
                .defaultProduces(MediaType.WILDCARD).build();

        Class<?>[] interfaces = new Class<?>[2];
        interfaces[0] = aClass;
        interfaces[1] = RestClientProxy.class;

        T proxy = (T) Proxy.newProxyInstance(classLoader, interfaces, new ProxyInvocationHandler(aClass, actualClient, null, client, asyncInterceptorFactories));
        ClientHeaderProviders.registerForClass(aClass, proxy);
        return proxy;
    }

    private boolean isMapperDisabled() {
        boolean disabled = false;
        Optional<Boolean> defaultMapperProp = config.getOptionalValue(DEFAULT_MAPPER_PROP, Boolean.class);

        // disabled through config api
        if (defaultMapperProp.isPresent() && defaultMapperProp.get().equals(Boolean.TRUE)) {
            disabled = true;
        } else if (!defaultMapperProp.isPresent()) {

            // disabled through jaxrs property
            try {
                Object property = builderDelegate.getConfiguration().getProperty(DEFAULT_MAPPER_PROP);
                if (property != null) {
                    disabled = (Boolean) property;
                }
            } catch (Throwable e) {
                // ignore cast exception
            }
        }
        return disabled;
    }

    @Override
    public Configuration getConfiguration() {
        return builderDelegate.getConfiguration();
    }

    @Override
    public RestClientBuilder property(String name, Object value) {
        if (name.startsWith(RESTEASY_PROPERTY_PREFIX)) {
            // Makes it possible to configure some of the ResteasyClientBuilder delegate properties
            String builderMethodName = name.substring(RESTEASY_PROPERTY_PREFIX.length());
            Method builderMethod = Arrays.stream(ResteasyClientBuilder.class.getMethods())
                    .filter(m -> builderMethodName.equals(m.getName()) && m.getParameterTypes().length >= 1)
                    .findFirst()
                    .orElse(null);
            if (builderMethod == null) {
                throw new IllegalArgumentException("ResteasyClientBuilder setter method not found: " + builderMethodName);
            }
            Object[] arguments;
            if (builderMethod.getParameterTypes().length > 1) {
                if (value instanceof List) {
                    arguments = ((List<?>) value).toArray();
                } else {
                    throw new IllegalArgumentException("Value must be an instance of List<> for ResteasyClientBuilder setter method: " + builderMethodName);
                }
            } else {
                arguments = new Object[]{value};
            }
            try {
                builderMethod.invoke(builderDelegate, arguments);
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                throw new IllegalStateException("Unable to invoke ResteasyClientBuilder method: " + builderMethodName, e);
            }
        }
        builderDelegate.property(name, value);
        return this;
    }

    private static Object newInstanceOf(Class<?> clazz) {
        try {
            return clazz.newInstance();
        } catch (Throwable t) {
            throw new RuntimeException("Failed to register " + clazz, t);
        }
    }

    @Override
    public RestClientBuilder register(Class<?> aClass) {
        register(newInstanceOf(aClass));
        return this;
    }

    @Override
    public RestClientBuilder register(Class<?> aClass, int i) {

        register(newInstanceOf(aClass), i);
        return this;
    }

    @Override
    public RestClientBuilder register(Class<?> aClass, Class<?>[] classes) {
        register(newInstanceOf(aClass), classes);
        return this;
    }

    @Override
    public RestClientBuilder register(Class<?> aClass, Map<Class<?>, Integer> map) {
        register(newInstanceOf(aClass), map);
        return this;
    }

    @Override
    public RestClientBuilder register(Object o) {
        if (o instanceof ResponseExceptionMapper) {
            ResponseExceptionMapper mapper = (ResponseExceptionMapper) o;
            register(mapper, mapper.getPriority());
        } else if (o instanceof ParamConverterProvider) {
            register(o, Priorities.USER);
        } else if (o instanceof AsyncInvocationInterceptorFactory) {
            asyncInterceptorFactories.add((AsyncInvocationInterceptorFactory) o);
        } else {
            builderDelegate.register(o);
        }
        return this;
    }

    @Override
    public RestClientBuilder register(Object o, int i) {
        if (o instanceof ResponseExceptionMapper) {

            // local
            ResponseExceptionMapper mapper = (ResponseExceptionMapper) o;

            // delegate
            builderDelegate.register(mapper, i);

        } else if (o instanceof ParamConverterProvider) {

            // local
            ParamConverterProvider converter = (ParamConverterProvider) o;

            // delegate
            builderDelegate.register(converter, i);

        } else if (o instanceof AsyncInvocationInterceptorFactory) {
            asyncInterceptorFactories.add((AsyncInvocationInterceptorFactory) o);
        } else {
            builderDelegate.register(o, i);
        }
        return this;
    }

    @Override
    public RestClientBuilder register(Object o, Class<?>[] classes) {

        // local
        for (Class<?> aClass : classes) {
            if (aClass.isAssignableFrom(ResponseExceptionMapper.class)) {
                register(o);
            }
        }

        // other
        builderDelegate.register(o, classes);
        return this;
    }

    @Override
    public RestClientBuilder register(Object o, Map<Class<?>, Integer> map) {
        builderDelegate.register(o, map);

        return this;
    }

    ResteasyClientBuilder getBuilderDelegate() {
        return builderDelegate;
    }

    private <T> void verifyInterface(Class<T> typeDef) {

        Method[] methods = typeDef.getMethods();

        // Multiple HTTP Verb verification
        for (Method method : methods) {
            boolean hasHttpMethod = false;
            for (Annotation annotation : method.getAnnotations()) {
                boolean isHttpMethod = (annotation.annotationType().getAnnotation(HttpMethod.class) != null);
                if (!hasHttpMethod && isHttpMethod) {
                    hasHttpMethod = true;
                } else if (hasHttpMethod && isHttpMethod) {
                    throw new RestClientDefinitionException("Ambiguous HTTP Method definition on " + typeDef.getName() + "::" + method.getName());
                }
            }
        }

        // Invalid URI template verification
        Path classPathAnnotation = typeDef.getAnnotation(Path.class);
        Set<String> classPathParams = new HashSet<>();

        if (classPathAnnotation != null) {
            Matcher matchPattern = parameterPattern.matcher(classPathAnnotation.value());

            while (matchPattern.find()) {
                classPathParams.add(matchPattern.group(1));
            }
        }

        AtomicBoolean foundClassParam = new AtomicBoolean(classPathParams.isEmpty());

        for (Method method : methods) {
            Set<String> methodParameterPathParams = new HashSet<>();
            for (Parameter p : method.getParameters()) {
                PathParam pathParam = p.getAnnotation(PathParam.class);
                if (pathParam != null) {
                    methodParameterPathParams.add(pathParam.value());
                }
            }

            Path methodPathAnno = method.getAnnotation(Path.class);
            Set<String> methodPathParams = new HashSet<>();

            if (methodPathAnno != null) {
                Matcher matchPattern = parameterPattern.matcher(methodPathAnno.value());
                while (matchPattern.find()) {
                    methodPathParams.add(matchPattern.group(1));
                }
            }

            if (methodPathAnno == null && !methodParameterPathParams.isEmpty()) {
                Set<String> unmatchedParams = methodParameterPathParams.stream()
                        .filter(s -> {
                            if (classPathParams.contains(s)) {
                                foundClassParam.set(true);
                            }
                            return !classPathParams.contains(s);
                        })
                        .collect(Collectors.toSet());

                if (!unmatchedParams.isEmpty()) {
                    throw new RestClientDefinitionException(
                            String.format("Parameters on %s::%s are not present on either Class or Method @Path: %s",
                                    typeDef.getName(), method.getName(), Arrays.toString(unmatchedParams.toArray())));
                }
            } else if (!methodPathParams.isEmpty() && !methodParameterPathParams.isEmpty()) {
                if (!classPathParams.isEmpty()) {
                    classPathParams.forEach(s -> {
                        if (methodParameterPathParams.remove(s)) {
                            foundClassParam.set(true);
                        }
                    });
                }

                if (methodPathParams.size() != methodParameterPathParams.size()) {
                    throw new RestClientDefinitionException("Mismatched @Path and @PathParam template variables on " + typeDef.getName() + "::" + method.getName());
                }
                if (!methodPathParams.containsAll(methodParameterPathParams)) {
                    throw new RestClientDefinitionException("Mismatched @Path and @PathParam template variables on " + typeDef.getName() + "::" + method.getName());
                }
            } else if (!methodPathParams.isEmpty()) {
                throw new RestClientDefinitionException("Mismatched @Path and @PathParam template variables on " + typeDef.getName() + "::" + method.getName());
            }
        }

        if (!foundClassParam.get()) {
            throw new RestClientDefinitionException("@Path on Class " + typeDef.getName() + " contains path template without corresponding @PathParam on method parameter.");
        }
    }
}
