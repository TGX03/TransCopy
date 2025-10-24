package eu.tgx03.ffmpeg;

/**
 * Indicates that an object can return an Array of type T
 *
 * @param <T> The type this object returns.
 */
public interface ToArray<T> {

    /**
     * Returns an array of type T that's somehow representative of an object.
     *
     * @return The array in question.
     */
    T[] toArray();

}
