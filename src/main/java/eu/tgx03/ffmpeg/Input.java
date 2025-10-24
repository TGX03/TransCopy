package eu.tgx03.ffmpeg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Represents an input file to FFMpeg with additional arguments applied to it like ss.
 */
public class Input implements ToArray<String> {

    /**
     * All the arguments to apply to this input.
     */
    private final List<String> arguments = new ArrayList<>();
    /**
     * The input.
     */
    private final String input;

    /**
     * Constructs an Input instance for the specified input.
     *
     * @param input The path or identifier of the input to be used with FFMpeg.
     */
    public Input(String input) {
        this.input = input;
    }

    /**
     * Constructs an Input instance for the specified input and additional arguments.
     *
     * @param input     The path or identifier of the input to be used with FFMpeg.
     * @param arguments Additional arguments to apply to this input.
     */
    public Input(String input, String... arguments) {
        this.input = input;
        Collections.addAll(this.arguments, arguments);
    }

    /**
     * Wraps the given string in double quotes.
     *
     * @param string The string to be wrapped in double quotes.
     * @return A new string with the input wrapped in double quotes.
     */
    private static String enquote(String string) {
        return "\"" + string + "\"";
    }

    /**
     * Adds an argument to apply to this input.
     *
     * @param argument The argument to be added.
     */
    public void addArgument(String argument) {
        this.arguments.addAll(Arrays.asList(argument.split(" ")));
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        for (String argument : arguments) {
            builder.append(argument).append(" ");
        }
        builder.append("-i ").append(enquote(input));
        return builder.toString();
    }

    /**
     * Returns an array which contains all arguments correctly split for usage by ProcessBuilder,
     * meaning every positional argument has its own slot in the array.
     * This also means no enquoting is necessary for spaces.
     *
     * @return An array of all the arguments in this object.
     */
    @Override
    public String[] toArray() {
        String[] result = new String[this.arguments.size() + 2];
        this.arguments.toArray(result);
        result[result.length - 2] = "-i";
        result[result.length - 1] = this.input;
        return result;
    }
}
