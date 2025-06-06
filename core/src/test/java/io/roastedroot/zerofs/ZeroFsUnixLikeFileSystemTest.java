package io.roastedroot.zerofs;

import static io.roastedroot.zerofs.TestUtils.bytesAsList;
import static io.roastedroot.zerofs.TestUtils.concat;
import static io.roastedroot.zerofs.TestUtils.permutations;
import static io.roastedroot.zerofs.TestUtils.preFilledBytes;
import static io.roastedroot.zerofs.TestUtils.readAll;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.DSYNC;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.SPARSE;
import static java.nio.file.StandardOpenOption.SYNC;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.ClosedDirectoryStreamException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.NotLinkException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;

/**
 * Tests an in-memory file system through the public APIs in {@link Files}, etc. This also acts as
 * the tests for {@code FileSystemView}, as each public API method is (mostly) implemented by a
 * method in {@code FileSystemView}.
 *
 * <p>These tests uses a Unix-like file system, but most of what they test applies to any file
 * system configuration.
 *
 * @author Colin Decker
 */
public class ZeroFsUnixLikeFileSystemTest extends AbstractZeroFsIntegrationTest {

    private static final Configuration UNIX_CONFIGURATION =
            Configuration.unix().toBuilder()
                    .setAttributeViews("basic", "owner", "posix", "unix")
                    .setMaxSize(1024 * 1024 * 1024) // 1 GB
                    .setMaxCacheSize(256 * 1024 * 1024) // 256 MB
                    .build();

    @Override
    protected FileSystem createFileSystem() {
        return ZeroFs.newFileSystem("unix", UNIX_CONFIGURATION);
    }

    @Test
    public void testFileSystem() {
        assertEquals("/", fs.getSeparator());
        Iterator<Path> rootDirsIter = fs.getRootDirectories().iterator();
        assertEquals(path("/"), rootDirsIter.next());
        assertFalse(rootDirsIter.hasNext());

        assertTrue(fs.isOpen());
        assertFalse(fs.isReadOnly());
        assertEquals(Set.of("basic", "owner", "posix", "unix"), fs.supportedFileAttributeViews());
        assertInstanceOf(ZeroFsFileSystemProvider.class, fs.provider());
    }

    @Test
    public void testFileStore() throws IOException {
        FileStore fileStore = fs.getFileStores().iterator().next();
        assertEquals("zerofs", fileStore.name());
        assertEquals("zerofs", fileStore.type());
        assertFalse(fileStore.isReadOnly());

        long totalSpace = 1024 * 1024 * 1024; // 1 GB
        assertEquals(totalSpace, fileStore.getTotalSpace());
        assertEquals(totalSpace, fileStore.getUnallocatedSpace());
        assertEquals(totalSpace, fileStore.getUsableSpace());

        Files.write(fs.getPath("/foo"), new byte[10000]);

        assertEquals(totalSpace, fileStore.getTotalSpace());

        // We wrote 10000 bytes, but since the file system allocates fixed size blocks, more than
        // 10k
        // bytes may have been allocated. As such, the unallocated space after the write can be at
        // most
        // maxUnallocatedSpace.
        assertTrue(fileStore.getUnallocatedSpace() <= totalSpace - 10000);

        // Usable space is at most unallocated space. (In this case, it's currently exactly
        // unallocated
        // space, but that's not required.)
        assertTrue(fileStore.getUsableSpace() <= fileStore.getUnallocatedSpace());

        Files.delete(fs.getPath("/foo"));
        assertEquals(totalSpace, fileStore.getTotalSpace());
        assertEquals(totalSpace, fileStore.getUnallocatedSpace());
        assertEquals(totalSpace, fileStore.getUsableSpace());
    }

    @Test
    public void testPaths() {
        assertThatPath("/").isAbsolute().and().hasRootComponent("/").and().hasNoNameComponents();
        assertThatPath("foo").isRelative().and().hasNameComponents("foo");
        assertThatPath("foo/bar").isRelative().and().hasNameComponents("foo", "bar");
        assertThatPath("/foo/bar/baz")
                .isAbsolute()
                .and()
                .hasRootComponent("/")
                .and()
                .hasNameComponents("foo", "bar", "baz");
    }

    @Test
    public void testPaths_equalityIsCaseSensitive() {
        assertNotEquals(path("FOO"), path("foo"));
    }

    @Test
    public void testPaths_areSortedCaseSensitive() {
        Path p1 = path("a");
        Path p2 = path("B");
        Path p3 = path("c");
        Path p4 = path("D");

        SortedSet<Path> sortedSet = new TreeSet<>();
        sortedSet.add(p3);
        sortedSet.add(p4);
        sortedSet.add(p1);
        sortedSet.add(p2);

        assertArrayEquals(new Path[] {p2, p4, p1, p3}, sortedSet.toArray(new Path[0]));
        // original test:
        // assertEquals(ImmutableSortedSet.of(p3, p4, p1, p2)).containsExactly(p2, p4, p1,
        // p3).inOrder();

        // would be p1, p2, p3, p4 if sorting were case insensitive
    }

    @Test
    public void testPaths_resolve() {
        this.assertThatPath(path("/").resolve("foo/bar"))
                .isAbsolute()
                .and()
                .hasRootComponent("/")
                .and()
                .hasNameComponents("foo", "bar");
        this.assertThatPath(path("foo/bar").resolveSibling("baz"))
                .isRelative()
                .and()
                .hasNameComponents("foo", "baz");
        this.assertThatPath(path("foo/bar").resolve("/one/two"))
                .isAbsolute()
                .and()
                .hasRootComponent("/")
                .and()
                .hasNameComponents("one", "two");
    }

    @Test
    public void testPaths_normalize() {
        this.assertThatPath(path("foo/bar/..").normalize())
                .isRelative()
                .and()
                .hasNameComponents("foo");
        this.assertThatPath(path("foo/./bar/../baz/test/./../stuff").normalize())
                .isRelative()
                .and()
                .hasNameComponents("foo", "baz", "stuff");
        this.assertThatPath(path("../../foo/./bar").normalize())
                .isRelative()
                .and()
                .hasNameComponents("..", "..", "foo", "bar");
        this.assertThatPath(path("foo/../../bar").normalize())
                .isRelative()
                .and()
                .hasNameComponents("..", "bar");
        this.assertThatPath(path(".././..").normalize())
                .isRelative()
                .and()
                .hasNameComponents("..", "..");
    }

