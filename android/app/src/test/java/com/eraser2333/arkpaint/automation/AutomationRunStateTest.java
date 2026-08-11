package com.eraser2333.arkpaint.automation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AutomationRunStateTest {
    @Test
    public void pauseAndResumeKeepTheSameRunAlive() throws Exception {
        AutomationRunState state = new AutomationRunState();

        assertTrue(state.begin(true));
        assertTrue(state.requestPause());
        assertTrue(state.isBusy());
        assertTrue(state.isPaused());
        assertTrue(state.requestResume());
        assertFalse(state.isPaused());
        assertTrue(state.awaitRunnable());
    }

    @Test
    public void verificationJobCannotBePaused() {
        AutomationRunState state = new AutomationRunState();

        assertTrue(state.begin(false));
        assertFalse(state.requestPause());
        assertFalse(state.canPause());
    }

    @Test
    public void stopWakesPausedWorker() throws Exception {
        AutomationRunState state = new AutomationRunState();
        assertTrue(state.begin(true));
        assertTrue(state.requestPause());

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean runnableResult = new AtomicBoolean(true);
        Thread waiter = new Thread(() -> {
            entered.countDown();
            try {
                runnableResult.set(state.awaitRunnable());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        });
        waiter.start();

        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertFalse(finished.await(80, TimeUnit.MILLISECONDS));
        assertTrue(state.requestStop());
        assertTrue(finished.await(1, TimeUnit.SECONDS));
        assertFalse(runnableResult.get());
        assertTrue(state.isStopRequested());
    }

    @Test
    public void resumeWakesPausedWorkerWithoutEndingRun() throws Exception {
        AutomationRunState state = new AutomationRunState();
        assertTrue(state.begin(true));
        assertTrue(state.requestPause());

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean runnableResult = new AtomicBoolean(false);
        Thread waiter = new Thread(() -> {
            entered.countDown();
            try {
                runnableResult.set(state.awaitRunnable());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        });
        waiter.start();

        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertFalse(finished.await(80, TimeUnit.MILLISECONDS));
        assertTrue(state.requestResume());
        assertTrue(finished.await(1, TimeUnit.SECONDS));
        assertTrue(runnableResult.get());
        assertTrue(state.isBusy());
        assertFalse(state.isPaused());
    }

    @Test
    public void finishAllowsANewRun() {
        AutomationRunState state = new AutomationRunState();

        assertTrue(state.begin(true));
        assertFalse(state.begin(true));
        state.finish();
        assertTrue(state.begin(false));
    }
}
