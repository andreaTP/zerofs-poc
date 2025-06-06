package io.roastedroot.zerofs;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * An object defining a specific type of path. Knows how to parse strings to a path and how to
 * render a path as a string as well as what the path separator is and what other separators are
 * recognized when parsing paths.
 *
 * @author Colin Decker
 */
public abstract class PathType {

    /**
     * Returns a Unix-style path type. "/" is both the root and the only separator. Any path starting
     * with "/" is considered absolute. The nul character ('\0') is disallowed in paths.
     */
    public static PathType unix() {
        return UnixPathType.INSTANCE;
    }

    /**
     * Returns a Windows-style path type. The canonical separator character is "\". "/" is also
     * treated as a separator when parsing paths.
     *
     * <p>As much as possible, this implementation follows the information provided in <a
     * href="http://msdn.microsoft.com/en-us/library/windows/desktop/aa365247(v=vs.85).aspx">this
     * article</a>. Paths with drive-letter roots (e.g. "C:\") and paths with UNC roots (e.g.
     * "\\host\share\") are supported.
     *
     * <p>Two Windows path features are not currently supported as they are too Windows-specific:
     *
     * <ul>
     *   <li>Relative paths containing a drive-letter root, for example "C:" or "C:foo\bar". Such
     *       paths have a root component and optionally have names, but are <i>relative</i> paths,
     *       relative to the working directory of the drive identified by the root.
     *   <li>Absolute paths with no root, for example "\foo\bar". Such paths are absolute paths on the
     *       current drive.
     * </ul>
     */
    public static PathType windows() {
        return WindowsPathType.INSTANCE;
    }

    private final boolean allowsMultipleRoots;
    private final String separator;
    private final String otherSeparators;
    private final String splitter;

    protected PathType(boolean allowsMultipleRoots, char separator, char... otherSeparators) {
        this.separator = String.valueOf(separator);
        this.allowsMultipleRoots = allowsMultipleRoots;
        this.otherSeparators = String.valueOf(otherSeparators);
        this.splitter = createSplitter(separator, otherSeparators);
    }

    private static final char[] regexReservedChars = "^$.?+*\\[]{}()".toCharArray();

    static {
        Arrays.sort(regexReservedChars);
    }

    private static String createSplitter(char separator, char... otherSeparators) {
        if (otherSeparators.length == 0) {
            return "" + separator;
        }

        // TODO(cgdecker): When CharMatcher is out of @Beta, us Splitter.on(CharMatcher)
        StringBuilder patternBuilder = new StringBuilder();
        patternBuilder.append("[");
        appendToRegex(separator, patternBuilder);
        for (char other : otherSeparators) {
            appendToRegex(other, patternBuilder);
        }
        patternBuilder.append("]");
        return patternBuilder.toString();
    }

    private static boolean isRegexReserved(char c) {
        return Arrays.binarySearch(regexReservedChars, c) >= 0;
    }

    private static void appendToRegex(char separator, StringBuilder patternBuilder) {
        if (isRegexReserved(separator)) {
            patternBuilder.append("\\");
        }
        patternBuilder.append(separator);
    }

    /** Returns whether or not this type of path allows multiple root directories. */
    public final boolean allowsMultipleRoots() {
        return allowsMultipleRoots;
    }

    /** Returns the path splitter for this path type. */
    public final String join(String[] strs) {
        return Arrays.asList(strs).stream().collect(Collectors.joining(separator));
    }

    /** Returns the path splitter for this path type. */
    public final String[] split(String str) {
        String[] baseSplit = str.split(splitter);
        ArrayList<String> result = new ArrayList<>();
        for (int i = 0; i < baseSplit.length; i++) {
            if (baseSplit[i] == null || baseSplit[i].isEmpty()) {
                // skip
            } else {
                result.add(baseSplit[i]);
            }
        }
        return result.toArray(new String[0]);
    }

    /**
     * Returns the canonical separator for this path type. The returned string always has a length of
     * one.
     */
    public final String getSeparator() {
        return separator;
    }

    /**
     * Returns the other separators that are recognized when parsing a path. If no other separators
     * are recognized, the empty string is returned.
     */
    public final String getOtherSeparators() {
        return otherSeparators;
    }

    /** Returns an empty path. */
    protected final ParseResult emptyPath() {
        return new ParseResult(null, new String[] {""});
    }

    /**
     * Parses the given strings as a path.
     *
     * @throws InvalidPathException if the path isn't valid for this path type
     */
    public abstract ParseResult parsePath(String path);

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }

    /** Returns the string form of the given path. */
    public abstract String toString(String root, String[] names);

    /**
     * Returns the string form of the given path for use in the path part of a URI. The root element
     * is not nullable as the path must be absolute. The elements of the returned path <i>do not</i>
     * need to be escaped. The {@code directory} boolean indicates whether the file the URI is for is
     * known to be a directory.
     */
    protected abstract String toUriPath(String root, String[] names, boolean directory);

    /**
     * Parses a path from the given URI path.
     *
     * @throws InvalidPathException if the given path isn't valid for this path type
     */
    protected abstract ParseResult parseUriPath(String uriPath);

    public final URI toUri(URI fileSystemUri, String root, List<String> names, boolean directory) {
        return toUri(fileSystemUri, root, names.toArray(String[]::new), directory);
    }

    /**
     * Creates a URI for the path with the given root and names in the file system with the given URI.
     */
    public final URI toUri(URI fileSystemUri, String root, String[] names, boolean directory) {
        String path = toUriPath(root, names, directory);
        try {
            // it should not suck this much to create a new URI that's the same except with a path
            // set =(
            // need to do it this way for automatic path escaping
            return new URI(
                    fileSystemUri.getScheme(),
                    fileSystemUri.getUserInfo(),
                    fileSystemUri.getHost(),
                    fileSystemUri.getPort(),
                    path,
                    null,
                    null);
        } catch (URISyntaxException e) {
            throw new AssertionError(e);
        }
    }

    /** Parses a path from the given URI. */
    public final ParseResult fromUri(URI uri) {
        return parseUriPath(uri.getPath());
    }

    /** Simple result of parsing a path. */
    public static final class ParseResult {

        private final String root;
        private final String[] names;

        public ParseResult(String root, String[] names) {
            this.root = root;
            this.names = Objects.requireNonNull(names);
        }

        /** Returns whether or not this result is an absolute path. */
        public boolean isAbsolute() {
            return root != null;
        }

        /** Returns whether or not this result represents a root path. */
        public boolean isRoot() {
            return root != null && (names == null || names.length == 0);
        }

        /** Returns the parsed root element, or null if there was no root. */
        public String root() {
            return root;
        }

        /** Returns the parsed name elements. */
        public String[] names() {
            return names;
        }
    }
}
