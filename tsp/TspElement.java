package tsp;

/**
 * A parsed element from the TSP parser.
 * Either a {@link TspToken} (valid {@code [[ID||TEXT]]}) or {@link TspText} (plain text).
 */
public sealed interface TspElement permits TspToken, TspText {
}
