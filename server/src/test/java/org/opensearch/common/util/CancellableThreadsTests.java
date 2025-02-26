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
 *     http://www.apache.org/licenses/LICENSE-2.0
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

package org.opensearch.common.util;

import org.opensearch.common.util.CancellableThreads.IOInterruptible;
import org.opensearch.common.util.CancellableThreads.Interruptible;
import org.opensearch.test.OpenSearchTestCase;
import org.hamcrest.Matchers;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.opensearch.common.util.CancellableThreads.ExecutionCancelledException;
import static org.hamcrest.Matchers.equalTo;

public class CancellableThreadsTests extends OpenSearchTestCase {
    public static class CustomException extends RuntimeException {
        public CustomException(String msg) {
            super(msg);
        }
    }

    public static class IOCustomException extends IOException {
        public IOCustomException(String msg) {
            super(msg);
        }
    }

    static class ThrowOnCancelException extends RuntimeException {
    }

    private class TestPlan {
        public final int id;
        public final boolean busySpin;
        public final boolean exceptBeforeCancel;
        public final boolean exitBeforeCancel;
        public final boolean exceptAfterCancel;
        public final boolean presetInterrupt;
        public final boolean ioOp;
        private final boolean ioException;

        private TestPlan(int id) {
            this.id = id;
            this.busySpin = randomBoolean();
            this.exceptBeforeCancel = randomBoolean();
            this.exitBeforeCancel = randomBoolean();
            this.exceptAfterCancel = randomBoolean();
            this.presetInterrupt = randomBoolean();
            this.ioOp = randomBoolean();
            this.ioException = ioOp && randomBoolean();
        }
    }

    static class TestRunnable implements Interruptible {
        final TestPlan plan;
        final CountDownLatch readyForCancel;

        TestRunnable(TestPlan plan, CountDownLatch readyForCancel) {
            this.plan = plan;
            this.readyForCancel = readyForCancel;
        }

        @Override
        public void run() throws InterruptedException {
            assertFalse("interrupt thread should have been clear", Thread.currentThread().isInterrupted());
            if (plan.exceptBeforeCancel) {
                throw new CustomException("thread [" + plan.id + "] pre-cancel exception");
            } else if (plan.exitBeforeCancel) {
                return;
            }
            readyForCancel.countDown();
            try {
                if (plan.busySpin) {
                    while (!Thread.currentThread().isInterrupted()) {
                    }
                } else {
                    Thread.sleep(50000);
                }
            } finally {
                if (plan.exceptAfterCancel) {
                    throw new CustomException("thread [" + plan.id + "] post-cancel exception");
                }
            }
        }
    }

    static class TestIORunnable implements IOInterruptible {
        final TestPlan plan;
        final CountDownLatch readyForCancel;

        TestIORunnable(TestPlan plan, CountDownLatch readyForCancel) {
            this.plan = plan;
            this.readyForCancel = readyForCancel;
        }

        @Override
        public void run() throws IOException, InterruptedException {
            assertFalse("interrupt thread should have been clear", Thread.currentThread().isInterrupted());
            if (plan.exceptBeforeCancel) {
                throw new IOCustomException("thread [" + plan.id + "] pre-cancel exception");
            } else if (plan.exitBeforeCancel) {
                return;
            }
            readyForCancel.countDown();
            try {
                if (plan.busySpin) {
                    while (!Thread.currentThread().isInterrupted()) {
                    }
                } else {
                    Thread.sleep(50000);
                }
            } finally {
                if (plan.exceptAfterCancel) {
                    throw new IOCustomException("thread [" + plan.id + "] post-cancel exception");
                }
            }

        }
    }

    public void testCancellableThreads() throws InterruptedException {
        Thread[] threads = new Thread[randomIntBetween(3, 10)];
        final TestPlan[] plans = new TestPlan[threads.length];
        final Exception[] exceptions = new Exception[threads.length];
        final boolean[] interrupted = new boolean[threads.length];
        final CancellableThreads cancellableThreads = new CancellableThreads();
        final CountDownLatch readyForCancel = new CountDownLatch(threads.length);
        for (int i = 0; i < threads.length; i++) {
            final TestPlan plan = new TestPlan(i);
            plans[i] = plan;
            threads[i] = new Thread(() -> {
                try {
                    if (plan.presetInterrupt) {
                        Thread.currentThread().interrupt();
                    }
                    if (plan.ioOp) {
                        if (plan.ioException) {
                            cancellableThreads.executeIO(new TestIORunnable(plan, readyForCancel));
                        } else {
                            cancellableThreads.executeIO(new TestRunnable(plan, readyForCancel));
                        }
                    } else {
                        cancellableThreads.execute(new TestRunnable(plan, readyForCancel));
                    }
                } catch (Exception e) {
                    exceptions[plan.id] = e;
                }
                if (plan.exceptBeforeCancel || plan.exitBeforeCancel) {
                    // we have to mark we're ready now (actually done).
                    readyForCancel.countDown();
                }
                interrupted[plan.id] = Thread.currentThread().isInterrupted();
            });
            threads[i].setDaemon(true);
            threads[i].start();
        }

        readyForCancel.await();
        final boolean throwInOnCancel = randomBoolean();
        final AtomicInteger invokeTimes = new AtomicInteger();
        cancellableThreads.setOnCancel((reason, beforeCancelException) -> {
            invokeTimes.getAndIncrement();
            if (throwInOnCancel) {
                ThrowOnCancelException e = new ThrowOnCancelException();
                if (beforeCancelException != null) {
                    e.addSuppressed(beforeCancelException);
                }
                throw e;
            }
        });

        cancellableThreads.cancel("test");
        for (Thread thread : threads) {
            thread.join(20000);
            assertFalse(thread.isAlive());
        }
        for (int i = 0; i < threads.length; i++) {
            TestPlan plan = plans[i];
            final Class<?> exceptionClass = plan.ioException ? IOCustomException.class : CustomException.class;
            if (plan.exceptBeforeCancel) {
                assertThat(exceptions[i], Matchers.instanceOf(exceptionClass));
            } else if (plan.exitBeforeCancel) {
                assertNull(exceptions[i]);
            } else {
                // in all other cases, we expect a cancellation exception.
                if (throwInOnCancel) {
                    assertThat(exceptions[i], Matchers.instanceOf(ThrowOnCancelException.class));
                } else {
                    assertThat(exceptions[i], Matchers.instanceOf(ExecutionCancelledException.class));
                }
                if (plan.exceptAfterCancel) {
                    assertThat(exceptions[i].getSuppressed(),
                            Matchers.arrayContaining(
                                    Matchers.instanceOf(exceptionClass)
                            ));
                } else {
                    assertThat(exceptions[i].getSuppressed(), Matchers.emptyArray());
                }
            }
            assertThat(interrupted[plan.id], equalTo(plan.presetInterrupt));
        }
        assertThat(invokeTimes.longValue(),
            equalTo(Arrays.stream(plans).filter(p -> p.exceptBeforeCancel == false && p.exitBeforeCancel == false).count()));
        if (throwInOnCancel) {
            expectThrows(ThrowOnCancelException.class, cancellableThreads::checkForCancel);
        } else {
            expectThrows(ExecutionCancelledException.class, cancellableThreads::checkForCancel);
        }
        assertThat(invokeTimes.longValue(),
            equalTo(Arrays.stream(plans).filter(p -> p.exceptBeforeCancel == false && p.exitBeforeCancel == false).count() + 1));
    }

}
