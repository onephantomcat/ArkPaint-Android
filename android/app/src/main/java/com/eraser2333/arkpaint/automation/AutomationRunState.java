package com.eraser2333.arkpaint.automation;

/**
 * Thread-safe lifecycle for one accessibility automation job.
 *
 * <p>Pause keeps the worker and all of its in-memory drawing state alive. Stop wakes a paused
 * worker so it can unwind immediately.</p>
 */
final class AutomationRunState {
    private enum State {
        IDLE,
        RUNNING,
        PAUSED,
        STOPPING
    }

    private State state = State.IDLE;
    private boolean pausable;
    private long pausedAtNanos;
    private long accumulatedPausedNanos;
    private long pauseGeneration;

    synchronized boolean begin(boolean allowPause) {
        if (state != State.IDLE) {
            return false;
        }
        state = State.RUNNING;
        pausable = allowPause;
        pausedAtNanos = 0L;
        accumulatedPausedNanos = 0L;
        pauseGeneration = 0L;
        return true;
    }

    synchronized boolean requestPause() {
        if (!pausable || state != State.RUNNING) {
            return false;
        }
        state = State.PAUSED;
        pausedAtNanos = System.nanoTime();
        pauseGeneration++;
        return true;
    }

    synchronized boolean requestResume() {
        if (state != State.PAUSED) {
            return false;
        }
        closePauseWindow();
        state = State.RUNNING;
        notifyAll();
        return true;
    }

    synchronized boolean requestStop() {
        if (state == State.IDLE || state == State.STOPPING) {
            return false;
        }
        if (state == State.PAUSED) {
            closePauseWindow();
        }
        state = State.STOPPING;
        notifyAll();
        return true;
    }

    synchronized boolean awaitRunnable() throws InterruptedException {
        while (state == State.PAUSED) {
            wait();
        }
        return state == State.RUNNING;
    }

    synchronized void finish() {
        state = State.IDLE;
        pausable = false;
        pausedAtNanos = 0L;
        accumulatedPausedNanos = 0L;
        notifyAll();
    }

    synchronized boolean isBusy() {
        return state != State.IDLE;
    }

    synchronized boolean isPaused() {
        return state == State.PAUSED;
    }

    synchronized boolean isStopRequested() {
        return state == State.STOPPING;
    }

    synchronized boolean canPause() {
        return pausable && state == State.RUNNING;
    }

    synchronized long getPausedDurationMillis() {
        long pausedNanos = accumulatedPausedNanos;
        if (state == State.PAUSED && pausedAtNanos != 0L) {
            pausedNanos += Math.max(0L, System.nanoTime() - pausedAtNanos);
        }
        return pausedNanos / 1_000_000L;
    }

    synchronized long getPauseGeneration() {
        return pauseGeneration;
    }

    private void closePauseWindow() {
        if (pausedAtNanos != 0L) {
            accumulatedPausedNanos += Math.max(0L, System.nanoTime() - pausedAtNanos);
            pausedAtNanos = 0L;
        }
    }
}