    @Test
    public void testPaths_relativize() {
        this.assertThatPath(path("/foo/bar").relativize(path("/foo/bar/baz")))
                .isRelative()
                .and()
                .hasNameComponents("baz");
        this.assertThatPath(path("/foo/bar/baz").relativize(path("/foo/bar")))
                .isRelative()
                .and()
                .hasNameComponents("..");
        this.assertThatPath(path("/foo/bar/baz").relativize(path("/foo/baz/bar")))
                .isRelative()
                .and()
                .hasNameComponents("..", "..", "baz", "bar");
        this.assertThatPath(path("foo/bar").relativize(path("foo")))
                .isRelative()
                .and()
                .hasNameComponents("..");
        this.assertThatPath(path("foo").relativize(path("foo/bar")))
                .isRelative()
                .and()
                .hasNameComponents("bar");

        try {
            Path unused = path("/foo/bar").relativize(path("bar"));
            fail();
        } catch (IllegalArgumentException expected) {
        }

        try {
            Path unused = path("bar").relativize(path("/foo/bar"));
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testPaths_startsWith_endsWith() {
        assertTrue(path("/foo/bar").startsWith("/"));
        assertTrue(path("/foo/bar").startsWith("/foo"));
        assertTrue(path("/foo/bar").startsWith("/foo/bar"));
        assertTrue(path("/foo/bar").endsWith("bar"));
        assertTrue(path("/foo/bar").endsWith("foo/bar"));
        assertTrue(path("/foo/bar").endsWith("/foo/bar"));
        assertFalse(path("/foo/bar").endsWith("/foo"));
        assertFalse(path("/foo/bar").startsWith("foo/bar"));
    }

    @Test
    public void testPaths_toAbsolutePath() {
        Path p1 =
                this.assertThatPath(path("/foo/bar").toAbsolutePath())
                        .isAbsolute()
                        .and()
                        .hasRootComponent("/")
                        .and()
                        .hasNameComponents("foo", "bar")
                        .path();
        assertEquals(path("/foo/bar"), p1);

        Path p2 =
                this.assertThatPath(path("foo/bar").toAbsolutePath())
                        .isAbsolute()
                        .and()
                        .hasRootComponent("/")
                        .and()
                        .hasNameComponents("work", "foo", "bar")
                        .path();
        assertEquals(path("/work/foo/bar"), p2);
    }

    @Test
    public void testPaths_toRealPath() throws IOException {
        Files.createDirectories(path("/foo/bar"));
        Files.createSymbolicLink(path("/link"), path("/"));

        assertEquals(path("/foo/bar"), path("/link/foo/bar").toRealPath());

        assertEquals(path("/work"), path("").toRealPath());
        assertEquals(path("/work"), path(".").toRealPath());
        assertEquals(path("/"), path("..").toRealPath());
        assertEquals(path("/"), path("../..").toRealPath());
        assertEquals(path("/"), path("./.././..").toRealPath());
        assertEquals(path("/"), path("./.././../.").toRealPath());
    }

    @Test
    public void testPaths_toUri() {
        assertEquals(URI.create("zerofs://unix/"), path("/").toUri());
        assertEquals(URI.create("zerofs://unix/foo"), path("/foo").toUri());
        assertEquals(URI.create("zerofs://unix/foo/bar"), path("/foo/bar").toUri());
        assertEquals(URI.create("zerofs://unix/work/foo"), path("foo").toUri());
        assertEquals(URI.create("zerofs://unix/work/foo/bar"), path("foo/bar").toUri());
        assertEquals(URI.create("zerofs://unix/work/"), path("").toUri());
        assertEquals(URI.create("zerofs://unix/work/./.././"), path("./../.").toUri());
    }

    @Test
    public void testPaths_getFromUri() {
        assertEquals(path("/"), Paths.get(URI.create("zerofs://unix/")));
        assertEquals(path("/foo"), Paths.get(URI.create("zerofs://unix/foo")));
        assertEquals(path("/foo bar"), Paths.get(URI.create("zerofs://unix/foo%20bar")));
        assertEquals(path("/foo/./bar"), Paths.get(URI.create("zerofs://unix/foo/./bar")));
        assertEquals(path("/foo/bar"), Paths.get(URI.create("zerofs://unix/foo/bar/")));
    }

    @Test
    public void testPathMatchers_regex() {
        assertThatPath("bar").matches("regex:.*");
        assertThatPath("bar").matches("regex:bar");
        assertThatPath("bar").matches("regex:[a-z]+");
        assertThatPath("/foo/bar").matches("regex:/.*");
        assertThatPath("/foo/bar").matches("regex:/.*/bar");
    }

    @Test
    public void testPathMatchers_glob() {
        assertThatPath("bar").matches("glob:bar");
        assertThatPath("bar").matches("glob:*");
        assertThatPath("/foo").doesNotMatch("glob:*");
        assertThatPath("/foo/bar").doesNotMatch("glob:*");
        assertThatPath("/foo/bar").matches("glob:**");
        assertThatPath("/foo/bar").matches("glob:/**");
        assertThatPath("foo/bar").doesNotMatch("glob:/**");
        assertThatPath("/foo/bar/baz/stuff").matches("glob:/foo/**");
        assertThatPath("/foo/bar/baz/stuff").matches("glob:/**/stuff");
        assertThatPath("/foo").matches("glob:/[a-z]*");
        assertThatPath("/Foo").doesNotMatch("glob:/[a-z]*");
        assertThatPath("/foo/bar/baz/Stuff.java").matches("glob:**/*.java");
        assertThatPath("/foo/bar/baz/Stuff.java").matches("glob:**/*.{java,class}");
        assertThatPath("/foo/bar/baz/Stuff.class").matches("glob:**/*.{java,class}");
        assertThatPath("/foo/bar/baz/Stuff.java").matches("glob:**/*.*");

        try {
            fs.getPathMatcher("glob:**/*.{java,class");
            fail();
        } catch (PatternSyntaxException expected) {
        }
    }

    @Test
    public void testPathMatchers_invalid() {
        try {
            fs.getPathMatcher("glob");
            fail();
        } catch (IllegalArgumentException expected) {
        }

        try {
            fs.getPathMatcher("foo:foo");
            fail();
        } catch (UnsupportedOperationException expected) {
            assertTrue(
                    expected.getMessage().contains("syntax"),
                    "message " + expected.getMessage() + " does not contains \"syntax\"");
        }
    }

    @Test
    public void testNewFileSystem_hasRootAndWorkingDirectory() throws IOException {
        assertThatPath("/").hasChildren("work");
        assertThatPath("/work").hasNoChildren();
    }

    @Test
    public void testCreateDirectory_absolute() throws IOException {
        Files.createDirectory(path("/test"));

        assertThatPath("/test").exists();
        assertThatPath("/").hasChildren("test", "work");

        Files.createDirectory(path("/foo"));
        Files.createDirectory(path("/foo/bar"));

        assertThatPath("/foo/bar").exists();
        assertThatPath("/foo").hasChildren("bar");
    }

    @Test
    public void testCreateFile_absolute() throws IOException {
        Files.createFile(path("/test.txt"));

        assertThatPath("/test.txt").isRegularFile();
        assertThatPath("/").hasChildren("test.txt", "work");

        Files.createDirectory(path("/foo"));
        Files.createFile(path("/foo/test.txt"));

        assertThatPath("/foo/test.txt").isRegularFile();
        assertThatPath("/foo").hasChildren("test.txt");
    }

    @Test
    public void testCreateSymbolicLink_absolute() throws IOException {
        Files.createSymbolicLink(path("/link.txt"), path("test.txt"));

        assertThatPath("/link.txt", NOFOLLOW_LINKS).isSymbolicLink().withTarget("test.txt");
        assertThatPath("/").hasChildren("link.txt", "work");

        Files.createDirectory(path("/foo"));
        Files.createSymbolicLink(path("/foo/link.txt"), path("test.txt"));

        assertThatPath("/foo/link.txt").noFollowLinks().isSymbolicLink().withTarget("test.txt");
        assertThatPath("/foo").hasChildren("link.txt");
    }

    @Test
    public void testCreateLink_absolute() throws IOException {
        Files.createFile(path("/test.txt"));
        Files.createLink(path("/link.txt"), path("/test.txt"));

        // don't assert that the link is the same file here, just that it was created
        // later tests check that linking works correctly
        assertThatPath("/link.txt", NOFOLLOW_LINKS).isRegularFile();
        assertThatPath("/").hasChildren("link.txt", "test.txt", "work");

        Files.createDirectory(path("/foo"));
        Files.createLink(path("/foo/link.txt"), path("/test.txt"));

        assertThatPath("/foo/link.txt", NOFOLLOW_LINKS).isRegularFile();
        assertThatPath("/foo").hasChildren("link.txt");
    }

    @Test
    public void testCreateDirectory_relative() throws IOException {
        Files.createDirectory(path("test"));

        assertThatPath("/work/test", NOFOLLOW_LINKS).isDirectory();
        assertThatPath("test", NOFOLLOW_LINKS).isDirectory();
        assertThatPath("/work").hasChildren("test");
        assertThatPath("test").isSameFileAs("/work/test");

        Files.createDirectory(path("foo"));
        Files.createDirectory(path("foo/bar"));

        assertThatPath("/work/foo/bar", NOFOLLOW_LINKS).isDirectory();
        assertThatPath("foo/bar", NOFOLLOW_LINKS).isDirectory();
        assertThatPath("/work/foo").hasChildren("bar");
        assertThatPath("foo").hasChildren("bar");
        assertThatPath("foo/bar").isSameFileAs("/work/foo/bar");
    }

    @Test
    public void testCreateFile_relative() throws IOException {
        Files.createFile(path("test.txt"));

        assertThatPath("/work/test.txt", NOFOLLOW_LINKS).isRegularFile();
        assertThatPath("test.txt", NOFOLLOW_LINKS).isRegularFile();
        assertThatPath("/work").hasChildren("test.txt");
        assertThatPath("test.txt").isSameFileAs("/work/test.txt");

        Files.createDirectory(path("foo"));
        Files.createFile(path("foo/test.txt"));

        assertThatPath("/work/foo/test.txt", NOFOLLOW_LINKS).isRegularFile();
        assertThatPath("foo/test.txt", NOFOLLOW_LINKS).isRegularFile();
        assertThatPath("/work/foo").hasChildren("test.txt");
        assertThatPath("foo").hasChildren("test.txt");
        assertThatPath("foo/test.txt").isSameFileAs("/work/foo/test.txt");
    }

    @Test
    public void testCreateSymbolicLink_relative() throws IOException {
        Files.createSymbolicLink(path("link.txt"), path("test.txt"));

        assertThatPath("/work/link.txt", NOFOLLOW_LINKS).isSymbolicLink().withTarget("test.txt");
        assertThatPath("link.txt", NOFOLLOW_LINKS).isSymbolicLink().withTarget("test.txt");
        assertThatPath("/work").hasChildren("link.txt");

        Files.createDirectory(path("foo"));
        Files.createSymbolicLink(path("foo/link.txt"), path("test.txt"));

        assertThatPath("/work/foo/link.txt", NOFOLLOW_LINKS)
                .isSymbolicLink()
                .withTarget("test.txt");
        assertThatPath("foo/link.txt", NOFOLLOW_LINKS).isSymbolicLink().withTarget("test.txt");
        assertThatPath("/work/foo").hasChildren("link.txt");
        assertThatPath("foo").hasChildren("link.txt");
    }

    @Test
    public void testCreateLink_relative() throws IOException {
        Files.createFile(path("test.txt"));
        Files.createLink(path("link.txt"), path("test.txt"));

        // don't assert that the link is the same file here, just that it was created
        // later tests check that linking works correctly
        assertThatPath("/work/link.txt", NOFOLLOW_LINKS).isRegularFile();
        assertThatPath("link.txt", NOFOLLOW_LINKS).isRegularFile();
        assertThatPath("/work").hasChildren("link.txt", "test.txt");

        Files.createDirectory(path("foo"));
        Files.createLink(path("foo/link.txt"), path("test.txt"));

        assertThatPath("/work/foo/link.txt", NOFOLLOW_LINKS).isRegularFile();
        assertThatPath("foo/link.txt", NOFOLLOW_LINKS).isRegularFile();
        assertThatPath("foo").hasChildren("link.txt");
    }

    @Test
    public void testCreateFile_existing() throws IOException {
        Files.createFile(path("/test"));
        try {
            Files.createFile(path("/test"));
            fail();
        } catch (FileAlreadyExistsException expected) {
            assertEquals("/test", expected.getMessage());
        }

        try {
            Files.createDirectory(path("/test"));
            fail();
        } catch (FileAlreadyExistsException expected) {
            assertEquals("/test", expected.getMessage());
        }

        try {
            Files.createSymbolicLink(path("/test"), path("/foo"));
            fail();
        } catch (FileAlreadyExistsException expected) {
            assertEquals("/test", expected.getMessage());
        }

        Files.createFile(path("/foo"));
        try {
            Files.createLink(path("/test"), path("/foo"));
            fail();
        } catch (FileAlreadyExistsException expected) {
            assertEquals("/test", expected.getMessage());
        }
    }

    @Test
    public void testCreateFile_parentDoesNotExist() throws IOException {
        try {
            Files.createFile(path("/foo/test"));
            fail();
        } catch (NoSuchFileException expected) {
            assertEquals("/foo/test", expected.getMessage());
        }

        try {
            Files.createDirectory(path("/foo/test"));
            fail();
        } catch (NoSuchFileException expected) {
            assertEquals("/foo/test", expected.getMessage());
        }

        try {
            Files.createSymbolicLink(path("/foo/test"), path("/bar"));
            fail();
        } catch (NoSuchFileException expected) {
            assertEquals("/foo/test", expected.getMessage());
        }

        Files.createFile(path("/bar"));
        try {
            Files.createLink(path("/foo/test"), path("/bar"));
            fail();
        } catch (NoSuchFileException expected) {
            assertEquals("/foo/test", expected.getMessage());
        }
    }

    @Test
    public void testCreateFile_parentIsNotDirectory() throws IOException {
        Files.createDirectory(path("/foo"));
        Files.createFile(path("/foo/bar"));

        try {
            Files.createFile(path("/foo/bar/baz"));
            fail();
        } catch (NoSuchFileException expected) {
            assertEquals("/foo/bar/baz", expected.getFile());
        }
    }

    @Test
    public void testCreateFile_nonDirectoryHigherInPath() throws IOException {
        Files.createDirectory(path("/foo"));
        Files.createFile(path("/foo/bar"));

        try {
            Files.createFile(path("/foo/bar/baz/stuff"));
            fail();
        } catch (NoSuchFileException expected) {
            assertEquals("/foo/bar/baz/stuff", expected.getFile());
        }
    }

    @Test
    public void testCreateFile_parentSymlinkDoesNotExist() throws IOException {
        Files.createDirectory(path("/foo"));
        Files.createSymbolicLink(path("/foo/bar"), path("/foo/nope"));

        try {
            Files.createFile(path("/foo/bar/baz"));
            fail();
        } catch (NoSuchFileException expected) {
            assertEquals("/foo/bar/baz", expected.getFile());
        }
    }

    @Test
    public void testCreateFile_symlinkHigherInPathDoesNotExist() throws IOException {
        Files.createDirectory(path("/foo"));
        Files.createSymbolicLink(path("/foo/bar"), path("nope"));

        try {
            Files.createFile(path("/foo/bar/baz/stuff"));
            fail();
        } catch (NoSuchFileException expected) {
            assertEquals("/foo/bar/baz/stuff", expected.getFile());
        }
    }

    @Test
    public void testCreateFile_parentSymlinkDoesPointsToNonDirectory() throws IOException {
        Files.createDirectory(path("/foo"));
        Files.createFile(path("/foo/file"));
        Files.createSymbolicLink(path("/foo/bar"), path("/foo/file"));

        try {
            Files.createFile(path("/foo/bar/baz"));
            fail();
        } catch (NoSuchFileException expected) {
            assertEquals("/foo/bar/baz", expected.getFile());
        }
    }

    @Test
    public void testCreateFile_symlinkHigherInPathPointsToNonDirectory() throws IOException {
        Files.createDirectory(path("/foo"));
        Files.createFile(path("/foo/file"));
        Files.createSymbolicLink(path("/foo/bar"), path("file"));

        try {
            Files.createFile(path("/foo/bar/baz/stuff"));
            fail();
        } catch (NoSuchFileException expected) {
            assertEquals("/foo/bar/baz/stuff", expected.getFile());
        }
    }

    @Test
    public void testCreateFile_withInitialAttributes() throws IOException {
        Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rwxrwxrwx");
        FileAttribute<?> permissionsAttr = PosixFilePermissions.asFileAttribute(permissions);

        Files.createFile(path("/normal"));
        Files.createFile(path("/foo"), permissionsAttr);

        assertThatPath("/normal").attribute("posix:permissions").isNot(permissions);
        assertThatPath("/foo").attribute("posix:permissions").is(permissions);
    }

    @Test
    public void testCreateFile_withInitialAttributes_illegalInitialAttribute() throws IOException {
        try {
            Files.createFile(
                    path("/foo"),
                    new BasicFileAttribute<>("basic:lastModifiedTime", FileTime.fromMillis(0L)));
            fail();
        } catch (UnsupportedOperationException expected) {
        }

        assertThatPath("/foo").doesNotExist();

        try {
            Files.createFile(
                    path("/foo"), new BasicFileAttribute<>("basic:noSuchAttribute", "foo"));
            fail();
        } catch (UnsupportedOperationException expected) {
        }

        assertThatPath("/foo").doesNotExist();
    }

    @Test
    public void testOpenChannel_withInitialAttributes_createNewFile() throws IOException {
        FileAttribute<Set<PosixFilePermission>> permissions =
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx"));
        Files.newByteChannel(path("/foo"), Set.of(WRITE, CREATE), permissions).close();

        assertThatPath("/foo")
                .isRegularFile()
                .and()
                .attribute("posix:permissions")
                .is(permissions.value());
    }

    @Test
    public void testOpenChannel_withInitialAttributes_fileExists() throws IOException {
        Files.createFile(path("/foo"));

        FileAttribute<Set<PosixFilePermission>> permissions =
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx"));
        Files.newByteChannel(path("/foo"), Set.of(WRITE, CREATE), permissions).close();

        assertThatPath("/foo")
                .isRegularFile()
                .and()
                .attribute("posix:permissions")
                .isNot(permissions.value());
    }

    @Test
    public void testCreateDirectory_withInitialAttributes() throws IOException {
        FileAttribute<Set<PosixFilePermission>> permissions =
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx"));

        Files.createDirectory(path("/foo"), permissions);

        assertThatPath("/foo")
                .isDirectory()
                .and()
                .attribute("posix:permissions")
                .is(permissions.value());

        Files.createDirectory(path("/normal"));

        assertThatPath("/normal")
                .isDirectory()
                .and()
                .attribute("posix:permissions")
                .isNot(permissions.value());
    }

    @Test
    public void testCreateSymbolicLink_withInitialAttributes() throws IOException {
        FileAttribute<Set<PosixFilePermission>> permissions =
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx"));

        Files.createSymbolicLink(path("/foo"), path("bar"), permissions);

        assertThatPath("/foo", NOFOLLOW_LINKS)
                .isSymbolicLink()
                .and()
                .attribute("posix:permissions")
                .is(permissions.value());

        Files.createSymbolicLink(path("/normal"), path("bar"));

        assertThatPath("/normal", NOFOLLOW_LINKS)
                .isSymbolicLink()
                .and()
                .attribute("posix:permissions")
                .isNot(permissions.value());
    }

    @Test
    public void testCreateDirectories() throws IOException {
        Files.createDirectories(path("/foo/bar/baz"));

        assertThatPath("/foo").isDirectory();
        assertThatPath("/foo/bar").isDirectory();
        assertThatPath("/foo/bar/baz").isDirectory();

        Files.createDirectories(path("/foo/asdf/jkl"));

        assertThatPath("/foo/asdf").isDirectory();
        assertThatPath("/foo/asdf/jkl").isDirectory();

        Files.createDirectories(path("bar/baz"));

        assertThatPath("bar/baz").isDirectory();
        assertThatPath("/work/bar/baz").isDirectory();
    }

    @Test
    public void testDirectories_newlyCreatedDirectoryHasTwoLinks() throws IOException {
        // one link from its parent to it; one from it to itself

        Files.createDirectory(path("/foo"));

        assertThatPath("/foo").hasLinkCount(2);
    }

    @Test
    public void testDirectories_creatingDirectoryAddsOneLinkToParent() throws IOException {
        // from the .. direntry

        Files.createDirectory(path("/foo"));
        Files.createDirectory(path("/foo/bar"));

        assertThatPath("/foo").hasLinkCount(3);

        Files.createDirectory(path("/foo/baz"));

        assertThatPath("/foo").hasLinkCount(4);
    }

    @Test
    public void testDirectories_creatingNonDirectoryDoesNotAddLinkToParent() throws IOException {
        Files.createDirectory(path("/foo"));
        Files.createFile(path("/foo/file"));
        Files.createSymbolicLink(path("/foo/fileSymlink"), path("file"));
        Files.createLink(path("/foo/link"), path("/foo/file"));
        Files.createSymbolicLink(path("/foo/fooSymlink"), path("/foo"));

        assertThatPath("/foo").hasLinkCount(2);
    }

    @Test
    public void testSize_forNewFile_isZero() throws IOException {
        Files.createFile(path("/test"));

        assertThatPath("/test").hasSize(0);
    }

    @Test
    public void testRead_forNewFile_isEmpty() throws IOException {
        Files.createFile(path("/test"));

        assertThatPath("/test").containsNoBytes();
    }

    @Test
    public void testWriteFile_succeeds() throws IOException {
        Files.createFile(path("/test"));
        Files.write(path("/test"), new byte[] {0, 1, 2, 3});
    }

    @Test
    public void testSize_forFileAfterWrite_isNumberOfBytesWritten() throws IOException {
        Files.write(path("/test"), new byte[] {0, 1, 2, 3});

        assertThatPath("/test").hasSize(4);
    }

    @Test
    public void testRead_forFileAfterWrite_isBytesWritten() throws IOException {
        byte[] bytes = {0, 1, 2, 3};
        Files.write(path("/test"), bytes);

        assertThatPath("/test").containsBytes(bytes);
    }

    @Test
    public void testWriteFile_withStandardOptions() throws IOException {
        Path test = path("/test");
        byte[] bytes = {0, 1, 2, 3};

        try {
            // CREATE and CREATE_NEW not specified
            Files.write(test, bytes, WRITE);
            fail();
        } catch (NoSuchFileException expected) {
            assertEquals(test.toString(), expected.getMessage());
        }

        Files.write(test, bytes, CREATE_NEW); // succeeds, file does not exist
        assertThatPath("/test").containsBytes(bytes);

        try {
            Files.write(test, bytes, CREATE_NEW); // CREATE_NEW requires file not exist
            fail();
        } catch (FileAlreadyExistsException expected) {
            assertEquals(test.toString(), expected.getMessage());
        }

        Files.write(test, new byte[] {4, 5}, CREATE); // succeeds, ok for file to already exist
        assertThatPath("/test")
                .containsBytes(4, 5, 2, 3); // did not truncate or append, so overwrote

        Files.write(test, bytes, WRITE, CREATE, TRUNCATE_EXISTING); // default options
        assertThatPath("/test").containsBytes(bytes);

        Files.write(test, bytes, WRITE, APPEND);
        assertThatPath("/test").containsBytes(0, 1, 2, 3, 0, 1, 2, 3);

        Files.write(test, bytes, WRITE, CREATE, TRUNCATE_EXISTING, APPEND, SPARSE, DSYNC, SYNC);
        assertThatPath("/test").containsBytes(bytes);

        try {
            Files.write(test, bytes, READ, WRITE); // READ not allowed
            fail();
        } catch (UnsupportedOperationException expected) {
        }
    }

    @Test
    public void testWriteLines_succeeds() throws IOException {
        Files.write(path("/test.txt"), List.of("hello", "world"), UTF_8);
    }

    @Test
    public void testOpenFile_withReadAndTruncateExisting_doesNotTruncateFile() throws IOException {
        byte[] bytes = TestUtils.bytes(1, 2, 3, 4);
        Files.write(path("/test"), bytes);

        try (FileChannel channel = FileChannel.open(path("/test"), READ, TRUNCATE_EXISTING)) {
            // TRUNCATE_EXISTING ignored when opening for read
            byte[] readBytes = new byte[4];
            channel.read(ByteBuffer.wrap(readBytes));

            assertEquals(bytesAsList(bytes), bytesAsList(readBytes));
        }
    }

    @Test
    public void testRead_forFileAfterWriteLines_isLinesWritten() throws IOException {
        Files.write(path("/test.txt"), List.of("hello", "world"), UTF_8);

        assertThatPath("/test.txt").containsLines("hello", "world");
    }

    @Test
    public void testWriteLines_withStandardOptions() throws IOException {
        Path test = path("/test.txt");
        List<String> lines = List.of("hello", "world");

        try {
            // CREATE and CREATE_NEW not specified
            Files.write(test, lines, UTF_8, WRITE);
            fail();
        } catch (NoSuchFileException expected) {
            assertEquals(test.toString(), expected.getMessage());
        }

        Files.write(test, lines, UTF_8, CREATE_NEW); // succeeds, file does not exist
        this.assertThatPath(test).containsLines(lines);

        try {
            Files.write(test, lines, UTF_8, CREATE_NEW); // CREATE_NEW requires file not exist
            fail();
        } catch (FileAlreadyExistsException expected) {
        }

        // succeeds, ok for file to already exist
        Files.write(test, List.of("foo"), UTF_8, CREATE);
        // did not truncate or append, so overwrote
        if (System.getProperty("line.separator").length() == 2) {
            // on Windows, an extra character is overwritten by the \r\n line separator
            this.assertThatPath(test).containsLines("foo", "", "world");
        } else {
            this.assertThatPath(test).containsLines("foo", "o", "world");
        }

        Files.write(test, lines, UTF_8, WRITE, CREATE, TRUNCATE_EXISTING); // default options
        this.assertThatPath(test).containsLines(lines);

        Files.write(test, lines, UTF_8, WRITE, APPEND);
        this.assertThatPath(test).containsLines("hello", "world", "hello", "world");

        Files.write(
                test, lines, UTF_8, WRITE, CREATE, TRUNCATE_EXISTING, APPEND, SPARSE, DSYNC, SYNC);
        this.assertThatPath(test).containsLines(lines);

        try {
            Files.write(test, lines, UTF_8, READ, WRITE); // READ not allowed
            fail();
        } catch (UnsupportedOperationException expected) {
        }
    }

    @Test
    public void testWrite_fileExistsButIsNotRegularFile() throws IOException {
        Files.createDirectory(path("/foo"));

        try {
            // non-CREATE mode
            Files.write(path("/foo"), preFilledBytes(10), WRITE);
            fail();
        } catch (FileSystemException expected) {
            assertEquals("/foo", expected.getFile());
            assertTrue(expected.getMessage().contains("regular file"));
        }

        try {
            // CREATE mode
            Files.write(path("/foo"), preFilledBytes(10));
            fail();
        } catch (FileSystemException expected) {
            assertEquals("/foo", expected.getFile());
            assertTrue(expected.getMessage().contains("regular file"));
        }
    }

    @Test
    public void testDelete_file() throws IOException {
        try {
            Files.delete(path("/test"));
            fail();
        } catch (NoSuchFileException expected) {
            assertEquals("/test", expected.getMessage());
        }

        try {
            Files.delete(path("/foo/bar"));
            fail();
        } catch (NoSuchFileException expected) {
            assertEquals("/foo/bar", expected.getMessage());
        }

        assertFalse(Files.deleteIfExists(path("/test")));
        assertFalse(Files.deleteIfExists(path("/foo/bar")));

        Files.createFile(path("/test"));
        assertThatPath("/test").isRegularFile();

        Files.delete(path("/test"));
        assertThatPath("/test").doesNotExist();

        Files.createFile(path("/test"));

        assertTrue(Files.deleteIfExists(path("/test")));
        assertThatPath("/test").doesNotExist();
    }

    @Test
    public void testDelete_file_whenOpenReferencesRemain() throws IOException {
        // the open streams should continue to function normally despite the deletion

        Path foo = path("/foo");
        byte[] bytes = preFilledBytes(100);
        Files.write(foo, bytes);

        InputStream in = Files.newInputStream(foo);
        OutputStream out = Files.newOutputStream(foo, APPEND);
        FileChannel channel = FileChannel.open(foo, READ, WRITE);

        assertEquals(100L, channel.size());

        Files.delete(foo);
        assertThatPath("/foo").doesNotExist();

        assertEquals(100L, channel.size());

        ByteBuffer buf = ByteBuffer.allocate(100);
        while (buf.hasRemaining()) {
            channel.read(buf);
        }

        assertArrayEquals(bytes, buf.array());

        byte[] moreBytes = {1, 2, 3, 4, 5};
        out.write(moreBytes);

        assertEquals(105L, channel.size());
        buf.clear();
        assertEquals(5, channel.read(buf));

        buf.flip();
        byte[] b = new byte[5];
        buf.get(b);
        assertArrayEquals(moreBytes, b);

        byte[] allBytes = new byte[105];
        int off = 0;
        int read;
        while ((read = in.read(allBytes, off, allBytes.length - off)) != -1) {
            off += read;
        }
        assertArrayEquals(concat(bytes, moreBytes), allBytes);

        channel.close();
        out.close();
        in.close();
    }

    @Test
    public void testDelete_directory() throws IOException {
        Files.createDirectories(path("/foo/bar"));
        assertThatPath("/foo").isDirectory();
        assertThatPath("/foo/bar").isDirectory();

        Files.delete(path("/foo/bar"));
        assertThatPath("/foo/bar").doesNotExist();

        assertTrue(Files.deleteIfExists(path("/foo")));
        assertThatPath("/foo").doesNotExist();
    }

    @Test
    public void testDelete_pathPermutations() throws IOException {
        Path bar = path("/work/foo/bar");
        Files.createDirectories(bar);
        for (Path path : permutations(bar)) {
            Files.createDirectories(bar);
            this.assertThatPath(path).isSameFileAs(bar);
            Files.delete(path);
            this.assertThatPath(bar).doesNotExist();
            this.assertThatPath(path).doesNotExist();
        }

        Path baz = path("/test/baz");
        Files.createDirectories(baz);
        Path hello = baz.resolve("hello.txt");
        for (Path path : permutations(hello)) {
            Files.createFile(hello);
            this.assertThatPath(path).isSameFileAs(hello);
            Files.delete(path);
            this.assertThatPath(hello).doesNotExist();
            this.assertThatPath(path).doesNotExist();
        }
    }

    @Test
    public void testDelete_directory_cantDeleteNonEmptyDirectory() throws IOException {
        Files.createDirectories(path("/foo/bar"));

        try {
            Files.delete(path("/foo"));
            fail();
        } catch (DirectoryNotEmptyException expected) {
            assertEquals("/foo", expected.getFile());
        }

        try {
            Files.deleteIfExists(path("/foo"));
            fail();
        } catch (DirectoryNotEmptyException expected) {
            assertEquals("/foo", expected.getFile());
        }
    }

    @Test
    public void testDelete_directory_canDeleteWorkingDirectoryByAbsolutePath() throws IOException {
        assertThatPath("/work").exists();
        assertThatPath("").exists();
        assertThatPath(".").exists();

        Files.delete(path("/work"));

        assertThatPath("/work").doesNotExist();
        assertThatPath("").exists();
        assertThatPath(".").exists();
    }

    @Test
    public void testDelete_directory_cantDeleteWorkingDirectoryByRelativePath() throws IOException {
        try {
            Files.delete(path(""));
            fail();
        } catch (FileSystemException expected) {
            assertEquals("", expected.getFile());
        }

        try {
            Files.delete(path("."));
            fail();
        } catch (FileSystemException expected) {
            assertEquals(".", expected.getFile());
        }

        try {
            Files.delete(path("../../work"));
            fail();
        } catch (FileSystemException expected) {
            assertEquals("../../work", expected.getFile());
        }

        try {
            Files.delete(path("./../work/.././../work/."));
            fail();
        } catch (FileSystemException expected) {
            assertEquals("./../work/.././../work/.", expected.getFile());
        }
    }

    @Test
    public void testDelete_directory_cantDeleteRoot() throws IOException {
        // delete working directory so that root is empty
        // don't want to just be testing the "can't delete when not empty" logic
        Files.delete(path("/work"));

        try {
            Files.delete(path("/"));
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("root"));
        }

        Files.createDirectories(path("/foo/bar"));

        try {
            Files.delete(path("/foo/bar/../.."));
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("root"));
        }

        try {
            Files.delete(path("/foo/./../foo/bar/./../bar/.././../../.."));
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("root"));
        }
    }

    @Test
    public void testSymbolicLinks() throws IOException {
        Files.createSymbolicLink(path("/link.txt"), path("/file.txt"));
        assertThatPath("/link.txt", NOFOLLOW_LINKS).isSymbolicLink().withTarget("/file.txt");
        assertThatPath("/link.txt").doesNotExist(); // following the link; target doesn't exist

        try {
            Files.createFile(path("/link.txt"));
            fail();
        } catch (FileAlreadyExistsException expected) {
        }

        try {
            Files.readAllBytes(path("/link.txt"));
            fail();
        } catch (NoSuchFileException expected) {
        }

        Files.createFile(path("/file.txt"));
        assertThatPath("/link.txt").isRegularFile(); // following the link; target does exist
        assertThatPath("/link.txt").containsNoBytes();

        Files.createSymbolicLink(path("/foo"), path("/bar/baz"));
        assertThatPath("/foo", NOFOLLOW_LINKS).isSymbolicLink().withTarget("/bar/baz");
        assertThatPath("/foo").doesNotExist(); // following the link; target doesn't exist

        Files.createDirectories(path("/bar/baz"));
        assertThatPath("/foo").isDirectory(); // following the link; target does exist

        Files.createFile(path("/bar/baz/test.txt"));
        assertThatPath("/foo/test.txt", NOFOLLOW_LINKS).isRegularFile(); // follow intermediate link

        try {
            Files.readSymbolicLink(path("/none"));
            fail();
        } catch (NoSuchFileException expected) {
            assertEquals("/none", expected.getMessage());
        }

        try {
            Files.readSymbolicLink(path("/file.txt"));
            fail();
        } catch (NotLinkException expected) {
            assertEquals("/file.txt", expected.getMessage());
        }
    }

    @Test
    public void testSymbolicLinks_symlinkCycle() throws IOException {
        Files.createDirectory(path("/foo"));
        Files.createSymbolicLink(path("/foo/bar"), path("baz"));
        Files.createSymbolicLink(path("/foo/baz"), path("bar"));

        try {
            Files.createFile(path("/foo/bar/file"));
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("symbolic link"));
        }

        try {
            Files.write(path("/foo/bar"), preFilledBytes(10));
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("symbolic link"));
        }
    }

    @Test
    public void testSymbolicLinks_lookupOfAbsoluteSymlinkPathFromRelativePath() throws IOException {
        // relative path lookups are in the FileSystemView for the working directory
        // this tests that when an absolute path is encountered, the lookup handles it correctly

        Files.createDirectories(path("/foo/bar/baz"));
        Files.createFile(path("/foo/bar/baz/file"));
        Files.createDirectories(path("one/two/three"));
        Files.createSymbolicLink(path("/work/one/two/three/link"), path("/foo/bar"));

        assertThatPath("one/two/three/link/baz/file").isSameFileAs("/foo/bar/baz/file");
    }

    @Test
    public void testLink() throws IOException {
        Files.createFile(path("/file.txt"));
        // checking link count requires "unix" attribute support, which we're using here
        assertThatPath("/file.txt").hasLinkCount(1);

        Files.createLink(path("/link.txt"), path("/file.txt"));

        assertThatPath("/link.txt").isSameFileAs("/file.txt");

        assertThatPath("/file.txt").hasLinkCount(2);
        assertThatPath("/link.txt").hasLinkCount(2);

        assertThatPath("/file.txt").containsNoBytes();
        assertThatPath("/link.txt").containsNoBytes();

        byte[] bytes = {0, 1, 2, 3};
        Files.write(path("/file.txt"), bytes);

        assertThatPath("/file.txt").containsBytes(bytes);
        assertThatPath("/link.txt").containsBytes(bytes);

        Files.write(path("/link.txt"), bytes, APPEND);

        assertThatPath("/file.txt").containsBytes(0, 1, 2, 3, 0, 1, 2, 3);
        assertThatPath("/link.txt").containsBytes(0, 1, 2, 3, 0, 1, 2, 3);

        Files.delete(path("/file.txt"));
        assertThatPath("/link.txt").hasLinkCount(1);

        assertThatPath("/link.txt").containsBytes(0, 1, 2, 3, 0, 1, 2, 3);
    }

    @Test
    public void testLink_forSymbolicLink_usesSymbolicLinkTarget() throws IOException {
        Files.createFile(path("/file"));
        Files.createSymbolicLink(path("/symlink"), path("/file"));

        Object key = getFileKey("/file");

        Files.createLink(path("/link"), path("/symlink"));

        assertThatPath("/link")
                .isRegularFile()
                .and()
                .hasLinkCount(2)
                .and()
                .attribute("fileKey")
                .is(key);
    }

    @Test
    public void testLink_failsWhenTargetDoesNotExist() throws IOException {
        try {
            Files.createLink(path("/link"), path("/foo"));
            fail();
        } catch (NoSuchFileException expected) {
            assertEquals("/foo", expected.getFile());
        }

        Files.createSymbolicLink(path("/foo"), path("/bar"));

        try {
            Files.createLink(path("/link"), path("/foo"));
            fail();
        } catch (NoSuchFileException expected) {
            assertEquals("/foo", expected.getFile());
        }
    }

    @Test
    public void testLink_failsForNonRegularFile() throws IOException {
        Files.createDirectory(path("/dir"));

        try {
            Files.createLink(path("/link"), path("/dir"));
            fail();
        } catch (FileSystemException expected) {
            assertEquals("/link", expected.getFile());
            assertEquals("/dir", expected.getOtherFile());
        }

        assertThatPath("/link").doesNotExist();
    }

    @Test
    public void testLinks_failsWhenTargetFileAlreadyExists() throws IOException {
        Files.createFile(path("/file"));
        Files.createFile(path("/link"));

        try {
            Files.createLink(path("/link"), path("/file"));
            fail();
        } catch (FileAlreadyExistsException expected) {
            assertEquals("/link", expected.getFile());
        }
    }

    @Test
    public void testStreams() throws IOException {
        try (OutputStream out = Files.newOutputStream(path("/test"))) {
            for (int i = 0; i < 100; i++) {
                out.write(i);
            }
        }

        byte[] expected = new byte[100];
        for (byte i = 0; i < 100; i++) {
            expected[i] = i;
        }

        try (InputStream in = Files.newInputStream(path("/test"))) {
            byte[] bytes = new byte[100];
            in.read(bytes);
            // ByteStreams.readFully(in, bytes);
            assertArrayEquals(expected, bytes);
        }

        try (Writer writer = Files.newBufferedWriter(path("/test.txt"), UTF_8)) {
            writer.write("hello");
        }

        try (Reader reader = Files.newBufferedReader(path("/test.txt"), UTF_8)) {
            assertEquals("hello", readAll(reader));
        }

        try (Writer writer = Files.newBufferedWriter(path("/test.txt"), UTF_8, APPEND)) {
            writer.write(" world");
        }

        try (Reader reader = Files.newBufferedReader(path("/test.txt"), UTF_8)) {
            assertEquals("hello world", readAll(reader));
        }
    }

    @Test
    public void testOutputStream_withTruncateExistingAndNotWrite_truncatesFile()
            throws IOException {
        // https://github.com/google/jimfs/pull/77
        Path path = path("/test");
        Files.write(path, new byte[] {1, 2, 3});
        this.assertThatPath(path).containsBytes(1, 2, 3);

        try (OutputStream out = Files.newOutputStream(path, CREATE, TRUNCATE_EXISTING)) {
            out.write(new byte[] {1, 2});
        }

        this.assertThatPath(path).containsBytes(1, 2);
    }

    @Test
    public void testChannels() throws IOException {
        try (FileChannel channel = FileChannel.open(path("/test.txt"), CREATE_NEW, WRITE)) {
            ByteBuffer buf1 = UTF_8.encode("hello");
            ByteBuffer buf2 = UTF_8.encode(" world");
            while (buf1.hasRemaining() || buf2.hasRemaining()) {
                channel.write(new ByteBuffer[] {buf1, buf2});
            }

            assertEquals(11, channel.position());
            assertEquals(11, channel.size());

            channel.write(UTF_8.encode("!"));

            assertEquals(12, channel.position());
            assertEquals(12, channel.size());
        }

        try (SeekableByteChannel channel = Files.newByteChannel(path("/test.txt"), READ)) {
            assertEquals(0, channel.position());
            assertEquals(12, channel.size());

            ByteBuffer buffer = ByteBuffer.allocate(100);
            while (channel.read(buffer) != -1) {}
            buffer.flip();
            assertEquals("hello world!", UTF_8.decode(buffer).toString());
        }

        byte[] bytes = preFilledBytes(100);

        Files.write(path("/test"), bytes);

        try (SeekableByteChannel channel = Files.newByteChannel(path("/test"), READ, WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(preFilledBytes(50));

            channel.position(50);
            channel.write(buffer);
            buffer.flip();
            channel.write(buffer);

            channel.position(0);
            ByteBuffer readBuffer = ByteBuffer.allocate(150);
            while (readBuffer.hasRemaining()) {
                channel.read(readBuffer);
            }

            byte[] expected = concat(preFilledBytes(50), preFilledBytes(50), preFilledBytes(50));

            assertArrayEquals(expected, readBuffer.array());
        }

        try (FileChannel channel = FileChannel.open(path("/test"), READ, WRITE)) {
            assertEquals(150, channel.size());

            channel.truncate(10);
            assertEquals(10, channel.size());

            ByteBuffer buffer = ByteBuffer.allocate(20);
            assertEquals(10, channel.read(buffer));
            buffer.flip();

            byte[] expected = new byte[20];
            System.arraycopy(preFilledBytes(10), 0, expected, 0, 10);
            assertArrayEquals(expected, buffer.array());
        }
    }

    @Test
    public void testCopy_inputStreamToFile() throws IOException {
        byte[] bytes = preFilledBytes(512);

        Files.copy(new ByteArrayInputStream(bytes), path("/test"));
        assertThatPath("/test").containsBytes(bytes);

        try {
            Files.copy(new ByteArrayInputStream(bytes), path("/test"));
            fail();
        } catch (FileAlreadyExistsException expected) {
            assertEquals("/test", expected.getMessage());
        }

        Files.copy(new ByteArrayInputStream(bytes), path("/test"), REPLACE_EXISTING);
        assertThatPath("/test").containsBytes(bytes);

        Files.copy(new ByteArrayInputStream(bytes), path("/foo"), REPLACE_EXISTING);
        assertThatPath("/foo").containsBytes(bytes);
    }

    @Test
    public void testCopy_fileToOutputStream() throws IOException {
        byte[] bytes = preFilledBytes(512);
        Files.write(path("/test"), bytes);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Files.copy(path("/test"), out);
        assertArrayEquals(bytes, out.toByteArray());
    }

    @Test
    public void testCopy_fileToPath() throws IOException {
        byte[] bytes = preFilledBytes(512);
        Files.write(path("/foo"), bytes);

        assertThatPath("/bar").doesNotExist();
        Files.copy(path("/foo"), path("/bar"));
        assertThatPath("/bar").containsBytes(bytes);

        byte[] moreBytes = preFilledBytes(2048);
        Files.write(path("/baz"), moreBytes);

        Files.copy(path("/baz"), path("/bar"), REPLACE_EXISTING);
        assertThatPath("/bar").containsBytes(moreBytes);

        try {
            Files.copy(path("/none"), path("/bar"));
            fail();
        } catch (NoSuchFileException expected) {
            assertEquals("/none", expected.getMessage());
        }
    }

    @Test
    public void testCopy_withCopyAttributes() throws IOException {
        Path foo = path("/foo");
        Files.createFile(foo);

        Files.getFileAttributeView(foo, BasicFileAttributeView.class)
                .setTimes(
                        FileTime.fromMillis(100),
                        FileTime.fromMillis(1000),
                        FileTime.fromMillis(10000));

        assertEquals(FileTime.fromMillis(100), Files.getAttribute(foo, "lastModifiedTime"));

        UserPrincipal zero = fs.getUserPrincipalLookupService().lookupPrincipalByName("zero");
        Files.setAttribute(foo, "owner:owner", zero);

        Path bar = path("/bar");
        Files.copy(foo, bar, COPY_ATTRIBUTES);

        BasicFileAttributes attributes = Files.readAttributes(bar, BasicFileAttributes.class);
        assertEquals(FileTime.fromMillis(100), attributes.lastModifiedTime());
        assertEquals(FileTime.fromMillis(1000), attributes.lastAccessTime());
        assertEquals(FileTime.fromMillis(10000), attributes.creationTime());
        assertEquals(zero, Files.getAttribute(bar, "owner:owner"));

        Path baz = path("/baz");
        Files.copy(foo, baz);

        // test that attributes are not copied when COPY_ATTRIBUTES is not specified
        attributes = Files.readAttributes(baz, BasicFileAttributes.class);
        assertNotEquals(FileTime.fromMillis(100), attributes.lastModifiedTime());
        assertNotEquals(FileTime.fromMillis(1000), attributes.lastAccessTime());
        assertNotEquals(FileTime.fromMillis(10000), attributes.creationTime());
        assertNotEquals(zero, Files.getAttribute(baz, "owner:owner"));
    }

    @Test
    public void testCopy_doesNotSupportAtomicMove() throws IOException {
        try {
            Files.copy(path("/foo"), path("/bar"), ATOMIC_MOVE);
            fail();
        } catch (UnsupportedOperationException expected) {
        }
    }

    @Test
    public void testCopy_directoryToPath() throws IOException {
        Files.createDirectory(path("/foo"));

        assertThatPath("/bar").doesNotExist();
        Files.copy(path("/foo"), path("/bar"));
        assertThatPath("/bar").isDirectory();
    }

    @Test
    public void testCopy_withoutReplaceExisting_failsWhenTargetExists() throws IOException {
        Files.createFile(path("/bar"));
        Files.createDirectory(path("/foo"));

        // dir -> file
        try {
            Files.copy(path("/foo"), path("/bar"));
            fail();
        } catch (FileAlreadyExistsException expected) {
            assertEquals("/bar", expected.getMessage());
        }

        Files.delete(path("/foo"));
        Files.createFile(path("/foo"));

        // file -> file
        try {
            Files.copy(path("/foo"), path("/bar"));
            fail();
        } catch (FileAlreadyExistsException expected) {
            assertEquals("/bar", expected.getMessage());
        }

        Files.delete(path("/bar"));
        Files.createDirectory(path("/bar"));

        // file -> dir
        try {
            Files.copy(path("/foo"), path("/bar"));
            fail();
        } catch (FileAlreadyExistsException expected) {
            assertEquals("/bar", expected.getMessage());
        }

        Files.delete(path("/foo"));
        Files.createDirectory(path("/foo"));

        // dir -> dir
        try {
            Files.copy(path("/foo"), path("/bar"));
            fail();
        } catch (FileAlreadyExistsException expected) {
            assertEquals("/bar", expected.getMessage());
        }
    }

    @Test
    public void testCopy_withReplaceExisting() throws IOException {
        Files.createFile(path("/bar"));
        Files.createDirectory(path("/test"));

        assertThatPath("/bar").isRegularFile();

        // overwrite regular file w/ directory
        Files.copy(path("/test"), path("/bar"), REPLACE_EXISTING);

        assertThatPath("/bar").isDirectory();

        byte[] bytes = {0, 1, 2, 3};
        Files.write(path("/baz"), bytes);

        // overwrite directory w/ regular file
        Files.copy(path("/baz"), path("/bar"), REPLACE_EXISTING);

        assertThatPath("/bar").containsSameBytesAs("/baz");
    }

    @Test
    public void testCopy_withReplaceExisting_cantReplaceNonEmptyDirectory() throws IOException {
        Files.createDirectory(path("/foo"));
        Files.createDirectory(path("/foo/bar"));
        Files.createFile(path("/foo/baz"));

        Files.createDirectory(path("/test"));

        try {
            Files.copy(path("/test"), path("/foo"), REPLACE_EXISTING);
            fail();
        } catch (DirectoryNotEmptyException expected) {
            assertEquals("/foo", expected.getMessage());
        }

        Files.delete(path("/test"));
        Files.createFile(path("/test"));

        try {
            Files.copy(path("/test"), path("/foo"), REPLACE_EXISTING);
            fail();
        } catch (DirectoryNotEmptyException expected) {
            assertEquals("/foo", expected.getMessage());
        }

        Files.delete(path("/foo/baz"));
        Files.delete(path("/foo/bar"));

        Files.copy(path("/test"), path("/foo"), REPLACE_EXISTING);
        assertThatPath("/foo").isRegularFile(); // replaced
    }

    @Test
    public void testCopy_directoryToPath_doesNotCopyDirectoryContents() throws IOException {
        Files.createDirectory(path("/foo"));
        Files.createDirectory(path("/foo/baz"));
        Files.createFile(path("/foo/test"));

        Files.copy(path("/foo"), path("/bar"));
        assertThatPath("/bar").hasNoChildren();
    }

    @Test
    public void testCopy_symbolicLinkToPath() throws IOException {
        byte[] bytes = preFilledBytes(128);
        Files.write(path("/test"), bytes);
        Files.createSymbolicLink(path("/link"), path("/test"));

        assertThatPath("/bar").doesNotExist();
        Files.copy(path("/link"), path("/bar"));
        assertThatPath("/bar", NOFOLLOW_LINKS).containsBytes(bytes);

        Files.delete(path("/bar"));

        Files.copy(path("/link"), path("/bar"), NOFOLLOW_LINKS);
        assertThatPath("/bar", NOFOLLOW_LINKS).isSymbolicLink().withTarget("/test");
        assertThatPath("/bar").isRegularFile();
        assertThatPath("/bar").containsBytes(bytes);

        Files.delete(path("/test"));
        assertThatPath("/bar", NOFOLLOW_LINKS).isSymbolicLink();
        assertThatPath("/bar").doesNotExist();
    }

    @Test
    public void testCopy_toDifferentFileSystem() throws IOException {
        try (FileSystem fs2 = ZeroFs.newFileSystem(UNIX_CONFIGURATION)) {
            Path foo = fs.getPath("/foo");
            byte[] bytes = {0, 1, 2, 3, 4};
            Files.write(foo, bytes);

            Path foo2 = fs2.getPath("/foo");
            Files.copy(foo, foo2);

            this.assertThatPath(foo).exists();
            this.assertThatPath(foo2).exists().and().containsBytes(bytes);
        }
    }

    @Test
    public void testCopy_toDifferentFileSystem_copyAttributes() throws IOException {
        try (FileSystem fs2 = ZeroFs.newFileSystem(UNIX_CONFIGURATION)) {
            Path foo = fs.getPath("/foo");
            byte[] bytes = {0, 1, 2, 3, 4};
            Files.write(foo, bytes);
            Files.getFileAttributeView(foo, BasicFileAttributeView.class)
                    .setTimes(
                            FileTime.fromMillis(0), FileTime.fromMillis(1), FileTime.fromMillis(2));

            UserPrincipal owner =
                    fs.getUserPrincipalLookupService().lookupPrincipalByName("foobar");
            Files.setOwner(foo, owner);

            this.assertThatPath(foo).attribute("owner:owner").is(owner);

            Path foo2 = fs2.getPath("/foo");
            Files.copy(foo, foo2, COPY_ATTRIBUTES);

            this.assertThatPath(foo).exists();

            // when copying with COPY_ATTRIBUTES to a different FileSystem, only basic
            // attributes(that
            // is, file times) can actually be copied
            this.assertThatPath(foo2)
                    .exists()
                    .and()
                    .attribute("lastModifiedTime")
                    .is(FileTime.fromMillis(0))
                    .and()
                    .attribute("lastAccessTime")
                    .is(FileTime.fromMillis(1))
                    .and()
                    .attribute("creationTime")
                    .is(FileTime.fromMillis(2))
                    .and()
                    .attribute("owner:owner")
                    .isNot(owner)
                    .and()
                    .attribute("owner:owner")
                    .isNot(fs2.getUserPrincipalLookupService().lookupPrincipalByName("foobar"))
                    .and()
                    .containsBytes(bytes); // do this last; it updates the access time
        }
    }

    @Test
    public void testMove() throws IOException {
        byte[] bytes = preFilledBytes(100);
        Files.write(path("/foo"), bytes);

        Object fooKey = getFileKey("/foo");

        Files.move(path("/foo"), path("/bar"));
        assertThatPath("/foo").doesNotExist();
        assertThatPath("/bar").containsBytes(bytes).and().attribute("fileKey").is(fooKey);

        Files.createDirectory(path("/foo"));
        Files.move(path("/bar"), path("/foo/bar"));

        assertThatPath("/bar").doesNotExist();
        assertThatPath("/foo/bar").isRegularFile();

        Files.move(path("/foo"), path("/baz"));
        assertThatPath("/foo").doesNotExist();
        assertThatPath("/baz").isDirectory();
        assertThatPath("/baz/bar").isRegularFile();
    }

    @Test
    public void testMove_movesSymbolicLinkNotTarget() throws IOException {
        byte[] bytes = preFilledBytes(100);
        Files.write(path("/foo.txt"), bytes);

        Files.createSymbolicLink(path("/link"), path("foo.txt"));

        Files.move(path("/link"), path("/link.txt"));

        assertThatPath("/foo.txt").noFollowLinks().isRegularFile().and().containsBytes(bytes);

        this.assertThatPath(path("/link")).doesNotExist();

        this.assertThatPath(path("/link.txt")).noFollowLinks().isSymbolicLink();

        this.assertThatPath(path("/link.txt")).isRegularFile().and().containsBytes(bytes);
    }

    @Test
    public void testMove_cannotMoveDirIntoOwnSubtree() throws IOException {
        Files.createDirectories(path("/foo"));

        try {
            Files.move(path("/foo"), path("/foo/bar"));
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("sub"));
        }

        Files.createDirectories(path("/foo/bar/baz/stuff"));
        Files.createDirectories(path("/hello/world"));
        Files.createSymbolicLink(path("/hello/world/link"), path("../../foo/bar/baz"));

        try {
            Files.move(path("/foo/bar"), path("/hello/world/link/bar"));
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("sub"));
        }
    }

    @Test
    public void testMove_withoutReplaceExisting_failsWhenTargetExists() throws IOException {
        byte[] bytes = preFilledBytes(50);
        Files.write(path("/test"), bytes);

        Object testKey = getFileKey("/test");

        Files.createFile(path("/bar"));

        try {
            Files.move(path("/test"), path("/bar"), ATOMIC_MOVE);
            fail();
        } catch (FileAlreadyExistsException expected) {
            assertEquals("/bar", expected.getMessage());
        }

        assertThatPath("/test").containsBytes(bytes).and().attribute("fileKey").is(testKey);

        Files.delete(path("/bar"));
        Files.createDirectory(path("/bar"));

        try {
            Files.move(path("/test"), path("/bar"), ATOMIC_MOVE);
            fail();
        } catch (FileAlreadyExistsException expected) {
            assertEquals("/bar", expected.getMessage());
        }

        assertThatPath("/test").containsBytes(bytes).and().attribute("fileKey").is(testKey);
    }

    @Test
    public void testMove_toDifferentFileSystem() throws IOException {
        try (FileSystem fs2 = ZeroFs.newFileSystem(Configuration.unix())) {
            Path foo = fs.getPath("/foo");
            byte[] bytes = {0, 1, 2, 3, 4};
            Files.write(foo, bytes);
            Files.getFileAttributeView(foo, BasicFileAttributeView.class)
                    .setTimes(
                            FileTime.fromMillis(0), FileTime.fromMillis(1), FileTime.fromMillis(2));

            Path foo2 = fs2.getPath("/foo");
            Files.move(foo, foo2);

            this.assertThatPath(foo).doesNotExist();
            this.assertThatPath(foo2)
                    .exists()
                    .and()
                    .attribute("lastModifiedTime")
                    .is(FileTime.fromMillis(0))
                    .and()
                    .attribute("lastAccessTime")
                    .is(FileTime.fromMillis(1))
                    .and()
                    .attribute("creationTime")
                    .is(FileTime.fromMillis(2))
                    .and()
                    .containsBytes(bytes); // do this last; it updates the access time
        }
    }

    @Test
    public void testIsSameFile() throws IOException {
        Files.createDirectory(path("/foo"));
        Files.createSymbolicLink(path("/bar"), path("/foo"));
        Files.createFile(path("/bar/test"));

        assertThatPath("/foo").isSameFileAs("/foo");
        assertThatPath("/bar").isSameFileAs("/bar");
        assertThatPath("/foo/test").isSameFileAs("/foo/test");
        assertThatPath("/bar/test").isSameFileAs("/bar/test");
        assertThatPath("/foo").isNotSameFileAs("test");
        assertThatPath("/bar").isNotSameFileAs("/test");
        assertThatPath("/foo").isSameFileAs("/bar");
        assertThatPath("/foo/test").isSameFileAs("/bar/test");

        Files.createSymbolicLink(path("/baz"), path("bar")); // relative path
        assertThatPath("/baz").isSameFileAs("/foo");
        assertThatPath("/baz/test").isSameFileAs("/foo/test");
    }

    @Test
    public void testIsSameFile_forPathFromDifferentFileSystemProvider() throws IOException {
        Path defaultFileSystemRoot =
                FileSystems.getDefault().getRootDirectories().iterator().next();

        assertFalse(Files.isSameFile(path("/"), defaultFileSystemRoot));
    }

    @Test
    public void testPathLookups() throws IOException {
        assertThatPath("/").isSameFileAs("/");
        assertThatPath("/..").isSameFileAs("/");
        assertThatPath("/../../..").isSameFileAs("/");
        assertThatPath("../../../..").isSameFileAs("/");
        assertThatPath("").isSameFileAs("/work");

        Files.createDirectories(path("/foo/bar/baz"));
        Files.createSymbolicLink(path("/foo/bar/link1"), path("../link2"));
        Files.createSymbolicLink(path("/foo/link2"), path("/"));

        assertThatPath("/foo/bar/link1/foo/bar/link1/foo").isSameFileAs("/foo");
    }

    @Test
    public void testSecureDirectoryStream() throws IOException {
        Files.createDirectories(path("/foo/bar"));
        Files.createFile(path("/foo/a"));
        Files.createFile(path("/foo/b"));
        Files.createSymbolicLink(path("/foo/barLink"), path("bar"));

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path("/foo"))) {
            if (!(stream instanceof SecureDirectoryStream)) {
                fail("should be a secure directory stream");
            }

            SecureDirectoryStream<Path> secureStream = (SecureDirectoryStream<Path>) stream;
            Iterator<Path> secureStreamIter = secureStream.iterator();
            List<Path> secureStreamList = new ArrayList<>();
            while (secureStreamIter.hasNext()) {
                secureStreamList.add(secureStreamIter.next());
            }

            assertEquals(
                    List.of(path("/foo/a"), path("/foo/b"), path("/foo/bar"), path("/foo/barLink")),
                    secureStreamList);

            secureStream.deleteFile(path("b"));
            assertThatPath("/foo/b").doesNotExist();

            secureStream.newByteChannel(path("b"), Set.of(WRITE, CREATE_NEW)).close();
            assertThatPath("/foo/b").isRegularFile();

            assertThatPath("/foo").hasChildren("a", "b", "bar", "barLink");

            Files.createDirectory(path("/baz"));
            Files.move(path("/foo"), path("/baz/stuff"));

            this.assertThatPath(path("/foo")).doesNotExist();

            assertThatPath("/baz/stuff").hasChildren("a", "b", "bar", "barLink");

            secureStream.deleteFile(path("b"));

            assertThatPath("/baz/stuff/b").doesNotExist();
            assertThatPath("/baz/stuff").hasChildren("a", "bar", "barLink");

            assertTrue(
                    secureStream
                            .getFileAttributeView(BasicFileAttributeView.class)
                            .readAttributes()
                            .isDirectory());

            assertTrue(
                    secureStream
                            .getFileAttributeView(path("a"), BasicFileAttributeView.class)
                            .readAttributes()
                            .isRegularFile());

            try {
                secureStream.deleteFile(path("bar"));
                fail();
            } catch (FileSystemException expected) {
                assertEquals("bar", expected.getFile());
            }

            try {
                secureStream.deleteDirectory(path("a"));
                fail();
            } catch (FileSystemException expected) {
                assertEquals("a", expected.getFile());
            }

            try (SecureDirectoryStream<Path> barStream =
                    secureStream.newDirectoryStream(path("bar"))) {
                barStream.newByteChannel(path("stuff"), Set.of(WRITE, CREATE_NEW)).close();
                assertTrue(
                        barStream
                                .getFileAttributeView(path("stuff"), BasicFileAttributeView.class)
                                .readAttributes()
                                .isRegularFile());

                assertTrue(
                        secureStream
                                .getFileAttributeView(
                                        path("bar/stuff"), BasicFileAttributeView.class)
                                .readAttributes()
                                .isRegularFile());
            }

            try (SecureDirectoryStream<Path> barLinkStream =
                    secureStream.newDirectoryStream(path("barLink"))) {
                assertTrue(
                        barLinkStream
                                .getFileAttributeView(path("stuff"), BasicFileAttributeView.class)
                                .readAttributes()
                                .isRegularFile());

                assertTrue(
                        barLinkStream
                                .getFileAttributeView(path(".."), BasicFileAttributeView.class)
                                .readAttributes()
                                .isDirectory());
            }

            try {
                secureStream.newDirectoryStream(path("barLink"), NOFOLLOW_LINKS);
                fail();
            } catch (NotDirectoryException expected) {
                assertEquals("barLink", expected.getFile());
            }

            try (SecureDirectoryStream<Path> barStream =
                    secureStream.newDirectoryStream(path("bar"))) {
                secureStream.move(path("a"), barStream, path("moved"));

                this.assertThatPath(path("/baz/stuff/a")).doesNotExist();
                this.assertThatPath(path("/baz/stuff/bar/moved")).isRegularFile();

                assertTrue(
                        barStream
                                .getFileAttributeView(path("moved"), BasicFileAttributeView.class)
                                .readAttributes()
                                .isRegularFile());
            }
        }
    }

    @Test
    public void testSecureDirectoryStreamBasedOnRelativePath() throws IOException {
        Files.createDirectories(path("foo"));
        Files.createFile(path("foo/a"));
        Files.createFile(path("foo/b"));
        Files.createDirectory(path("foo/c"));
        Files.createFile(path("foo/c/d"));
        Files.createFile(path("foo/c/e"));

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path("foo"))) {
            SecureDirectoryStream<Path> secureStream = (SecureDirectoryStream<Path>) stream;
            Iterator<Path> secureStreamIter = secureStream.iterator();
            List<Path> secureStreamList = new ArrayList<>();
            while (secureStreamIter.hasNext()) {
                secureStreamList.add(secureStreamIter.next());
            }

            assertEquals(List.of(path("foo/a"), path("foo/b"), path("foo/c")), secureStreamList);

            try (DirectoryStream<Path> stream2 = secureStream.newDirectoryStream(path("c"))) {
                Iterator<Path> secureStream2Iter = stream2.iterator();
                List<Path> secureStream2List = new ArrayList<>();
                while (secureStream2Iter.hasNext()) {
                    secureStream2List.add(secureStream2Iter.next());
                }
                assertEquals(List.of(path("foo/c/d"), path("foo/c/e")), secureStream2List);
            }
        }
    }

    @Test
    public void testClosedSecureDirectoryStream() throws IOException {
        Files.createDirectory(path("/foo"));
        SecureDirectoryStream<Path> stream =
                (SecureDirectoryStream<Path>) Files.newDirectoryStream(path("/foo"));

        stream.close();

        try {
            stream.iterator();
            fail("expected ClosedDirectoryStreamException");
        } catch (ClosedDirectoryStreamException expected) {
        }

        try {
            stream.deleteDirectory(fs.getPath("a"));
            fail("expected ClosedDirectoryStreamException");
        } catch (ClosedDirectoryStreamException expected) {
        }

        try {
            stream.deleteFile(fs.getPath("a"));
            fail("expected ClosedDirectoryStreamException");
        } catch (ClosedDirectoryStreamException expected) {
        }

        try {
            stream.newByteChannel(fs.getPath("a"), Set.of(CREATE, WRITE));
            fail("expected ClosedDirectoryStreamException");
        } catch (ClosedDirectoryStreamException expected) {
        }

        try {
            stream.newDirectoryStream(fs.getPath("a"));
            fail("expected ClosedDirectoryStreamException");
        } catch (ClosedDirectoryStreamException expected) {
        }

        try {
            stream.move(fs.getPath("a"), stream, fs.getPath("b"));
            fail("expected ClosedDirectoryStreamException");
        } catch (ClosedDirectoryStreamException expected) {
        }

        try {
            stream.getFileAttributeView(BasicFileAttributeView.class);
            fail("expected ClosedDirectoryStreamException");
        } catch (ClosedDirectoryStreamException expected) {
        }

        try {
            stream.getFileAttributeView(fs.getPath("a"), BasicFileAttributeView.class);
            fail("expected ClosedDirectoryStreamException");
        } catch (ClosedDirectoryStreamException expected) {
        }
    }

    @Test
    public void testClosedSecureDirectoryStreamAttributeViewAndIterator() throws IOException {
        Files.createDirectory(path("/foo"));
        Files.createDirectory(path("/foo/bar"));
        SecureDirectoryStream<Path> stream =
                (SecureDirectoryStream<Path>) Files.newDirectoryStream(path("/foo"));

        Iterator<Path> iter = stream.iterator();
        BasicFileAttributeView view1 = stream.getFileAttributeView(BasicFileAttributeView.class);
        BasicFileAttributeView view2 =
                stream.getFileAttributeView(path("bar"), BasicFileAttributeView.class);

        try {
            stream.iterator();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
        }

        stream.close();

        try {
            iter.next();
            fail("expected ClosedDirectoryStreamException");
        } catch (ClosedDirectoryStreamException expected) {
        }

        try {
            view1.readAttributes();
            fail("expected ClosedDirectoryStreamException");
        } catch (ClosedDirectoryStreamException expected) {
        }

        try {
            view2.readAttributes();
            fail("expected ClosedDirectoryStreamException");
        } catch (ClosedDirectoryStreamException expected) {
        }

        try {
            view1.setTimes(null, null, null);
            fail("expected ClosedDirectoryStreamException");
        } catch (ClosedDirectoryStreamException expected) {
        }

        try {
            view2.setTimes(null, null, null);
            fail("expected ClosedDirectoryStreamException");
        } catch (ClosedDirectoryStreamException expected) {
        }
    }

    @Test
    public void testDirectoryAccessAndModifiedTimeUpdates() throws Exception {
        Files.createDirectories(path("/foo/bar"));
        FileTimeTester tester = new FileTimeTester(path("/foo/bar"));
        tester.assertAccessTimeDidNotChange();
        tester.assertModifiedTimeDidNotChange();

        // TODO(cgdecker): Use a Clock for file times so I can test this reliably without sleeping
        Thread.sleep(1);
        Files.createFile(path("/foo/bar/baz.txt"));

        tester.assertAccessTimeDidNotChange();
        tester.assertModifiedTimeChanged();

        Thread.sleep(1);
        // access time is updated by reading the full contents of the directory
        // not just by doing a lookup in it
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path("/foo/bar"))) {
            // iterate the stream, forcing the directory to actually be read
            Iterator<Path> streamIter = stream.iterator();
            while (streamIter.hasNext()) {
                streamIter.next();
            }
        }

        tester.assertAccessTimeChanged();
        tester.assertModifiedTimeDidNotChange();

        Thread.sleep(1);
        Files.move(path("/foo/bar/baz.txt"), path("/foo/bar/baz2.txt"));

        tester.assertAccessTimeDidNotChange();
        tester.assertModifiedTimeChanged();

        Thread.sleep(1);
        Files.delete(path("/foo/bar/baz2.txt"));

        tester.assertAccessTimeDidNotChange();
        tester.assertModifiedTimeChanged();
    }

    @Test
    public void testRegularFileAccessAndModifiedTimeUpdates() throws Exception {
        Path foo = path("foo");
        Files.createFile(foo);

        FileTimeTester tester = new FileTimeTester(foo);
        tester.assertAccessTimeDidNotChange();
        tester.assertModifiedTimeDidNotChange();

        Thread.sleep(1);
        try (FileChannel channel = FileChannel.open(foo, READ)) {
            // opening READ channel does not change times
            tester.assertAccessTimeDidNotChange();
            tester.assertModifiedTimeDidNotChange();

            Thread.sleep(1);
            channel.read(ByteBuffer.allocate(100));

            // read call on channel does
            tester.assertAccessTimeChanged();
            tester.assertModifiedTimeDidNotChange();

            Thread.sleep(1);
            channel.read(ByteBuffer.allocate(100));

            tester.assertAccessTimeChanged();
            tester.assertModifiedTimeDidNotChange();

            Thread.sleep(1);
            try {
                channel.write(ByteBuffer.wrap(new byte[] {0, 1, 2, 3}));
            } catch (NonWritableChannelException ignore) {
            }

            // failed write on non-readable channel does not change times
            tester.assertAccessTimeDidNotChange();
            tester.assertModifiedTimeDidNotChange();

            Thread.sleep(1);
        }

        // closing channel does not change times
        tester.assertAccessTimeDidNotChange();
        tester.assertModifiedTimeDidNotChange();

        Thread.sleep(1);
        try (FileChannel channel = FileChannel.open(foo, WRITE)) {
            // opening WRITE channel does not change times
            tester.assertAccessTimeDidNotChange();
            tester.assertModifiedTimeDidNotChange();

            Thread.sleep(1);
            channel.write(ByteBuffer.wrap(new byte[] {0, 1, 2, 3}));

            // write call on channel does
            tester.assertAccessTimeDidNotChange();
            tester.assertModifiedTimeChanged();

            Thread.sleep(1);
            channel.write(ByteBuffer.wrap(new byte[] {4, 5, 6, 7}));

            tester.assertAccessTimeDidNotChange();
            tester.assertModifiedTimeChanged();

            Thread.sleep(1);
            try {
                channel.read(ByteBuffer.allocate(100));
            } catch (NonReadableChannelException ignore) {
            }

            // failed read on non-readable channel does not change times
            tester.assertAccessTimeDidNotChange();
            tester.assertModifiedTimeDidNotChange();

            Thread.sleep(1);
        }

        // closing channel does not change times
        tester.assertAccessTimeDidNotChange();
        tester.assertModifiedTimeDidNotChange();
    }

    @Test
    public void testUnsupportedFeatures() throws IOException {
        FileSystem fileSystem =
                ZeroFs.newFileSystem(
                        Configuration.unix().toBuilder()
                                .setSupportedFeatures() // none
                                .build());

        Path foo = fileSystem.getPath("foo");
        Path bar = foo.resolveSibling("bar");

        try {
            Files.createLink(foo, bar);
            fail();
        } catch (UnsupportedOperationException expected) {
        }

        try {
            Files.createSymbolicLink(foo, bar);
            fail();
        } catch (UnsupportedOperationException expected) {
        }

        try {
            Files.readSymbolicLink(foo);
            fail();
        } catch (UnsupportedOperationException expected) {
        }

        try {
            FileChannel.open(foo);
            fail();
        } catch (UnsupportedOperationException expected) {
        }

        try {
            AsynchronousFileChannel.open(foo);
            fail();
        } catch (UnsupportedOperationException expected) {
        }

        Files.createDirectory(foo);
        Files.createFile(bar);

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(foo)) {
            assertFalse(stream instanceof SecureDirectoryStream);
        }

        try (SeekableByteChannel channel = Files.newByteChannel(bar)) {
            assertFalse(channel instanceof FileChannel);
        }
    }
}
