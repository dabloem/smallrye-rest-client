/**
 * Copyright 2019 Red Hat, Inc, and individual contributors.
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
package io.smallrye.restclient.header;

import org.eclipse.microprofile.rest.client.RestClientDefinitionException;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;

import javax.ws.rs.core.MultivaluedMap;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public class ClientHeaderProvider {

    static Optional<ClientHeaderProvider> forMethod(Method method, Object clientProxy) {
        Class<?> declaringClass = method.getDeclaringClass();

        ClientHeaderParam[] methodAnnotations = method.getAnnotationsByType(ClientHeaderParam.class);
        ClientHeaderParam[] classAnnotations = declaringClass.getAnnotationsByType(ClientHeaderParam.class);

        Map<String, ClientHeaderValueGenerator> generators = new HashMap<>();

        for (ClientHeaderParam annotation : methodAnnotations) {
            if (generators.containsKey(annotation.name())) {
                throw new RestClientDefinitionException("Duplicate " + ClientHeaderParam.class.getSimpleName() +
                        " annotation definitions found on " + method.toString());
            }
            generators.put(annotation.name(), new ClientHeaderValueGenerator(annotation, declaringClass, clientProxy));
        }

        checkForDuplicateClassLevelAnnotations(classAnnotations, declaringClass);

        Stream.of(classAnnotations)
                .filter(a -> !generators.containsKey(a.name()))
                .forEach(a -> generators.put(a.name(), new ClientHeaderValueGenerator(a, declaringClass, clientProxy)));

        return generators.isEmpty()
                ? Optional.empty()
                : Optional.of(new ClientHeaderProvider(generators.values()));
    }

    private static void checkForDuplicateClassLevelAnnotations(ClientHeaderParam[] classAnnotations, Class<?> declaringClass) {
        Set<String> headerNames = new HashSet<>();
        Arrays.stream(classAnnotations)
                .map(ClientHeaderParam::name)
                .forEach(
                        name -> {
                            if (!headerNames.add(name)) {
                                throw new RestClientDefinitionException(
                                        "Duplicate ClientHeaderParam definition for header name " + name + " on class "
                                                + declaringClass.getCanonicalName());
                            }
                        }
                );
    }

    private final Collection<ClientHeaderValueGenerator> generators;

    ClientHeaderProvider(Collection<ClientHeaderValueGenerator> generators) {
        this.generators = generators;
    }

    public void addHeaders(MultivaluedMap<String, String> headers) {
        generators.forEach(g -> g.fillHeaders(headers));
    }

}
