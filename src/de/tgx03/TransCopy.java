package de.tgx03;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.net.URLConnection;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.*;

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
	private static final BlockingQueue<Runnable> COPY_QUEUE = new LinkedBlockingQueue<>();
	/**
	 * A queue for video encodings.
	 * Used as parallel encoding usually doesn't make much sense,
	 * and when using NVENC for example not even possible.
	 */
	private static final BlockingQueue<Runnable> VIDEO_QUEUE = new LinkedBlockingQueue<>();
	/**
	 * The thread pool used for the threads which traverse the directory.
	 */
	private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(2, 2 * Runtime.getRuntime().availableProcessors(), 5, TimeUnit.SECONDS, new LinkedBlockingQueue<>());

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
	 * Whether the input directory has been completely scanned.
	 */
	private static volatile boolean inputDone = false;
	/**
	 * Whether all videos have been transcoded.
	 */
	private static volatile boolean videoDone = false;

	static {
		EXECUTOR.setThreadFactory(VirtualDaemonFactory.getINSTANCE());
	}

	/**
	 * Copies images and videos from one location to another recursively,
	 * while transcoding videos in the process.
	 *
	 * @param args First argument is the source, second is the target, third is the Handbrake executable and fourth is the name of the Handbrake preset.
	 */
	public static void main(@NotNull String @NotNull [] args) {
		File source = new File(args[0]);
		sourcePath = source.toPath();
		targetPath = new File(args[1]).toPath();
		handBrake = args[2];
		VideoOperation.presetName = args[3];
		Phaser rootPhaser = new Phaser(1);

		Thread copier = Thread.ofVirtual().name("Copier").start(() -> {
			while (!inputDone || !videoDone) {
				try {
					COPY_QUEUE.take().run();
				} catch (InterruptedException e) {
					// Probably means done was set
				}
			}
			Runnable operation;
			while ((operation = COPY_QUEUE.poll()) != null) operation.run();
		});

		Thread encoder = Thread.ofVirtual().name("Encoder").start(() -> {
			while (!inputDone) {
				try {
					VIDEO_QUEUE.take().run();
				} catch (InterruptedException e) {
					// Probably means done was set
				}
			}
			Runnable operation;
			while ((operation = VIDEO_QUEUE.poll()) != null) operation.run();
			videoDone = true;
			copier.interrupt();
		});

		traverseDirectory(source, rootPhaser);
		rootPhaser.arriveAndAwaitAdvance();
		inputDone = true;
		copier.interrupt();
		encoder.interrupt();
	}

	/**
	 * Goes through a directory, recursively creating new jobs for subdirectories and files.
	 *
	 * @param directory The current directory to scan.
	 * @param parentPhaser The phaser to register and afterward deregister to/from.
	 */
	private static void traverseDirectory(@NotNull File directory, Phaser parentPhaser) {
		assert directory.isDirectory();
		parentPhaser.register();
		Phaser childPhaser = new Phaser(parentPhaser);
		for (File file : directory.listFiles()) {
			if (file.isDirectory()) EXECUTOR.execute(() -> traverseDirectory(file, childPhaser));
			else handleFile(file);
		}
		parentPhaser.arriveAndDeregister();
	}

	/**
	 * Handles a specific file and either creates a copy or video job for it.
	 *
	 * @param file The file to copy.
	 */
	private static void handleFile(@NotNull File file) {
		assert !file.isDirectory();

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
				if (!op.deleteSourceIfExists()) {
					try {
						COPY_QUEUE.put(op);
					} catch (InterruptedException ignored) {
					}
				}
			}
			case "video" -> {
				VideoOperation op = new VideoOperation(source, target);
				if (!op.deleteSourceIfExists()) {
					try {
						VIDEO_QUEUE.put(op);
					} catch (InterruptedException ignored) {
					}
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
		 * Checks if the target already exists and then deletes the source.
		 *
		 * @return Whether the target already exists.
		 */
		public boolean deleteSourceIfExists() {
			try {
				if (Files.exists(target)) {
					System.out.println("Deleting " + relative);
					Files.delete(source);
					return true;
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
			return false;
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

		@Override
		public void run() {
			try {
				if (!Files.exists(target.getParent())) {
					Files.createDirectories(target.getParent());
				}
				System.out.println("Copying " + relative);
				Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
			} catch (FileAlreadyExistsException ignored) {
			} catch (IOException e) {
				e.printStackTrace();
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
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		/**
		 * Wait for the encoding to finish. Returns false if the thread was interrupted.
		 *
		 * @param encode The process to wait for.
		 * @return Whether the process could finish without an interrupt.
		 * @throws IOException If the source file could not be deleted.
		 */
		private boolean await(@NotNull Process encode) throws IOException {
			try {
				if (encode.waitFor() == 0) {
					COPY_QUEUE.put(() -> {
						try {
							if (!Files.exists(target.getParent())) {
								Files.createDirectories(target.getParent());
							}
							System.out.println("Copying " + relative);
							Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
						} catch (IOException e) {
							e.printStackTrace();
						}
					});
					Files.deleteIfExists(source);
				} else System.err.println(sourcePath.relativize(source) + " failed to encode");
				return true;
			} catch (InterruptedException e) {
				return false;
			}
		}
	}
}