/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.centraldogma.client;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;

/**
 * Watches the changes of a repository or a file.
 *
 * @param <T> the watch result type
 */
public interface Watcher<T> extends AutoCloseable {

    /**
     * Creates a forked {@link Watcher} based on an existing {@link JsonNode}-watching {@link Watcher}.
     *
     * @param jsonPointer a <a href="https://tools.ietf.org/html/rfc6901">JSON pointer</a> that is encoded
     *
     * @return A new child {@link Watcher}, whose transformation is a
     *         <a href="https://tools.ietf.org/html/rfc6901">JSON pointer</a> query.
     */
    static Watcher<JsonNode> atJsonPointer(Watcher<JsonNode> watcher, String jsonPointer) {
        requireNonNull(watcher, "watcher");
        requireNonNull(jsonPointer, "jsonPointer");
        return watcher.newChild(new Function<JsonNode, JsonNode>() {
            @Override
            public JsonNode apply(JsonNode node) {
                if (node == null) {
                    return MissingNode.getInstance();
                } else {
                    return node.at(jsonPointer);
                }
            }

            @Override
            public String toString() {
                return "JSON pointer " + jsonPointer;
            }
        });
    }

    /**
     * Returns the {@link CompletableFuture} which is completed when the initial value retrieval is done
     * successfully.
     */
    CompletableFuture<Latest<T>> initialValueFuture();

    /**
     * Waits for the initial value to be available.
     *
     * @return the {@link Latest} object that contains the initial value and the {@link Revision} where
     *         the initial value came from.
     *
     * @throws CancellationException if this watcher has been closed by {@link #close()}
     */
    default Latest<T> awaitInitialValue() throws InterruptedException {
        try {
            return initialValueFuture().get();
        } catch (ExecutionException e) {
            // Should never occur because we never complete this future exceptionally.
            throw new Error(e);
        }
    }

    /**
     * Waits for the initial value to be available. Specify the default value with
     * {@link #awaitInitialValue(long, TimeUnit, Object)} or make sure to handle
     * a {@link TimeoutException} properly if the initial value must be available
     * even when the server is unavailable.
     *
     * @param timeout the maximum amount of time to wait for the initial value. Note that timeout is basically
     *                a trade-off. If you specify a smaller timeout, this method will have a higher chance of
     *                throwing a {@link TimeoutException} when the server does not respond in time.
     *                If you specify a larger timeout, you will have a better chance of successful retrieval.
     *                It is generally recommended to use a value not less than
     *                {@value WatchConstants#RECOMMENDED_AWAIT_TIMEOUT_SECONDS} seconds so that
     *                the client can retry at least a few times before timing out.
     *                Consider using {@link #awaitInitialValue(long, TimeUnit, Object)} with a sensible default
     *                value if you cannot tolerate a timeout or need to use a small timeout.
     * @param unit the {@link TimeUnit} of {@code timeout}.
     *
     * @return the {@link Latest} object that contains the initial value and the {@link Revision} where
     *         the initial value came from.
     *
     * @throws CancellationException if this watcher has been closed by {@link #close()}
     * @throws TimeoutException if failed to retrieve the initial value within the specified timeout
     */
    default Latest<T> awaitInitialValue(long timeout, TimeUnit unit) throws InterruptedException,
                                                                            TimeoutException {
        requireNonNull(unit, "unit");
        try {
            return initialValueFuture().get(timeout, unit);
        } catch (ExecutionException e) {
            // Should never occur because we never complete this future exceptionally.
            throw new Error(e);
        }
    }

    /**
     * Waits for the initial value to be available and returns the specified default value if failed
     * to retrieve the initial value from the server.
     *
     * @param timeout the maximum amount of time to wait for the initial value. Note that timeout is basically
     *                a trade-off. If you specify a smaller timeout, this method will have a higher chance of
     *                falling back to the {@code defaultValue} when the server does not respond in time.
     *                If you specify a larger timeout, you will have a better chance of retrieving
     *                an up-to-date initial value. It is generally recommended to use a value not less than
     *                {@value WatchConstants#RECOMMENDED_AWAIT_TIMEOUT_SECONDS} seconds
     *                so that the client can retry at least a few times before timing out.
     * @param unit the {@link TimeUnit} of {@code timeout}.
     * @param defaultValue the default value to use when timed out.
     *
     * @return the initial value, or the default value if timed out.
     *
     * @throws CancellationException if this watcher has been closed by {@link #close()}
     */
    @Nullable
    default T awaitInitialValue(long timeout, TimeUnit unit, @Nullable T defaultValue)
            throws InterruptedException {
        try {
            return awaitInitialValue(timeout, unit).value();
        } catch (TimeoutException e) {
            return defaultValue;
        }
    }

    /**
     * Returns the latest {@link Revision} and value of {@code watchFile()} result.
     *
     * @throws IllegalStateException if the value is not available yet.
     *                               Use {@link #awaitInitialValue(long, TimeUnit)} first or
     *                               add a listener using {@link #watch(BiConsumer)} instead.
     */
    Latest<T> latest();

    /**
     * Returns the latest value of {@code watchFile()} result.
     *
     * @throws IllegalStateException if the value is not available yet.
     *                               Use {@link #awaitInitialValue(long, TimeUnit)} first or
     *                               add a listener using {@link #watch(BiConsumer)} instead.
     */
    @Nullable
    default T latestValue() {
        return latest().value();
    }

    /**
     * Returns the latest value of {@code watchFile()} result.
     *
     * @param defaultValue the default value which is returned when the value is not available yet
     */
    @Nullable
    default T latestValue(@Nullable T defaultValue) {
        final CompletableFuture<Latest<T>> initialValueFuture = initialValueFuture();
        if (initialValueFuture.isDone() && !initialValueFuture.isCompletedExceptionally()) {
            return latest().value();
        } else {
            return defaultValue;
        }
    }

    /**
     * Stops watching the file specified in the {@link Query} or the {@code pathPattern} in the repository.
     */
    @Override
    void close();

    /**
     * Registers a {@link BiConsumer} that will be invoked when the value of the watched entry becomes
     * available or changes.
     */
    void watch(BiConsumer<? super Revision, ? super T> listener);

    /**
     * Registers a {@link BiConsumer} that will be invoked when the value of the watched entry becomes
     * available or changes.
     *
     * @param executor the {@link Executor} that executes the {@link BiConsumer}
     */
    void watch(BiConsumer<? super Revision, ? super T> listener, Executor executor);

    /**
     * Registers a {@link Consumer} that will be invoked when the value of the watched entry becomes available
     * or changes.
     */
    default void watch(Consumer<? super T> listener) {
        requireNonNull(listener, "listener");
        watch((revision, value) -> listener.accept(value));
    }

    /**
     * Registers a {@link Consumer} that will be invoked when the value of the watched entry becomes available
     * or changes.
     */
    default void watch(Consumer<? super T> listener, Executor executor) {
        requireNonNull(listener, "listener");
        requireNonNull(executor, "executor");
        watch((revision, value) -> listener.accept(value), executor);
    }

    /**
     * Forks into a new {@link Watcher}, that reuses the current watcher and applies a transformation.
     *
     * @return A {@link Watcher} that is effectively filtering in a sense that,
     *         its listeners are <b>not</b> notified when a change has no effect on the transformed value.
     *         Furthermore, it does not need to be closed after use.
     */
    default <U> Watcher<U> newChild(Function<T, U> transformer) {
        requireNonNull(transformer, "transformer");
        return new TransformingWatcher<>(this, transformer);
    }
}
