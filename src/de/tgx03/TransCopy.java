package de.tgx03;

import me.tongfei.progressbar.ProgressBar;
import org.jetbrains.annotations.NotNull;
import org.mp4parser.IsoFile;
import org.mp4parser.boxes.sampleentry.VisualSampleEntry;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * A class intended to copy images and videos from one location to another,
 * whereby duplicates are deleted from the source
 * and videos get encoded using a preset from Handbrake.
 */
public class TransCopy {

	/**
	 * The queue for file copy operations.
	 * Used as parallel copy usually takes longer than serial.
	 */
	private static final ExecutorService COPIER = new SingleThreadFuturePriorityExecutorService();
	/**
	 * A queue for video encodings.
	 * Used as parallel encoding usually doesn't make much sense,
	 * and when using NVENC for example not even possible.
	 */
	private static final ExecutorService ENCODER = Executors.newSingleThreadExecutor();
	/**
	 * The thread pool used for the threads which traverse the directory.
	 */
	private static final ExecutorService TRAVERSER = Executors.newCachedThreadPool(r -> Thread.ofVirtual().unstarted(r));
	/**
	 * Counter for how many tasks exist in total.
	 */
	private static final LongAdder TASK_COUNT = new LongAdder();
	/**
	 * Counter for how many tasks are completed.
	 */
	private static final LongAdder TASK_COMPLETED = new LongAdder();

	/**
	 * The source directory to copy the files from.
	 */
	private static Path sourcePath;
	/**
	 * The path to copy the files to.
	 */
	private static Path targetPath;
	/**
	 * The location of the Handbrake executable.
	 */
	private static String handBrake;

	/**
	 * Copies images and videos from one location to another recursively,
	 * while transcoding videos in the process.
	 *
	 * @param args First argument is the source, second is the target, third is the Handbrake executable and fourth is the name of the Handbrake preset.
	 */
	public static void main(@NotNull String @NotNull [] args) throws InterruptedException {
		File source = new File(args[0]);
		sourcePath = source.toPath();
		targetPath = new File(args[1]).toPath();
		handBrake = args[2];
		VideoOperation.presetName = args[3];
		Phaser rootPhaser = new Phaser(2);

		traverseDirectory(source, rootPhaser);
		Thread progressBar = new Thread(TransCopy::drawProgressBar, "ProgressBar");
		progressBar.setDaemon(true);
		progressBar.start();
		rootPhaser.arriveAndAwaitAdvance();
		TRAVERSER.shutdown();
		ENCODER.shutdown();
		ENCODER.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
		COPIER.shutdown();
		COPIER.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
	}

	/**
	 * Goes through a directory, recursively creating new jobs for subdirectories and files.
	 *
	 * @param directory    The current directory to scan.
	 * @param parentPhaser The phaser to register and afterward deregister to/from.
	 */
	private static void traverseDirectory(@NotNull File directory, @NotNull Phaser parentPhaser) {
		try {
			assert directory.isDirectory();
			Phaser childPhaser = new Phaser(parentPhaser);
			for (File file : directory.listFiles()) {
				if (file.isDirectory()) {
					childPhaser.register();
					TASK_COUNT.increment();
					TRAVERSER.execute(() -> traverseDirectory(file, childPhaser));
				} else handleFile(file);
			}
		} finally {
			parentPhaser.arrive();
			TASK_COMPLETED.increment();
		}
	}

	/**
	 * Handles a specific file and either creates a copy or video job for it.
	 *
	 * @param file The file to copy.
	 */
	private static void handleFile(@NotNull File file) {
		assert !file.isDirectory();
		TASK_COUNT.increment();

		// Determine the type of file.
		String mimeType = URLConnection.guessContentTypeFromName(file.getName());
		if (mimeType == null) return;
		mimeType = mimeType.split("/")[0];

		// Calculate the target path from the source path.
		Path source = file.toPath();
		Path relativePath = sourcePath.relativize(source);
		Path target = targetPath.resolve(relativePath);

		switch (mimeType) {
			case "image" -> {
				MoveOperation op = new MoveOperation(source, target);
				if (op.deleteSourceIfExists()) TASK_COMPLETED.increment();
				else COPIER.execute(op);
			}
			case "video" -> {
				VideoOperation op = new VideoOperation(source, target);
				if (op.deleteSourceIfExists()) TASK_COMPLETED.increment();
				else ENCODER.execute(op);
			}
		}
	}

	/**
	 * Creates a progress bar on System.out
	 * For this it gets the completed tasks from each thread-pool and divides it by the total
	 * task count of all thread-pools.
	 * The task count doesn't seem to be the most stable method, so it varies a bit.
	 */
	private static void drawProgressBar() {
		try (ProgressBar bar = new ProgressBar("Progress", 1)) {
			ProgressBar.wrap(System.out, "Progress");
			while (true) {
				bar.maxHint(TASK_COUNT.sum());
				bar.stepTo(TASK_COMPLETED.sum());
				bar.refresh();
				try {
					Thread.sleep(500);
				} catch (InterruptedException ignored) {
				}
			}
		}
	}

	/**
	 * A class representing an action to move a file to another location.
	 */
	private static abstract class Operation implements Runnable {

		/**
		 * The source path.
		 */
		protected final Path source;
		/**
		 * The target path of the move operation.
		 */
		protected final Path target;
		/**
		 * The Path relative to the parent directories.
		 */
		protected final Path relative;

		/**
		 * Creates a new Operation to move on file between two locations.
		 *
		 * @param source The source path.
		 * @param target The target path.
		 */
		public Operation(@NotNull Path source, @NotNull Path target) {
			this.source = source;
			this.target = target;
			this.relative = targetPath.relativize(target);
		}

