package eu.tgx03.transcode;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An executor service who uses a priority queue to prefer callables over runnables,
 * so that futures get returned as fast as possible, while normal runnables run later.
 */
public class SingleThreadFuturePriorityExecutorService extends AbstractExecutorService implements ExecutorService {

	/**
	 * The priority queue used for the tasks.
	 */
	private final PriorityBlockingQueue<Runnable> queue = new PriorityBlockingQueue<>(16, ((o1, o2) -> {
		assert o1 != null && o2 != null;
		if (o1 instanceof FutureTask<?>) {
			if (o2 instanceof FutureTask<?>) return 0;
			else return 1;
		} else {
			if (o2 instanceof FutureTask<?>) return -1;
			else return 0;
		}
	}));
	/**
	 * The thread actually executing the tasks.
	 */
	private final Thread worker;
	/**
	 * The lock that gets used to allow other threads to wait for the termination of this Executor.
	 */
	private final ReentrantLock terminationLock = new ReentrantLock();
	/**
	 * The condition other threads actually wait for.
	 */
	private final Condition terminationCondition = terminationLock.newCondition();

	/**
	 * This boolean gets set when this Executor shall shut down in a normal manner,
	 * meaning it doesn't accept any new tasks.
	 */
	private volatile boolean shutdown = false;
	/**
	 * This boolean gets set when this Executor shall shut down immediately,
	 * dropping all the tasks that are still lined up.
	 */
	private volatile boolean forceShutdown = false;
	/**
	 * This boolean gets set when the worker thread has shut down.
	 */
	private volatile boolean terminated = false;

	/**
	 * Create a new Executor with default parameters.
	 */
	public SingleThreadFuturePriorityExecutorService() {
		worker = new Thread(new Worker());
		worker.start();
	}

	/**
	 * Creates a new Executor with the given thread factory.
	 * The thread factory only gets used once to create the single worker thread.
	 *
	 * @param threadFactory The thread factory that should be used to create the worker thread.
	 */
	public SingleThreadFuturePriorityExecutorService(ThreadFactory threadFactory) {
		worker = threadFactory.newThread(new Worker());
		worker.start();
	}

	/**
	 * Shuts down this Executor in a clean manner, meaning no more tasks get accepted,
	 * but all remaining tasks get executed and the thread then shuts down.
	 */
	@Override
	public void shutdown() {
		shutdown = true;
	}

	/**
	 * Shuts down this Executor immediately, returning a list of all tasks remaining in the queue.
	 *
	 * @return All not-yet executed tasks.
	 */
	@NotNull
	@Override
	public List<Runnable> shutdownNow() {
		shutdown = true;
		forceShutdown = true;
		worker.interrupt();
		return queue.stream().toList();
	}

	/**
	 * Returns whether this Executor was told to shut down.
	 *
	 * @return Whether this Executor was informed to shut down.
	 */
	@Override
	public boolean isShutdown() {
		return shutdown;
	}

	/**
	 * Returns true if the worker thread in the background has terminated after execution.
	 *
	 * @return Whether this executor has terminated.
	 */
	@Override
	public boolean isTerminated() {
		return terminated;
	}

	@Override
	public boolean awaitTermination(long timeout, @NotNull TimeUnit unit) throws InterruptedException {
		if (terminated) return true;
		terminationLock.lock();
		try {
			return terminationCondition.await(timeout, unit);
		} finally {
			terminationLock.unlock();
		}
	}

	/**
	 * Adds a new runnable to this task.
	 *
	 * @param command The runnable to add.
	 * @throws RejectedExecutionException When the task couldn't be added, likely because the Executor was shut down.
	 */
	@Override
	public void execute(@NotNull Runnable command) throws RejectedExecutionException {
		if (shutdown || forceShutdown || terminated)
			throw new RejectedExecutionException("This executor has been shut down");
		queue.put(command);
	}

	/**
	 * The runnable used to execute the tasks.
	 */
	private class Worker implements Runnable {

		@Override
		public void run() {
			while (!shutdown) {
				try {
					queue.take().run();
				} catch (InterruptedException e) {
					// This should only be reached if the thread gets interrupted in a force shutdown.
					assert forceShutdown : e;
				} catch (RuntimeException e) {
					e.printStackTrace();
				}
			}
			while (!forceShutdown && !queue.isEmpty()) {
				Runnable task = queue.poll();
				if (task != null) {
					try {
						task.run();
					} catch (RuntimeException e) {
						e.printStackTrace();
					}
				}
			}
			terminated = true;
			terminationLock.lock();
			terminationCondition.signalAll();
			terminationLock.unlock();
		}
	}
}