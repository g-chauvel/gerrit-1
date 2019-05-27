// Copyright (C) 2018 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.logging;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;

/**
 * Wrapper for a {@link Runnable} that copies the {@link LoggingContext} from the current thread to
 * the thread that executes the runnable.
 *
 * <p>The state of the logging context that is copied to the thread that executes the runnable is
 * fixed at the creation time of this wrapper. If the runnable is submitted to an executor and is
 * executed later this means that changes that are done to the logging context in between creating
 * and executing the runnable do not apply.
 *
 * <p>Example:
 *
 * <pre>
 *   try (TraceContext traceContext = TraceContext.newTrace(true, ...)) {
 *     executor
 *         .submit(new LoggingContextAwareRunnable(
 *             () -> {
 *               // Tracing is enabled since the runnable is created within the TraceContext.
 *               // Tracing is even enabled if the executor runs the runnable only after the
 *               // TraceContext was closed.
 *
 *               // The tag "foo=bar" is not set, since it was added to the logging context only
 *               // after this runnable was created.
 *
 *               // do stuff
 *             }))
 *         .get();
 *     traceContext.addTag("foo", "bar");
 *   }
 * </pre>
 *
 * @see LoggingContextAwareCallable
 */
public class LoggingContextAwareRunnable implements Runnable {
  private final Runnable runnable;
  private final Thread callingThread;
  private final ImmutableSetMultimap<String, String> tags;
  private final boolean forceLogging;
  private final boolean performanceLogging;
  private final MutablePerformanceLogRecords mutablePerformanceLogRecords;

  /**
   * Creates a LoggingContextAwareRunnable that wraps the given {@link Runnable}.
   *
   * @param runnable Runnable that should be wrapped.
   * @param mutablePerformanceLogRecords instance of {@link MutablePerformanceLogRecords} to which
   *     performance log records that are created from the runnable are added
   */
  LoggingContextAwareRunnable(
      Runnable runnable, MutablePerformanceLogRecords mutablePerformanceLogRecords) {
    this.runnable = runnable;
    this.callingThread = Thread.currentThread();
    this.tags = LoggingContext.getInstance().getTagsAsMap();
    this.forceLogging = LoggingContext.getInstance().isLoggingForced();
    this.performanceLogging = LoggingContext.getInstance().isPerformanceLogging();
    this.mutablePerformanceLogRecords = mutablePerformanceLogRecords;
  }

  public Runnable unwrap() {
    return runnable;
  }

  @Override
  public void run() {
    if (callingThread.equals(Thread.currentThread())) {
      // propagation of logging context is not needed
      runnable.run();
      return;
    }

    // propagate logging context
    LoggingContext loggingCtx = LoggingContext.getInstance();
    ImmutableSetMultimap<String, String> oldTags = loggingCtx.getTagsAsMap();
    boolean oldForceLogging = loggingCtx.isLoggingForced();
    boolean oldPerformanceLogging = loggingCtx.isPerformanceLogging();
    ImmutableList<PerformanceLogRecord> oldPerformanceLogRecords =
        loggingCtx.getPerformanceLogRecords();
    loggingCtx.setTags(tags);
    loggingCtx.forceLogging(forceLogging);
    loggingCtx.performanceLogging(performanceLogging);

    // For the performance log records use the {@link MutablePerformanceLogRecords} instance from
    // the logging context of the calling thread in the logging context of the new thread. This way
    // performance log records that are created from the new thread are available from the logging
    // context of the calling thread. This is important since performance log records are processed
    // only at the end of the request and performance log records that are created in another thread
    // should not get lost.
    loggingCtx.setMutablePerformanceLogRecords(mutablePerformanceLogRecords);
    try {
      runnable.run();
    } finally {
      loggingCtx.setTags(oldTags);
      loggingCtx.forceLogging(oldForceLogging);
      loggingCtx.performanceLogging(oldPerformanceLogging);
      loggingCtx.setPerformanceLogRecords(oldPerformanceLogRecords);
    }
  }
}
