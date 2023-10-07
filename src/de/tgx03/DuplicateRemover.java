package de.tgx03;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.HashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Phaser;

/**
 * A class which is used to remove files that exist with 2 different file endings, yet are fully identical, in the implemented case here .jpg and .jpeg.
 */
public class DuplicateRemover {

	/**
	 * Launch the program
	 *
	 * @param args First parameter is the directory to clean, rest is ignored.
	 */
	public static void main(String[] args) {
		File path = new File(args[0]);
		Phaser rootPhaser = new Phaser(1);
		traverseDirectory(path, rootPhaser);
		rootPhaser.arriveAndAwaitAdvance();
	}

	/**
	 * Traverse a directory, checking all its files for duplicates.
	 * Subdirectories get traversed in separate threads from the common ForkJoinPool.
	 *
	 * @param path The path to traverse.
	 */
	private static void traverseDirectory(@NotNull File path, Phaser parentPhaser) {
		assert path.isDirectory();
		parentPhaser.register();
		HashMap<String, Pair> pairs = new HashMap<>();
		Phaser subPhaser = new Phaser(parentPhaser);
		for (File current : path.listFiles()) {
			if (current.isDirectory()) ForkJoinPool.commonPool().execute(() -> traverseDirectory(current, subPhaser));
			else handleFile(current, pairs);
		}
		parentPhaser.arriveAndDeregister();
	}

	/**
	 * Checks if another file with different ending but otherwise same name exists and if so, deletes the hpg version.
	 *
	 * @param file  The file to check for duplicate.
	 * @param pairs The HashMap used for quick look-up.
	 */
	private static void handleFile(File file, HashMap<String, Pair> pairs) {
		String[] split = file.getName().split("\\.");
		String extension = split[split.length - 1];
		{
			String[] withoutExtension = new String[split.length - 1];
			System.arraycopy(split, 0, withoutExtension, 0, withoutExtension.length);
			split = withoutExtension;
		}
		String withoutExtension = String.join(".", split);
		Pair pair;
		if (pairs.containsKey(withoutExtension)) pair = pairs.get(withoutExtension);
		else {
			if (pairs.containsKey(withoutExtension)) pair = pairs.get(withoutExtension);
			else {
				pair = new Pair();
				pairs.put(withoutExtension, pair);
			}
		}
		switch (extension) {
			case "jpg" -> pair.setJpg(file);
			case "jpeg" -> pair.setJpeg(file);
		}
	}

	/**
	 * A class representing a file that exists with 2 different endings.
	 */
	private static class Pair {

		/**
		 * The jpg variant of the file.
		 */
		private File jpg;
		/**
		 * The jpeg variant of the file.
		 */
		private File jpeg;

		/**
		 * Set the jpg parameter.
		 * If the jpeg variant exists, the jpeg variant directly gets deleted.
		 *
		 * @param value The file with jpg ending.
		 */
		public void setJpg(File value) {
			this.jpg = value;
			checkDuplicate();
		}

		/**
		 * Sets the jpeg parameter.
		 * If the jpg variant exists, the jpeg variant directly gets deleted.
		 *
		 * @param value The file with jpeg ending.
		 */
		public void setJpeg(File value) {
			this.jpeg = value;
			checkDuplicate();
		}

		/**
		 * Checks whether both files exist and if so, deletes the jpeg variant.
		 */
		private void checkDuplicate() {
			if (jpg != null && jpeg != null) {
				if (jpeg.delete()) System.out.println("Deleted " + jpeg.toString());
				else System.err.println("Couldn't delete " + jpeg.toString());
			}
		}
	}
}