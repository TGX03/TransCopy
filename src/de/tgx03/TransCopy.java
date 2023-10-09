package de.tgx03;

import org.jetbrains.annotations.NotNull;
import org.mp4parser.IsoFile;
import org.mp4parser.boxes.sampleentry.VisualSampleEntry;

import java.io.File;
import java.io.IOException;
import java.net.URLConnection;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Phaser;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
	private static final ThreadPoolExecutor COPIER = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
	/**
	 * A queue for video encodings.
	 * Used as parallel encoding usually doesn't make much sense,
	 * and when using NVENC for example not even possible.
	 */
	private static final ThreadPoolExecutor ENCODER = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
	/**
	 * The thread pool used for the threads which traverse the directory.
	 */
	private static final ThreadPoolExecutor TRAVERSER = new ThreadPoolExecutor(2, 2 * Runtime.getRuntime().availableProcessors(), 5, TimeUnit.SECONDS, new LinkedBlockingQueue<>());

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

	static {
		TRAVERSER.setThreadFactory(VirtualThreadFactory.VIRTUAL_FACTORY);
		COPIER.setThreadFactory(VirtualThreadFactory.VIRTUAL_FACTORY);
		ENCODER.setThreadFactory(VirtualThreadFactory.VIRTUAL_FACTORY);
	}

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
		Phaser rootPhaser = new Phaser(1);

		traverseDirectory(source, rootPhaser);
		rootPhaser.arriveAndAwaitAdvance();
		TRAVERSER.shutdown();
		ENCODER.shutdown();
		ENCODER.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
		COPIER.shutdown();
	}

	/**
	 * Goes through a directory, recursively creating new jobs for subdirectories and files.
	 *
	 * @param directory    The current directory to scan.
	 * @param parentPhaser The phaser to register and afterward deregister to/from.
	 */
	private static void traverseDirectory(@NotNull File directory, Phaser parentPhaser) {
		parentPhaser.register();
		assert directory.isDirectory();
		Phaser childPhaser = new Phaser(parentPhaser);
		for (File file : directory.listFiles()) {
			if (file.isDirectory()) TRAVERSER.execute(() -> traverseDirectory(file, childPhaser));
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
				if (!op.deleteSourceIfExists()) COPIER.execute(op);
			}
			case "video" -> {
				VideoOperation op = new VideoOperation(source, target);
				if (!op.deleteSourceIfExists()) ENCODER.execute(op);
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
				checkJPG();
				if (Files.exists(target)) {
					System.out.println("Deleting " + relative);
					Files.delete(source);
					return true;
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
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

		@Override
		public boolean deleteSourceIfExists() {
			if (Files.exists(target)) {
				try {
					int[] dimensions = getTargetDimensions();
					if (dimensions[1] == 1080 && dimensions[0] != 1920) {
						System.out.println("Renewing " + relative);
						Files.delete(target);
						return false;
					} else {
						System.out.println("Deleting " + relative);
						Files.delete(source);
						return true;
					}
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			} else return false;
		}

		/**
		 * Gets the dimensions of the already existing target video file.
		 * First int is width, second is height.
		 *
		 * @return The dimensions of the video.
		 * @throws IOException If the file couldn't be read.
		 */
		private int[] getTargetDimensions() throws IOException {
			try (IsoFile file = new IsoFile(target.toFile())) {
				VisualSampleEntry entry = file.getBoxes(VisualSampleEntry.class, true).get(0);
				return new int[]{entry.getWidth(), entry.getHeight()};
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
					COPIER.execute(() -> {
						try {
							if (!Files.exists(target.getParent())) {
								Files.createDirectories(target.getParent());
							}
							System.out.println("Copying " + relative);
							Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
						} catch (IOException e) {
							throw new RuntimeException(e);
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