		/**
		 * Creates a new Operation to move on file between two locations.
		 * Also allows for explicit specification of the relative path.
		 *
		 * @param source   The source path.
		 * @param target   The target path.
		 * @param relative The relative path.
		 */
		protected Operation(@NotNull Path source, @NotNull Path target, @NotNull Path relative) {
			this.source = source;
			this.target = target;
			this.relative = relative;
		}

		/**
		 * Checks if the target already exists and then deletes the source.
		 *
		 * @return Whether the target already exists.
		 */
		public boolean deleteSourceIfExists() {
			try {
				checkJPG();
				if (Files.exists(target)) {
					System.out.println("Deleting " + relative);
					Files.delete(source);
					return true;
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			return false;
		}

		/**
		 * Check whether a file with same name but jpg extension instead of jpeg exists to prevent duplicates.
		 */
		private void checkJPG() throws IOException {
			String name = target.getFileName().toString();
			String replaced = name.replace(".jpeg", ".jpg");
			Path potentialDuplicate = target.getParent().resolve(replaced);
			if (potentialDuplicate.toFile().exists()) {
				System.out.println("Deleting " + targetPath.relativize(potentialDuplicate));
				Files.delete(potentialDuplicate);
			}
		}
	}

	/**
	 * A class representing a move operation of a file.
	 */
	private static class MoveOperation extends Operation {

		/**
		 * Creates a new operation to move a file from one location to another.
		 *
		 * @param source The source file.
		 * @param target The target file.
		 */
		public MoveOperation(@NotNull Path source, @NotNull Path target) {
			super(source, target);
		}

		/**
		 * Creates a new operation to move a file from one location to another.
		 * Also allows for explicit specification of the relative path.
		 *
		 * @param source   The source file.
		 * @param target   The target file.
		 * @param relative The relative path of the file.
		 */
		protected MoveOperation(@NotNull Path source, @NotNull Path target, @NotNull Path relative) {
			super(source, target, relative);
		}

		@Override
		public void run() {
			try {
				if (!Files.exists(target.getParent())) {
					Files.createDirectories(target.getParent());
				}
				System.out.println("Copying " + relative);
				int errorCount = 0;
				do {
					Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
					errorCount++;
				} while (!target.toFile().exists() && errorCount < 5);
				if (errorCount >= 5) throw new IOException("Could not copy " + relative);
				else Files.deleteIfExists(source);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			} finally {
				TASK_COMPLETED.increment();
			}
		}
	}

	/**
	 * A class representing a transcode operation.
	 */
	private static class VideoOperation extends Operation {

		/**
		 * The temporary directory of the system.
		 */
		private static final Path TEMP = new File(System.getProperty("java.io.tmpdir")).toPath();
		/**
		 * The name of the Handbrake preset to use.
		 */
		private static String presetName;
		/**
		 * The location of the temporary file before the file gets copied to the final location.
		 * Gets used as this tool was meant to be used to copy files over the network,
		 * and transcoding over the network is slow.
		 */
		private final Path temp;

		/**
		 * Creates a new transcoding operation.
		 *
		 * @param source The source file.
		 * @param target The target file.
		 */
		public VideoOperation(@NotNull Path source, @NotNull Path target) {
			super(source, target);
			temp = TEMP.resolve(source.getFileName());
		}

		@Override
		public void run() {
			try {
				System.out.println("Encoding " + targetPath.relativize(target));
				ProcessBuilder builder = new ProcessBuilder(handBrake, "--preset-import-gui", "-Z", presetName, "-i", source.toString(), "-o", temp.toString());
				builder.redirectErrorStream(true);
				Process encode = builder.start();
				new StreamNuller(encode.getInputStream()).start();
				boolean successful;
				do
				{    // This loop is required as when the interrupt to recheck whether the input is done comes at the wrong time, the final encodings will be aborted.
					successful = await(encode);
				} while (!successful);
				COPIER.execute(new MoveOperation(this.temp, this.target, this.relative));
				Files.delete(this.source);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			} finally {
				TASK_COMPLETED.increment();
			}
		}

		@Override
		public boolean deleteSourceIfExists() {
			if (Files.exists(target)) {
				try {
					int[] dimensions = COPIER.submit(new DimensionCalculator()).get();
					if (dimensions[1] == 1080 && dimensions[0] != 1920) {
						System.out.println("Renewing " + relative);
						Files.delete(target);
					} else {
						System.out.println("Deleting " + relative);
						Files.delete(source);
						return true;
					}
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				} catch (InterruptedException ignored) {
				} catch (ExecutionException e) {
					throw new RuntimeException(e);
				}
			}
			TASK_COUNT.increment();
			return false;
		}

		/**
		 * Wait for the encoding to finish. Returns false if the thread was interrupted.
		 *
		 * @param encode The process to wait for.
		 * @return Whether the process could finish without an interrupt.
		 * @throws IOException Gets thrown in case the file could not be encoded.
		 */
		private boolean await(@NotNull Process encode) throws IOException {
			try {
				if (encode.waitFor() == 0) return true;
				else throw new IOException("Encoding failed for " + relative.toString());
			} catch (InterruptedException e) {
				return false;
			}
		}

		/**
		 * The callable used to get the size of the video.
		 */
		private class DimensionCalculator implements Callable<int[]> {

			@Override
			public int[] call() throws IOException {
				try (IsoFile file = new IsoFile(target.toFile())) {
					VisualSampleEntry entry = file.getBoxes(VisualSampleEntry.class, true).getFirst();
					return new int[]{entry.getWidth(), entry.getHeight()};
				}
			}
		}
	}
}