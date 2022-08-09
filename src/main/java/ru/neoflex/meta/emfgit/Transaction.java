package ru.neoflex.meta.emfgit;

import com.beijunyi.parallelgit.filesystem.Gfs;
import com.beijunyi.parallelgit.filesystem.GitFileSystem;
import com.beijunyi.parallelgit.filesystem.GitPath;
import com.beijunyi.parallelgit.filesystem.commands.GfsCommit;
import com.beijunyi.parallelgit.filesystem.io.DirectoryNode;
import com.beijunyi.parallelgit.filesystem.io.Node;
import com.github.marschall.pathclassloader.PathClassLoader;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static ru.neoflex.meta.emfgit.Database.IDS_PATH;

public class Transaction implements Closeable {
    private Database database;
    private String branch;
    private GitFileSystem gfs;
    public enum LockType {READ, WRITE, EXCLUSIVE}
    private LockType lockType;
    private static final ThreadLocal<Transaction> tlTransaction = new ThreadLocal<>();

    public static void setCurrent(Transaction tx) {
        tlTransaction.set(tx);
    }

    public static Transaction getCurrent() {
        return tlTransaction.get();
    }

    public Transaction(Database database, String branch, LockType lockType) throws IOException {
        this.database = database;
        this.branch = branch;
        this.lockType = lockType;
        if (lockType == LockType.EXCLUSIVE) {
            database.getLock().writeLock().lock();
        }
        else if (lockType == LockType.WRITE) {
            database.getLock().readLock().lock();
        }
        this.gfs =  Gfs.newFileSystem(branch, database.getRepository());
    }

    public Transaction(Database database, String branch) throws IOException {
        this(database, branch, LockType.WRITE);
    }

    @Override
    public void close() throws IOException {
        gfs.close();
        if (lockType == LockType.EXCLUSIVE) {
            database.getLock().writeLock().unlock();
        }
        if (lockType == LockType.WRITE) {
            database.getLock().readLock().unlock();
        }
    }

    public RevCommit getLastCommit(EntityId entityId) throws IOException {
        return getLastCommit(getIdPath(entityId));
    }

    public RevCommit getLastCommit(String path) throws IOException {
        GitPath gfsPath = gfs.getPath(path);
        return getLastCommit(gfsPath);
    }

    private RevCommit getLastCommit(GitPath gfsPath) throws IOException {
        String relPath = gfs.getRootPath().relativize(gfsPath).toString();
        try(RevWalk revCommits = new RevWalk(gfs.getRepository());) {
            revCommits.setTreeFilter(PathFilter.create(relPath));
            ObjectId branchId = gfs.getRepository().resolve(gfs.getStatusProvider().branch());
            revCommits.markStart(revCommits.parseCommit(branchId));
            RevCommit last = revCommits.next();
            return last;
        }
    }

    public void commit(String message, String author, String email) throws IOException {
        if (lockType == LockType.READ) {
            throw new IOException("Can't commit readonly transaction");
        }
        GfsCommit commit = Gfs.commit(gfs).message(message);
        if (author != null && email != null) {
            PersonIdent authorId = new PersonIdent(author, email);
            commit.author(authorId);
        }
        commit.execute();
    }

    public void commit(String message) throws IOException {
        commit(message, null, null);
    }

    public GitPath getIdPath(EntityId entityId) {
        return gfs.getPath("/", IDS_PATH, entityId.getId());
    }

    static ObjectId getObjectId(GitPath path) throws IOException {
        if(!path.isAbsolute()) throw new IllegalArgumentException(path.toString());
        Node current = path.getFileStore().getRoot();
        for(int i = 0; i < path.getNameCount(); i++) {
            GitPath name = path.getName(i);
            if(current instanceof DirectoryNode) {
                current = ((DirectoryNode) current).getChild(name.toString());
                if (current == null) {
                    return null;
                }
            }
            else
                return null;
        }
        return current.getObjectId(false);
    }

    static SecureRandom prng;

    static {
        try {
            prng = SecureRandom.getInstance("SHA1PRNG");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static String getRandomId(int length) {
        byte[] bytes = new byte[length];
        prng.nextBytes(bytes);
        return hex(bytes);
    }

//    public static String getUUID() {
//        byte[] bytes = new byte[16];
//        EcoreUtil.generateUUID(bytes);
//        return hex(bytes);
//    }

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    private static String hex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    public Entity create(Entity entity) throws IOException {
        if (entity.getId() == null) {
//          String id = getUUID();
            String id = getRandomId(2) + "/" + getRandomId(14);
            entity.setId(id);
        }
        GitPath path = getIdPath(entity);
        Files.createDirectories(path.getParent());
        Files.write(path, entity.getContent());
        entity.setRev(getRev(path));
        return entity;
    }

    public Entity load(EntityId entityId) throws IOException {
        GitPath path = getIdPath(entityId);
        long rev = getRev(path);
        byte[] content = Files.readAllBytes(path);
        return new Entity(entityId.getId(), rev, content);
    }

    public boolean isResourceExists(EntityId entityId) {
        GitPath path = getIdPath(entityId);
        try {
            return getObjectId(path) != null;
        }
        catch (Throwable e) {
            return false;
        }
    }

    private long getRev(GitPath path) throws IOException {
        ObjectId objectId = getObjectId(path);
        if (objectId == null) {
            throw new IOException("Path not found: " + path.toString());
        }
        String sha1 = objectId.getName();
        return Long.parseUnsignedLong(sha1.substring(sha1.length() - 12), 16);
//        RevCommit commit = getLastCommit(path);
//        if (commit == null) {
//            throw new IOException("Path not found: " + path.toString());
//        }
//        int commitTime = commit.getCommitTime();
//        return commitTime;
    }

    public Entity update(Entity entity) throws IOException {
        GitPath path = getIdPath(entity);
        if (getRev(path) != entity.getRev()) {
            throw new ConcurrentModificationException("Entity was updated: " + entity.getId());
        }
        Files.createDirectories(path.getParent());
        Files.write(path, entity.getContent());
        entity.setRev(getRev(path));
        return entity;
    }

    public void delete(EntityId entityId) throws IOException {
        GitPath path = getIdPath(entityId);
        if (getRev(path) != entityId.getRev()) {
            throw new ConcurrentModificationException("Entity was updated: " + entityId.getId());
        }
        Files.delete(path);
    }

    public List<EntityId> all() throws IOException {
        GitPath idsPath = gfs.getPath("/", IDS_PATH);
        if (!Files.exists(idsPath)) {
            return Collections.emptyList();
        }
        return Files.walk(idsPath).filter(Files::isRegularFile).map(file -> {
            String id = idsPath.relativize(file).toString();
            long commitTime;
            try {
                commitTime = getRev((GitPath) file);
            }
            catch (Throwable e) {
                commitTime = 0;
            }
            return new EntityId(id, commitTime);
        }).collect(Collectors.toList());
    }

    public Database getDatabase() {
        return database;
    }

    public GitFileSystem getFileSystem() {
        return gfs;
    }
    public ClassLoader getClassLoader(ClassLoader parent) {
        ClassLoader classLoader = new PathClassLoader(gfs.getRootPath(), parent);
        return classLoader;
    }

    public<R> R withClassLoader(Callable<R> f) throws Exception {
        ClassLoader parent = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClassLoader(parent));
        try {
            return f.call();
        } finally {
            Thread.currentThread().setContextClassLoader(parent);
        }
    }

    public<R> R withCurrent(Callable<R> f) throws Exception {
        Transaction old = getCurrent();
        setCurrent(this);
        try {
            return f.call();
        } finally {
            setCurrent(old);
        }
    }
}
