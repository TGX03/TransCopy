package eu.tgx03.transcode;

import eu.tgx03.ffmpeg.Command;
import eu.tgx03.ffmpeg.Input;
import eu.tgx03.ffmpeg.Output;
import me.tongfei.progressbar.ProgressBar;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final ExecutorService COPIER = Executors.newSingleThreadExecutor();
    /**
     * The queue for encoding tasks, as parallelization makes little sense for that.
     */
    private static final ExecutorService ENCODER = Executors.newSingleThreadExecutor();
    /**
     * The thread pool used for the threads which traverse the directory.
     */
    private static final ExecutorService VIRTUAL_EXECUTOR = Executors.newCachedThreadPool(r -> Thread.ofPlatform().unstarted(r));
    /**
     * Pool for scanning files.
     * Doesn't get executed in the virtual executor as that overwhelms any computer.
     */
    private static final ExecutorService CROPDETECT_SCANNERS =  new ThreadPoolExecutor(2, Runtime.getRuntime().availableProcessors(), 20, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> Thread.ofVirtual().unstarted(r));
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
     * Whether the tool should automatically crop the videos.
     */
    private static boolean cropdetect;
    /**
     * Contains prefix arguments for FFMpeg
     * like hwaccel or y
     */
    private static String[] prefixOptions;
    /**
     * Contains input options for FFMpeg like ss.
     * Keep in mind they are the same for all files, so ss is probably a bad example.
     */
    private static String[] inputOptions;
    /**
     * Contains all the output options for a file
     * like codecs, bitrates, and filters.
     */
    private static String[] outputOptions;

    /**
     * Copies images and videos from one location to another recursively,
     * while transcoding videos in the process.
     *
     * @param args First argument is the source, second is the target, third may be -cropdetect to auto-crop videos, then general options, then -input to specify input options, then -output to specify output options
     */
    public static void main(@NotNull String @NotNull [] args) throws InterruptedException {
        parseCommandLine(args);
        Phaser rootPhaser = new Phaser(2);
        traverseDirectory(sourcePath.toFile(), rootPhaser);
        Thread progressBar = new Thread(TransCopy::drawProgressBar, "ProgressBar");
        progressBar.setDaemon(true);
        progressBar.start();
        rootPhaser.arriveAndAwaitAdvance();
        VIRTUAL_EXECUTOR.shutdown();
        VIRTUAL_EXECUTOR.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
        CROPDETECT_SCANNERS.shutdown();
        CROPDETECT_SCANNERS.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
        ENCODER.shutdown();
        ENCODER.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
        COPIER.shutdown();
        COPIER.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
    }

    /**
     * Fills prefix, input, and output with the arguments received from the command line.
     *
     * @param args All arguments except the first three.
     */
    private static void parseCommandLine(@NotNull String[] args) {
        sourcePath = Path.of(args[0]);
        targetPath = Path.of(args[1]);

        int position = 2;
        if ("-cropdetect".equals(args[position])) {
            cropdetect = true;
            position++;
        }

        String input = String.join(" ", Arrays.copyOfRange(args, position, args.length));
        String[] wordSplit = input.split("-input");
        String prefix = wordSplit[0];

        wordSplit = wordSplit[1].split("-output");
        String inputOptions = wordSplit[0];
        String outputOptions = wordSplit[1];

        prefixOptions = parseArguments(prefix.split(" "));
        TransCopy.inputOptions = parseArguments(inputOptions.split(" "));
        TransCopy.outputOptions = parseArguments(outputOptions.split(" "));
    }

    /**
     * Is used to parse the arguments of the three individual sections.
     * Only works for arguments with no or one parameter, as I'm currently not aware of arguments that have more than one parameter.
     *
     * @param arguments The arguments to parse.
     * @return An array of all individual arguments.
     */
    private static String[] parseArguments(String[] arguments) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < arguments.length; i++) {
            if (i + 2 < arguments.length && !arguments[i + 1].startsWith("-")) {
                result.add(arguments[i].trim() + " " + arguments[i + 1].trim());
                i++;
            } else if (!arguments[i].isBlank()) result.add(arguments[i]);
        }
        return result.toArray(new String[0]);
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
                    VIRTUAL_EXECUTOR.execute(() -> traverseDirectory(file, childPhaser));
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

        switch (mimeType) {
            case "image" -> COPIER.execute(new ImageOperation(relativePath));
            case "video" -> CROPDETECT_SCANNERS.execute(new VideoOperation(relativePath));
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
         * The Path relative to the parent directories.
         */
        protected final Path relative;

        /**
         * Create a new Operation for the given relative path.
         *
         * @param relative The relative path of this operation.
         */
        public Operation(@NotNull Path relative) {
            this.relative = relative;
        }
    }

    /**
     * Just copies a file directly from the source to the target.
     * Does check for duplicates of jpg/jpeg files however.
     */
    private static class ImageOperation extends Operation {

        /**
         * Creates an image operation that copies a file from the source directory to the target directory.
         * Ensures duplicate files with similar names (e.g., .jpg and .jpeg) are handled appropriately.
         *
         * @param relative The relative path of the file to be copied, beginning from the root of the source directory.
         */
        public ImageOperation(@NotNull Path relative) {
            super(relative);
        }

        @Override
        public void run() {
            Path target = targetPath.resolve(relative);
            Path source = sourcePath.resolve(relative);
            try {
                if (!Files.exists(target.getParent())) {
                    Files.createDirectories(target.getParent());
                } else checkJPG(target);
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                TASK_COMPLETED.increment();
            }
        }

        /**
         * Check whether a file with the same name but jpg extension instead of jpeg exists to prevent duplicates.
         */
        private void checkJPG(Path target) throws IOException {
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
     * Transcodes a video file and then copies it to the target directory.
     * Uses the system temp folder as an intermediate destination, which may lead to issues on Linux.
     * Also can automatically crop videos if desired.
     */
    private static class VideoOperation extends Operation {

        /**
         * The system temp directory as a Path.
         */
        private static final Path TEMP = new File(System.getProperty("java.io.tmpdir")).toPath();

        /**
         * The source video file.
         */
        private final Path inputPath;
        /**
         * Where to store the file during the transcode.
         * Isn't the target directory as such actions over the network slow down the process.
         */
        private final Path temp;
        /**
         * The target after transcode.
         */
        private final Path targetPath;

        /**
         * Constructs a new {@code VideoOperation} for the specified relative path. This operation
         * is used to process video files, leveraging parent class functionality for file handling
         * and extension management.
         *
         * @param relative The relative path of the video file to be processed. Must not be null.
         */
        public VideoOperation(@NotNull Path relative) {
            super(relative);
            this.inputPath = sourcePath.resolve(relative);
            this.temp = setFileExtension(TEMP.resolve(relative.getFileName()), ".mp4");
            this.targetPath = setFileExtension(TransCopy.targetPath.resolve(relative), ".mp4");
        }

        @Override
        public void run() {
            try {
                if (cropdetect) {
                    String crop = cropdetect();
                    encode(crop);
                } else encode(null);

                COPIER.execute(() -> {
                    try {
                        if (!Files.exists(targetPath.getParent())) {
                            Files.createDirectories(targetPath.getParent());
                        }
                        Files.move(temp, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
                Files.delete(inputPath);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            } finally {
                TASK_COMPLETED.increment();
            }
        }

        /**
         * Runs an FFmpeg command to detect the cropping values for a video file.
         * The method constructs and executes a command with crop detection filter,
         * parses the output to find the cropping parameters, and returns them as a string.
         *
         * @return A string containing the crop parameters in the format `crop=width:height:x:y`,
         * or null if no crop information is detected in the FFmpeg output.
         * @throws IOException          If an I/O error occurs during command execution.
         * @throws ExecutionException   If the computation throws an exception.
         * @throws InterruptedException If the thread executing the command is interrupted.
         */
        @Nullable
        private String cropdetect() throws IOException, ExecutionException, InterruptedException {
            Command command = new Command();
            command.addInput(new Input(inputPath.toAbsolutePath().toString()));
            for (String prefix : prefixOptions) command.addPrefix(prefix);

            Output output = new Output("-");
            output.addArgument("-map 0:v:0");
            output.addArgument("-c:v vnull");
            output.addArgument("-filter:v cropdetect");
            output.addArgument("-f null");
            command.addOutput(output);
            List<String> ffmpegOut = command.call();

            Pattern pattern = Pattern.compile("crop=\\d+:\\d+:\\d+:\\d+");
            String result = null;
            for (String line : ffmpegOut.reversed()) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    result = matcher.group();
                    break;
                }
            }
            return result;
        }

        /**
         * Encodes the video based on the specified crop detection filter and applies defined input/output options.
         * Constructs an FFmpeg command to process the video with the given configurations.
         *
         * @param cropdetect The crop detection filter to apply to the video. Nullable, meaning no crop filter will be applied if set to null.
         * @throws ExecutionException   If an exception occurs during the execution of the command.
         * @throws InterruptedException If the current thread is interrupted while waiting for the command to complete.
         */
        private void encode(@Nullable String cropdetect) throws ExecutionException, InterruptedException {
            Command command = new Command();
            command.addPrefix("-y");
            for (String prefix : prefixOptions) command.addPrefix(prefix);

            Input input = new Input(inputPath.toAbsolutePath().toString());
            for (String option : inputOptions) input.addArgument(option);
            command.addInput(input);

            Output output = new Output(temp.toAbsolutePath().toString());
            for (String option : outputOptions) output.addArgument(option);
            if (cropdetect != null) output.addArgument("-filter:v " + cropdetect);
            command.addOutput(output);

            Future<?> result = ENCODER.submit(command);
            result.get();
        }

        /**
         * Replaces the file extension of a given path with a new extension.
         * @param path The old path to get a new extension.
         * @param extension The new extension to set.
         * @return The original path with a new extension.
         */
        private static Path setFileExtension(@NotNull Path path, @NotNull String extension) {
            String filename = path.getFileName().toString();
            filename = filename.replaceAll("\\.[^.]*$", extension);
            return path.getParent().resolve(filename);
        }
    }
}