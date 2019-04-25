/**
 * Copyright 2015-2017 Red Hat, Inc, and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.smallrye.restclient;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientResponseContext;
import javax.ws.rs.client.ClientResponseFilter;
import javax.ws.rs.core.Response;

import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

public class ExceptionMapping implements ClientResponseFilter {

    public ExceptionMapping(Set<Object> instances) {
        this.instances = instances;
    }

    @Override
    public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext) throws IOException {

        Response response = new PartialResponse(responseContext);

        Map<ResponseExceptionMapper, Integer> mappers = new HashMap<>();
        for (Object o : instances) {
            if (o instanceof ResponseExceptionMapper) {
                ResponseExceptionMapper candiate = (ResponseExceptionMapper) o;
                if (candiate.handles(response.getStatus(), response.getHeaders())) {
                    mappers.put(candiate, candiate.getPriority());
                }
            }
        }

        if (mappers.size() > 0) {
            Map<Optional<Throwable>, Integer> errors = new HashMap<>();

            mappers.forEach((m, i) -> {
                Optional<Throwable> t = Optional.ofNullable(m.toThrowable(response));
                errors.put(t, i);
            });

            Optional<Throwable> prioritised = Optional.empty();
            for (Optional<Throwable> throwable : errors.keySet()) {
                if (throwable.isPresent()) {
                    if (!prioritised.isPresent()) {
                        prioritised = throwable;
                    }  else if (errors.get(throwable) < errors.get(prioritised)) {
                        prioritised = throwable;
                    }
                }
            }

            response.bufferEntity();
            if (prioritised.isPresent()) { // strange rule from the spec
                Throwable throwable = prioritised.get();
                WebApplicationException exception;
                if (throwable instanceof WebApplicationException) {
                    exception = (WebApplicationException) throwable;
                } else {
                    exception = new WebApplicationException(throwable);
                }
                throw exception;
            }
        }

    }

    private Set<Object> instances;
}
