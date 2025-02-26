/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.script.mustache;

import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.action.CompositeIndicesRequest;
import org.opensearch.action.search.MultiSearchRequest;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.support.IndicesOptions;
import org.opensearch.common.bytes.BytesReference;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.xcontent.ToXContent;
import org.opensearch.common.xcontent.XContent;
import org.opensearch.common.xcontent.XContentBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.opensearch.action.ValidateActions.addValidationError;

public class MultiSearchTemplateRequest extends ActionRequest implements CompositeIndicesRequest {

    private int maxConcurrentSearchRequests = 0;
    private List<SearchTemplateRequest> requests = new ArrayList<>();

    private IndicesOptions indicesOptions = IndicesOptions.strictExpandOpenAndForbidClosedIgnoreThrottled();

    public MultiSearchTemplateRequest() {}

    public MultiSearchTemplateRequest(StreamInput in) throws IOException {
        super(in);
        maxConcurrentSearchRequests = in.readVInt();
        requests = in.readList(SearchTemplateRequest::new);
    }

    /**
     * Add a search template request to execute. Note, the order is important, the search response will be returned in the
     * same order as the search requests.
     */
    public MultiSearchTemplateRequest add(SearchTemplateRequestBuilder request) {
        requests.add(request.request());
        return this;
    }

    /**
     * Add a search template request to execute. Note, the order is important, the search response will be returned in the
     * same order as the search requests.
     */
    public MultiSearchTemplateRequest add(SearchTemplateRequest request) {
        requests.add(request);
        return this;
    }

    /**
     * Returns the amount of search requests specified in this multi search requests are allowed to be ran concurrently.
     */
    public int maxConcurrentSearchRequests() {
        return maxConcurrentSearchRequests;
    }

    /**
     * Sets how many search requests specified in this multi search requests are allowed to be ran concurrently.
     */
    public MultiSearchTemplateRequest maxConcurrentSearchRequests(int maxConcurrentSearchRequests) {
        if (maxConcurrentSearchRequests < 1) {
            throw new IllegalArgumentException("maxConcurrentSearchRequests must be positive");
        }

        this.maxConcurrentSearchRequests = maxConcurrentSearchRequests;
        return this;
    }

    public List<SearchTemplateRequest> requests() {
        return this.requests;
    }

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = null;
        if (requests.isEmpty()) {
            validationException = addValidationError("no requests added", validationException);
        }
        for (SearchTemplateRequest request : requests) {
            ActionRequestValidationException ex = request.validate();
            if (ex != null) {
                if (validationException == null) {
                    validationException = new ActionRequestValidationException();
                }
                validationException.addValidationErrors(ex.validationErrors());
            }
        }
        return validationException;
    }

    public IndicesOptions indicesOptions() {
        return indicesOptions;
    }

    public MultiSearchTemplateRequest indicesOptions(IndicesOptions indicesOptions) {
        this.indicesOptions = indicesOptions;
        return this;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeVInt(maxConcurrentSearchRequests);
        out.writeList(requests);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MultiSearchTemplateRequest that = (MultiSearchTemplateRequest) o;
        return maxConcurrentSearchRequests == that.maxConcurrentSearchRequests &&
                Objects.equals(requests, that.requests) &&
                Objects.equals(indicesOptions, that.indicesOptions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxConcurrentSearchRequests, requests, indicesOptions);
    }

    public static byte[] writeMultiLineFormat(MultiSearchTemplateRequest multiSearchTemplateRequest,
            XContent xContent) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (SearchTemplateRequest templateRequest : multiSearchTemplateRequest.requests()) {
            final SearchRequest searchRequest = templateRequest.getRequest();
            try (XContentBuilder xContentBuilder = XContentBuilder.builder(xContent)) {
                MultiSearchRequest.writeSearchRequestParams(searchRequest, xContentBuilder);
                BytesReference.bytes(xContentBuilder).writeTo(output);
            }
            output.write(xContent.streamSeparator());
            try (XContentBuilder xContentBuilder = XContentBuilder.builder(xContent)) {
                templateRequest.toXContent(xContentBuilder, ToXContent.EMPTY_PARAMS);
                BytesReference.bytes(xContentBuilder).writeTo(output);
            }
            output.write(xContent.streamSeparator());
        }
        return output.toByteArray();
    }

}
